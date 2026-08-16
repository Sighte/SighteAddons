package sighteaddons.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sighteaddons.ui.motion.Animatable
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.motion.Spring

/**
 * The motion system is the one part of this UI whose correctness cannot be seen in a screenshot.
 *
 * A Bézier solved wrongly still animates — just on the wrong curve. A spring integrated per frame
 * still springs — just differently at 144 fps than at 60. Both would ship looking fine. So the two
 * claims that are actually load-bearing get pinned here: the curve is the curve, and the result does
 * not depend on how often it was sampled.
 *
 * [Clock] and [Motion] are process-wide state, so both are reset around every test.
 */
class UiMotionTest {

    @BeforeEach
    fun reset() {
        Clock.resetForTest()
        Motion.reduceMotion = false
    }

    @AfterEach
    fun restore() {
        Motion.reduceMotion = false
    }

    // --- Easing -----------------------------------------------------------------------------

    /** Every curve passes through both corners exactly, and clamps outside the unit interval. */
    @Test
    fun `every curve is pinned at both ends`() {
        for (easing in listOf(Easing.STANDARD, Easing.ENTRANCE, Easing.EXIT, Easing.LINEAR)) {
            assertEquals(0f, easing.ease(0f), 1e-4f)
            assertEquals(1f, easing.ease(1f), 1e-4f)
            assertEquals(0f, easing.ease(-0.5f), 1e-4f)
            assertEquals(1f, easing.ease(2f), 1e-4f)
        }
    }

    /** A progress curve that ever goes backwards makes an element visibly stutter mid-move. */
    @Test
    fun `curves are monotonic`() {
        for (easing in listOf(Easing.STANDARD, Easing.ENTRANCE, Easing.EXIT)) {
            var previous = 0f
            for (step in 0..200) {
                val value = easing.ease(step / 200f)
                assertTrue(value >= previous - 1e-4f, "curve went backwards at t=${step / 200f}")
                previous = value
            }
        }
    }

    /**
     * The solver has to invert `x(t)`, not assume `t == x`. A decelerating curve is past halfway in
     * output well before it is halfway in time; if the solve were skipped this would read ~0.5.
     */
    @Test
    fun `decelerating curves are ahead of linear at the midpoint`() {
        assertTrue(Easing.STANDARD.ease(0.5f) > 0.7f, "standard decelerate should be well past half")
        assertTrue(Easing.ENTRANCE.ease(0.5f) > 0.8f, "entrance should be nearly arrived at half")
        assertTrue(Easing.EXIT.ease(0.5f) < 0.3f, "exit accelerates away, so it lags at half")
        assertEquals(0.5f, Easing.LINEAR.ease(0.5f), 1e-6f)
    }

    /** The ambient breather must close its loop, or the seam shows once every cycle. */
    @Test
    fun `the sine curve is seamless across a full period`() {
        assertEquals(0f, Easing.SINE.ease(0f), 1e-5f)
        assertEquals(1f, Easing.SINE.ease(0.5f), 1e-5f)
        assertEquals(0f, Easing.SINE.ease(1f), 1e-5f)
    }

    // --- Animatable -------------------------------------------------------------------------

    /**
     * The headline claim: identical at any frame rate.
     *
     * The same animation sampled every 4 ms (240 fps) and every 16.6 ms (60 fps) must agree at every
     * instant both of them see — because neither the frame rate nor the number of samples appears
     * anywhere in the arithmetic.
     */
    @Test
    fun `an animation is identical at 60 and 240 fps`() {
        val slow = Animatable(0f)
        val fast = Animatable(0f)
        slow.animateTo(1f, ms = 220)
        fast.animateTo(1f, ms = 220)

        var slowValue = 0f
        var fastValue = 0f
        var elapsed = 0.0
        while (elapsed < 220.0) {
            // 240 fps samples four times for every one at 60 fps; both land on the same instants.
            repeat(4) {
                Clock.advanceForTest(50.0 / 12.0)
                fastValue = fast.value
            }
            elapsed += 50.0 / 3.0
            slowValue = slow.value
            assertEquals(slowValue, fastValue, 1e-4f, "diverged at ${elapsed}ms")
        }
    }

    /** A value that arrives early or overshoots its target is a broken transition. */
    @Test
    fun `an animation runs from its start value to exactly its target`() {
        val a = Animatable(10f)
        a.animateTo(20f, ms = 100, easing = Easing.LINEAR)
        assertEquals(10f, a.value, 1e-4f)

        Clock.advanceForTest(50.0)
        assertEquals(15f, a.value, 0.01f, "linear should be exactly halfway")
        assertTrue(a.running)

        Clock.advanceForTest(50.0)
        assertEquals(20f, a.value, 1e-4f)
        assertTrue(!a.running)

        Clock.advanceForTest(10_000.0)
        assertEquals(20f, a.value, 1e-4f, "must hold, not drift past")
    }

