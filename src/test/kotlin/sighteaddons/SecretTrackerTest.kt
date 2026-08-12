package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Both cases here were found in a real M7 log, not reasoned about: every room reported its first
 * secret as a jump of seventy, and every one of them was credited to the local player.
 */
class SecretTrackerTest {
    @Test
    fun `strips the colour code in front of the count`() {
        // The bug: '§7' is grey, and '\d+' ate the 7 — an empty 8-secret room read as 70 of 8.
        assertEquals(SecretTracker.BarSecrets(0, 8), SecretTracker.parseSecrets("§70/8 Secrets"))
        assertEquals(SecretTracker.BarSecrets(8, 8), SecretTracker.parseSecrets("§78/8 Secrets"))
        // A code that is not a digit never caused the bug, and must keep working.
        assertEquals(SecretTracker.BarSecrets(3, 8), SecretTracker.parseSecrets("§b3/8 Secrets"))
        assertEquals(SecretTracker.BarSecrets(3, 8), SecretTracker.parseSecrets("3/8 Secrets"))
    }

    @Test
    fun `finds the count inside a full action bar`() {
        val bar = "§c1500/1500❤     §a750§a❈ Defense     §b1/5 Secrets     §b3000/3000✎ Mana"
        assertEquals(SecretTracker.BarSecrets(1, 5), SecretTracker.parseSecrets(bar))
    }

    @Test
    fun `ignores bars without a secret counter`() {
        assertNull(SecretTracker.parseSecrets("§c1500/1500❤     §b3000/3000✎ Mana"))
        assertNull(SecretTracker.parseSecrets(""))
    }

    @Test
    fun `a secret without a preceding click is not yours`() {
        // The bug: runTicks - Int.MIN_VALUE overflows negative and passed the window check, so this
        // returned true 25 ticks into a run in which nothing had been clicked at all.
        assertFalse(SecretTracker.isOwn(runTicks = 25, lastInteraction = Int.MIN_VALUE))
        assertFalse(SecretTracker.isOwn(runTicks = 4946, lastInteraction = Int.MIN_VALUE))
    }

    @Test
    fun `a secret shortly after your own click is yours`() {
        assertTrue(SecretTracker.isOwn(runTicks = 100, lastInteraction = 100))
        assertTrue(SecretTracker.isOwn(runTicks = 140, lastInteraction = 100))
        // 41 ticks later the two are no longer considered related.
        assertFalse(SecretTracker.isOwn(runTicks = 141, lastInteraction = 100))
    }
}
