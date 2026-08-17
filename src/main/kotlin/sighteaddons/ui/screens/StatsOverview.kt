package sighteaddons.ui.screens

import sighteaddons.RoomHistory
import sighteaddons.ui.Format

/**
 * What `history.jsonl` knows about the player as a whole, before any single room is opened.
 *
 * The table under this answers "how fast is this room". Nothing answered "how much of the dungeon have
 * I actually recorded", "how many of these figures rest on three runs", or "when did I last set
 * anything" — all of which the file has carried since the first version and none of which was ever read
 * back.
 *
 * ### Nothing here redefines a metric
 *
 * Every figure is an aggregate over lines the table already reads, of exactly one kind at a time. A
 * `clear` is still [RoomHistory.CLEAR], a `secretrun` is still [RoomHistory.SECRETS], a `bloodclear` is
 * still [RoomHistory.BLOOD], and this counts and orders them without ever asking what they mean.
 *
 * **The three kinds are never mixed into one figure**, and that is the whole of it. `RecordTable` folds
 * a blood room into its clear column because a reader looking at one row wants "how fast was this
 * room"; a *median* that folded a two-minute boss fight in with forty-second rooms would be a different
 * number wearing the same word, which is precisely the silent redefinition the history's contract
 * forbids. So there are three medians and each one names its kind.
 *
 * ### A figure says how much it rests on
 *
 * A median over two attempts is not a median, it is a claim. `RoomHistory` folds the whole progression,
 * so the sample size is known exactly and is printed next to every figure that has one — and below
 * [MIN_SAMPLE] no median is offered at all. What is shown instead is the fastest of the sample, which
 * is a fact about runs that happened rather than an estimate of a run that did not.
 *
 * ### Deliberately free of Minecraft types
 *
 * Records in, strings out. The screen supplies the data and the clock, so every case that matters — an
 * empty history, a single attempt, a room whose only line is a retired kind — is checkable without a
 * client. A wrong aggregate is invisible: it looks exactly like a right one.
 */
internal object StatsOverview {

    /**
     * The smallest sample this will call a median.
     *
     * Five, and the reason is the arithmetic rather than a feeling about statistics. The median here is
     * the upper middle element (`sorted[n / 2]`), which for an even sample sits *above* the true middle
     * — at two attempts it is literally the slower of the two, and at four it is the third fastest of
     * four. That bias is a rounding detail at 268 attempts and most of the figure at three. Below five
     * the honest answer is the one the sample can actually support.
     */
    const val MIN_SAMPLE = 5

    /** What counts as recent for the records line. A week is a play session or two. */
    const val RECENT_DAYS = 7L

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** How many floors get their own line before the rest are summed. */
    const val FLOORS_SHOWN = 4

    /** The floor a line written before the floor was known carries. */
    private const val UNKNOWN_FLOOR = "?"

    /**
     * One line: what it is, the figure, and what the figure is made of.
     *
     * [meta] is not decoration — it is the sample the value rests on, and it is the half that makes the
     * value readable. [thin] says the sample is below [MIN_SAMPLE], which the screen carries as a
     * change of *word* ("fastest of 3" against "median of 268") before it carries it as a tone.
     *
     * [fraction] is `0f..1f` for a line that also draws a bar, or negative for one that does not. Only
     * a ratio gets one: a bar under a count would be a bar with no denominator.
     */
    class Line(
        val label: String,
        val value: String,
        val meta: String = "",
        val thin: Boolean = false,
        val fraction: Float = -1f,
    )

    /** A heading and the lines under it. [meta] is the note that rides on the heading's right. */
    class Section(val title: String, val meta: String, val lines: List<Line>)

    /** The whole page. The section list is empty exactly when the history is. */
    class Overview(val sections: List<Section>)

    /** One history kind, with the two names it needs: a count is plural, a time is singular. */
    private class Kind(val key: String, val plural: String, val singular: String)

