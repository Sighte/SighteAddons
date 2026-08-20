package sighteaddons

/**
 * The lines behind the two personal-best tables on the `/sa` records page: the split records per
 * floor, and the whole-run records per floor and party size.
 *
 * Separate from [SettingsScreen] for [RecordTable]'s reason, which holds here twice over: a `Screen`
 * cannot be built in a unit test, and every decision below is one an off-by-one would make silently
 * wrong on screen — a floor in the wrong place, a split out of the run's order, or two clocks in one
 * column. So it lives here, free of Minecraft types, and is tested.
 *
 * ### One flat list of lines, headings included
 *
 * A heading is a line and not a group object, which is
 * [sighteaddons.ui.screens.SettingsPage.Item]'s argument in the same screen: the table draws every
 * line in one loop, scrolls them with one offset in whole rows, and hit-tests them by dividing by
 * [sighteaddons.ui.components.Table.ROW]. A nested structure would put a line's height in a different
 * place from the loop that has to agree with it.
 *
 * ### Master mode first, and the highest floor first inside it
 *
 * `M7, M6 … M1, F7 … F1, E`. Not the order the floors are numbered in, and not the order the records
 * happened to be written in either — [SplitPbs] keeps its store in insertion order so its file stays
 * diffable, which is the right property for a file and no property at all for a list somebody reads.
 * The floor a player wants is the one they run, and that is the hardest one they have records on.
 *
 * ### The two clocks never share a column, and never share a headline
 *
 * [RunPbs] states that a Hypixel-timed run and one timed by this mod are not comparable numbers, so
 * they are not put side by side: the own-clock rows come after the ranked ones and say so in their own
 * label, and a floor's heading carries its best **ranked** time or nothing at all. A heading that
 * quietly showed an own-clock time because the floor had no ranked one would be exactly the
 * comparison that object refuses.
 */
internal object PbTable {

    /**
     * One line of a personal-best table.
     *
     * [label] null is what makes a line the floor's heading — the tag is in [floor] either way, so a
     * record line knows which floor it belongs to without the renderer tracking the last heading it
     * drew. [time] is the record on a record line and the floor's headline on a heading, and is empty
     * on a heading for a floor that has no headline to state.
     */
    class Line(val floor: String, val label: String?, val time: String) {
        val heading: Boolean get() = label == null
    }

    /** The split whose record is a whole floor, and therefore the floor heading's own number. */
    private const val TOTAL = DungeonSplits.TOTAL

    /** A party of one, spelled the way a player says it rather than as `1 players`. */
    private const val SOLO = "solo"

    /**
     * What an own-clock run says about itself, inside the row's own label.
     *
     * In the label rather than in a column of its own, because a column is the thing that comes off
     * first when the window narrows ([sighteaddons.ui.screens.RecordColumns] names the order), and the
     * column that came off here would leave a ranked row and an own-clock row for the same floor and
     * party size looking like the same record written down twice.
     */
    private const val OWN = " · own clock"

    /** `M7` is 0 and `M1` is 6. */
    private const val MASTER_FIRST = 7

    /** `F7` is 7 and `F1` is 13. */
    private const val NORMAL_FIRST = 14

    private const val ENTRANCE = 15

    private const val UNKNOWN = 16

    /**
     * Where a floor tag sorts. Master first, highest first, the entrance last — see the class comment.
     *
     * Anything this does not recognise sorts after everything it does rather than being dropped: a
     * hand-edited `config.json`, or a floor a later version adds, is still a record somebody set, and
     * a record that is silently not on the screen is worse than one in an odd place.
     */
    fun order(tag: String): Int {
        if (tag == ENTRANCE_TAG) return ENTRANCE
        if (tag.length != 2) return UNKNOWN
        val number = tag[1].digitToIntOrNull()?.takeIf { it in 1..7 } ?: return UNKNOWN
        return when (tag[0]) {
            'M' -> MASTER_FIRST - number
            'F' -> NORMAL_FIRST - number
            else -> UNKNOWN
        }
    }

