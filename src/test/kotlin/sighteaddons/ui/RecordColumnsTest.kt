package sighteaddons.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.RecordTable
import sighteaddons.ui.components.EmptyState
import sighteaddons.ui.screens.Frame
import sighteaddons.ui.screens.RecordColumns
import sighteaddons.ui.theme.Tokens

/**
 * The history table's columns, at the window sizes Minecraft actually produces.
 *
 * **This test exists because a layout comment is only ever right about one number.** The columns were
 * apportioned twice by hand, each time against `guiScaledWidth = 480`, each time with the arithmetic
 * written out and each time correct — at 480. Minecraft's auto scale hands out 456 on a 1366×768
 * display and 427 on both 1280×720 and 2560×1440, and by 478 the two pixels of slack the second pass
 * left were gone: the `secrets` caret sat inside the `clear` label, and because the press zones were
 * padded four pixels either side and resolved with `firstOrNull`, **a press on `secrets` sorted by
 * `clear`.**
 *
 * So the property is pinned rather than the numbers. At every size: the zones partition the content
 * column exactly, nothing is painted outside its own zone, and nothing crosses the right edge. None of
 * that depends on the font, which is why it survives a font change and why the same assertions run
 * against a deliberately fat measurer as well.
 */
class RecordColumnsTest {

    /**
     * The GUI-scaled sizes to check.
     *
     * Vanilla's auto scale picks the largest whole scale that leaves at least 320×240, so these are
     * the real ones and 320×240 is the floor below which the game will not go.
     */
    private val sizes = listOf(
        Triple(480, 270, "1920×1080 at scale 4"),
        Triple(456, 256, "1366×768 at scale 3"),
        Triple(427, 240, "1280×720 at scale 3, and 2560×1440 at scale 6"),
        Triple(320, 240, "the vanilla minimum"),
    )

    /**
     * Vanilla's default-font advances: glyph width plus one pixel of spacing.
     *
     * Everything not named is 6, which is the widest ASCII glyph, so this measurer is never narrower
     * than the real font — a layout that clears here clears in the game.
     */
    private val narrow = mapOf(
        ' ' to 4, '!' to 2, '\'' to 3, ',' to 2, '.' to 2, ':' to 2, ';' to 2, '|' to 2,
        'i' to 2, 'l' to 3, 't' to 4, 'f' to 5, 'k' to 5, 'I' to 4,
    )

    private fun value(text: String): Int = text.sumOf { narrow[it] ?: 6 }

    /** A tracked uppercase header, the way `Labels.width` measures one. */
    private fun header(label: String): Int {
        val glyphs = value(label.uppercase())
        return if (label.isEmpty()) 0 else glyphs + Math.round(Tokens.TRACKING_LABEL * (label.length - 1))
    }

    private fun layoutAt(guiWidth: Int, wantType: Boolean = true) =
        RecordColumns.of(Frame.contentLeft(guiWidth), Frame.content(guiWidth), wantType, ::header, ::value)

    /**
     * The invariant, at every real size and with the type column both asked for and not.
     *
     * Four assertions, and the second is the one the old layout failed: a column painting outside its
     * own zone is a column painting into its neighbour's, which is the same fact as two headers
     * overlapping and as a press landing on the wrong one.
     */
    @Test
    fun `columns partition the row and paint only inside their own zone, at every gui size`() {
        for ((guiWidth, _, name) in sizes) {
            for (wantType in listOf(true, false)) {
                val contentLeft = Frame.contentLeft(guiWidth)
                val lastX = contentLeft + Frame.content(guiWidth)
                val layout = layoutAt(guiWidth, wantType)
                val where = "$name, wantType=$wantType"

                assertEquals(contentLeft, layout.columns.first().hit0, "$where: the row starts at the content edge")
                assertEquals(lastX + 1, layout.columns.last().hit1, "$where: and ends at it")

                for (index in layout.columns.indices) {
                    val column = layout.columns[index]
                    assertTrue(column.hit0 < column.hit1, "$where: ${column.label} has an empty zone")
                    if (index > 0) {
                        assertEquals(
                            layout.columns[index - 1].hit1, column.hit0,
                            "$where: a gap or an overlap before ${column.label}",
                        )
                    }
                    assertTrue(
                        column.paintFrom >= column.hit0 && column.paintTo <= column.hit1,
                        "$where: ${column.label} paints ${column.paintFrom}..${column.paintTo} " +
                            "outside its zone ${column.hit0}..${column.hit1}",
                    )
                    assertTrue(column.paintFrom >= contentLeft, "$where: ${column.label} starts left of the panel")
                    assertTrue(column.paintTo <= lastX, "$where: ${column.label} runs past the content edge")
                }
            }
        }
    }

