package sighteaddons.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.ui.components.Badge
import sighteaddons.ui.components.Button
import sighteaddons.ui.components.Labels
import sighteaddons.ui.components.Nav
import sighteaddons.ui.components.ProgressBar
import sighteaddons.ui.components.Slider
import sighteaddons.ui.components.Stepper
import sighteaddons.ui.components.TextField
import sighteaddons.ui.components.Tooltip
import sighteaddons.ui.theme.Contrast
import sighteaddons.ui.theme.Palette
import sighteaddons.ui.theme.Tokens

/**
 * The half of the component layer a screenshot cannot check.
 *
 * Nothing here tests that a component draws a rectangle. What it tests is the arithmetic that is
 * invisible when it is wrong: a caret that scrolls out of its own field, a slider whose maximum
 * cannot be reached, a tooltip that leaves the screen at one corner, a label measured without its
 * tracking — and the contrast of every button state, which is the same acceptance bar `UiThemeTest`
 * already holds the palette to, extended to the states a component composites on top of it.
 *
 * [Tokens.palette] is process state, so it is put back in [restore]. Two test classes disagreeing
 * about which theme is active is the sort of failure that only appears when they run in one order.
 */
class UiComponentsTest {

    @AfterEach
    fun restore() {
        Tokens.palette = Palette.DARK
    }

    // --- Contrast ---------------------------------------------------------------------------

