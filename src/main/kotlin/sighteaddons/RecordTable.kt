package sighteaddons

/**
 * The rows behind the `/sa` history table: the join from records to rooms, the type filter, the
 * search and the sort.
 *
 * Separate from [SettingsScreen] because none of it needs a screen. A `Screen` cannot be built in a
 * unit test without a running client, and this is the only part of that file where an off-by-one
 * would silently show the wrong room — so it lives here, free of Minecraft types, and is tested.
 *
 * The table has exactly one arrangement at any time: a filter says which rooms, a sort says in which
 * order. Neither changes the other. The previous screen grouped rooms under type headings only while
 * sorted by type, so clicking a column header rebuilt the whole structure and the same rooms turned
 * up in what looked like two different lists.
 */
internal object RecordTable {
    /** Column, and therefore sort key. [SECRETS] and [LAST] were headers without a sort before. */
    enum class Sort { ROOM, TYPE, CLEAR, SECRETS, RUNS, LAST }

    /** What the table is narrowed by, and therefore what escape undoes next. [NONE] means it closes. */
    enum class Narrowing { SEARCH, CHIP, NONE }

    /** The type chips. Exactly one is active, and [ALL] is the default. */
    enum class Filter(val label: String) {
        ALL("all"),
        PUZZLE("puzzle"),
        TRAP("trap"),
        RARE("rare"),
        NORMAL("normal"),
        OTHER("other"),
        ;

        /**
         * [OTHER] is defined as the leftovers rather than as a list, so a type the database gains
         * later — or one it does not name at all — lands somewhere instead of vanishing from every
         * chip. The chip counts add up to [ALL] for that reason.
         */
        fun matches(type: String?) = when (this) {
            ALL -> true
            OTHER -> type !in NAMED
            else -> type == name
        }
    }

    class Row(
        val room: String,
        /** Database type in upper case, or null for a room `rooms.json` does not know. */
        val type: String?,
        val clear: Int?,
        val secrets: Int?,
        val runs: Int,
        val lastTs: Long,
    ) {
        /** What the `type` column prints. Lower case like every other label on that screen. */
        val typeLabel: String get() = type?.lowercase() ?: "?"
    }

    /** The named types, so [Filter.OTHER] is everything else. */
    private val NAMED = setOf("PUZZLE", "TRAP", "RARE", "NORMAL")

    /**
     * One row per room, both record kinds side by side. [runs] counts clears rather than clears plus
     * secrets: a room is completed once per run, and its secrets line is an extra event in the same
     * run, not a second one.
     *
     * [typeOf] is passed in rather than reaching for [RoomDatabase] so a test needs no `rooms.json`.
     */
    fun rows(records: Map<String, RoomHistory.Record>, typeOf: (String) -> String?): List<Row> =
        RoomHistory.roomsWithRecords(records.keys).map { room ->
            val clear = records["$room|${RoomHistory.CLEAR}"]
            val secrets = records["$room|${RoomHistory.SECRETS}"]
            Row(
                room = room,
                type = typeOf(room),
                clear = clear?.ticks,
                secrets = secrets?.ticks,
                runs = clear?.runs ?: secrets?.runs ?: 0,
                lastTs = maxOf(clear?.lastTs ?: 0L, secrets?.lastTs ?: 0L),
            )
        }

    /**
     * One narrowing at a time, search first — escape undoes exactly this one, and the footer promises
     * exactly this one.
     *
     * Both of them ask here rather than each testing the two fields themselves, because they did: the
     * footer said "esc  close" while escape was resetting the chip and leaving the screen open. Two
     * copies of a three-case condition are one edit away from disagreeing again.
     */
    fun narrowing(query: String, filter: Filter) = when {
        query.isNotEmpty() -> Narrowing.SEARCH
        filter != Filter.ALL -> Narrowing.CHIP
        else -> Narrowing.NONE
    }

    /** Case-insensitive substring on the room name. Blank query means everything. */
    fun search(rows: List<Row>, query: String): List<Row> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return rows
        return rows.filter { it.room.lowercase().contains(needle) }
    }

    /**
     * The number on each chip, over the rows the search already narrowed — so clicking a chip yields
     * exactly the count it advertises rather than a number from before the search.
     */
    fun counts(rows: List<Row>): Map<Filter, Int> =
        Filter.entries.associateWith { filter -> rows.count { filter.matches(it.type) } }

    /**
     * Sorted, with rows that have no value for the column always at the bottom — in **both**
     * directions. A room without a secret run is neither the fastest nor the slowest one; reversing
     * the column would otherwise open the table with a screen of dashes.
     *
     * Ties break on the room name so the order never depends on which run wrote its line first.
     */
    fun sort(rows: List<Row>, by: Sort, desc: Boolean): List<Row> {
        val within = if (by == Sort.ROOM || by == Sort.TYPE) {
            compareBy<Row> { text(it, by) }
        } else {
            compareBy<Row> { number(it, by) }
        }
        val directed = if (desc) within.reversed() else within
        return rows.sortedWith(
            compareBy<Row> { if (present(it, by)) 0 else 1 }
                .then(directed)
                .thenBy { it.room.lowercase() },
        )
    }

    /** Whether the column has a value at all. A run count of 0 is a value; a missing time is not. */
    private fun present(row: Row, by: Sort) = when (by) {
        Sort.ROOM, Sort.RUNS -> true
        Sort.TYPE -> row.type != null
        Sort.CLEAR -> row.clear != null
        Sort.SECRETS -> row.secrets != null
        Sort.LAST -> row.lastTs != 0L
    }

    private fun text(row: Row, by: Sort) = if (by == Sort.TYPE) row.typeLabel else row.room.lowercase()

    private fun number(row: Row, by: Sort): Long = when (by) {
        Sort.CLEAR -> row.clear?.toLong() ?: 0L
        Sort.SECRETS -> row.secrets?.toLong() ?: 0L
        Sort.RUNS -> row.runs.toLong()
        else -> row.lastTs
    }
}
