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
     * The shapes Hypixel writes a dungeon duration in, all of them seen on a real floor: `59s`,
     * `01m 00s`, and the colon form. Shared with [DungeonTab] so the sidebar and the tab list cannot
     * disagree about what a time looks like.
     */
    internal val TIME_VALUE = Regex("""(?:\d{1,2}h ?)?(?:\d{1,2}m ?)?\d{1,2}s|\d{1,3}:\d{2}""")

    /** See [readCleared]. The bracketed score is optional; the percentage is not. */
    private val CLEARED = Regex("""(?i)Cleared: (\d{1,3})%(?: \((\d+)\))?""")

    /**
     * `Time Elapsed: 01m 00s` on the sidebar — Hypixel's own clock, live.
     *
     * **Under a minute it is `59s`, with no minute part at all.** Measured on the M7s of 2026-08-19:
     * requiring the minutes made the first sixty seconds of every run unreadable, which is exactly the
     * window a fast clear-phase score is reached in.
     */
    private val ELAPSED = Regex(TIME_VALUE.pattern.let { """(?i)Time Elapsed: ($it)""" })

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

    /**
     * The live score Hypixel publishes on the sidebar, or null if that line has never carried one.
     *
     * Never cleared by a reading that lacks it: the sidebar is rebuilt by the server and a single tick
     * without the bracket would otherwise drop the gate's input to nothing. `reset()` owns its lifetime,
     * like [floor].
     */
    @Volatile
    var sidebarScore: Int? = null
        private set

    /** The sidebar's clear percentage as a fraction, for [LiveScore.totalRooms]. */
    @Volatile
    var clearedFraction: Double? = null
        private set

    /** Hypixel's elapsed time off the sidebar, kept for the announcement. */
    @Volatile
    var sidebarTime: String? = null
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
        inBoss = false
        mapMissingTicks = 0
        physicalEntrance = null
        mapEntrance = null
        mapRoomSize = 0
        SighteAddons.summaryPrinted = false
        // The next run gets to be reported: RunReport's guard is per run, and this is where a run
        // ends. Before `runloss-001` the JOIN site did the guarding with `summaryPrinted` alone,
        // which could not see a report written on the way out.
        RunReport.reset()
        // Per run for the same reason: a `solo` latch carried into the next floor would announce a
        // party run as a solo clear, and the flags only mean anything about one run.
        SoloClear.reset()
        LiveScore.reset()
        sidebarScore = null
        clearedFraction = null
        sidebarTime = null
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
        // The blood room belongs to the floor it was fought on. A start tick carried into the next
        // run would be subtracted from that run's pass line and file a blood clear measured across
        // two floors.
        BloodClear.reset()
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
        readCleared(lines)
        sidebarTime = lines.firstNotNullOfOrNull { ELAPSED.find(it)?.groupValues?.get(1) } ?: sidebarTime
        return seen != null
    }

    /**
     * `Cleared: 68% (152)` — the clear percentage and, in brackets, **the run's live score**.
     *
     * Both halves are read here so a reader gets them from one line rather than from two passes that
     * could disagree: [LiveScore] wants the score when it is there and the percentage to derive the
     * floor's room count from when it is not, and those two answers describe the same instant.
     *
     * The bracket is optional in the pattern on purpose. Nothing in this repository has seen this line on
     * a real floor; if Hypixel prints only the percentage, the score stays null and [LiveScore] falls
     * through to its next source rather than reading a wrong number out of a partial match.
     */
    private fun readCleared(lines: List<String>) {
        for (line in lines) {
            val match = CLEARED.find(line) ?: continue
            clearedFraction = match.groupValues[1].toDoubleOrNull()?.div(100.0) ?: clearedFraction
            match.groupValues[2].toIntOrNull()?.let { sidebarScore = it }
            return
        }
    }

    /**
     * Whether the player is in the boss room. Recomputed once per tick by [observe].
     *
     * A field rather than a call so the two readers in a tick — [update] and the HUD snapshot — get
     * the same answer, and so the transition can be logged where it happens. Odin holds it the same
     * way and for the same reason.
     *
     * **Not latched.** The clear phase does not come back in a real run, so a latch would cost
     * nothing there — but it costs a great deal when the answer is wrong, and this answer is a
     * coordinate threshold per floor. A latched false positive in the entrance would make [update]
     * return false for the rest of the run and the mod would sit out the whole floor. Live, a wrong
     * reading corrects itself the moment the player moves.
     *
     * `@Volatile` for the same reason [floor] is: written on the client thread, and the report paths
     * can read it from a Netty thread.
     */
    @Volatile
    var inBoss = false
        private set

    /**
     * Consecutive ticks the dungeon map has been unreadable, since the last time it was not.
     *
     * The second boss signal, and the only one the M1 of 2026-08-17 actually produced: Hypixel takes
     * the map out of hotbar slot 9 for the boss and it never comes back. Measured in that run's
     * second floor — the blood door opened at tick 1014 and the map went at 2557, so this does not
     * fire at the blood room.
     *
     * Not in Odin, which needs no such thing: coordinates alone have carried that mod for years. It
     * is kept here as the belt to their braces, because this repository has still never watched the
     * thresholds return true in a real game, and because it costs nothing to be wrong about — room
     * sampling reads the map and is already stopped for as long as it is gone, so all this decides is
     * whether the HUD is on screen while nothing can be tracked anyway.
     */
    private var mapMissingTicks = 0

    /** Two seconds. Long enough that a hotbar swap does not read as a boss. */
    private const val MAP_GONE_TICKS = 40

    /**
     * Recomputes [inBoss]. Called once per tick from `SighteAddons.onTick`, before anything reads it.
     *
     * **There is no chat signal here, and that is a correction rather than an omission.** 0.16.0-dev5
     * latched the boss on any anchored `[BOSS] ` line, on the reasoning that every floor's boss speaks
     * on entry. So does The Watcher, who is `[BOSS] `-prefixed and stands in the blood room: measured
     * on the M1 of 2026-08-17, `blood_door` at tick 393 and the latch two ticks later at 395, a full
     * boss's worth of floor early. Boss dialogue does not identify the boss room.
     */
    fun observe(client: Minecraft, mapPresent: Boolean) {
        mapMissingTicks = if (mapPresent) 0 else mapMissingTicks + 1

        val player = client.player
        val byPosition = player != null && atBoss(floorNumber, player.x, player.z)
        // The map is only allowed to speak once the run is calibrated: before that there is no run to
        // be past the clear phase of, and slot 9 is empty in every lobby.
        val byMap = calibrated && mapMissingTicks >= MAP_GONE_TICKS
        val now = byPosition || byMap

        if (now == inBoss) return
        inBoss = now
        DebugLog.event(
            "boss_phase", "inBoss" to now, "at" to runTicks,
            "by" to if (byPosition) "position" else "map",
        )
    }

    /**
     * The boss room is a fixed area per floor, so a coordinate check is enough. Thresholds per floor
     * exactly as Odin's `DungeonListener.getBoss` uses them, which is where they were ported from.
     *
     * Pure, and the seam the tests drive — [observeSidebar] is the other one. What it has to get
     * right is that no part of the *clear* phase falls inside these regions, and that is checkable
     * against the positions a real floor logged.
     */
    internal fun atBoss(floorNumber: Int?, x: Double, z: Double): Boolean = when (floorNumber) {
        1 -> x > -71 && z > -39
        2, 3, 4 -> x > -39 && z > -39
        5, 6 -> x > -39 && z > -7
        7 -> x > -7 && z > -7
        else -> false
    }

    /** Calibrates once per run. Returns false while calibration is incomplete or in the boss. */
    fun update(client: Minecraft, map: MapItemSavedData): Boolean {
        if (inBoss) return false
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