    /**
     * Every button state, on every surface, in both themes.
     *
     * The state space is the point. A hover wash that passes at rest and at full can still fail
     * halfway through the fade if the label is being blended at the same time — and halfway is where a
     * cursor spends most of its life. `disabled` is absent for the reason `UiThemeTest` gives: WCAG
     * exempts inactive controls, and one that met the same bar would not read as inactive.
     */
    @Test
    fun `every button state clears the contrast floor in both themes`() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            Tokens.palette = palette
            for (variant in Button.Variant.entries) {
                for (hover in listOf(0f, 0.5f, 1f)) {
                    for (press in listOf(0f, 0.5f, 1f)) {
                        val fill = Button.fill(variant, hover, press, enabled = true)
                        val label = Button.labelColour(variant, hover, press, enabled = true)
                        for (surface in palette.surfaces) {
                            val backdrop = if (fill == 0) surface else Contrast.over(fill, surface)
                            val ratio = Contrast.ratio(label, backdrop)
                            assertTrue(
                                ratio >= Contrast.AA,
                                "${palette.name} $variant hover=$hover press=$press: %.2f:1".format(ratio),
                            )
                        }
                    }
                }
            }
        }
    }

    /** A badge is a word on a fill, and the solid one inverts — so it gets measured like the chip does. */
    @Test
    fun `a badge's label is legible on its own fill`() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            Tokens.palette = palette
            for (style in Badge.Style.entries) {
                val fill = Badge.fill(style, enabled = true)
                val label = Badge.labelColour(style, enabled = true)
                for (surface in palette.surfaces) {
                    val backdrop = if (fill == 0) surface else Contrast.over(fill, surface)
                    val ratio = Contrast.ratio(label, backdrop)
                    assertTrue(ratio >= Contrast.AA, "${palette.name} $style: %.2f:1".format(ratio))
                }
            }
        }
    }

    // --- Labels -----------------------------------------------------------------------------

    /**
     * Tracking sits between glyphs, so `n` characters have `n - 1` gaps.
     *
     * One gap too many is 0.66px, which is nothing on a header and a whole pixel by the time a room
     * name is being truncated to fit beside a clock — and the failure is a name that overlaps the
     * clock, which nobody attributes to a rounding rule.
     */
    @Test
    fun `a label's width counts the gaps between glyphs and not after the last one`() {
        assertEquals(0, Labels.trackedWidth(0, 0))
        assertEquals(10, Labels.trackedWidth(10, 1), "one glyph has no gap to its right")
        assertEquals(20 + Math.round(Tokens.TRACKING_LABEL * 3), Labels.trackedWidth(20, 4))
        assertTrue(
            Labels.trackedWidth(40, 8) > Labels.trackedWidth(40, 4),
            "the same glyph width spread over more characters is wider",
        )
    }

    // --- Text field -------------------------------------------------------------------------

    /**
     * The single most common text-field bug: backspace with a selection open deletes one character
     * instead of the selection, so selecting a whole key and typing over it leaves the last character
     * of the old one behind — and a Hypixel key that is wrong by one character fails exactly like a
     * key that was never entered.
     */
    @Test
    fun `backspace takes the selection when there is one`() {
        val edit = TextField.Edit("Water Board")
        edit.placeCaret(0)
        edit.move(5, extend = true)
        assertEquals("Water", edit.selected())

        assertTrue(edit.backspace())
        assertEquals(" Board", edit.text)
        assertEquals(0, edit.caret)
        assertFalse(edit.hasSelection, "deleting a selection must leave no selection behind")

        // And with no selection it is one character, from before the caret.
        edit.end(extend = false)
        assertTrue(edit.backspace())
        assertEquals(" Boar", edit.text)
        assertTrue(edit.backspace())
        assertEquals(" Boa", edit.text)

        // At the very start there is nothing to delete, and saying so is what lets a caller skip a save.
        edit.home(extend = false)
        assertFalse(edit.backspace())
        assertEquals(" Boa", edit.text)
    }

    /** Typing replaces a selection, and the maximum length truncates rather than refusing. */
    @Test
    fun `typing replaces the selection and respects the maximum length`() {
        val edit = TextField.Edit("Water Board")
        edit.selectAll()
        edit.insert("Thunder")
        assertEquals("Thunder", edit.text)
        assertEquals(7, edit.caret)

        val short = TextField.Edit("ab", maxLength = 4)
        short.insert("cdef")
        assertEquals("abcd", short.text, "a paste that overflows keeps what fits")
        assertEquals(4, short.caret)
        short.insert("x")
        assertEquals("abcd", short.text, "a full field accepts nothing more")

        // Constructed over the limit as well — a config file can hold anything.
        assertEquals("abcd", TextField.Edit("abcdefgh", maxLength = 4).text)
    }

    /**
     * A caret that had a selection collapses to the end it is moving toward, rather than stepping one
     * character past it. Left-then-right has to return you to where you started.
     */
    @Test
    fun `moving out of a selection collapses to the end it moved toward`() {
        val edit = TextField.Edit("Water Board")
        edit.placeCaret(2)
        edit.move(4, extend = true)
        assertEquals(2, edit.selectionStart)
        assertEquals(6, edit.selectionEnd)

        edit.move(-1, extend = false)
        assertEquals(2, edit.caret, "moving left out of a selection lands on its left end")

        edit.placeCaret(2)
        edit.move(4, extend = true)
        edit.move(1, extend = false)
        assertEquals(6, edit.caret, "and moving right lands on its right end")
    }

    /**
     * The field scrolls exactly far enough to keep the caret visible, and no further.
     *
     * Both failures are silent: too little and you are typing into a field whose caret left the box,
     * too much and the text jumps a character at a time under the cursor. The "already visible" case
     * is the one that has to return the offset unchanged, or every keystroke re-centres the field.
     */
    @Test
    fun `the field scrolls only far enough to keep the caret visible`() {
        // Everything fits: never scrolled, whatever it was scrolled to before.
        assertEquals(0, TextField.scrollFor(contentWidth = 40, caretOffset = 40, innerWidth = 100, scroll = 30))

        // Caret past the right edge: pulled back by exactly the overhang.
        assertEquals(50, TextField.scrollFor(contentWidth = 300, caretOffset = 150, innerWidth = 100, scroll = 0))

        // Caret behind the left edge: the offset follows it back.
        assertEquals(20, TextField.scrollFor(contentWidth = 300, caretOffset = 20, innerWidth = 100, scroll = 90))

        // Already visible: unchanged, which is what stops the jitter.
        assertEquals(60, TextField.scrollFor(contentWidth = 300, caretOffset = 100, innerWidth = 100, scroll = 60))

        // Never past the end of the content, so there is no blank gap at the right.
        assertEquals(200, TextField.scrollFor(contentWidth = 300, caretOffset = 300, innerWidth = 100, scroll = 999))
    }

    /**
     * A masked value is laid out by its own advance, not by the font — which is the whole reason the
     * arithmetic is separable, and the reason a masked field can be checked at all without a client.
     */
    @Test
    fun `a masked value measures and hit-tests by its own advance`() {
        assertEquals(0, TextField.maskedWidth(0))
        assertEquals(TextField.MASK_ADVANCE * 8, TextField.maskedWidth(8))

        // A click rounds to the nearer gap: the left half of a mark puts the caret before it.
        assertEquals(0, TextField.maskedIndexAt(8, 0))
        assertEquals(0, TextField.maskedIndexAt(8, TextField.MASK_ADVANCE / 2 - 1))
        assertEquals(1, TextField.maskedIndexAt(8, TextField.MASK_ADVANCE / 2 + 1))
        assertEquals(2, TextField.maskedIndexAt(8, TextField.MASK_ADVANCE * 2))
        assertEquals(8, TextField.maskedIndexAt(8, 10_000), "a click past the end lands after the last mark")
    }

    // --- Slider and bar ---------------------------------------------------------------------

    /**
     * Both ends of a slider's range must be reachable.
     *
     * A slider that measures the cursor against its full width rather than the knob's travel is one
     * knob short at both ends: it reads 97 % at the far right and can never be set to its maximum,
     * which is the kind of bug that gets called "the setting does not save".
     */
    @Test
    fun `both ends of a slider's range are reachable`() {
        val x = 40
        val width = 120
        assertEquals(0f, Slider.fractionAt(x, width, 0), "left of the track is zero")
        assertEquals(1f, Slider.fractionAt(x, width, x + width), "the right edge is one")
        assertEquals(0.5f, Slider.fractionAt(x, width, x + Slider.KNOB / 2 + (width - Slider.KNOB) / 2), 0.01f)

        assertEquals(0, Slider.valueAt(0, 100, 0f))
        assertEquals(100, Slider.valueAt(0, 100, 1f))
        assertEquals(50, Slider.valueAt(0, 100, 0.5f))
        assertEquals(160, Slider.valueAt(160, 160, 0.5f), "an empty range is its own only value")

        // Round trip: a value put in comes back out.
        for (value in listOf(0, 1, 55, 99, 100)) {
            assertEquals(value, Slider.valueAt(0, 100, Slider.fractionOf(value, 0, 100)))
        }
    }

    /** A bar must never read empty when something has started, or full when it has not finished. */
    @Test
    fun `a progress bar states the two ends honestly`() {
        assertEquals(0, ProgressBar.fillWidth(100, 0f))
        assertEquals(100, ProgressBar.fillWidth(100, 1f))
        assertEquals(1, ProgressBar.fillWidth(100, 0.001f), "started is not empty")
        assertEquals(99, ProgressBar.fillWidth(100, 0.999f), "nearly done is not done")
        assertEquals(40, ProgressBar.fillWidth(100, 0.4f))
        assertEquals(0, ProgressBar.fillWidth(0, 0.5f), "no width, nothing to fill")
    }

    // --- Placement --------------------------------------------------------------------------

    /**
     * A tooltip flips rather than leaving the screen, and clamps when neither side fits.
     *
     * The case only happens at one cursor position on one window size, which is exactly the case
     * nobody reproduces by hand — and a tooltip half outside the window is unreadable in the one
     * situation where it was asked for.
     */
    @Test
    fun `a tooltip flips rather than leaving the screen`() {
        val screen = 400
        val width = 120

        assertEquals(200 + Tooltip.OFFSET, Tooltip.placeX(200, width, screen), "room to the right, go right")
        assertEquals(
            380 - Tooltip.OFFSET - width, Tooltip.placeX(380, width, screen),
            "no room to the right, flip to the left of the cursor",
        )

        // Neither side fits: clamped inside the window rather than hanging off it.
        val clamped = Tooltip.placeX(20, 380, screen)
        assertTrue(clamped >= Tooltip.MARGIN && clamped + 380 <= screen, "clamped to $clamped")

        assertEquals(100 + Tooltip.OFFSET, Tooltip.placeY(100, 40, screen))
        assertEquals(390 - Tooltip.OFFSET - 40, Tooltip.placeY(390, 40, screen))
    }

    /**
     * Hit testing must agree with the drawing, which is why both come out of the component rather than
     * being written twice — the bug is a row that highlights one place and acts on another.
     */
    @Test
    fun `rail rows and stepper arms hit-test where they are drawn`() {
        val top = 60
        assertEquals(-1, Nav.rowAt(top, 4, top - 1))
        assertEquals(0, Nav.rowAt(top, 4, top))
        assertEquals(0, Nav.rowAt(top, 4, top + Nav.ROW - 1))
        assertEquals(1, Nav.rowAt(top, 4, top + Nav.ROW))
        assertEquals(3, Nav.rowAt(top, 4, top + Nav.ROW * 4 - 1))
        assertEquals(-1, Nav.rowAt(top, 4, top + Nav.ROW * 4), "one past the last row is nothing")

        // `0` is "neither arm", which is not the same answer as `-1`; the value between them must not
        // step the setting in whichever direction the layout happened to put nearest.
        val x = 100
        val width = 120
        assertEquals(0, Stepper.armAt(x, width, x - 1))
        assertEquals(-1, Stepper.armAt(x, width, x))
        assertEquals(-1, Stepper.armAt(x, width, x + Stepper.ARM - 1))
        assertEquals(0, Stepper.armAt(x, width, x + Stepper.ARM))
        assertEquals(0, Stepper.armAt(x, width, x + width / 2), "the value between the arms is not a target")
        assertEquals(0, Stepper.armAt(x, width, x + width - Stepper.ARM - 1))
        assertEquals(1, Stepper.armAt(x, width, x + width - Stepper.ARM))
        assertEquals(1, Stepper.armAt(x, width, x + width - 1))
        assertEquals(0, Stepper.armAt(x, width, x + width))
    }
}