    private val KINDS = listOf(
        Kind(RoomHistory.CLEAR, "clears", "clear"),
        Kind(RoomHistory.SECRETS, "secret runs", "secret run"),
        Kind(RoomHistory.BLOOD, "blood rooms", "blood room"),
    )

    /**
     * One kind's attempts, gathered once, kept both flat and per room.
     *
     * Both shapes rather than one and a regroup: the totals want the flat list and the "most recorded"
     * line wants the per-room counts, and deriving the second from a second pass over the records map
     * would be a second place the two could disagree about what a room has in it.
     */
    private class Bucket(
        val kind: Kind,
        val byRoom: Map<String, List<RoomHistory.Attempt>>,
        val attempts: List<RoomHistory.Attempt>,
    )

    /**
     * The whole overview.
     *
     * [attemptsOf] is passed in rather than reached for so a test needs no config directory, and
     * [knownRooms] rather than a [sighteaddons.RoomDatabase] lookup so a test needs no `rooms.json`.
     * [now] is a parameter for the obvious reason: "3 in the last week" is not a fact a test can pin
     * against a clock it does not control.
     */
    fun of(
        records: Map<String, RoomHistory.Record>,
        attemptsOf: (String, String) -> List<RoomHistory.Attempt>,
        knownRooms: Int,
        now: Long,
    ): Overview {
        if (records.isEmpty()) return Overview(emptyList())

        val buckets = KINDS.map { kind ->
            val rooms = records.keys.filter { it.substringAfter('|') == kind.key }.map { it.substringBefore('|') }
            val byRoom = rooms.associateWith { attemptsOf(it, kind.key) }
            Bucket(kind, byRoom, byRoom.values.flatten())
        }
        val everything = buckets.flatMap { it.attempts }

        return Overview(
            listOf(
                history(records, buckets, everything, knownRooms),
                times(buckets),
                bests(records, buckets, everything, now),
                floors(everything),
            ).filter { it.lines.isNotEmpty() },
        )
    }

    /**
     * How much there is, and how much of the dungeon it covers.
     *
     * Counts only. The coverage ratio is the one figure on this page that a bar can honestly carry,
     * because it is the only one with a denominator that means anything — 47 of 213 rooms is a
     * fraction, 268 clears is not.
     */
    private fun history(
        records: Map<String, RoomHistory.Record>,
        buckets: List<Bucket>,
        everything: List<RoomHistory.Attempt>,
        knownRooms: Int,
    ): Section {
        val recorded = RoomHistory.roomsWithRecords(records.keys).size
        val lines = mutableListOf<Line>()

        lines.add(
            if (knownRooms > 0) {
                Line(
                    "rooms recorded", "$recorded of $knownRooms", "in the database",
                    fraction = (recorded.toFloat() / knownRooms).coerceIn(0f, 1f),
                )
            } else {
                // No database loaded — `rooms.json` missing, which the log already shouts about. A
                // ratio with no denominator is not a smaller answer, it is a different one.
                Line("rooms recorded", "$recorded", "no database loaded")
            },
        )

        for (bucket in buckets) {
            lines.add(Line(bucket.kind.plural, "${bucket.attempts.size}", inRooms(bucket.byRoom.size)))
        }

        // Lines of a kind nothing reads any more — the old `secrets`, retired when it became
        // `secretrun`. Named rather than quietly left out of the totals: the file is append-only, so
        // they are still in it, and a reader counting the lines by hand would otherwise come out ahead.
        val total = records.values.sumOf { it.runs }
        val retired = total - everything.size
        if (retired > 0) {
            lines.add(Line("retired lines", "$retired", "an older kind, unread"))
        }
        return Section("history", "$total lines", lines)
    }

    /** The three medians, each naming its own kind and its own sample. */
    private fun times(buckets: List<Bucket>): Section =
        Section(
            "times", "lower is better",
            buckets.filter { it.attempts.isNotEmpty() }.map { middle(it.kind.singular, it.attempts) },
        )

