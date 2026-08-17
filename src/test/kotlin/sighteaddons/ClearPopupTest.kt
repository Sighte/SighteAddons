package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The popup's opacity over time — the one part of a centred overlay a screenshot cannot check.
 *
 * Everything else about [ClearPopup] is `Minecraft` calls, but the ramp is arithmetic, it has three
 * phases with two boundaries between them, and every way of getting it wrong looks like a rendering
 * fault rather than like a bug: a popup that never reaches full opacity, one that hangs on screen
 * because the fade divided by a zero duration, one that vanishes between two frames.
 *
 * The durations are passed in already resolved, so these cases cover reduce-motion by covering the
 * values it produces — including a zero, which is what [sighteaddons.ui.motion.Motion.duration]
 * returns for anything it refuses to animate.
 */
class ClearPopupTest {

    /** Resolved durations for the default settings: 140 ms in, 2200 ms held, 800 ms out. */
    private val rise = 140
    private val fade = 800

    private fun at(ageMs: Long) = ClearPopup.opacity(ageMs, rise, fade)

    /**
     * Rise, hold, fall — and full opacity actually reached in the middle.
     *
     * A ramp that peaks at 0.98 is one nobody would ever notice reading wrong, and it is what an
     * off-by-one in either boundary produces.
     */
    @Test
    fun `the popup rises, holds at full, and is gone at three seconds`() {
        assertEquals(0f, at(-1), "a clock that went backwards is not a popup at full brightness")
        assertEquals(0f, at(0), "the first frame is the start of the entrance, not the end of it")
        assertTrue(at(70) > 0f && at(70) < 1f, "mid-entrance is neither end of it")

        assertEquals(1f, at(140), "the entrance is over when its duration is")
        assertEquals(1f, at(1000))
        assertEquals(1f, at(2200), "the hold owns its last millisecond")

        assertEquals(0f, at(3000), "hold plus fade, and nothing after it")
        assertFalse(ClearPopup.expired(3000, fade), "the last frame is drawn, not dropped")
        assertTrue(ClearPopup.expired(3001, fade), "one millisecond later there is nothing to keep")
    }

    /**
     * The fall leaves early and lands softly, which is the whole reason it is not the exit curve.
     *
     * An accelerating exit holds above 0.9 for four fifths of the window and then drops over a
     * handful of frames — indistinguishable, at 800 ms and sixty frames a second, from the popup
     * being cut off mid-frame. That is the artefact this fade exists to avoid, so the shape is
     * pinned rather than described.
     */
    @Test
    fun `the fade is a dissolve and not a cliff`() {
        val quarter = at(2400)
        val half = at(2600)
        val threeQuarters = at(2800)

        assertTrue(quarter < 0.9f, "a quarter of the way out it should already be visibly going: $quarter")
        assertTrue(quarter > half, "$quarter then $half")
        assertTrue(half > threeQuarters, "$half then $threeQuarters")
        assertTrue(threeQuarters > 0f, "still on screen until the window closes")
    }

    /**
     * Reduce-motion, and the degenerate case underneath it.
     *
     * `Motion.duration` clamps an opacity fade to 100 ms and returns **zero** for anything it refuses
     * outright. Zero is a legal duration meaning "no animation" and every consumer has to handle it
     * rather than divide by it — a popup that divided by zero here would sit on screen at `NaN`
     * opacity until the run ended.
     */
    @Test
    fun `a clamped fade still ends, and a zero-length one does not divide`() {
        assertEquals(1f, ClearPopup.opacity(1000, 100, 100), "the hold is unchanged by a shorter fade")
        assertTrue(ClearPopup.opacity(2250, 100, 100) < 1f, "and the shorter fade is still a fade")
        assertEquals(0f, ClearPopup.opacity(2300, 100, 100))

        assertEquals(1f, ClearPopup.opacity(0, 0, 800), "no entrance means present on the first frame")
        assertEquals(0f, ClearPopup.opacity(2200, 0, 0), "no fade means gone the instant the hold ends")
        assertTrue(ClearPopup.expired(2201, 0))
    }

    /**
     * The chip stays on the screen it is centred on, however narrow that screen is.
     *
     * The other half of this file's argument: a popup that has run off the edge looks like a rendering
     * fault, not like a bug, and nobody is going to be in a dungeon at GUI scale 4 on a 1366×768 window
     * with a stopwatch. That combination gives `guiScaledWidth` 342, and the longest name in Odin's
     * database — `Cobble Wall Pillar` — with its detail and a `PB` badge measures roughly 439 px at
     * this chip's double scale. `(screenWidth - width) / 2` was then **negative**: the name ran off the
     * left of the screen and the badge off the right, and the reading the popup exists to deliver was
     * the part that went.
     *
     * Two functions rather than one because they fix two different halves. The budget keeps the text
     * inside the screen; the clamp catches a screen too narrow for even the padding and the chevron,
     * which the budget alone cannot, because a budget of zero still leaves a chip wider than nothing.
     */
    @Test
    fun `a chip too wide for the screen is trimmed rather than pushed off it`() {
        // 1366x768 at GUI scale 4, with a record: chevron plus gap, and the gap plus badge on the end.
        val narrow = 342
        val mark = 14
        val badge = 30

        val budget = ClearPopup.textBudget(narrow, mark, badge)
        assertTrue(budget in 1 until narrow, "the two text runs get a real budget, not the whole screen: $budget")

        // Whatever the two runs measure, the chip they add up to fits — and starts on the screen.
        val width = PAD_X * 2 + mark + budget + GAP + badge
        assertTrue(width <= narrow, "a chip built to the budget is $width on a $narrow px screen")
        assertEquals(0, ClearPopup.leftEdge(narrow, width), "flush left, never past it")

        // A screen with no room even for the fixed parts asks for no text at all rather than for less
        // than none, and the chip still starts at zero instead of at a negative coordinate.
        assertEquals(0, ClearPopup.textBudget(20, mark, badge))
        assertEquals(0, ClearPopup.leftEdge(20, 200), "a chip wider than the screen starts at its edge")

        // And on a screen with room to spare nothing changes: still centred, still trimming nothing.
        assertEquals(160, ClearPopup.leftEdge(480, 160), "an ordinary window still centres the chip")
    }

    private companion object {
        /** `Tokens.SPACE_12` and `Tokens.SPACE_8`, which is what the chip is padded and gapped with. */
        const val PAD_X = 12
        const val GAP = 8
    }
}