    /** [SoloClear.floorTag]'s spelling of the entrance, which is what the stores are keyed by. */
    private const val ENTRANCE_TAG = "E"

    /** The floors, in reading order, with an alphabetical tiebreak for anything [order] cannot place. */
    fun floors(tags: Collection<String>): List<String> =
        tags.distinct().sortedWith(compareBy({ order(it) }, { it }))

    /**
     * The split records, grouped by floor and in the run's own order inside each floor.
     *
     * [records] is keyed by floor **tag** rather than by [SplitPbs]' `DungeonF7` key: the tag is what a
     * heading prints and what [order] sorts on, and converting once at the caller keeps one spelling of
     * a floor in this file. [chainOf] is [DungeonSplits.chainFor]'s names, which is the run's order —
     * passed in so a test needs no chain table and so this file holds no second copy of one.
     *
     * **The order inside a floor is the run's, and it is not a control.** Every other table on this
     * screen sorts by a clicked column; a chain does not, because `blood open` before `blood clear`
     * before `terminals` is the fact the reader came for, and a chain sorted by name or by time is a
     * list of times that no longer describes a run. Names the chain does not know — a key imported from
     * a version of Odin this mod has never seen — come after it, alphabetically, rather than vanishing.
     *
     * [time] formats seconds, so this file states no duration spelling of its own.
     */
    fun splits(
        records: Map<String, Map<String, Float>>,
        chainOf: (String) -> List<String>,
        time: (Float) -> String,
    ): List<Line> = buildList {
        for (tag in floors(records.keys)) {
            val floor = records[tag] ?: continue
            if (floor.isEmpty()) continue
            add(Line(tag, null, floor[TOTAL]?.let(time) ?: ""))
            for (split in ordered(floor.keys, chainOf(tag))) {
                add(Line(tag, split, time(floor.getValue(split))))
            }
        }
    }

    /**
     * [names] in [chain]'s order, then whatever [chain] does not mention, alphabetically.
     *
     * Driven by the records rather than by the chain, so a floor with two records produces two lines
     * and not a chain of ten with eight blanks in it — a blank row on a records table reads as a record
     * that failed to load.
     */
    private fun ordered(names: Set<String>, chain: List<String>): List<String> =
        chain.filter { it in names } + names.filter { it !in chain }.sorted()

    /**
     * The whole-run records, grouped by floor, ranked rows before own-clock rows.
     *
     * Party size ascends inside each clock, because solo is where a player starts and a bigger party is
     * a different run rather than a better one. The heading is read off the ranked rows only and the
     * rows come off the whole floor, so an own-clock record is still a row on a floor that has nothing
     * rankable on it.
     */
    fun runs(records: List<RunPbs.Record>, time: (Float) -> String): List<Line> {
        val byFloor = records.groupBy { it.floor }
        return buildList {
            for (tag in floors(byFloor.keys)) {
                val floor = byFloor[tag] ?: continue
                if (floor.isEmpty()) continue
                val ranked = floor.filter { it.clock == RunPbs.Clock.HYPIXEL }
                add(Line(tag, null, ranked.minByOrNull { it.seconds }?.seconds?.let(time) ?: ""))
                // `false` sorts before `true`, so "not the own clock first" is one comparator rather
                // than two lists concatenated.
                val order = compareBy<RunPbs.Record>({ it.clock != RunPbs.Clock.HYPIXEL }, { it.players })
                for (record in floor.sortedWith(order)) add(Line(tag, label(record), time(record.seconds)))
            }
        }
    }

    /** A run row's label: the party, and whose clock timed it when it was not Hypixel's. */
    private fun label(record: RunPbs.Record): String {
        val party = if (record.players <= 1) SOLO else "${record.players} players"
        return if (record.clock == RunPbs.Clock.HYPIXEL) party else party + OWN
    }

    /** How many records [lines] holds, which is not how many lines it holds. For the header count. */
    fun count(lines: List<Line>): Int = lines.count { !it.heading }
}
