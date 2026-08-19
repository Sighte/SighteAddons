package sighteaddons

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.hud.HudKeys
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.hud.HudSnapshot
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.screens.GalleryScreen
import sighteaddons.ui.theme.Density
import net.minecraft.resources.Identifier
import sighteaddons.mixin.PlayerTabOverlayAccessor
import java.util.Locale
import org.slf4j.LoggerFactory

/**
 * Entry point: samples the dungeon every tick and draws the live room timeline plus standings.
 *
 * Everything here is read-only — item map, tab list, scoreboard, blocks — plus an HUD overlay and
 * client-side chat messages. No packets are sent and nothing is automated.
 */
class SighteAddons : ClientModInitializer {
    override fun onInitializeClient() {
        Config.load()
        // /sa opens the settings, /sa pbs jumps straight to the records table, /sa gallery opens the
        // UI gallery — a development screen, undocumented on purpose, that renders every design token
        // and every motion curve so they can be checked without a dungeon.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("sa")
                    .executes { open(SettingsScreen.Tab.HUD) }
                    .then(ClientCommands.literal("pbs").executes { open(SettingsScreen.Tab.RECORDS) })
                    .then(ClientCommands.literal("gallery").executes { openGallery() }),
            )
        }
        ClientTickEvents.END_CLIENT_TICK.register(::onTick)
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            ContributionTracker.onChunkLoad(chunk.pos.x, chunk.pos.z)
        }
        SecretTracker.init()
        HudKeys.register()
        // The second parameter marks action bar messages, which is where Hypixel puts the current
        // room's secret progress — everything else is ordinary chat.
        ClientReceiveMessageEvents.GAME.register { text, overlay ->
            if (overlay) onActionBar(text.string) else onChat(text.string)
        }
        // Entering or leaving a dungeon is a server transfer on Hypixel, so this starts each run clean.
        // The history is append-only and flushed per line, so there is nothing to save on the way out.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            // Before the reset, because the reset is what erases the run this reports on. Warping out
            // of a floor mid-run lands here and nowhere else — the end-of-run headline never comes, so
            // without this the rooms that *were* cleared went nowhere. write() ignores a session with
            // no rooms, which is every ordinary hub hop.
            //
            // No `summaryPrinted` check any more: since `runloss-001` the once-per-run guard is
            // RunReport's own, because it now has to hold across the DISCONNECT path below as well —
            // and `summaryPrinted` never covered the pair that path introduces. See
            // RunReport.reported.
            RunReport.write(complete = false)
            DungeonSession.reset()
            // The HUD is gated on calibration and so is already invisible here, but a snapshot of the
            // previous run left in the field would be the first thing the next one draws before its
            // own first tick lands.
            HudSnapshot.clear()
            uploadNotice()
        }
        // The other way out of a floor, and the one that cost a real M7 on 2026-08-14: quitting the
        // game, or dropping to the title screen, from inside the dungeon. There is no headline and no
        // subsequent JOIN, so before this the run was simply gone — ten cleared rooms that never
        // reached the box (`git show afb3233:docs/evidence/session-1786719912927/`).
        //
        // `complete = false` unconditionally. A run left this way did not reach its end by
        // definition, and the headline remains the only thing allowed to claim that it did; if the
        // headline *had* come, the report is already written and RunReport.reported makes this a
        // no-op.
        //
        // No DungeonSession.reset() here, deliberately. This callback can arrive on a Netty
        // event-loop thread (see RunReport.uploader), and reset() tears down ContributionTracker,
        // PartyTracker and ClearPopup — state the client thread reads every frame in renderHud. A
        // dropped connection must not turn into a ConcurrentModificationException in the render
        // loop. The write itself only reads, and the next JOIN resets as it always did.
        //
        // Wrapped, because the caller is Netty's pipeline or a shutdown sequence: an exception
        // escaping into either is a worse outcome than a missing report, and telemetry is never a
        // reason for the game to misbehave.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            try {
                RunReport.write(complete = false)
            } catch (e: Exception) {
                LOGGER.error("Could not write the run report on disconnect", e)
            }
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.OVERLAY_MESSAGE, STANDINGS) { graphics, _ ->
            renderHud(graphics)
        }
        // Both of these do their networking on their own daemon thread and neither may delay the
        // game reaching a menu, let alone a tick.
        //
        // Asks the receiver for the current room weights. Nothing waits for the answer: the first
        // room of the first run is minutes away, and if it arrives after that anyway, the run it
        // would have changed keeps the numbers it started with.
        RoomStats.start()
        // Ships the previous sessions' logs while nothing is happening yet. No-op without a config.
        TelemetryUpload.start()
    }

    /**
     * Said once, on the first server this install ever joins.
     *
     * The mod uploads by default, and a default that nobody was told about is the thing that gets
     * mods pulled and authors distrusted — the README and the Modrinth page carry the same text, but
     * neither is read by somebody who installed this from a modpack. One line, then never again.
     *
     * "without your name" is the default and stays true until somebody switches *send my name* on
     * themselves, which cannot have happened yet on the first server this install ever joins. The
     * switch is named anyway: this line is the only place most players learn what leaves the machine,
     * and one that describes the default without mentioning the choice reads later like it was hiding
     * it.
     */
    private fun uploadNotice() {
        if (Config.uploadNoticeShown) return
        Config.uploadNoticeShown = true
        Config.save()
        // The one line that spells the mod's name out in full rather than leaving it at the tag. The
        // tag says which mod is speaking; this sentence has to be readable on its own by somebody who
        // has never seen the tag before, because it is a disclosure and it gets exactly one showing.
        //
        // Wording untouched by the chat redesign, alone among this mod's lines. Every other line is
        // a reading and was shortened towards its numbers; this one is a disclosure with no numbers
        // in it, it is read once by somebody who has never seen the mod, and trimming it would trim
        // the part that says what leaves the machine. [Chat.label] end to end for the same reason:
        // there is no value on it to make a value of.
        Chat.say(
            Chat.label(
                "Sighte Addons sends a report of each dungeon run to the mod's analysis server: " +
                    "rooms, times and classes, under a random id, without your name. " +
                    "Turn it off — or put your name on it — with /sa → debug.",
            ),
        )
    }

    /**
     * Deferred to the next tick on purpose: the chat screen closes right after a command runs and
     * would take the new screen with it, so setting it directly from the callback shows nothing.
     */
    private fun open(tab: SettingsScreen.Tab): Int {
        val client = Minecraft.getInstance()
        client.schedule { client.setScreen(SettingsScreen(tab)) }
        return 1
    }

    /** Deferred for the same reason as [open]. */
    private fun openGallery(): Int {
        val client = Minecraft.getInstance()
        client.schedule { client.setScreen(GalleryScreen()) }
        return 1
    }

    private fun onTick(client: Minecraft) {
        // Outside the dungeon gate: the key is drained every tick regardless, so a press made while
        // the queue is not being read does not sit there and fire on the next dungeon entry.
        // The keybind and the `/sa` switch change one value, so a player can have the panel either way
        // round: open all the time, or a keypress away. Written to the file, because the alternative is a
        // panel that forgets every game start — which is what the flag this replaced did, and half of why
        // switching on "idle & nav" looked like nothing happening. One small write per keypress, and a
        // keypress is a human action; the per-frame case this file is careful about is the scrim slider.
        if (HudKeys.tick()) {
            Config.totalsOpen = !Config.totalsOpen
            Config.save()
        }

        if (!DungeonSession.inDungeon(client)) return

        // **The map item is not there for the whole run.** Hypixel takes it out of hotbar slot 9 when
        // the boss starts, and until 0.16.0-dev5 a null map returned from this function before
        // anything else could happen — so the boss phase advanced no clock, published no snapshot and
        // never once evaluated `inBoss`. Measured on the M1 of 2026-08-17: the blood room cleared at
        // tick 2379, `run_end` came at 2549, and the report claimed a 2:07 run for a floor that had a
        // whole Bonzo fight after it. The HUD froze on its last clear-phase frame for the same reason.
        //
        // A missing map is therefore handled exactly like the boss, which is what it usually is: keep
        // the clock, publish the snapshot, sample nothing. Room sampling genuinely cannot run without
        // the map — it is the only source that sees rooms this client has not loaded — but everything
        // else can, and stopping all of it was never the intent.
        val map = DungeonMapReader.mapState(client)
        // Once per tick, before anything reads `DungeonSession.inBoss` — which is both branches below
        // and the HUD snapshot. Odin recomputes it in the same place for the same reason.
        DungeonSession.observe(client, map != null)
        val wasCalibrated = DungeonSession.calibrated
        if (map == null || !DungeonSession.update(client, map)) {
            // In the boss: stop sampling rooms but keep the run clock going, so the summary
            // reports the real run time and not just the clear phase.
            if (DungeonSession.calibrated) {
                DungeonSession.tickClock()
                // Republish, or the HUD freezes on the last clear-phase snapshot and the run clock
                // stops on screen while it is still running underneath. The old readout kept ticking
                // here because it read the field live; the snapshot has to be told.
                HudSnapshot.publish(client)
            }
            return
        }
        if (!wasCalibrated) {
            RoomHistory.startRun()
            // Read the roster immediately: waiting for the next 20-tick slot would leave the first
            // second of the run unattributed, with nobody mapped to any decoration.
            PartyTracker.update(client)
            DebugLog.event(
                "run_start",
                "floor" to DungeonSession.floor, "mcVersion" to client.launchedVersion,
                "roomCores" to RoomDatabase.size,
            )
        }

        DungeonSession.tickClock()
        // Every tick, because the sidebar carries both the score and the elapsed time and is rewritten
        // that often — and because the announced time is only as precise as the reading that triggered
        // it. The tab rows are the once-a-second ones and only feed the computed fallback.
        LiveScore.observe(
            floor = DungeonSession.floor,
            sidebarScore = DungeonSession.sidebarScore,
            footer = ::tabFooter,
            rows = PartyTracker.lastRows,
            clearedFraction = DungeonSession.clearedFraction,
            secretsPercent = DungeonTab.secretsPercent,
            inBoss = DungeonSession.inBoss,
            nowMs = System.currentTimeMillis(),
        )
        // The announcement the gate is for: the moment the clear phase reaches the score. Refuses on its
        // own for a party run, the wrong floor, the boss, or a score nothing could read.
        SoloClear.onScore(LiveScore.score, DungeonSession.inBoss)
        // Before the trackers, because a bat dying is an arming event and the action bar update that
        // pays for it can land on this very tick. In the clear phase only: the boss room has no
        // secrets in it, and the bats in there are somebody's fight, not somebody's find.
        SecretTracker.tickBats(client)
        // Same reason, one layer over: Hypixel hands out some secret items rather than dropping them,
        // and an item that never exists in the world is never seen being collected.
        SecretTracker.tickInventory(client)
        // The party barely changes during a run; re-reading the tab list once a second is plenty.
        if (DungeonSession.runTicks % 20 == 0) PartyTracker.update(client)
        ContributionTracker.tick(client, map)
        // After the tracker, because the room the player is standing in may only have been
        // discovered by the call above — on the tick you cross a threshold, asking first would count
        // the arrival as navigation. In the same loop that advances `runTicks`, which is what makes
        // `idleTicks + navTicks <= runTicks` true rather than merely intended, and *inside* the two
        // early returns above: the boss advances the clock without being either a room or a
        // corridor, and counting it as navigation would make every run look like an hour of walking.
        IdleTime.tick(currentRoom(client))

        // Last, so the snapshot the HUD reads is built from state every tracker above has already
        // advanced this tick. The renderer never reaches into the trackers itself: records(),
        // attempts() and pointsByPlayer() all hand out their live internal maps, and ensureLoaded()
        // does file I/O on whichever thread asks first. One immutable object, published by reference
        // swap, is the whole of the synchronisation.
        HudSnapshot.publish(client)
    }

    private fun onActionBar(text: String) {
        val player = Minecraft.getInstance().player ?: return
        // The name goes with the position because a secret attributed here is now credited as
        // ClearPoints, and the tracker keys on the same Minecraft name the roster and the tab do.
        SecretTracker.onActionBar(text, player.name.string, player.x, player.z)
    }

    /** Hypixel prints the results headline when the run ends, e.g. "The Catacombs - Floor VII". */
    private fun onChat(message: String) {
        // Stripped like the sidebar and the action bar are: Hypixel puts legacy § codes inside the
        // text, and this anchored match is the only thing that triggers the permanent run report —
        // one added colour code would stop it being written, silently.
        //
        // Stripped once and shared since `chat-001`, because two consumers now read the same line and
        // a second `stripFormatting` call would be a second chance for them to disagree about what
        // the line said. The event pass runs first and unconditionally: the headline path below
        // returns early on every ordinary line, and putting the events after that return is how they
        // would silently stop arriving the moment somebody reorders this function.
        val text = ChatFormatting.stripFormatting(message).orEmpty()
        onDungeonEvent(text)
        onCrit(text)
        onStorm(text)
        // Unconditional and before the return below, because two of the three lines it reads arrive
        // *after* the headline — `Team Score:` is what releases an armed announcement, and the Prince
        // falls mid-run. Putting this behind the headline check is how they would stop arriving.
        SoloClear.onChatLine(text)
        if (summaryPrinted || !RUN_END.matches(text)) return
        summaryPrinted = true
        DebugLog.event(
            "run_end",
            "floor" to DungeonSession.floor, "roomsCleared" to ContributionTracker.roomsCleared,
            "points" to Pseudonym.keys(ContributionTracker.pointsByPlayer()).toString(),
            "unnamed" to ContributionTracker.visitedRooms().count { it.name == null },
            "newRecords" to RoomHistory.newBestsThisRun().size,
        )
        RoomHistory.printSummary()
        // Permanent record of the whole run, unlike the chat summary and unlike the debug log. The
        // headline is the only evidence that the run reached its end, which is why this is the one
        // call site that may claim it.
        RunReport.write(complete = true)
        // Same reason, and the same single call site: a floor that was left is not a clear, so the two
        // paths that write a report on the way out (`JOIN`, `DISCONNECT`) deliberately do not announce
        // one. Off unless [Config.soloClears] is on, and a no-op for a run with company. Arms rather
        // than sends when a score gate is set — this headline is the block's first line, the score is
        // further down it.
        SoloClear.onRunEnd()
    }

    /**
     * The dungeon facts Hypixel states outright, routed to whoever can use them. [text] is already
     * stripped.
     *
     * **This is the wiring, and it is the half of `chat-001` that nothing in this repository can
     * verify.** [ChatEvents.parse] is pure and exhaustively tested; that Fabric delivers a given line
     * here, with `overlay` false, on the tick Hypixel sent it, is not observable without a real
     * session — and neither is whether the strings [ChatEvents] matches are the strings that arrive.
     * A `chat_unparsed` line in a debug session is what turns that into a measurement.
     *
     * Gated on [DungeonSession.calibrated], the same gate [SecretTracker.onActionBar] uses: every
     * consumer below writes into run-scoped state that does not exist before a run is calibrated, and
     * a death charged into it would be charged to nothing. It costs the announcements between
     * entering the floor and the map becoming readable, which is a few seconds of the entrance.
     */
    private fun onDungeonEvent(text: String) {
        if (!DungeonSession.calibrated) return
        val self = Minecraft.getInstance().player?.name?.string ?: return
        val at = DungeonSession.runTicks
        // `resolve`: Hypixel writes "You" in the lines it addresses to this client, and every
        // consumer downstream keys on a real Minecraft name.
        fun resolve(player: String) = if (player == ChatEvents.SELF) self else player

        when (val event = ChatEvents.parse(text)) {
            // The line looked like ours and did not parse. Redacted through the same function an
            // unparsed tab row goes through — this is the case that exists precisely because the
            // format is not what we thought, so nothing here can be assumed about where the name is.
            null -> ChatEvents.nearMiss(text)?.let { DebugLog.event("chat_unparsed", "line" to Pseudonym.row(it)) }
            is ChatEvents.Event.Death ->
                ContributionTracker.onDeath(resolve(event.player), at, ContributionTracker.DeathSource.CHAT)
            is ChatEvents.Event.Revived -> ContributionTracker.onRevive(resolve(event.player))
            // Only whether it was ours crosses this boundary. SecretTracker counts one number about
            // the local player (`ownSecrets`); a per-player secret breakdown is a report field and
            // therefore a receiver change first — recorded as `chatfields-001`, not built here.
            is ChatEvents.Event.SecretFound -> SecretTracker.onChatSecret(resolve(event.player) == self, at)
            // Doors and puzzles reach the debug log and stop there, on purpose. Every field the run
            // report writes is one the receiver's RUN_KEYS/ROOM_KEYS already knows, and a report
            // carrying a key it has not learned is a 400 that TelemetryUpload files under rejected/
            // and never retries. The receiver moves first; until it has, these are diagnostics.
            is ChatEvents.Event.WitherDoor -> attributed("wither_door", resolve(event.player))
            // The one event with no name in it — Hypixel does not say who opened the blood door. It
            // is also one of the two ways the blood room's clock can start; see BloodClear.onOpen for
            // why the first of the two wins.
            ChatEvents.Event.BloodDoor -> {
                DebugLog.event("blood_door")
                BloodClear.onOpen(at)
            }
            ChatEvents.Event.BloodOpen -> BloodClear.onOpen(at)
            ChatEvents.Event.BloodDone -> {
                BloodClear.onDone(at)
                // The same line, for a second reader: it is one of the inputs to the computed score's
                // room count. Routed here rather than parsed again — one pattern, one meaning.
                LiveScore.onBloodDone()
            }
            is ChatEvents.Event.PuzzleSolved -> attributed("puzzle_solved", resolve(event.player))
            is ChatEvents.Event.PuzzleFailed -> attributed("puzzle_failed", resolve(event.player))
        }
    }

    /**
     * The Explosive Shot crit readout, printed into the local player's own chat. [text] is already
     * stripped.
     *
     * **Local only, and that is the whole design constraint.** `addClientSystemMessage` writes into
     * this client's chat buffer and sends nothing; the mod this was ported from typed the same figure
     * into `/msg` and party chat and POSTed it to a third-party server, and none of that is here (see
     * [CritMeter]). Nothing about a crit is written to [RunReport] either, so no receiver change is
     * owed in either direction.
     *
     * **Not gated on [DungeonSession.calibrated]**, unlike [onDungeonEvent]. [CritMeter]'s own combat
     * window is the gate that matters and it is a stricter one — nothing is read until Maxor's line
     * has arrived — while `calibrated` would additionally require the dungeon map to have been read,
     * which has nothing to do with whether a crit landed and would silently cost the reading on a
     * floor where calibration failed.
     *
     * The near-miss check runs outside the toggle on purpose: it writes to the debug log and not to
     * the screen, and a player who switched the readout off has not asked for the diagnostic that
     * tells the next session the pattern is wrong to stop as well.
     *
     * **This function is the unverified half.** That Fabric delivers Hypixel's crit line here with
     * `overlay` false, and that the accessor below returns a footer containing blessing rows, needs a
     * live `Minecraft` in an M7 — the dev client cannot reach Hypixel. Everything [CritMeter] decides
     * is driven by `CritMeterTest` instead.
     */
    private fun onCrit(text: String) {
        if (Config.critLine) {
            // [Chat.fields] rather than [Chat.value] on the whole string: a crit readout is three
            // figures with units between them and no sentence to label, so every part of it is a
            // value — but its separators have to be the same grey as the separators on every other
            // line, and `value(line)` painted them a shade brighter. The wording stays in CritMeter,
            // where CritMeterTest can drive it without a Minecraft.
            CritMeter.onChat(text, ::tabFooter)?.let { line -> Chat.say(Chat.fields(line)) }
        }
        // A crit line Hypixel sent and CritMeter could not read. Carries no player name — see
        // CritMeter.nearMiss for why that makes it safe to log unredacted — and it is the only thing
        // that will ever tell anybody the ported pattern is wrong.
        CritMeter.nearMiss(text)?.let { DebugLog.event("crit_unparsed", "line" to it) }
    }

    /**
     * Storm's countdown, started from his own chat line. [text] is already stripped.
     *
     * **Not gated on [Config.stormTimer]**, unlike the readout it feeds. The switch decides whether
     * the countdown is *drawn* ([StormHud]), and starting a clock nobody is looking at costs one
     * field; gating here instead would mean a player who switched the timer on mid-fight got nothing
     * until Storm spoke again, which is a whole cast later.
     *
     * **Not gated on [DungeonSession.calibrated]** either, on the same argument as [onCrit]:
     * `calibrated` requires the dungeon map to have been read, which has nothing to do with whether
     * Storm is casting, and a floor whose calibration failed would silently lose the timer.
     *
     * **This function and [StormHud] are the unverified half of `stormtimer-001`.** That Fabric
     * delivers Storm's line here with `overlay` false, that it is worded the way [StormTimer] assumes,
     * and that `renderHud` is reached at all during a boss phase, all need a live `Minecraft` in an
     * M7. `storm_start` is what says the first two worked; `storm_unparsed` quotes the line when they
     * did not.
     */
    private fun onStorm(text: String) {
        val client = Minecraft.getInstance()
        if (StormTimer.onChat(text) { client.level?.gameTime }) {
            // The positive signal. Zero of these AND zero `storm_unparsed` in an M7 where Storm spoke
            // means the line never reached this function, which is a different fault from the wording
            // being wrong and would be indistinguishable without both events.
            DebugLog.event("storm_start", "countdownTicks" to Config.stormCountdownTicks)
        }
        // Carries no player name — `[BOSS] ` is a server-only prefix, see StormTimer.nearMiss — and
        // it is the only thing that will ever confirm or correct the two strings.
        StormTimer.nearMiss(text)?.let { DebugLog.event("storm_unparsed", "line" to it) }
    }

    /**
     * The tab list footer as plain text, or null when the server has not sent one.
     *
     * Read through the mixin accessor because `PlayerTabOverlay.footer` has a setter and no getter.
     * Nothing here writes it.
     */
    private fun tabFooter(): String? =
        (Minecraft.getInstance().gui.tabList as PlayerTabOverlayAccessor).footer?.string

    /**
     * Logs an event against the player it names and the room they were last seen in.
     *
     * The room is the same inference [ContributionTracker.onDeath] charges against and carries the
     * same weakness — it is where the decoration last put them, not where they are. For a door that
     * is close to exact, since opening one is what puts you at it; for a puzzle it is the room the
     * puzzle is in, which is the answer that was wanted anyway.
     */
    private fun attributed(type: String, player: String) = DebugLog.event(
        type, "player" to Pseudonym.of(player), "room" to ContributionTracker.lastRoomOf(player)?.label(),
    )

    private fun renderHud(graphics: GuiGraphicsExtractor) {
        val client = Minecraft.getInstance()
        val font = client.font

        // The per-frame preamble for the whole UI, and the only place it happens. Density must be
        // established before anything draws a hairline; the clock is safe to drive from here and from
        // an open screen in the same frame, because it derives from the wall clock rather than
        // accumulating deltas.
        val window = client.window
        Density.beginFrame(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        Clock.frame(paused = client.isPaused)

        // Storm's countdown, and the only thing here drawn *before* the calibration gate below.
        // Storm is a boss phase and `calibrated` means the dungeon map was read during the clear
        // phase — the same argument that keeps `onCrit` off that gate. Its own state is the gate that
        // matters and it is a far stricter one: nothing is drawn until Storm has actually spoken.
        //
        // No second HudElementRegistry registration, deliberately. This repository holds one, and a
        // separate element would be a second unsynchronised read of the same fields on a callback
        // whose ordering against this one nothing here defines.
        StormHud.render(graphics, font, client.window.guiScaledWidth, client.window.guiScaledHeight, client.level?.gameTime)

        if (!DungeonSession.calibrated) return

        // Drawn before the corner readout and outside its switch: a centred element that fades
        // itself out is not part of the anchored block, and must not disappear with it.
        ClearPopup.render(graphics, font, client.window.guiScaledWidth, client.window.guiScaledHeight)

        // The corner readout, rebuilt on the design system. Everything below draws from
        // HudSnapshot.current, which the client tick published — see onTick.
        HudRoot.live.render(graphics, font)
    }

    private fun currentRoom(client: Minecraft): TrackedRoom? {
        val player = client.player ?: return null
        return ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(player.x, player.z))
    }

    companion object {
        const val ID = "sighteaddons"
        val LOGGER = LoggerFactory.getLogger(ID)!!

        private val STANDINGS: Identifier = Identifier.fromNamespaceAndPath(ID, "standings")
        /** Anchored, so a party member typing the headline cannot suppress the real summary. */
        private val RUN_END = Regex("""^\s*(?:Master Mode )?(?:The )?Catacombs - (?:Floor \w+|Entrance)\s*$""")

        /** Reset by [DungeonSession.reset], so each run prints its summary exactly once. */
        var summaryPrinted = false
    }
}
