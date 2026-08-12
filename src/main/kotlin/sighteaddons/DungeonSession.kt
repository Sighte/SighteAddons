package sighteaddons

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.scores.DisplaySlot

/**
 * Tracks whether we are in the clearing phase of a dungeon and holds the calibration that
 * links world coordinates to map pixels.
 *
 * Calibration needs two anchors: the entrance room's northwest corner in the world (taken
 * from the Mort NPC armor stand, who always stands in the entrance) and the entrance room's
 * top-left pixel on the map.
 */
object DungeonSession {
    /** Sidebar shows e.g. "⏣ The Catacombs (F7)" — also matches "(M7)" and "(Entrance)". */
    private val FLOOR = Regex("""The Catacombs \((\w+)\)""")

    /** "F7", "M7", "Entrance", or null when not in a dungeon. */
    var floor: String? = null
        private set
    var physicalEntrance: Pos? = null
        private set
    var mapEntrance: Pos? = null
        private set
    var mapRoomSize = 0
        private set

    /** Ticks since calibration — the run clock all timestamps are relative to. */
    var runTicks = 0
        private set

    val floorNumber: Int? get() = floor?.lastOrNull()?.digitToIntOrNull()
    val calibrated get() = physicalEntrance != null && mapEntrance != null && mapRoomSize > 0

    fun tickClock() {
        runTicks++
    }

    fun reset() {
        floor = null
        runTicks = 0
        physicalEntrance = null
        mapEntrance = null
        mapRoomSize = 0
        SighteAddons.summaryPrinted = false
        ContributionTracker.reset()
        PartyTracker.reset()
        SecretTracker.reset()
    }

    /** Reads the floor from the sidebar as a side effect; that is also the in-dungeon check. */
    fun inDungeon(client: Minecraft): Boolean {
        floor = sidebarLines(client).firstNotNullOfOrNull { FLOOR.find(it)?.groupValues?.get(1) }
        return floor != null
    }

    /**
     * The boss room is a fixed area per floor, so a coordinate check is enough — no need to guess
     * from chat or from the map switching to the boss layout. Thresholds per floor as used by Odin.
     */
    fun inBoss(client: Minecraft): Boolean {
        val player = client.player ?: return false
        return when (floorNumber) {
            1 -> player.x > -71 && player.z > -39
            2, 3, 4 -> player.x > -39 && player.z > -39
            5, 6 -> player.x > -39 && player.z > -7
            7 -> player.x > -7 && player.z > -7
            else -> false
        }
    }

    /** Calibrates once per run. Returns false while calibration is incomplete or in the boss. */
    fun update(client: Minecraft, map: MapItemSavedData): Boolean {
        if (inBoss(client)) return false
        if (physicalEntrance == null) {
            val mort = mortPos(client)
            if (mort == null) {
                DebugLog.event("calibration_waiting", "reason" to "mort armor stand not found")
                return false
            }
            physicalEntrance = DungeonGrid.physicalRoomPos(mort.first, mort.second)
            DebugLog.event(
                "mort_found",
                "x" to mort.first, "z" to mort.second, "physicalEntrance" to physicalEntrance,
            )
        }
        if (mapEntrance == null || mapRoomSize == 0) {
            val calibration = DungeonMapReader.calibrate(map)
            if (calibration == null) {
                DebugLog.event("calibration_waiting", "reason" to "no entrance block on map")
                return false
            }
            val (pos, size) = calibration
            mapEntrance = pos
            mapRoomSize = size
            SighteAddons.LOGGER.info(
                "Dungeon {} calibrated: map entrance {}, room size {}, physical entrance {}",
                floor, pos, size, physicalEntrance,
            )
            DebugLog.event(
                "calibrated",
                "floor" to floor, "mapEntrance" to pos, "mapRoomSize" to size,
                "physicalEntrance" to physicalEntrance,
            )
        }
        return true
    }

    private fun sidebarLines(client: Minecraft): List<String> {
        val scoreboard = client.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.BY_ID.apply(1)) ?: return emptyList()
        return scoreboard.trackedPlayers.mapNotNull { holder ->
            if (!scoreboard.listPlayerScores(holder).containsKey(objective)) return@mapNotNull null
            val team = scoreboard.getPlayersTeam(holder.scoreboardName) ?: return@mapNotNull null
            // Hypixel puts formatting codes inside the floor parentheses, so strip them first.
            ChatFormatting.stripFormatting(team.playerPrefix.string + team.playerSuffix.string)
        }
    }

    private fun mortPos(client: Minecraft): Pair<Double, Double>? {
        val level = client.level ?: return null
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            if (entity.customName?.string?.contains("Mort") != true) continue
            return entity.position().x to entity.position().z
        }
        return null
    }
}
