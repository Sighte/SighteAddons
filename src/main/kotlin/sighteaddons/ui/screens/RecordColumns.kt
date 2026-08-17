package sighteaddons.ui.screens

import sighteaddons.RecordTable
import sighteaddons.ui.Format
import sighteaddons.ui.components.Table
import sighteaddons.ui.theme.Tokens

/**
 * Where the history table's columns go, derived from what will actually be drawn in them.
 *
 * ### Why this is not five percentages
 *
 * It was. Two rounds of them, each budgeted by hand against one window size and each correct there and
 * nowhere else — the first for the *values*, which put the `SECRETS` caret inside the `CLEAR` label,
 * and the second for the *headers* at `guiScaledWidth = 480`, which left two pixels of slack that are
 * gone by 478. Minecraft's auto scale hands out 456 on a 1366×768 display and 427 on both 1280×720 and
 * 2560×1440, so "measured at 1080p" is not a size most players are on.
 *
 * A percentage cannot express the constraint, because the constraint is not proportional: `SECRETS` is
 * sixty pixels of tracked capitals plus a caret whatever the window does, and `yesterday` is fifty-two.
 * So the table is laid out **right to left from the measured widths**, each column taking exactly the
 * wider of its header and its widest value, and whatever survives on the left is the room name's.
 *
 * ### The zones are a partition, and that is the bug fix
 *
 * The previous version padded each press zone by [Tokens.SPACE_4] on both sides to make a 26-pixel word
 * reachable, then resolved with `firstOrNull`. Two zones four pixels apart therefore overlapped by
 * four, and at 427 the `clear` and `secrets` zones overlapped by sixteen: **a press on `secrets` sorted
 * by `clear`.** Here every pixel of the header row belongs to exactly one column by construction, so a
 * press is unambiguous without any padding at all — and each zone still contains the whole of the mark
 * drawn inside it, which is the half that makes the header reachable.
 *
 * ### Columns come off rather than collide
 *
 * At the vanilla minimum GUI size of 320×240 the content column is 168 pixels and the four right-hand
 * columns alone want 251. Something has to go, and a table that overlaps itself is the one answer that
 * is not allowed — so [OPTIONAL] names the order they are given up in, least load-bearing first.
 * `room`, `clear` and `last` are what a history table is and never come off.
 *
 * ### Free of Minecraft types
 *
 * Widths come in as two lambdas. That is what lets `RecordColumnsTest` walk the real `guiScaled` sizes
 * and assert that no two zones overlap and nothing runs past the content edge — the check a layout
 * comment could not make, because a comment can only be right about one number.
 */
internal object RecordColumns {

    /**
     * One column: what it sorts by, its label, where it is drawn, and the strip of the header row that
     * belongs to it.
     *
     * [x0] and [x1] are what [Table.headerCell] aligns against — the left edge for a left-aligned
     * header, the right edge for a right-aligned one. [hit0] and [hit1] are the press zone, which is
     * wider than the mark and shared with nobody.
     */
    class Column(
        val sort: RecordTable.Sort,
        val label: String,
        val x0: Int,
        val x1: Int,
        val rightAligned: Boolean,
        val hit0: Int,
        val hit1: Int,
        /** The leftmost and rightmost pixel this column ever paints, header or value, whichever is wider. */
        val paintFrom: Int,
        val paintTo: Int,
    ) {
        operator fun contains(mouseX: Int): Boolean = mouseX >= hit0 && mouseX < hit1
    }

    /**
     * The finished layout: the columns, the right edge each value is aligned to, and how much room the
     * room name has left.
     *
     * The `*X` fields are `-1` for a column that was dropped, so a renderer that forgets to ask whether
     * a column is there draws off the left of the screen rather than silently into another column.
     */
    class Layout(
        val columns: List<Column>,
        val nameWidth: Int,
        val typeX: Int,
        val clearX: Int,
        val secretsX: Int,
        val runsX: Int,
        val lastX: Int,
    ) {
        val showType: Boolean get() = typeX >= 0

        fun at(mouseX: Int): Column? = columns.firstOrNull { mouseX in it }
    }

    /** The gap between two columns' drawn extents. */
    const val GAP = Tokens.SPACE_6

    /**
     * The least room the room column may keep.
     *
     * Eight characters and its truncation. Below this the column stops being a name and becomes a
     * prefix, and the answer is to drop the column beside it rather than keep shaving this one — a
     * truncated name has a tooltip behind it and a missing column has nothing.
     */
    const val NAME_MIN = 48

    /**
     * The order columns are given up in when the window cannot hold them all.
     *
     * `type` first, because it is one word the chips above the table already say and which `showType`
     * drops anyway whenever a chip is active. Then `runs`, whose count is repeated in the accordion and
     * totalled on the stats page. Then `secrets` — a real loss, and the last one available, because
     * `room`, `clear` and `last` are the table.
     */
    val OPTIONAL = listOf(RecordTable.Sort.TYPE, RecordTable.Sort.RUNS, RecordTable.Sort.SECRETS)

    /** A left-aligned header's drawn width: the label, a gap, then its caret. */
    fun leadingWidth(labelWidth: Int): Int = labelWidth + Tokens.SPACE_4 + Table.CARET

    /** A right-aligned header's: its caret, a gap, then the label. */
    fun trailingWidth(labelWidth: Int): Int = labelWidth + Tokens.SPACE_8 + Table.CARET

