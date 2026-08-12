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

    /** Last logged decoration→player assignment, so only changes are written to the debug log. */
    private val lastAssignment = HashMap<String, Pos>()

    /** Whether the map and the tab list currently disagree about who is alive. See [positions]. */
    private var skewed = false

    fun reset() {
        players.fill(null)
        lastAssignment.clear()
        localSlot = 0
        skewed = false
    }

    fun update(client: Minecraft) {
        val connection = client.connection ?: return
        // Keep null rows so indices stay aligned with the tab grid.
        val rows = connection.onlinePlayers
            .sortedWith(PlayerTabOverlayAccessor.getOrdering())
            .map { it.tabListDisplayName?.string?.trim() }
        for (i in players.indices) {
            val previous = players[i]
            val match = rows.getOrNull(1 + i * 4)?.let { TAB.matchEntire(it) }
            // groupValues: 1 = name, 2 = class, 3 = level
            val parsed = match
                ?.let { DungeonPlayer(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                ?.let { carryLiving(previous, it) }
            // The class flipping to DEAD is the only death signal the client gets without parsing
            // chat, and it is the strongest difficulty signal a room has.
            if (previous?.alive == true && parsed?.alive == false) {
                ContributionTracker.onDeath(previous.name)
            }
            // Compares the whole entry, not just the name: a class flipping to DEAD excludes that
            // player from attribution, so it has to be visible in the log.
            if (DebugLog.enabled && parsed != previous) {
                // The raw row is logged too: if the regex ever stops matching Hypixel's format,
                // this is the only place that shows what it actually received.
                DebugLog.event(
                    "tab_slot",
                    "slot" to i, "row" to rows.getOrNull(1 + i * 4),
                    "parsed" to parsed?.name, "class" to parsed?.dungeonClass, "alive" to parsed?.alive,
                )
            }
            players[i] = parsed
        }

        val self = client.player?.name?.string
        val slot = players.indexOfFirst { it != null && it.name == self }
        if (slot >= 0 && slot != localSlot) {
            localSlot = slot
            DebugLog.event("local_slot", "slot" to slot, "player" to self)
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
     * Every party member's current room, read from the map decorations. Works for members whose
     * chunks are not loaded on this client, which is what makes client-side attribution possible.
     */
    fun positions(map: MapItemSavedData): List<Pair<String, Pos>> {
        val mapEntrance = DungeonSession.mapEntrance ?: return emptyList()
        val physicalEntrance = DungeonSession.physicalEntrance ?: return emptyList()
        val roomSize = DungeonSession.mapRoomSize
        // ponytail: assumes decoration order matches tab order — same as Skyblocker and Odin.
        // Upgrade path if this ever mismatches: NoammAddons reads the decoration's map key instead,
        // whose last character is a digit identifying the player slot (needs a MapItemSavedData
        // accessor mixin, since getDecorations() drops the keys). That would also make the guard
        // below unnecessary, since the assignment would no longer depend on counting at all.
        val teammates = players.indices
            .filter { it != localSlot }
            .mapNotNull { players[it] }
            .filter { it.alive }

        // The map drops a dead player's marker 10-20 ticks *before* their tab row flips to DEAD, so
        // for about a second the two sources disagree about who is alive. Since teammates are matched
        // by position in the list, one missing marker shifts every teammate after it onto somebody
        // else's room — and ContributionTracker.onDeath charges a death at exactly that moment, off
        // the last room the victim was seen in. When the counts disagree the order is not
        // trustworthy, so teammates are skipped entirely for those ticks: no position at all beats a
        // wrong one, and the last good position is what the death then gets charged to. The local
        // player's own marker is identified by its type, not by counting, and stays valid throughout.
        val markers = map.decorations.count { it.type().value() != MapDecorationTypes.FRAME.value() }
        val trustOrder = markers == teammates.size
        if (trustOrder == skewed) {
            skewed = !trustOrder
            DebugLog.event("roster_skew", "skewed" to skewed, "markers" to markers, "alive" to teammates.size)
        }

        val result = mutableListOf<Pair<String, Pos>>()
        var next = 0
        for ((index, decoration) in map.decorations.withIndex()) {
            val player: DungeonPlayer? = if (decoration.type().value() == MapDecorationTypes.FRAME.value()) {
                players[localSlot]
            } else {
                if (!trustOrder) continue
                teammates.getOrNull(next++)
            }
            if (player == null || !player.alive) continue
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
                    "player" to player.name, "cell" to cell,
                    "worldX" to x, "worldZ" to z,
                    "decoIndex" to index, "decoType" to decoration.type().value(),
                    "decoX" to decoration.x(), "decoY" to decoration.y(),
                )
            }
        }
        return result
    }
}