    /** A press lands on exactly one column, everywhere along the row. */
    @Test
    fun `every pixel of the header row belongs to exactly one column`() {
        for ((guiWidth, _, name) in sizes) {
            val contentLeft = Frame.contentLeft(guiWidth)
            val lastX = contentLeft + Frame.content(guiWidth)
            val layout = layoutAt(guiWidth)
            for (x in contentLeft..lastX) {
                assertEquals(
                    1, layout.columns.count { x in it },
                    "$name: $x is claimed by ${layout.columns.count { x in it }} columns",
                )
            }
            assertEquals(null, layout.at(contentLeft - 1), "$name: left of the panel is no column")
            assertEquals(null, layout.at(lastX + 1), "$name: and right of it is none either")
        }
    }

    /**
     * The table sheds columns rather than overlapping itself, and never sheds the three it is made of.
     *
     * The specific outcomes are stated because they are the visible consequence of the rule: at 1080p
     * every column fits, at the two mid sizes the type column comes off so the room name stays a name,
     * and at the vanilla minimum the table is down to what a history table cannot do without.
     */
    @Test
    fun `columns are given up in order, and room, clear and last never are`() {
        for ((guiWidth, _, name) in sizes) {
            val layout = layoutAt(guiWidth)
            for (sort in listOf(RecordTable.Sort.ROOM, RecordTable.Sort.CLEAR, RecordTable.Sort.LAST)) {
                assertTrue(layout.columns.any { it.sort == sort }, "$name: $sort must survive")
            }
            assertTrue(layout.nameWidth >= 0, "$name: a negative room column")
        }

        assertTrue(layoutAt(480).showType, "1080p at scale 4 has room for the type column")
        assertFalse(layoutAt(456).showType, "1366×768 does not, and the room name is worth more")
        assertFalse(layoutAt(427).showType)
        assertTrue(
            layoutAt(320).columns.none { it.sort == RecordTable.Sort.SECRETS },
            "at the vanilla minimum only room, clear and last are left",
        )

        // A chip already hides the type column; asking for it back must not resurrect it where it does
        // not fit, and must not change anything else about where the rest sit.
        assertFalse(layoutAt(480, wantType = false).showType)
        assertEquals(layoutAt(427).clearX, layoutAt(427, wantType = false).clearX)
    }

    /**
     * The room name keeps a readable width wherever the layout has a choice.
     *
     * Only where it has one: at the vanilla minimum there is nothing left to drop, and a cramped name
     * beats no table. Everywhere else dropping a column is the answer, which is the whole reason
     * [RecordColumns.NAME_MIN] exists rather than the name simply taking what is left.
     */
    @Test
    fun `the room column keeps its floor wherever a column can be dropped instead`() {
        for ((guiWidth, _, name) in sizes.dropLast(1)) {
            assertTrue(
                layoutAt(guiWidth).nameWidth >= RecordColumns.NAME_MIN,
                "$name: room column is ${layoutAt(guiWidth).nameWidth}",
            )
        }
    }

    /**
     * A measurer half again as wide as vanilla's, to show the invariant is about the algorithm and not
     * about the font.
     *
     * The bundled TTF is a live proposal — `Tokens` argues for it in its own comment — and a font swap
     * that quietly reintroduced overlapping headers would look exactly like a font swap that did not.
     */
    @Test
    fun `the invariant holds for a much wider font too`() {
        val fat: (String) -> Int = { it.length * 9 }
        for ((guiWidth, _, name) in sizes) {
            val contentLeft = Frame.contentLeft(guiWidth)
            val lastX = contentLeft + Frame.content(guiWidth)
            val layout = RecordColumns.of(contentLeft, Frame.content(guiWidth), true, fat, fat)
            for (index in layout.columns.indices) {
                val column = layout.columns[index]
                if (index > 0) assertEquals(layout.columns[index - 1].hit1, column.hit0, "$name")
                assertTrue(column.paintTo <= lastX, "$name: ${column.label} past the edge")
                assertTrue(column.hit0 < column.hit1, "$name: ${column.label} has an empty zone")
            }
        }
    }

    /**
     * The empty state fits between the table's first row and the footer at every height.
     *
     * The other half of the same bug, and the one a fresh install sees first: the block used to be
     * dropped a fixed distance below the header, which at `guiScaledHeight` 240 put its last line
     * through the footer.
     */
    @Test
    fun `the empty state fits the band it is centred in`() {
        val firstRow = Frame.bodyTop + Tokens.SPACE_24 + Tokens.SPACE_16
        for ((_, guiHeight, name) in sizes) {
            val bottom = Frame.listBottom(guiHeight)
            for (top in listOf(Frame.bodyTop, firstRow)) {
                val height = EmptyState.height("config/sighteaddons/history.jsonl")
                assertTrue(height <= bottom - top, "$name: the empty state is taller than its band")
                val y = top + ((bottom - top - height) / 2).coerceAtLeast(0)
                assertTrue(y >= top, "$name: centred above its band")
                assertTrue(y + height <= bottom, "$name: centred through the footer")
            }
        }
    }
}