    /**
     * The widest value each column ever draws.
     *
     * `yesterday` and `1000d ago` rather than `today`: a column budgeted against its ordinary content
     * is a column that overlaps its neighbour on the day somebody comes back after a break. The `last`
     * column's third state is asked of [Format.ago] itself rather than spelt out here, so a change of
     * wording there cannot leave this measuring a string nothing prints. [Format.MISSING] is the same
     * width as a time by construction — see there — so one sample covers both states of a record
     * column.
     */
    private const val SAMPLE_TIME = "0:41.2"
    private val SAMPLE_LAST = listOf("yesterday", "1000d ago", Format.ago(0L, 0L))
    private val SAMPLE_RUNS = listOf("999")
    private val SAMPLE_TYPE = RecordTable.Filter.entries.map { it.label }

    /** Right-aligned columns in the order they are placed, rightmost first. */
    private val TRAILING = listOf(
        Triple(RecordTable.Sort.LAST, "last", SAMPLE_LAST),
        Triple(RecordTable.Sort.RUNS, "runs", SAMPLE_RUNS),
        Triple(RecordTable.Sort.SECRETS, "secrets", listOf(SAMPLE_TIME)),
        Triple(RecordTable.Sort.CLEAR, "clear", listOf(SAMPLE_TIME)),
    )

    /**
     * Lays the table out in `[contentLeft, contentLeft + content]`.
     *
     * [wantType] is the caller's own rule — the `type` column is redundant while a type chip is active —
     * and is kept apart from whether there is *room* for it, which is this function's.
     *
     * [header] measures a header as [Table.headerCell] draws it, which is tracked uppercase and not
     * `font.width(label)`; [value] measures an ordinary string.
     */
    fun of(
        contentLeft: Int,
        content: Int,
        wantType: Boolean,
        header: (String) -> Int,
        value: (String) -> Int,
    ): Layout {
        for (dropped in 0 until OPTIONAL.size) {
            place(contentLeft, content, wantType, header, value, dropped)?.let { return it }
        }
        // Nothing left to give up: a cramped name beats no table, and the columns are still disjoint
        // because they were placed from real widths rather than from a share of the window.
        return place(contentLeft, content, wantType, header, value, OPTIONAL.size, force = true)!!
    }

    /** One placement with the first [dropped] of [OPTIONAL] left out, or null if the name is too small. */
    private fun place(
        contentLeft: Int,
        content: Int,
        wantType: Boolean,
        header: (String) -> Int,
        value: (String) -> Int,
        dropped: Int,
        force: Boolean = false,
    ): Layout? {
        val gone = OPTIONAL.take(dropped).toSet()
        val lastX = contentLeft + content

        /** A column's placement: where its right edge is and how far left of it it paints. */
        class Slot(val sort: RecordTable.Sort, val label: String, val edge: Int, val reach: Int, val rightAligned: Boolean)

        val trailing = ArrayList<Slot>()
        var cursor = lastX
        for ((sort, label, samples) in TRAILING) {
            if (sort in gone) continue
            val reach = maxOf(trailingWidth(header(label)), samples.maxOf { value(it) })
            trailing.add(Slot(sort, label, cursor, reach, rightAligned = true))
            cursor -= reach + GAP
        }

        // Everything from here leftward is the room name's, and the type column's if it fits beside it.
        val typeNeed = maxOf(leadingWidth(header("type")), SAMPLE_TYPE.maxOf { value(it) })
        val available = cursor - contentLeft
        val showType = wantType && RecordTable.Sort.TYPE !in gone && available >= NAME_MIN + GAP + typeNeed
        val typeX = if (showType) cursor - typeNeed else -1
        val nameWidth = (if (showType) typeX - GAP else cursor) - contentLeft
        if (nameWidth < NAME_MIN && !force) return null

        // A left-aligned column's `reach` is its width, measured from its edge rightward; a
        // right-aligned one's is its width measured leftward. Both are "how much of the row it paints".
        val slots = ArrayList<Slot>(trailing.size + 2)
        slots.add(Slot(RecordTable.Sort.ROOM, "room", contentLeft, maxOf(nameWidth, 0), rightAligned = false))
        if (showType) slots.add(Slot(RecordTable.Sort.TYPE, "type", typeX, typeNeed, rightAligned = false))
        slots.addAll(trailing.asReversed())

        // Zones, left to right: each column owns from where its neighbour stopped up to where the next
        // one starts painting. A partition of the whole header row, so `at` cannot be ambiguous.
        val columns = ArrayList<Column>(slots.size)
        var boundary = contentLeft
        for (index in slots.indices) {
            val slot = slots[index]
            val next = slots.getOrNull(index + 1)
            val nextPaints = if (next == null) lastX + 1 else if (next.rightAligned) next.edge - next.reach else next.edge
            val hit1 = maxOf(nextPaints, boundary + 1)
            columns.add(
                Column(
                    sort = slot.sort,
                    label = slot.label,
                    x0 = if (slot.rightAligned) slot.edge - trailingWidth(header(slot.label)) else slot.edge,
                    x1 = slot.edge,
                    rightAligned = slot.rightAligned,
                    hit0 = boundary,
                    hit1 = hit1,
                    paintFrom = if (slot.rightAligned) slot.edge - slot.reach else slot.edge,
                    paintTo = if (slot.rightAligned) slot.edge else slot.edge + slot.reach,
                ),
            )
            boundary = hit1
        }

        return Layout(
            columns = columns,
            nameWidth = maxOf(nameWidth, 0),
            typeX = typeX,
            clearX = trailing.first { it.sort == RecordTable.Sort.CLEAR }.edge,
            secretsX = trailing.firstOrNull { it.sort == RecordTable.Sort.SECRETS }?.edge ?: -1,
            runsX = trailing.firstOrNull { it.sort == RecordTable.Sort.RUNS }?.edge ?: -1,
            lastX = lastX,
        )
    }
}
