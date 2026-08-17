package sighteaddons.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.ui.hud.HudSnapshot
import sighteaddons.ui.render.Surface

/**
 * The HUD's arithmetic, which is the half of it a screenshot cannot check.
 *
 * A split with the wrong sign, a radius that quietly rounds up past what the sheet carries, or a
 * cache that reformats every frame all look completely normal on screen.
 */
class UiHudTest {

    // --- Splits -----------------------------------------------------------------------------

    /**
     * The sign is always shown and always means the same thing: negative is faster.
     *
     * With no colour available, the sign and the chevron are the entire message. One of them being
     * ambiguous makes the other the only signal, which is exactly what the design forbids.
     */
    @Test
    fun `a split always carries its sign`() {
        // 84 ticks = 4.2s faster. The minus is U+2212, not a hyphen.
        assertEquals("−4.2s", Format.delta(-84))
        assertEquals("+1.8s", Format.delta(36))
        assertEquals("+0.0s", Format.delta(0), "a dead heat is not an improvement")
        assertTrue(Format.delta(-84).startsWith("−"), "must be a real minus sign")
    }

    /** No record, or no time yet, is not a delta of zero — it is the absence of one. */
    @Test
    fun `a room with no record reports no split`() {
        val noRecord = snapshot(roomTicks = 400, clearBest = Format.NONE)
        assertEquals(Format.NONE, noRecord.clearDelta)

        val notEntered = snapshot(roomTicks = 0, clearBest = 900)
        assertEquals(Format.NONE, notEntered.clearDelta, "zero time in the room is not a fast time")

        val real = snapshot(roomTicks = 816, clearBest = 900)
        assertEquals(-84, real.clearDelta)
    }

    /** A secret run that was discarded must not compare as an infinitely fast one. */
    @Test
    fun `a discarded secret run reports no split`() {
        assertEquals(Format.NONE, snapshot(secretRunTicks = Format.NONE, secretBest = 260).secretDelta)
        assertEquals(20, snapshot(secretRunTicks = 280, secretBest = 260).secretDelta)
    }

    // --- Formatting -------------------------------------------------------------------------

    /**
     * A missing time has to occupy the same width as a real one, or a column of times stops being a
     * column the moment one room has no record.
     */
    @Test
    fun `the missing-time placeholder is the width of a real time`() {
        assertEquals(Format.ticks(824).length, Format.MISSING.length)
        assertEquals(Format.MISSING, Format.ticks(Format.NONE))
        assertEquals("0:41.2", Format.ticks(824))
    }

    /**
     * The cache is the allocation claim, so it gets a test rather than a comment.
     *
     * Same input must return the identical instance — not merely an equal string — because the point
     * is that no new one was built.
     */
    @Test
    fun `the formatting cache only rebuilds when its input changes`() {
        var calls = 0
        val cached = Format.Cached { value -> calls++; "v$value" }

        val first = cached.of(824)
        val again = cached.of(824)
        assertSame(first, again, "an unchanged value must not be reformatted")
        assertEquals(1, calls)

        val changed = cached.of(825)
        assertEquals("v825", changed)
        assertEquals(2, calls)

        // And back again: the cache holds one value, so this is a genuine rebuild, not a hit.
        cached.of(824)
        assertEquals(3, calls)
    }

    // --- Geometry ---------------------------------------------------------------------------

    /**
     * A radius must snap *down* to one the sheet actually carries, and must never exceed half the
     * shorter side — a corner larger than that overlaps its opposite and the card develops a waist.
     */
    @Test
    fun `radii snap down to an authored value and never overlap`() {
        // Exact hits stay put.
        assertEquals(12, Surface.resolveRadius(12, 200, 100))
        assertEquals(4, Surface.resolveRadius(4, 200, 100))

        // Between two authored radii, take the smaller.
        assertEquals(8, Surface.resolveRadius(10, 200, 100))

        // Below the smallest authored corner there is no corner at all, which is a square fill.
        assertEquals(0, Surface.resolveRadius(2, 200, 100))
        assertEquals(0, Surface.resolveRadius(0, 200, 100))

        // Capped by the box: a 12px-tall row cannot carry a radius-12 corner.
        assertEquals(4, Surface.resolveRadius(12, 200, 12))
        assertEquals(0, Surface.resolveRadius(16, 200, 6))
    }

    /** A chip asks for "as round as this allows" and gets a lozenge, never an overlap. */
    @Test
    fun `a full radius resolves to half the shorter side`() {
        assertEquals(8, Surface.resolveRadius(-1, 200, 20), "half of 20 is 10, snapped down to 8")
        assertEquals(16, Surface.resolveRadius(-1, 200, 40))
        assertEquals(4, Surface.resolveRadius(-1, 9, 200), "width is the shorter side here")
    }

    // --- Helpers ----------------------------------------------------------------------------

    private fun snapshot(
        roomTicks: Int = 0,
        clearBest: Int = Format.NONE,
        secretRunTicks: Int = Format.NONE,
        secretBest: Int = Format.NONE,
    ) = HudSnapshot(
        inDungeon = true, floor = "M7", runTicks = 4000, roomsCleared = 12,
        roomName = "Water Board", roomType = "PUZZLE", roomTicks = roomTicks, roomClearedAt = Format.NONE,
        secretsFound = 3, secretsTotal = 6, ownSecrets = 2, secretRunTicks = secretRunTicks,
        clearBest = clearBest, secretBest = secretBest,
        runOwnSecrets = 14, idleTicks = 0, navTicks = 0,
        history = emptyArray(), standings = emptyArray(),
    )
}