    /**
     * Reversing mid-flight has to start from where the thing currently *is*. A row hovered and
     * unhovered inside 140 ms otherwise jumps back to the start before sliding out, which reads as a
     * flicker.
     */
    @Test
    fun `re-targeting mid-flight continues from the current value`() {
        val a = Animatable(0f)
        a.animateTo(1f, ms = 100, easing = Easing.LINEAR)
        Clock.advanceForTest(50.0)
        val midpoint = a.value
        assertEquals(0.5f, midpoint, 0.01f)

        a.animateTo(0f, ms = 100, easing = Easing.LINEAR)
        assertEquals(midpoint, a.value, 1e-4f, "reversal must begin where it was")

        Clock.advanceForTest(50.0)
        assertEquals(0.25f, a.value, 0.01f)
    }

    /** Asking for the target it is already heading to must not restart the animation. */
    @Test
    fun `re-targeting to the same value is ignored`() {
        val a = Animatable(0f)
        a.animateTo(1f, ms = 100, easing = Easing.LINEAR)
        Clock.advanceForTest(50.0)
        a.animateTo(1f, ms = 100, easing = Easing.LINEAR)
        Clock.advanceForTest(50.0)
        assertEquals(1f, a.value, 1e-4f, "the animation should have finished on its original schedule")
    }

    // --- Reduce motion ----------------------------------------------------------------------

    /** The policy: fades survive, clamped; everything that moves or loops does not. */
    @Test
    fun `reduce motion keeps short fades and removes movement`() {
        Motion.reduceMotion = true
        assertEquals(80, Motion.duration(80, Motion.Kind.OPACITY), "already under the cap")
        assertEquals(100, Motion.duration(360, Motion.Kind.OPACITY), "clamped to the cap")
        assertEquals(0, Motion.duration(220, Motion.Kind.TRANSFORM))
        assertEquals(0, Motion.duration(1200, Motion.Kind.AMBIENT))
        assertTrue(!Motion.ambientEnabled())

        Motion.reduceMotion = false
        assertEquals(360, Motion.duration(360, Motion.Kind.OPACITY))
        assertEquals(220, Motion.duration(220, Motion.Kind.TRANSFORM))
        assertTrue(Motion.ambientEnabled())
    }

    /** With motion reduced, a transform must arrive immediately rather than merely arrive faster. */
    @Test
    fun `a transform snaps under reduce motion`() {
        Motion.reduceMotion = true
        val a = Animatable(0f)
        a.animateTo(1f, ms = 360)
        assertEquals(1f, a.value, 1e-4f)

        val spring = Spring(0f)
        spring.springTo(1f)
        assertEquals(1f, spring.value, 1e-4f, "a spring with the bounce removed is not a spring")
    }

    // --- Spring -----------------------------------------------------------------------------

    /** The overshoot is the reason a spring exists here; 1.06 is the specified peak. */
    @Test
    fun `the spring overshoots by about six percent and settles`() {
        var peak = 0f
        var peakAt = 0f
        for (step in 0..1000) {
            val t = step / 1000f
            val value = Spring.response(t)
            if (value > peak) {
                peak = value
                peakAt = t
            }
        }
        assertEquals(1.06f, peak, 0.01f, "first peak should be ~1.06")
        assertTrue(peakAt in 0.4f..0.7f, "the peak should land mid-travel, was at $peakAt")

        assertEquals(0f, Spring.response(0f), 1e-4f)
        assertEquals(1f, Spring.response(1f), 0.01f, "should have visually arrived by its stated duration")
    }

    /** A knob that does not end exactly on its target ends slightly off-centre, forever. */
    @Test
    fun `a spring lands exactly on its target`() {
        val spring = Spring(0f)
        spring.springTo(1f, ms = 220)
        Clock.advanceForTest(220.0)
        assertEquals(1f, spring.value, 1e-6f)
        assertTrue(!spring.running)
    }

    // --- Clock ------------------------------------------------------------------------------

    /**
     * A paused stretch must not arrive as one enormous delta the moment the game resumes — that
     * would run every in-flight animation to completion during the frame the pause menu closes.
     */
    @Test
    fun `a paused clock does not accumulate the pause`() {
        Clock.resetForTest()
        Clock.frame(paused = false)

        // The pause begins on this frame, so the baseline is read after it, not before: the
        // milliseconds that elapsed while the game was still running are legitimately counted.
        Clock.frame(paused = true)
        val before = Clock.nowMs

        repeat(5) {
            Thread.sleep(2)
            Clock.frame(paused = true)
        }
        assertEquals(before, Clock.nowMs, 1.0, "time must not pass while paused")

        Clock.frame(paused = false)
        assertTrue(
            Clock.nowMs - before < 5.0,
            "the frame after a pause must not deliver the whole paused stretch",
        )
    }

    /**
     * The property that makes it safe for both the HUD element and an open screen to drive the clock.
     *
     * If [Clock.frame] accumulated per-frame deltas instead of deriving from the wall clock, two
     * callers in one frame would advance time twice — and the bug would present as "animations are
     * subtly too fast whenever the settings screen is open", which nobody would trace back to here.
     */
    @Test
    fun `driving the clock twice in one frame advances it once`() {
        Clock.resetForTest()
        Clock.frame(paused = false)
        Thread.sleep(20)

        Clock.frame(paused = false)
        val once = Clock.nowMs
        Clock.frame(paused = false)
        Clock.frame(paused = false)
        assertEquals(once, Clock.nowMs, 2.0, "extra calls in the same frame must not add time")
    }
}
