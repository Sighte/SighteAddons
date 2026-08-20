package sighteaddons.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sighteaddons.ui.components.TextField
import sighteaddons.ui.screens.Scroll
import sighteaddons.ui.screens.SettingsPage

/**
 * The arithmetic that decides where a settings row is, how far a page may scroll, and which mask a
 * press on the key field lands on.
 *
 * All three are invisible when they are wrong. A page clamped one unit too generously scrolls into
 * blank space, which reads as the settings having disappeared; one clamped too tightly hides its last
 * row, which reads as a setting that was never written.
 */
class SettingsPageTest {

    private fun item(kind: SettingsPage.Kind, label: String = kind.name, fraction: Float = -1f) =
        SettingsPage.Item(kind, label, fraction = fraction)

    private val page = listOf(
        item(SettingsPage.Kind.SECTION),
        item(SettingsPage.Kind.TOGGLE),
        item(SettingsPage.Kind.NOTE),
        item(SettingsPage.Kind.STAT, "bar", fraction = 0.5f),
        item(SettingsPage.Kind.SLIDER),
        item(SettingsPage.Kind.INFO),
    )

    /**
     * Every row owns its own pixels and no others.
     *
     * Half-open at the bottom, so the last pixel of a row belongs to that row and the first pixel of
     * the next belongs to the next — an inclusive bound is how two adjacent switches both answer one
     * press on the hairline between them.
     */
    @Test
    fun `every row owns its own pixels, and the page is exactly as tall as its rows`() {
        val tops = SettingsPage.tops(page)
        assertEquals(0, tops.first())
        assertEquals(SettingsPage.SECTION, tops[1], "the heading carries its own air above it")
        assertEquals(
            SettingsPage.total(page), tops.last() + page.last().height,
            "the total and the walk that produces the tops have to agree",
        )

        for (index in page.indices) {
            assertEquals(index, SettingsPage.itemAt(page, tops[index]), "first pixel of row $index")
            assertEquals(
                index, SettingsPage.itemAt(page, tops[index] + page[index].height - 1),
                "last pixel of row $index",
            )
        }
        assertEquals(-1, SettingsPage.itemAt(page, -1))
        assertEquals(-1, SettingsPage.itemAt(page, SettingsPage.total(page)), "one past the end is nothing")
    }

    /** A figure with a bar under it is taller by exactly the bar, and only when it has one. */
    @Test
    fun `only a stat with a bar is taller than a row`() {
        assertEquals(SettingsPage.ROW, item(SettingsPage.Kind.STAT).height)
        assertEquals(SettingsPage.ROW + SettingsPage.BAR, item(SettingsPage.Kind.STAT, fraction = 0f).height)
        assertEquals(SettingsPage.NOTE, item(SettingsPage.Kind.NOTE).height)
    }

    /** Both ends of the scroll range, in both units the screen counts in. */
    @Test
    fun `a page scrolls to its last unit and no further`() {
        assertEquals(0, Scroll.clamp(50, total = 100, visible = 100), "everything fits, nothing scrolls")
        assertEquals(0, Scroll.clamp(50, total = 80, visible = 100), "shorter than the window is the same case")
        assertEquals(0, Scroll.clamp(-5, total = 300, visible = 200))
        assertEquals(37, Scroll.clamp(37, total = 300, visible = 200))
        assertEquals(100, Scroll.clamp(999, total = 300, visible = 200), "the last pixel is reachable")
        assertEquals(2, Scroll.clamp(99, total = 8, visible = 6), "and the last row, when the unit is rows")
    }

    /**
     * A notch moves three of whatever the list counts in — rows on the table, [SettingsPage.ROW] pixels
     * on a settings page — and up means a smaller offset.
     */
    @Test
    fun `a wheel notch moves three units, in the direction it was turned`() {
        assertEquals(3, Scroll.wheel(0, -1.0, 1, total = 100, visible = 10), "the table counts rows")
        assertEquals(0, Scroll.wheel(3, 1.0, 1, total = 100, visible = 10))
        assertEquals(0, Scroll.wheel(0, 1.0, 1, total = 100, visible = 10), "already at the top")

        val step = SettingsPage.ROW
        assertEquals(step * 3, Scroll.wheel(0, -1.0, step, total = 1000, visible = 200))
        assertEquals(800, Scroll.wheel(0, -99.0, step, total = 1000, visible = 200), "clamped on the way")
    }

    /**
     * A press on the masked key field, end to end and without a font.
     *
     * `draw` puts the first character at `x + PADDING - scroll`, so the hit test has to undo both terms.
     * Forgetting either one is a caret that lands a padding or a whole scroll away from the mark that
     * was pressed — and on a field of identical marks there is nothing on screen to notice it with.
     */
    @Test
    fun `a press on a masked field lands on the mark it was aimed at`() {
        val x = 260
        assertEquals(0, TextField.offsetAt(x, x + TextField.PADDING, scroll = 0))
        assertEquals(
            TextField.MASK_ADVANCE * 4,
            TextField.offsetAt(x, x + TextField.PADDING + TextField.MASK_ADVANCE * 4, scroll = 0),
        )
        // Scrolled, the same pixel is further into the value by exactly the scroll.
        assertEquals(30, TextField.offsetAt(x, x + TextField.PADDING, scroll = 30))

        // And the whole round trip: the fifth mark of a 36-character key, pressed on its left half.
        val key = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        val pressed = x + TextField.PADDING + TextField.MASK_ADVANCE * 5
        assertEquals(
            5,
            TextField.maskedIndexAt(key.length, TextField.offsetAt(x, pressed, scroll = 0)),
        )
        assertEquals(
            key.length,
            TextField.maskedIndexAt(key.length, TextField.offsetAt(x, x + 10_000, scroll = 0)),
            "a press past the last mark lands after it, never outside the value",
        )
    }
}
