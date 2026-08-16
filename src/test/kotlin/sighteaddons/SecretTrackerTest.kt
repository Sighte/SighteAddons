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

    /**
     * `chat-001`: **null is not false**, and the whole feature turns on that.
     *
     * Hypixel names the finder of a wither-essence secret and of nothing else — no chest, lever,
     * item or redstone key is announced anywhere ([ChatEvents.Event.SecretFound]). So for almost
     * every secret this returns null, and null has to fall back to the 40-tick coincidence in
     * [SecretTracker.isOwn] rather than to "not yours". Reading a missing fact as a denial would
     * un-credit every chest in the dungeon — a change that would look like a working feature and
     * would quietly zero `ownSecrets` on every run.
     */
    @Test
    fun `chat says nothing about the secrets it does not name a finder for`() {
        assertNull(SecretTracker.chatAttribution(runTicks = 100, chatAt = Int.MIN_VALUE, chatMine = false))
        // Still null once a line has landed, if it landed too long ago to be about this counter rise.
        assertNull(SecretTracker.chatAttribution(runTicks = 200, chatAt = 100, chatMine = true))
    }

    /**
     * When it has spoken, it decides — in both directions. The false case is the one worth having:
     * a teammate taking the essence while your own click on a chest is still inside its window used
     * to credit you, and the line naming them is what overrules it.
     */
    @Test
    fun `a named finder settles it either way`() {
        assertEquals(true, SecretTracker.chatAttribution(runTicks = 100, chatAt = 100, chatMine = true))
        assertEquals(false, SecretTracker.chatAttribution(runTicks = 100, chatAt = 100, chatMine = false))
        assertEquals(true, SecretTracker.chatAttribution(runTicks = 120, chatAt = 100, chatMine = true))
        // 21 ticks: the chat line and the bar update are two renderings of one server-side event, so
        // the window is half [SecretTracker.isOwn]'s. Wider would mean an unrelated chest arriving
        // first and inheriting somebody else's attribution.
        assertNull(SecretTracker.chatAttribution(runTicks = 121, chatAt = 100, chatMine = true))
    }

    /**
     * The same overflow that made [isOwn] credit every unclicked secret, in the same shape one layer
     * over. `runTicks - Int.MIN_VALUE` is a large negative, which passes a `<=` window check — so
     * before any chat line had ever landed, every secret in the run would have been attributed to
     * whatever `chatMine` happened to be initialised to. The range check makes it unrepresentable
     * rather than merely absent, which is the difference between a fixed bug and a fixed instance.
     */
    @Test
    fun `the sentinel cannot be subtracted from here either`() {
        assertNull(SecretTracker.chatAttribution(runTicks = 25, chatAt = Int.MIN_VALUE, chatMine = true))
        assertNull(SecretTracker.chatAttribution(runTicks = 4946, chatAt = Int.MIN_VALUE, chatMine = true))
        // And a line stamped after the reading it is compared against is not about it.
        assertNull(SecretTracker.chatAttribution(runTicks = 100, chatAt = 101, chatMine = true))
    }

    /**
     * `ownsecrets-001` added two signals — a secret you walk over and a bat you kill. The bat's
     * decision is `entity is Bat` and there is nothing to test in it; the pickup's is
     * [SecretTracker.secretItem], and it is the whole of the feature's judgement. The name arrives
     * from the item stack exactly as Hypixel wrote it — legacy colour codes in front, which are what
     * the count parser above trips over too.
     */
    @Test
    fun `a secret item is recognised through its colour codes`() {
        assertEquals("Spirit Leap", SecretTracker.secretItem("§9Spirit Leap"))
        assertEquals("Superboom TNT", SecretTracker.secretItem("§9Superboom TNT"))
        // Lower-cased only to compare; what comes back is what Hypixel wrote, which is what gets
        // logged — so a floor that renames an item shows the rename rather than this mod's guess.
        assertEquals("DECOY", SecretTracker.secretItem("§aDECOY"))
        assertEquals("Defuse Kit", SecretTracker.secretItem("  §aDefuse   Kit  "))
    }

    /**
     * **The one that decides whether this feature is safe to ship.** A dungeon floor is full of
     * pickups that are not secrets, and a credit invented here is a permanent wrong record where the
     * missing one it replaces is not. So the rule is exact match against a whitelist, and the cases
     * that matter are the near misses: substring or prefix matching would take every one of these.
     */
    @Test
    fun `ordinary loot is not a secret and neither is a name that merely contains one`() {
        assertNull(SecretTracker.secretItem("§fRotten Flesh"))
        assertNull(SecretTracker.secretItem("§9Enchanted Book"))
        assertNull(SecretTracker.secretItem("§5Undead Essence"))
        assertNull(SecretTracker.secretItem(""))
        // Prefix and suffix: `Decoy` and `Superboom TNT` are in the list, these are not.
        assertNull(SecretTracker.secretItem("§9Enchanted Superboom TNT"))
        assertNull(SecretTracker.secretItem("§aDecoy Wand"))
    }

    /**
     * The unknown-name log is the only thing that can ever correct the vocabulary, since no build
     * has ever looked at an item name and the logs on disk therefore cannot contain the answer. It
     * is fed by every pickup in a dungeon, so it has to say each name once and then stop: the
     * failure it guards against is a debug file on the user's disk growing without bound.
     */
    @Test
    fun `an unknown pickup is reported once, and the report is bounded`() {
        SecretTracker.reset()
        assertTrue(SecretTracker.noteUnmatched("§fRotten Flesh"))
        assertFalse(SecretTracker.noteUnmatched("§fRotten Flesh"), "same item, second time")
        assertFalse(SecretTracker.noteUnmatched("Rotten  Flesh"), "same item, normalised")
        assertTrue(SecretTracker.noteUnmatched("§7Bone"), "a different item is still worth one line")
        // 2 distinct names so far; the cap is 32, and past it nothing is reported at all.
        repeat(30) { assertTrue(SecretTracker.noteUnmatched("filler $it")) }
        assertFalse(SecretTracker.noteUnmatched("one too many"))
        // A new run starts the vocabulary over — the item that was noisy last floor may be the one
        // that matters on this one.
        SecretTracker.reset()
        assertTrue(SecretTracker.noteUnmatched("one too many"))
        SecretTracker.reset()
    }
}
