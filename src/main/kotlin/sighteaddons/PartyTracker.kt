package sighteaddons

import sighteaddons.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/**
 * [level] is the class level from the tab row, empty when the row carries none. It is the closest
 * thing the client has to a power rating, which is what makes room times comparable across parties.
 */
data class DungeonPlayer(
    val name: String,
    val dungeonClass: String,
    val level: String = "",
    /**
     * Class and level from the last row that was not `DEAD`, carried across deaths by
     * [PartyTracker.carryLiving]. A dead player's tab row reads `Name (DEAD)` and nothing else, so
     * whoever is dead when the run ends would otherwise reach the permanent run report with their
     * class erased — and the class level is exactly the normaliser that makes room times comparable
     * between parties. Defaults to the live values, so a roster built by hand needs no second pair.
     */
    val livingClass: String = dungeonClass,
    val livingLevel: String = level,
) {
    val alive get() = dungeonClass != "DEAD" && dungeonClass != "EMPTY"
}

/**
 * The party, read from the dungeon tab list, and where each member currently is.
 *
 * Hypixel puts the five party slots on tab rows 1, 5, 9, 13 and 17 of the vanilla-sorted
 * player list, and only shows `Name (Class Level)` — no per-player secrets, deaths or rooms.
 * That is exactly the gap this mod fills.
 */
object PartyTracker {
    /**
     * `[42] [MVP+] Name (Berserk VII)` — tolerates rank prefixes, emblems and the ironman icon.
     *
     * The rank class is `[^\]]+` and repeatable on purpose. Skyblocker and Odin both use classes
     * that exclude `+`, which would fail on every MVP+ / VIP+ player and leave the roster empty —
     * the most damaging possible failure, since nothing at all gets tracked then.
     */
    internal val TAB = Regex("""\[\d+] (?:\[[^\]]+] )*(?<name>[A-Za-z0-9_]+) (?:.+ )?\((?<cls>\S+) ?(?<lvl>[LXVI0]+)?\)""")

    /** Tab order, which is also the order the map decorations come in. Nulls keep the slots aligned. */
    private val players = arrayOfNulls<DungeonPlayer>(5)

    /**
     * Tab slot of the local player, whose marker is the frame. Looked up by name rather than assumed
     * to be slot 0: if Hypixel ever sorts the local player elsewhere, assuming it would hand the
     * frame — the one marker that is identified by type and always correct — to a teammate and shift
     * every other assignment with it.
     */
    private var localSlot = 0

    /**
     * The local player's Minecraft name, kept from the last [update] of this run.
     *
     * **Held rather than looked up because the run report has to survive the client going away.**
     * `RunReport.write` is keyed on this name — it picks the uploader's own class out of the roster
     * and their own ticks out of every room's tick map — and since `runloss-001` one of its call
     * sites is `ClientPlayConnectionEvents.DISCONNECT`, where `Minecraft.getInstance().player` is
     * being torn down on another thread and may already be null. Measured, not assumed: see the
     * disassembly cited on [RunReport.uploader]. A field that was read once a second all run long
     * cannot be racing anything by the time the connection drops.
     *
     * Nulled by [reset], like everything else here, so it never carries into the next run.
     */
    internal var localName: String? = null
        private set

    /** Last logged decoration→player assignment, so only changes are written to the debug log. */
    private val lastAssignment = HashMap<String, Pos>()

    /** Whether the map and the tab list currently disagree about who is alive. See [positions]. */
    private var skewed = false

    fun reset() {
        players.fill(null)
        lastAssignment.clear()
        localSlot = 0
        localName = null
        skewed = false
    }

