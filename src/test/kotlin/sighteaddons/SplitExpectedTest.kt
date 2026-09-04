package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import sighteaddons.ui.Format

/**
 * The typed-duration parser — the one place a player's keystrokes become seconds in the store.
 *
 * Pinned because a mis-parse here is invisible in the way [SplitPbsTest] describes for the import: a
 * `1:05` read as five seconds does not fail, it plants a plan [RunEstimate] then sums into a projected
 * time that is quietly a minute short. The round-trip case is the field's whole contract: a committed
 * value re-reads as itself after [Format.seconds] prints it back into the box.
 */
class SplitExpectedTest {

    @Test
    fun `the shapes a player types`() {
        assertEquals(65f, SplitExpected.parse("1:05"))
        assertEquals(65.5f, SplitExpected.parse("1:05.5"))
        assertEquals(65f, SplitExpected.parse("65"))
        // Bare seconds are unbounded — someone thinking in seconds types 120 and means two minutes.
        assertEquals(120f, SplitExpected.parse("120"))
        assertEquals(5.5f, SplitExpected.parse("5.5"))
        assertEquals(65f, SplitExpected.parse("  1:05  "))
    }

    @Test
    fun `what is not a duration`() {
        assertNull(SplitExpected.parse(""))
        assertNull(SplitExpected.parse("abc"))
        // A zero plan is not a plan — SplitExpected.set refuses it and the parser says so first.
        assertNull(SplitExpected.parse("0"))
        // With minutes present, 60+ seconds is a typo rather than a reading.
        assertNull(SplitExpected.parse("1:75"))
        assertNull(SplitExpected.parse("1:5:0"))
        assertNull(SplitExpected.parse("-10"))
    }

    @Test
    fun `a committed value re-reads as itself`() {
        // Format.seconds is what the settings field prints back into the box after a commit.
        assertEquals(65.5f, SplitExpected.parse(Format.seconds(65.5f)))
        assertEquals(19.6f, SplitExpected.parse(Format.seconds(19.6f)))
    }
}
