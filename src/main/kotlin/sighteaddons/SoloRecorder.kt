package sighteaddons

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/**
 * Records what the `/sa` solo tab shows afterwards: the floor's layout, the local player's route through
 * it, and when the score crossed 270 and 300.
 *
 * **Independent of [SoloClear].** That object is the announcement — it returns early on
 * [Config.soloClears], on the gate setting, on a floor outside the gate — and a run that was not worth
 * a Discord line is still a run worth reading back. This reads the same facts ([SoloClear.solo],
 * [LiveScore.computedScore]) and decides nothing about them.
 *
 * **Three hooks, and their positions are the design:**
 *
 *  - [observeScore] runs right after [LiveScore.observe] in `SighteAddons.onTick`, *above* the boss
 *    return, because the projection keeps moving until the map is gone and the mark has to be taken
 *    on the tick it crosses.
 *  - [tick] runs after `IdleTime.tick`, *inside* both early returns — so it never sees a null map, and
 *    route recording stops on its own in the boss phase, which is not a room.
 *  - [finish] is the **first line** of [DungeonSession.reset]. Every later line of that function
 *    destroys one of its inputs (`floor`, `SoloClear`, `LiveScore`, `ContributionTracker`, `IdleTime`,
 *    `DungeonTab`, `BloodClear`), and the JOIN that runs `reset()` is exactly how a solo clear ends: the
 *    player has 300 and warps out. A run that did reach its headline is filed on the same later JOIN,
 *    with `complete` true.
 *
 * **Not on DISCONNECT, deliberately.** That callback runs on a Netty thread and `reset()` does not
 * run there for the reasons `SighteAddons` gives at its registration; this iterates lists the client
 * thread writes. Quitting the game from inside a solo run costs the local record. `RunReport` still
 * has the rooms.
 *
 * Visits are kept in world grid positions ([Pos]) while the run is on and turned into lattice cells at
 * the end, because [DungeonLayout.scan] normalises its grid to the rooms it has seen so far — a room
 * revealed further west than any before it would shift every earlier cell by one.
 */
object SoloRecorder {

    private class Stay(val cell: Pos, val enter: Int, var leave: Int)

    private var layout: Layout? = null
    private var layoutLogged = false
    private val stays = ArrayList<Stay>()
    private var current: Stay? = null

    private val marks = ArrayList<SoloRuns.Mark>()
    private val curve = ArrayList<Pair<Int, Int>>()
    private var lastCurve = Int.MIN_VALUE

    /** Every tick of a run, before the boss return. Takes the first crossing of each threshold, per source. */
    fun observeScore(runTicks: Int, projected: Int?, sidebar: Int?, clock: String?, nowMs: Long) {
        if (projected != null && projected != lastCurve) {
            lastCurve = projected
            curve.add(runTicks to projected)
        }
        for (threshold in SoloRuns.THRESHOLDS) {
            mark(threshold, SoloRuns.PROJECTED, projected, runTicks, clock, nowMs)
            mark(threshold, SoloRuns.SIDEBAR, sidebar, runTicks, clock, nowMs)
        }
    }

    private fun mark(threshold: Int, kind: String, score: Int?, runTicks: Int, clock: String?, nowMs: Long) {
        if (score == null || score < threshold) return
        if (marks.any { it.threshold == threshold && it.kind == kind }) return
        marks.add(SoloRuns.Mark(threshold, kind, runTicks, clock, nowMs))
        DebugLog.event("solo_mark", "threshold" to threshold, "kind" to kind, "score" to score, "clock" to clock)
    }

    /** Every clear-phase tick, after the trackers. Rescans the layout once a second and follows the local player. */
    fun tick(client: Minecraft, map: MapItemSavedData) {
        val mapEntrance = DungeonSession.mapEntrance ?: return
        val roomSize = DungeonSession.mapRoomSize
        if (roomSize <= 0) return
        val t = DungeonSession.runTicks
        // Offset from PartyTracker's roster read, so the two once-a-second jobs do not land on one tick.
        if (layout == null || t % 20 == 10) {
            val scanned = DungeonLayout.scan(map, mapEntrance, roomSize)
            layout = scanned
            if (!layoutLogged && scanned.rooms.isNotEmpty()) {
                layoutLogged = true
                DebugLog.event(
                    "solo_layout",
                    "rooms" to scanned.rooms.size, "doors" to scanned.doors.size,
                    "cells" to scanned.rooms.sumOf { it.cells.size }, "cols" to scanned.cols, "rows" to scanned.rows,
                )
            }
        }
        val player = client.player ?: return
        val cell = DungeonGrid.physicalRoomPos(player.x, player.z)
        val stay = current
        if (stay != null && stay.cell == cell) {
            stay.leave = t
            return
        }
        stay?.leave = t
        current = Stay(cell, t, t).also { stays.add(it) }
    }