    /**
     * One kind's typical time, or the honest refusal to name one.
     *
     * Three shapes, and the *word* is what separates them — `median of 268`, `fastest of 3`, `the only
     * one`. A reader who never notices the tone still cannot mistake one for another, which is the rule
     * this whole design runs on.
     */
    internal fun middle(label: String, attempts: List<RoomHistory.Attempt>): Line {
        if (attempts.isEmpty()) return Line(label, Format.MISSING, "nothing recorded")
        val ticks = attempts.map { it.ticks }.sorted()
        return when {
            ticks.size == 1 -> Line(label, Format.ticks(ticks[0]), "the only one", thin = true)
            ticks.size < MIN_SAMPLE ->
                Line(label, Format.ticks(ticks.first()), "fastest of ${ticks.size}", thin = true)
            else -> Line(label, Format.ticks(ticks[ticks.size / 2]), "median of ${ticks.size}")
        }
    }

    /**
     * Personal bests, the room with the most lines in it, and when the file last grew.
     *
     * **The personal-best count is a floor and not a total.** [RoomHistory.Attempt.pb] is read out of
     * the line, and a line written before that field existed folds as `false` — see `RoomHistory.fold`.
     * Undercounting is the direction this repository takes everywhere it cannot know: a record that is
     * missing is a record you go and look for, a record that was invented is one you believe.
     */
    private fun bests(
        records: Map<String, RoomHistory.Record>,
        buckets: List<Bucket>,
        everything: List<RoomHistory.Attempt>,
        now: Long,
    ): Section {
        val pbs = everything.count { it.pb }
        val since = now - RECENT_DAYS * DAY_MS
        val recent = everything.count { it.pb && it.ts >= since }

        val played = HashMap<String, Int>()
        for (bucket in buckets) {
            for ((room, attempts) in bucket.byRoom) played[room] = (played[room] ?: 0) + attempts.size
        }
        // Ties break on the name, so the line does not depend on which run wrote its record first —
        // the same rule `RecordTable.sort` holds the table to.
        val top = played.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()

        val lastTs = records.values.maxOfOrNull { it.lastTs } ?: 0L

        return Section(
            "records", "",
            listOfNotNull(
                Line("personal bests", "$pbs", recentNote(recent)),
                top?.let { Line("most recorded", it.key, attemptsNote(it.value)) },
                Line("last recorded", Format.ago(lastTs, now), ""),
            ),
        )
    }

    /** How many attempts each floor carries. The rest are summed rather than dropped. */
    private fun floors(everything: List<RoomHistory.Attempt>): Section {
        if (everything.isEmpty()) return Section("floors", "", emptyList())
        val counts = HashMap<String, Int>()
        for (attempt in everything) counts[attempt.floor] = (counts[attempt.floor] ?: 0) + 1

        val ordered = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val rest = ordered.drop(FLOORS_SHOWN).sumOf { it.value }

        val lines = ordered.take(FLOORS_SHOWN).map { (floor, count) ->
            if (floor == UNKNOWN_FLOOR) {
                Line("unknown", "$count", "floor not yet known")
            } else {
                Line(floor, "$count", "")
            }
        }.toMutableList()
        if (rest > 0) lines.add(Line("other floors", "$rest", "${ordered.size - FLOORS_SHOWN} of them"))
        return Section("floors", seenNote(counts.size), lines)
    }

    private fun inRooms(count: Int): String = when (count) {
        0 -> "none yet"
        1 -> "in 1 room"
        else -> "in $count rooms"
    }

    private fun attemptsNote(count: Int): String = if (count == 1) "1 attempt" else "$count attempts"

    private fun recentNote(count: Int): String = when (count) {
        0 -> "none in the last week"
        1 -> "1 in the last week"
        else -> "$count in the last week"
    }

    private fun seenNote(count: Int): String = if (count == 1) "1 seen" else "$count seen"
}
