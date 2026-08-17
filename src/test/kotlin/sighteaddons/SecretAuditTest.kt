package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The grade the live tracker gets, which is the first number `ownsecrets-001` has ever had.
 *
 * The direction is the whole content of this: under-counting is designed and over-counting is a
 * defect, and a comparison that reported them as one absolute difference would say nothing.
 */
class SecretAuditTest {

    private val counts = mapOf("Sighte" to 12, "Nordwand" to 7, "Tanksalot" to 3)

    @Test
    fun `agreeing exactly is its own answer`() {
        val audit = SecretAudit.of(tracked = 12, counts = counts, self = "Sighte", floorTracked = 22, asked = 3)
        assertEquals(SecretAudit.Verdict.EXACT, audit.verdict)
        assertEquals(0, audit.delta)
        assertEquals(22, audit.floorActual, "the API total is the sum of everyone it answered for")
        assertTrue(audit.complete)
    }

    /**
     * The designed direction. A bat killed at range, an unrecognised item, a counter that moved
     * between rooms — every one of those loses you a secret you found, and none of them is a bug.
     */
    @Test
    fun `crediting fewer than Hypixel counted is a miss`() {
        val audit = SecretAudit.of(tracked = 10, counts = counts, self = "Sighte", floorTracked = 22, asked = 3)
        assertEquals(SecretAudit.Verdict.MISSED, audit.verdict)
        assertEquals(-2, audit.delta, "negative is short, and the sign is what carries the direction")
    }

    /**
     * The defect. This says the tracker wrote a teammate's secret onto your screen and into your
     * points — the failure `ownSecrets == secretsFound` exists to prevent — so it must never be
     * folded together with the harmless direction.
     */
    @Test
    fun `crediting more than Hypixel counted is a defect, not a miss`() {
        val audit = SecretAudit.of(tracked = 13, counts = counts, self = "Sighte", floorTracked = 22, asked = 3)
        assertEquals(SecretAudit.Verdict.OVER, audit.verdict)
        assertEquals(1, audit.delta, "positive is over-credited")
    }

    /**
     * A player Hypixel did not answer for is absent, never zero — the same rule [SecretApi.delta]
     * holds. If that player is you there is nothing to grade, and a missing reading must not be
     * graded as "you found nothing and tracked ten".
     */
    @Test
    fun `no reading for the local player is not a verdict`() {
        val audit = SecretAudit.of(tracked = 10, counts = counts, self = "Someone Else", floorTracked = 22, asked = 4)
        assertEquals(SecretAudit.Verdict.UNKNOWN, audit.verdict)
        assertNull(audit.delta)
        assertNull(audit.actual)

        val noName = SecretAudit.of(tracked = 10, counts = counts, self = null, floorTracked = 22, asked = 3)
        assertEquals(SecretAudit.Verdict.UNKNOWN, noName.verdict)
    }

    /**
     * The floor halves are only comparable when everyone answered: one private profile makes the API
     * sum short by whatever that player found, and a reader who compared the two anyway would read
     * their absence as the tab list being wrong.
     */
    @Test
    fun `an incomplete reading says so`() {
        val short = SecretAudit.of(tracked = 12, counts = counts, self = "Sighte", floorTracked = 29, asked = 5)
        assertFalse(short.complete, "three of five answered")
        assertEquals(3, short.answered)
        assertEquals(22, short.floorActual)
        assertEquals(29, short.floorTracked, "the tab list still knows the real floor total")

        // An empty party cannot be complete either, whatever the arithmetic says.
        assertFalse(SecretAudit.of(0, emptyMap(), "Sighte", null, 0).complete)
    }
}