    fun update(client: Minecraft) {
        val connection = client.connection ?: return
        // Keep null rows so indices stay aligned with the tab grid.
        val rows = connection.onlinePlayers
            .sortedWith(PlayerTabOverlayAccessor.getOrdering())
            .map { it.tabListDisplayName?.string?.trim() }
        // The five party rows below are five entries of roughly eighty. The rest of the list carries
        // the floor's own totals, and the sort — not the parsing — is the expensive part, so the one
        // reader that wants the whole list is handed the list this one already built rather than
        // sorting it a second time each second. See DungeonTab for what it takes out of it.
        DungeonTab.observe(rows)
        for (i in players.indices) {
            val previous = players[i]
            val match = rows.getOrNull(1 + i * 4)?.let { TAB.matchEntire(it) }
            // groupValues: 1 = name, 2 = class, 3 = level
            val parsed = match
                ?.let { DungeonPlayer(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                ?.let { carryLiving(previous, it) }
            // The class flipping to DEAD is the death signal that needs no chat line, and it is the
            // strongest difficulty signal a room has. Since `chat-001` it is the *second* source
            // rather than the only one: ChatEvents reads the same death off Hypixel's announcement,
            // on the tick it happens, and whichever arrives first charges it — see
            // ContributionTracker.DeathSource for why this path is kept rather than replaced.
            // This update runs once a second, so `at` here is already up to 20 ticks late.
            if (previous?.alive == true && parsed?.alive == false) {
                ContributionTracker.onDeath(
                    previous.name, DungeonSession.runTicks, ContributionTracker.DeathSource.TAB,
                )
            }
            // Compares the whole entry, not just the name: a class flipping to DEAD excludes that
            // player from attribution, so it has to be visible in the log.
            if (DebugLog.enabled && parsed != previous) {
                // The raw row is logged too: if the regex ever stops matching Hypixel's format,
                // this is the only place that shows what it actually received. Only the name in it
                // is replaced — see [Pseudonym.row] for what happens when it does not parse.
                DebugLog.event(
                    "tab_slot",
                    "slot" to i, "row" to Pseudonym.row(rows.getOrNull(1 + i * 4)),
                    "parsed" to parsed?.name?.let(Pseudonym::of),
                    "class" to parsed?.dungeonClass, "alive" to parsed?.alive,
                )
            }
            players[i] = parsed
        }

        val self = client.player?.name?.string
        // Only ever overwritten with a real name: a null here means the client is between players,
        // and forgetting who we are because of one such tick is exactly the loss [localName] exists
        // to prevent.
        if (self != null) localName = self
        val slot = players.indexOfFirst { it != null && it.name == self }
        if (slot >= 0 && slot != localSlot) {
            localSlot = slot
            DebugLog.event("local_slot", "slot" to slot, "player" to self?.let(Pseudonym::of))
        }
    }

    /**
     * Keeps [DungeonPlayer.livingClass] alive across a death. Only carries within the same slot and
     * the same name, so a party member being replaced starts clean rather than inheriting a class
     * that was never theirs.
     */
    internal fun carryLiving(previous: DungeonPlayer?, parsed: DungeonPlayer): DungeonPlayer =
        if (!parsed.alive && previous != null && previous.name == parsed.name) {
            parsed.copy(livingClass = previous.livingClass, livingLevel = previous.livingLevel)
        } else {
            parsed
        }

    fun roster(): List<DungeonPlayer> = players.filterNotNull()

    /**
     * The outcome of [assign]: which roster slot each decoration belongs to, plus the two counts the
     * decision was made from, so the caller can log them without recomputing them.
     */
    internal data class Assignment(
        /** One entry per decoration, in map order. `null` where the decoration was left unassigned. */
        val slots: List<Int?>,
        /** Whether the marker count matched the living-teammate count. See [assign]. */
        val trustOrder: Boolean,
        val markers: Int,
        val aliveTeammates: Int,
    )

    /**
     * Decoration → roster slot, and the whole of the attribution heuristic.
     *
     * Pure on purpose, and that is a design decision rather than a tidy-up: [positions] takes a
     * `MapItemSavedData` and reads `DungeonSession` statics, so it could not be tested at all, while
     * being both the least verifiable part of the mod *and* the loop room discovery runs through —
     * `ContributionTracker.tick` only ever creates a room some decoration resolved into, so a change
     * here changes which rooms exist, get named, get cleared and get scored. Same extraction seam as
     * `SecretTracker.parseSecrets`, [TAB], `Pseudonym.row` and `ContributionTracker`'s `TrackedRoom`.
     *
     * [isFrame] is one boolean per decoration, in map order. The local player's marker is identified
     * by its decoration *type* and is therefore always correct; everybody else is matched by position
     * in the list, which is the assumption below.
     *
     * ponytail: assumes decoration order matches tab order — same as Skyblocker and Odin.
     *
     * **There is no known way to do better client-side, and the upgrade path this comment used to
     * name was wrong.** It claimed NoammAddons reads the decoration's map key, whose last character
     * is a digit identifying the player slot, and that an accessor mixin over the private
     * `MapItemSavedData.decorations` map would therefore remove the counting. Measured against the
     * `26.1.2` classes this module compiles against, both halves are false:
     *
     * - `ClientboundMapItemDataPacket` carries `Optional<List<MapDecoration>>` — an **unkeyed list**.
     *   No key is ever transmitted. `MapItemSavedData.addClientSideDecorations` clears the private
     *   map and re-keys every entry `"icon-" + i` from its own loop index (that class's string-concat
     *   bootstrap constants are literally `icon-` and `frame-`). The map is a
     *   `LinkedHashMap`, so an accessor would hand back this client's own list order, spelled as a
     *   string — and past nine decorations the "last character" is not even the whole index.
     * - NoammAddons does not do what the comment said either. Its `DungeonUtils.kt` reads
     *   `val index = key[key.lastIndex].digitToInt()` and uses it as an index into `livingTeammates`:
     *   the identical order heuristic, with an extra defect this one does not have — the digit counts
     *   the local player's own marker while the list it indexes does not contain them.
     *
     * The one channel that could still carry identity is `MapDecoration.name()`
     * (`Optional<Component>`), which does survive the wire. Whether Hypixel populates it on a dungeon
     * map is unknown and unknowable from here — tracked as `deconame-001`.
     */
    internal fun assign(roster: List<DungeonPlayer?>, localSlot: Int, isFrame: List<Boolean>): Assignment {
        val teammates = roster.indices
            .filter { it != localSlot }
            .filter { roster[it]?.alive == true }

        // The map drops a dead player's marker 10-20 ticks *before* their tab row flips to DEAD, so
        // for about a second the two sources disagree about who is alive. Since teammates are matched
        // by position in the list, one missing marker shifts every teammate after it onto somebody
        // else's marker — a different *room*, so a different cell, which is the damaging failure and
        // the one worth guarding. (Two teammates standing in one room produce two decorations a few
        // pixels apart that both resolve to that room's cell, so mixing those two up changes
        // nothing.) ContributionTracker.onDeath charges a death at exactly the moment the counts
        // disagree, off the last room the victim was seen in. So when they disagree the order is not
        // trustworthy and teammates are skipped entirely for those ticks: no position at all beats a
        // wrong one, and the last good position is what the death then gets charged to. The local
        // player's own marker is identified by its type, not by counting, and stays valid throughout.
        val markers = isFrame.count { !it }
        val trustOrder = markers == teammates.size

        var next = 0
        val slots = isFrame.map { frame ->
            if (frame) {
                localSlot.takeIf { roster.getOrNull(it)?.alive == true }
            } else {
                if (trustOrder) teammates.getOrNull(next++) else null
            }
        }
        return Assignment(slots, trustOrder, markers, teammates.size)
    }

    /**
     * Every party member's current room, read from the map decorations. Works for members whose
     * chunks are not loaded on this client, which is what makes client-side attribution possible.
     *
     * Everything decidable is decided in [assign]; what is left here is the Minecraft plumbing, the
     * grid math and the logging — none of which is testable without a client.
     */
    fun positions(map: MapItemSavedData): List<Pair<String, Pos>> {
        val mapEntrance = DungeonSession.mapEntrance ?: return emptyList()
        val physicalEntrance = DungeonSession.physicalEntrance ?: return emptyList()
        val roomSize = DungeonSession.mapRoomSize
        // Snapshotted once: getDecorations() is a live view of the underlying LinkedHashMap, and the
        // counts and the assignment have to be made against one and the same list.
        val decorations = map.decorations.toList()
        val assignment = assign(
            players.toList(),
            localSlot,
            decorations.map { it.type().value() == MapDecorationTypes.FRAME.value() },
        )
        if (assignment.trustOrder == skewed) {
            skewed = !assignment.trustOrder
            DebugLog.event(
                "roster_skew",
                "skewed" to skewed,
                "markers" to assignment.markers,
                "alive" to assignment.aliveTeammates,
            )
        }

        val result = mutableListOf<Pair<String, Pos>>()
        for ((index, decoration) in decorations.withIndex()) {
            val player = assignment.slots[index]?.let { players[it] } ?: continue
            val (x, z) = DungeonGrid.mapToPhysical(
                mapEntrance,
                roomSize,
                physicalEntrance,
                decoration.x() / 2.0 + 64,
                decoration.y() / 2.0 + 64,
            )
            val cell = DungeonGrid.physicalRoomPos(x, z)
            result.add(player.name to cell)

            // The decoration→player mapping is the least verifiable part of the mod, so log every
            // change together with the raw decoration it came from.
            if (DebugLog.enabled && lastAssignment.put(player.name, cell) != cell) {
                DebugLog.event(
                    "player_room",
                    "player" to Pseudonym.of(player.name), "cell" to cell,
                    "worldX" to x, "worldZ" to z,
                    "decoIndex" to index, "decoType" to decoration.type().value(),
                    "decoX" to decoration.x(), "decoY" to decoration.y(),
                )
            }
        }
        return result
    }
}