    /**
     * Files the run if it was a solo F7/M7, then forgets everything. Called first in [DungeonSession.reset].
     *
     * Wrapped whole: a record that could not be written is a lost record and nothing else, never a
     * reason for the reset it is riding on to stop halfway.
     */
    fun finish() {
        try {
            file()
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Could not file the solo run", e)
        } finally {
            layout = null
            layoutLogged = false
            stays.clear()
            current = null
            marks.clear()
            curve.clear()
            lastCurve = Int.MIN_VALUE
        }
    }

    private fun file() {
        val floor = SoloClear.floorTag(DungeonSession.floor)
        val gated = DungeonSession.calibrated && floor in SoloClear.GATED_FLOORS
        if (!gated) return
        val lay = layout
        val why = when {
            !SoloClear.solo -> "not solo"
            DungeonSession.runTicks <= 0 -> "no ticks"
            lay == null || lay.rooms.isEmpty() -> "no layout"
            else -> null
        }
        if (why != null) {
            DebugLog.event("solo_run_skipped", "why" to why, "floor" to floor)
            return
        }
        lay!!

        val physicalEntrance = DungeonSession.physicalEntrance ?: return
        val runTicks = DungeonSession.runTicks
        current?.leave = runTicks
        val self = PartyTracker.localName ?: Minecraft.getInstance().player?.name?.string

        val rooms = lay.rooms.map { room ->
            val world = room.cells.map { DungeonLayout.worldOf(it, physicalEntrance, lay.entrance) }
            val tracked = world.firstNotNullOfOrNull { ContributionTracker.roomAt(it) }
            val info = tracked?.info ?: world.firstNotNullOfOrNull { ContributionTracker.identifiedAt(it) }
            SoloRuns.Room(
                type = room.type,
                state = room.state,
                cells = room.cells,
                name = tracked?.name ?: info?.name,
                shape = info?.shape,
                maxSecrets = info?.secrets,
                crypts = info?.crypts,
                enterTick = tracked?.enteredAtTick,
                clearTick = tracked?.clearedAtTick,
                secretsTick = tracked?.secretsAtTick,
                secretRunTicks = tracked?.secretRunTicks,
                secretsFound = tracked?.secretsFound,
                ownSecrets = tracked?.ownSecrets,
                ownTicks = tracked?.let { r -> self?.let { r.ticks[it] } ?: r.ticks.values.maxOrNull() },
                deaths = tracked?.deaths,
                preCleared = tracked?.preCleared ?: false,
            )
        }
        val route = stays.map {
            SoloRuns.Visit(DungeonLayout.gridOf(it.cell, physicalEntrance, lay.entrance), it.enter, it.leave)
        }

        val ts = System.currentTimeMillis()
        val record = SoloRuns.Record(
            ts = ts,
            floor = floor,
            player = self,
            complete = SighteAddons.summaryPrinted,
            modVersion = TelemetryUpload.modVersion(),
            runTicks = runTicks,
            idleTicks = IdleTime.idleTicks,
            navTicks = IdleTime.navTicks,
            roomsCleared = ContributionTracker.roomsCleared,
            secrets = DungeonTab.secretsFound,
            secretsPercent = DungeonTab.secretsPercent,
            deaths = ContributionTracker.deaths,
            clock = DungeonSession.sidebarTime ?: DungeonTab.elapsed,
            score = SoloRuns.Score(
                high = LiveScore.high,
                projectedHigh = LiveScore.projectedHigh,
                source = LiveScore.source.name.lowercase(),
                hypixel = SoloClear.hypixelScore,
                final = LiveScore.breakdown,
                marks = marks.toList(),
                curve = curve.toList(),
            ),
            blood = SoloRuns.Blood(BloodClear.openedAt, BloodClear.doneAt),
            layout = SoloRuns.Floor(lay.cols, lay.rows, lay.entrance, rooms, lay.doors),
            route = route,
        )

        val dir = FabricLoader.getInstance().configDir.resolve("sighteaddons/solo")
        val name = "solo-$ts.json"
        if (!RunReport.publish(dir, name, SoloRuns.encode(record).toString())) return
        SoloRuns.add(record)
        DebugLog.event(
            "solo_run",
            "file" to name, "rooms" to rooms.size, "doors" to lay.doors.size, "visits" to route.size,
            "marks" to marks.size, "to300" to record.to300?.tick, "complete" to record.complete,
        )
    }
}
