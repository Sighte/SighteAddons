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
}
