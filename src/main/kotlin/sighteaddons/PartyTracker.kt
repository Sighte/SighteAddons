package sighteaddons

import sighteaddons.mixin.PlayerTabOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

data class DungeonPlayer(val name: String, val dungeonClass: String) {
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

    /** Index 0 is always the local player. Order matters: it maps decorations to names. */
    private val players = arrayOfNulls<DungeonPlayer>(5)

    /** Last logged decoration→player assignment, so only changes are written to the debug log. */
    private val lastAssignment = HashMap<String, Pos>()

    fun reset() {
        players.fill(null)
        lastAssignment.clear()
    }

    fun update(client: Minecraft) {
        val connection = client.connection ?: return
        // Keep null rows so indices stay aligned with the tab grid.
        val rows = connection.onlinePlayers
            .sortedWith(PlayerTabOverlayAccessor.getOrdering())
            .map { it.tabListDisplayName?.string?.trim() }
        for (i in players.indices) {
            val match = rows.getOrNull(1 + i * 4)?.let { TAB.matchEntire(it) }
            // groupValues: 1 = name, 2 = class, 3 = level
            val parsed = match?.let { DungeonPlayer(it.groupValues[1], it.groupValues[2]) }
            // Compares the whole entry, not just the name: a class flipping to DEAD excludes that
            // player from attribution, so it has to be visible in the log.
            if (DebugLog.enabled && parsed != players[i]) {
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
        val result = mutableListOf<Pair<String, Pos>>()
        var next = 1
        for ((index, decoration) in map.decorations.withIndex()) {
            val player: DungeonPlayer?
            if (decoration.type().value() == MapDecorationTypes.FRAME.value()) {
                player = players[0]
            } else {
                // ponytail: assumes decoration order matches tab order — same as Skyblocker and
                // Odin. Upgrade path if this ever mismatches: NoammAddons reads the decoration's
                // map key instead, whose last character is a digit identifying the player slot
                // (needs a MapItemSavedData accessor mixin, since getDecorations() drops the keys).
                var candidate: DungeonPlayer? = null
                while (next < players.size && (candidate == null || !candidate.alive)) {
                    candidate = players[next]
                    next++
                }
                player = candidate
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
