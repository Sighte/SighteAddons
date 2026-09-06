package sighteaddons.ui.screens

import sighteaddons.Cell
import sighteaddons.SoloRuns
import sighteaddons.ui.Format

/**
 * The lines behind the solo tab, out of a [SoloRuns.Record] — the list's rows, a run's summary, and
 * the route as stops a reader can follow. Minecraft-free for [sighteaddons.PbTable]'s reason.
 *
 * ### Stops, not visits
 *
 * The file keeps every cell the player stood in, raw. Read back, that is a border flicker per
 * doorway and one line per cell of a 2×2 room, which is not a route anybody would describe. So this
 * collapses it: consecutive visits inside one room are one stop, a stay shorter than [MIN_STAY]
 * between two stretches in the same room is folded into them (the doorway you brushed), and the
 * corridor between two stops is a number on the later one rather than a stop of its own. The file is
 * not changed by any of this — a different reading is a different function.
 */
internal object SoloRundown {

    /** The shortest stay that counts as having been somewhere: one second, [sighteaddons.ContributionTracker.MIN_TICKS]. */
    const val MIN_STAY = 20

    class ListRow(
        val ts: Long,
        val label: String,
        /** The time to 300, or to 270, or [Format.MISSING]. [reached] says which. */
        val time: String,
        val reached: Int,
        val score: String,
        val meta: String,
        val pb: Boolean,
    )

    fun listRows(records: List<SoloRuns.Record>, now: Long): List<ListRow> {
        val bests = records.map { it.floor }.distinct().associateWith { SoloRuns.bestTo300(records, it) }
        return records.map { r ->
            val to300 = r.to300
            val to270 = r.to270
            val (time, reached) = when {
                to300 != null -> Format.ticks(to300.tick) to 300
                to270 != null -> Format.ticks(to270.tick) to 270
                else -> Format.MISSING to 0
            }
            ListRow(
                ts = r.ts,
                label = "${r.floor} · ${Format.ago(r.ts, now)}",
                time = time,
                reached = reached,
                score = r.score.projectedHigh.toString(),
                meta = "${r.secrets?.toString() ?: "?"} secrets · ${r.deaths} deaths",
                pb = to300 != null && bests[r.floor] == to300.tick,
            )
        }
    }

    /** One stretch in one room, in route order, with the corridor time that led to it. */
    class Stop(
        val index: Int,
        val room: SoloRuns.Room,
        val enter: Int,
        val leave: Int,
        /** Ticks spent in no room at all right before this stop. */
        val walk: Int,
    ) {
        val ticks: Int get() = leave - enter

        /** How long the room took to clear *in this stop*, when its checkmark landed inside it. */
        val clear: Int? get() = room.clearTick?.takeIf { it in enter..leave }?.let { it - enter }
    }

    private class Seg(val room: SoloRuns.Room?, var enter: Int, var leave: Int) {
        val ticks get() = leave - enter
    }

    fun stops(record: SoloRuns.Record): List<Stop> {
        val segs = ArrayList<Seg>()
        for (v in record.route) {
            val room = record.layout.roomAt(v.cell)
            val last = segs.lastOrNull()
            if (last != null && last.room === room) last.leave = v.leave else segs.add(Seg(room, v.enter, v.leave))
        }
        // Fold a brush with a neighbouring cell: A, short B, A becomes one A.
        var folded = true
        while (folded) {
            folded = false
            var i = 1
            while (i < segs.size - 1) {
                val prev = segs[i - 1]
                val cur = segs[i]
                val next = segs[i + 1]
                if (cur.ticks < MIN_STAY && prev.room === next.room) {
                    prev.leave = next.leave
                    segs.removeAt(i + 1)
                    segs.removeAt(i)
                    folded = true
                } else {
                    i++
                }
            }
        }
        val out = ArrayList<Stop>()
        var walk = 0
        for (seg in segs) {
            val room = seg.room
            if (room == null) {
                walk += seg.ticks
                continue
            }
            out.add(Stop(out.size + 1, room, seg.enter, seg.leave, walk))
            walk = 0
        }
        return out
    }

    /** The stop that was under way at [tick], for the ring around the room where 300 fell. */
    fun stopAt(stops: List<Stop>, tick: Int): Stop? = stops.firstOrNull { tick in it.enter..it.leave }

    /** The one line a run is summed up in: floor, and the time that is its headline. */
    fun title(record: SoloRuns.Record): String {
        val to300 = record.to300
        val to270 = record.to270
        return when {
            to300 != null -> "${record.floor} · ${Format.ticks(to300.tick)} to 300"
            to270 != null -> "${record.floor} · ${Format.ticks(to270.tick)} to 270"
            else -> "${record.floor} · ${Format.ticks(record.runTicks)} · no 270"
        }
    }

    /** Label and value pairs for the summary card. Absent facts are absent lines, not dashes. */
    fun summary(record: SoloRuns.Record): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun mark(threshold: Int) {
            val m = record.mark(threshold) ?: return
            out.add("to $threshold" to (Format.ticks(m.tick) + (m.clock?.let { " · $it" } ?: "")))
        }
        mark(270)
        mark(300)
        record.score.final?.let { b ->
            out.add("projected" to "${b.skill} · ${b.explore} · ${b.time} · +${b.bonus} = ${b.total}")
        }
        if (record.score.high > 0) out.add("sidebar high" to record.score.high.toString())
        record.score.hypixel?.let { out.add("team score" to it.toString()) }
        record.secrets?.let { s ->
            val pct = record.secretsPercent?.let { " (${Math.round(it)} %)" } ?: ""
            out.add("secrets" to "$s$pct")
        }
        out.add("rooms cleared" to record.roomsCleared.toString())
        out.add("deaths" to record.deaths.toString())
        out.add("idle · nav" to "${Format.ticks(record.idleTicks)} · ${Format.ticks(record.navTicks)}")
        val open = record.blood.open
        val done = record.blood.done
        if (open != null) {
            out.add(
                "blood" to if (done != null) {
                    "${Format.ticks(open)} → ${Format.ticks(done)} (${Format.ticks(done - open)})"
                } else {
                    "open ${Format.ticks(open)}"
                },
            )
        }
        out.add((if (record.complete) "finished at" else "left at") to Format.ticks(record.runTicks))
        return out
    }

    /** The cells a hover tooltip describes, as lines. */
    fun tooltip(room: SoloRuns.Room, stops: List<Stop>): List<String> {
        val own = stops.filter { it.room === room }
        val lines = ArrayList<String>()
        lines.add(room.label)
        lines.add("${room.type.name.lowercase()} · ${room.state.name.lowercase()}")
        own.firstOrNull()?.let { lines.add("in at ${Format.ticks(it.enter)}") }
        if (own.isNotEmpty()) lines.add("stay ${Format.ticks(own.sumOf { it.ticks })}")
        own.firstNotNullOfOrNull { it.clear }?.let { lines.add("clear ${Format.ticks(it)}") }
        val max = room.maxSecrets
        if (max != null && max > 0) {
            lines.add("secrets ${room.secretsFound ?: 0}/$max" + (room.secretRunTicks?.let { " in ${Format.ticks(it)}" } ?: ""))
        }
        return lines
    }

    /** How the route is drawn: the centres to connect, one per stop, skipping a stop back into the same room. */
    fun path(stops: List<Stop>): List<Set<Cell>> {
        val out = ArrayList<Set<Cell>>()
        for (stop in stops) {
            if (out.lastOrNull() !== stop.room.cells) out.add(stop.room.cells)
        }
        return out
    }
}
