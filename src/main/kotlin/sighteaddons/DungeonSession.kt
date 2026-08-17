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

    /**
     * The floor this run is on: "F7", "M7", "Entrance", or null before one has been seen.
     *
     * **The last floor seen, not the floor the sidebar is showing right now**, and the difference is
     * `floorloss-001`. Until 0.10.0 this field was assigned by [inDungeon] on every tick — including
     * the ticks after the player left — so it was `null` again by the time anything wanted to read
     * it. Measured on the box: 20 of 22 uploaded reports carried `?`, and the only two that named a
     * floor were written from the run-end headline, while the player was still inside. Two of the
     * three report paths ([SighteAddons]'s `JOIN` and `DISCONNECT`) fire *after* leaving by
     * definition, so for them the live reading is always the wrong one.
     *
     * It is per-run rather than per-process because [reset] clears it, and [reset] runs on `JOIN` —
     * entering or leaving a dungeon is a server transfer on Hypixel, so every run starts with this
     * `null` and no run can inherit the previous one's floor.
     *
     * `@Volatile` because `ClientPlayConnectionEvents.DISCONNECT` reads it from a Netty event-loop
     * thread while the client thread is the one writing it — the same reason
     * [RunReport.reported] is an `AtomicBoolean`.
     */
    @Volatile
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
        // The only place the floor is forgotten, since `floorloss-001`. It has to be here and
        // nowhere else: this runs on `JOIN`, *after* the JOIN site has written the report for the
        // run being left. Clearing it any earlier — or letting the in-dungeon check clear it, which
        // is what used to happen — files that report under `?`.
        floor = null
        runTicks = 0
        // Per run, like the floor: a latch carried into the next one would tell it the clear phase
        // was over before it began, and nothing would be sampled at all.
        bossStarted = false
        mapMissingTicks = 0
        physicalEntrance = null
        mapEntrance = null
        mapRoomSize = 0
        SighteAddons.summaryPrinted = false
        // The next run gets to be reported: RunReport's guard is per run, and this is where a run
        // ends. Before `runloss-001` the JOIN site did the guarding with `summaryPrinted` alone,
        // which could not see a report written on the way out.
        RunReport.reset()
        ContributionTracker.reset()
        // Counted against `runTicks`, so it is forgotten where `runTicks` is: a run that inherited
        // the previous floor's idle time would report a number describing two runs.
        IdleTime.reset()
        // The Maxor window belongs to the floor it opened on. Left standing, a crit line landing in
        // the next run's entrance would be read against that run's blessings.
        CritMeter.reset()
        // Storm's countdown belongs to the fight it started in. World time does not reset with the
        // run, so one carried across would read as long expired rather than as running — quiet, but
        // a run must not inherit any of the previous one's state.
        StormTimer.reset()
        PartyTracker.reset()
        SecretTracker.reset()
        DungeonTab.reset()
        // Drops the run's opening snapshot with it. A baseline that outlived its run would be
        // subtracted from the next one and credit every player with the difference between two
        // unrelated floors.
        SecretApi.reset()
        ClearPopup.reset()
    }

    /** Reads the floor from the sidebar as a side effect; that is also the in-dungeon check. */
    fun inDungeon(client: Minecraft): Boolean = observeSidebar(sidebarLines(client))

    /**
     * Whether [lines] are a dungeon's sidebar — and, if they are, the floor they name is remembered.
     *
     * **Two jobs that used to be one assignment, and separating them is the whole of
     * `floorloss-001`.** The answer returned is about *now*: it gates the entire session state
     * machine in `SighteAddons.onTick`, so it must go false the moment the sidebar stops naming a
     * floor. What must *not* go with it is the fact the report needs — so a sidebar with no floor in
     * it leaves [floor] alone rather than clearing it. Only [reset] clears, and [reset] is where a
     * run actually ends.
     *
     * A floor that is seen replaces whatever was there. That matters for nothing today (the sidebar
     * cannot change floor without a server transfer, and a transfer resets) but it keeps the field
     * meaning "the last floor seen" rather than "the first", which is the reading that stays true if
     * Hypixel ever changes that.
     *
     * `internal` and taking the lines rather than a `Minecraft` because `sidebarLines` needs a live
     * client and this does not. It is the seam the tests drive; the real regex runs on the real
     * strings either way.
     */
    internal fun observeSidebar(lines: List<String>): Boolean {
        val seen = lines.firstNotNullOfOrNull { FLOOR.find(it)?.groupValues?.get(1) }
        if (seen != null) floor = seen
        return seen != null
    }

    /**
     * Whether the boss fight has started. Latched, and only [reset] clears it.
     *
     * Latched because the clear phase does not come back: the boss door is one way, and every
     * consumer of [inBoss] — room sampling, [IdleTime], the HUD — is asking "is the clear over",
     * which is a question that only gets answered once per run.
     *
     * `@Volatile` for the same reason [floor] is: it is written on the client thread and the report
     * paths can read it from a Netty thread.
     */
    @Volatile
    var bossStarted = false
        private set

    /**
     * Boss dialogue, which is the signal that does not depend on knowing where the boss room is.
     *
     * Anchored, and safe anchored for the reason [CritMeter.nearMiss] and [StormTimer.nearMiss] both
     * rely on: `[BOSS] ` is a prefix only the server writes. A player saying it has their own name in
     * front of it, so no party member can start this by typing.
     */
    private val BOSS_CHAT = Regex("""^\[BOSS] .+""")

    /**
     * Consecutive ticks the dungeon map has been unreadable, since the last time it was not.
     *
     * The third boss signal, and the only one of the three the M1 of 2026-08-17 actually produced:
     * the map left hotbar slot 9 and never came back, which is what stopped that run's clock dead.
     *
     * **Not latched, unlike the other two.** A missing map is strong evidence and weak proof — it is
     * also what a player briefly rearranging their hotbar looks like — so this one is allowed to
     * change its mind when the map returns. It costs nothing to be wrong about for a second: room
     * sampling reads the map and is already stopped for as long as it is gone, so all this decides is
     * whether the HUD is on screen while nothing can be tracked anyway.
     */
    private var mapMissingTicks = 0

    /** Two seconds. Long enough that a hotbar swap does not read as a boss. */
    private const val MAP_GONE_TICKS = 40

    /** Called once per tick from `SighteAddons.onTick`, before anything reads [inBoss]. */
    fun observeMap(present: Boolean) {
        if (present) {
            mapMissingTicks = 0
            return
        }
        mapMissingTicks++
        // Exactly on the crossing, so one outage logs one line.
        if (mapMissingTicks == MAP_GONE_TICKS && calibrated) {
            DebugLog.event("map_lost", "at" to runTicks, "bossStarted" to bossStarted)
        }
    }

    /**
     * A chat line, already stripped, offered as evidence that the boss has started.
     *
     * **The floor thresholds in [inBoss] are inherited numbers and the M1 of 2026-08-17 could not
     * confirm a single one of them** — the tick loop never reached the check, so it has still never
     * been observed returning true in a real game. This is the second, independent signal: every
     * dungeon boss speaks on entry, on every floor, and that line arrives whatever this mod believes
     * about coordinates.
     *
     * Gated on [calibrated] so a `[BOSS]` line cannot latch anything outside a run that is actually
     * being tracked.
     */
    fun observeChat(text: String) {
        if (bossStarted || !calibrated) return
        if (!isBossLine(text)) return
        bossStarted = true
        DebugLog.event("boss_start", "by" to "chat", "at" to runTicks)
    }

    /**
     * The seam the tests drive, for the same reason [observeSidebar] is one: the decision is a regex
     * over a string, and everything around it needs a live client this repository cannot build.
     *
     * What it has to get right is the anchor. A latch that any party member could set by typing would
     * let one of them take the HUD off your screen and stop your run being sampled.
     */
    internal fun isBossLine(text: String) = BOSS_CHAT.matches(text)

    /**
     * The boss room is a fixed area per floor, so a coordinate check is enough — no need to guess
     * from the map switching to the boss layout. Thresholds per floor as used by Odin.
     *
     * The position check **latches only once the run is calibrated**, and that asymmetry is not
     * tidiness. [update] consults this before it calibrates, so a latch that could fire in the
     * entrance would return false forever after and the run would never be calibrated at all —
     * the mod would sit out the whole floor. Before calibration this stays a live reading that
     * corrects itself; after it, the answer is kept.
     */
    fun inBoss(client: Minecraft): Boolean {
        if (bossStarted) return true
        if (calibrated && mapMissingTicks >= MAP_GONE_TICKS) return true
        val player = client.player ?: return false
        val here = when (floorNumber) {
            1 -> player.x > -71 && player.z > -39
            2, 3, 4 -> player.x > -39 && player.z > -39
            5, 6 -> player.x > -39 && player.z > -7
            7 -> player.x > -7 && player.z > -7
            else -> false
        }
        if (here && calibrated) {
            bossStarted = true
            DebugLog.event("boss_start", "by" to "position", "at" to runTicks)
        }
        return here
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
