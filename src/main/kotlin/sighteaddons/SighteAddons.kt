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
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.screens.GalleryScreen
import net.minecraft.network.chat.Component
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
            uploadNotice()
        }
        // The other way out of a floor, and the one that cost a real M7 on 2026-08-14: quitting the
        // game, or dropping to the title screen, from inside the dungeon. There is no headline and no
        // subsequent JOIN, so before this the run was simply gone — ten cleared rooms that never
        // reached the box (`docs/evidence/session-1786719912927/`).
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
        val client = Minecraft.getInstance()
        client.schedule {
            client.gui.chat.addClientSystemMessage(
                Component.literal("Sighte Addons ").withStyle(ChatFormatting.GOLD).append(
                    Component.literal(
                        "sends a report of each dungeon run to the mod's analysis server: " +
                            "rooms, times and classes, under a random id, without your name. " +
                            "Turn it off — or put your name on it — with /sa → debug.",
                    ).withStyle(ChatFormatting.GRAY),
                ),
            )
        }
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
        if (!DungeonSession.inDungeon(client)) return
        val map = DungeonMapReader.mapState(client) ?: return
        val wasCalibrated = DungeonSession.calibrated
        if (!DungeonSession.update(client, map)) {
            // In the boss: stop sampling rooms but keep the run clock going, so the summary
            // reports the real run time and not just the clear phase.
            if (DungeonSession.calibrated) DungeonSession.tickClock()
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
            // The one event with no name in it — Hypixel does not say who opened the blood door.
            ChatEvents.Event.BloodDoor -> DebugLog.event("blood_door")
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
            CritMeter.onChat(text, ::tabFooter)?.let { line ->
                val client = Minecraft.getInstance()
                client.schedule {
                    client.gui.chat.addClientSystemMessage(
                        Component.literal(line).withStyle(ChatFormatting.AQUA),
                    )
                }
            }
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

        if (!Config.hud) return
        var y = Config.hudY

        fun line(text: String, color: Int) {
            graphics.text(font, Component.literal(text), Config.hudX, y, color)
            y += 10
        }

        line(
            "Sighte ${DungeonSession.floor ?: ""} ${DungeonGrid.formatTicks(DungeonSession.runTicks)}" +
                "  ${ContributionTracker.roomsCleared} rooms",
            WHITE,
        )

        // Standing, and directly under the header on purpose: the header is the one line that is
        // always drawn, so anchoring here is what keeps this readout from moving up and down the
        // screen as another toggle changes. Its own switch rather than a part of `showRoom` — it is
        // about the run as much as about the room, and it still says something while you are walking
        // between rooms, which is when the room block below has nothing at all.
        //
        // Both reads are of state the client thread owns and the block around it already reads —
        // `currentRoom` is `ContributionTracker.roomAt`, and `visitedRooms()` is the same snapshot
        // `ContributionTracker.tick` takes every tick. Nothing off-thread mutates that map: the
        // DISCONNECT path deliberately does not reset (see the comment on its registration above),
        // which is what makes this a read of the existing shared state rather than a new one.
        if (Config.showSecrets) line(SecretHud.line(currentRoom(client), ContributionTracker.visitedRooms()), WHITE)

        // Same anchoring argument and the same thread argument as the line above: two run-level
        // counters that keep saying something while you are between rooms, which is precisely when
        // one of them is the number that is moving. The read is of two `Int`s the client thread
        // itself advances in `onTick` — nothing off-thread writes them (see IdleTime).
        if (Config.showIdle) line(IdleTime.line(), GREY)

        if (Config.showRoom) currentRoom(client)?.let { room ->
            val self = client.player?.name?.string
            val inRoom = self?.let { room.ticks[it] } ?: 0
            line("${room.label()}  ${DungeonGrid.formatTicks(inRoom)}", YELLOW)
            // Independent: a room can be cleared long before its last secret is found, or never get one.
            line("  cleared  ${room.clearedAtTick?.let(DungeonGrid::formatTicks) ?: "--:--.-"}", GREY)
            // Two numbers, never a blended one: room total from the action bar, and the subset that
            // coincided with your own interaction.
            val max = room.info?.secrets ?: 0
            val secrets = if (max > 0) "  ${room.secretsFound}/$max (${room.ownSecrets} you)" else ""
            // The secret run, live: it starts with the room's first secret and goes back to dashes
            // when the run is discarded, so a stalled room never reads as a fast one.
            val elapsed = room.secretRunElapsed(DungeonSession.runTicks)
            line("  secrets  ${elapsed?.let(DungeonGrid::formatTicks) ?: "--:--.-"}$secrets", GREY)
        }

        if (!Config.showStandings) return
        // Read every frame, and since `secretpoints-001` it moves between clears: your own secrets
        // are credited into this map on the tick they are attributed, not on the next checkmark.
        val points = ContributionTracker.pointsByPlayer()
        PartyTracker.roster()
            .map { it.name to (points[it.name] ?: 0.0) }
            .sortedByDescending { it.second }
            .forEach { (name, earned) ->
                // Locale.ROOT: a German default locale would render "2,50", inconsistent with the JSON log.
                line("%.2f  %s".format(Locale.ROOT, earned, name), GREY)
            }
    }

    private fun currentRoom(client: Minecraft): TrackedRoom? {
        val player = client.player ?: return null
        return ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(player.x, player.z))
    }

    companion object {
        const val ID = "sighteaddons"
        val LOGGER = LoggerFactory.getLogger(ID)!!

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFDD55.toInt()
        private const val GREY = 0xFFAAAAAA.toInt()

        private val STANDINGS: Identifier = Identifier.fromNamespaceAndPath(ID, "standings")
        /** Anchored, so a party member typing the headline cannot suppress the real summary. */
        private val RUN_END = Regex("""^\s*(?:Master Mode )?(?:The )?Catacombs - (?:Floor \w+|Entrance)\s*$""")

        /** Reset by [DungeonSession.reset], so each run prints its summary exactly once. */
        var summaryPrinted = false
    }
}
