package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the crit readout decides, for lines given to it directly.
 *
 * This is the half of `critcalc-001` that can be verified without a game: the parse, the division by
 * enemy count, the roman numerals, the power sum, the combat window and the wording. The other half
 * — that Fabric delivers Hypixel's crit line to [SighteAddons.onChat], and that the tab-list mixin
 * accessor returns a footer with blessing rows in it — needs a live `Minecraft` in an M7 and is
 * unverified, like every other wiring line in this repository.
 *
 * **And the strings themselves are unverified in a second way that no test here can fix.** They came
 * out of a decompiled third-party mod, not out of a log this repository holds; nothing in
 * `docs/evidence/` or the session logs on disk contains "Explosive Shot" or "Blessing of Power",
 * because no build has ever looked for them. So the cases below pin the *behaviour* against the
 * assumed shape, and `CritMeter.nearMiss` is what will eventually say whether the shape was right.
 */
class CritMeterTest {
    /** [CritMeter.inCombat] is object state, so each case starts with the window shut. */
    @BeforeEach
    fun closeWindow() = CritMeter.reset()

    private val MAXOR = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"

    /**
     * The division is the whole feature. An Explosive Shot spreads its damage over everything it
     * touched, so the announced figure describes the shot and only the quotient describes the hit —
     * a crit on five enemies and one on three are otherwise not comparable at all. Returning the
     * announced number would leave every reading five times too large and still look plausible.
     */
    @Test
    fun `damage is per target, not per shot`() {
        assertEquals(12_000_000_000.0, CritMeter.perTarget("Your Explosive Shot hit 5 enemies for 60,000,000,000.0 damage!"))
        // Singular, no decimal, and no comma — three separate ways the pattern could be too narrow.
        assertEquals(7_000_000_000.0, CritMeter.perTarget("Your Explosive Shot hit 1 enemy for 7000000000 damage!"))
    }

    /**
     * Anchoring, the property every chat pattern in this repository rests on. Hypixel puts a name in
     * front of anything a player typed, so a start anchor is what stops a teammate producing a crit
     * reading — and a nonsense enemy count is rejected rather than divided by, because `Infinity B
     * per power` is a worse readout than none.
     */
    @Test
    fun `a line somebody typed is not a crit, and neither is a division by zero`() {
        assertNull(CritMeter.perTarget("Party > [MVP+] Bob: Your Explosive Shot hit 5 enemies for 99,000,000,000.0 damage!"))
        assertNull(CritMeter.perTarget("Your Explosive Shot hit 0 enemies for 60,000,000,000.0 damage!"))
        assertNull(CritMeter.perTarget("Your Explosive Shot missed."))
    }

    /**
     * Subtractive notation is the case a naive left-to-right sum gets wrong, and it is not exotic:
     * Blessing of Power IV and IX are ordinary rolls. An unreadable numeral is null rather than an
     * exception — the source mod threw a `NullPointerException` from inside a chat callback here,
     * which the player would have seen as the game hitching mid-boss.
     */
    @Test
    fun `roman numerals subtract, and rubbish is null rather than a crash`() {
        assertEquals(4, CritMeter.romanToInt("IV"))
        assertEquals(9, CritMeter.romanToInt("IX"))
        assertEquals(7, CritMeter.romanToInt("VII"))
        assertEquals(14, CritMeter.romanToInt("XIV"))
        assertNull(CritMeter.romanToInt("VQ"))
        assertNull(CritMeter.romanToInt(""))
    }

    /**
     * The footer sum: Power by its numeral, plus 2.5 for each Blessing of Time.
     *
     * The 2.5 is inherited from the source mod and cannot be checked here — this pins that it is
     * applied, not that it is right (`CritMeter.TIME_WORTH` says so at the site).
     */
    @Test
    fun `power is the numeral plus two and a half per blessing of time`() {
        val footer = """
            Blessing of Power VII
            Blessing of Time I
            Blessing of Stone IV
        """.trimIndent()

        assertEquals(9.5, CritMeter.power(footer))
    }

    /**
     * Null and zero are different answers. A footer with no blessing row at all means the reading
     * failed — the wrong screen, an empty tab list, a format that changed — and treating that as
     * zero power would turn a failed read into a division by zero presented as a fact.
     */
    @Test
    fun `a footer with no blessings reads as unknown, not as zero`() {
        assertNull(CritMeter.power("You are playing on profile: Pineapple\nCo-op with nobody"))
        assertNull(CritMeter.power(""))
    }

    /**
     * The numeral has to follow the words. The source mod took the first `[IVXLCDM]+` run anywhere
     * on the line, which reads a numeral out of whatever else shares the row — an all-caps word
     * containing only those letters is enough.
     */
    @Test
    fun `the numeral is read from after the blessing name and not from elsewhere on the line`() {
        assertNull(CritMeter.power("MIX Blessing of Power"))
        assertEquals(5.0, CritMeter.power("MIX Blessing of Power V"))
    }

    /**
     * The number the feature exists for is written last, where the eye lands, and it is a quotient
     * rather than the raw figure. 12B over power 8 is 1.50B per power; a readout that printed the
     * raw number twice would look right and say nothing.
     */
    @Test
    fun `the readout divides the crit by the power`() {
        assertEquals("Crit 12.0B · power 8 · 1.50B per power", CritMeter.line(12e9, 8.0))
        // A half-point power keeps its decimal; a whole one does not carry a pointless ".0".
        assertEquals("Crit 19.0B · power 9.5 · 2.00B per power", CritMeter.line(19e9, 9.5))
    }

    /**
     * Without a power reading the crit is still shown. The raw number is what the player just
     * watched land, and suppressing it because the tab list was unreadable would make a failure to
     * read the footer look like a failure to crit.
     */
    @Test
    fun `an unreadable footer still shows the crit`() {
        assertEquals("Crit 12.0B · power unknown", CritMeter.line(12e9, null))
        assertEquals("Crit 12.0B · power unknown", CritMeter.line(12e9, 0.0))
    }

    /**
     * The window, end to end. Only the Maxor phase produces figures anybody compares, so a crit
     * outside it is not read at all — and the window closing is what stops a Goldor-phase or
     * next-floor hit being reported against the blessings of a fight that is over.
     */
    @Test
    fun `nothing is read until Maxor speaks, and nothing after Goldor does`() {
        val crit = "Your Explosive Shot hit 4 enemies for 48,000,000,000.0 damage!"
        val footer = { "Blessing of Power VI" }

        assertNull(CritMeter.onChat(crit, footer), "before the window opened")
        assertFalse(CritMeter.inCombat)

        assertNull(CritMeter.onChat(MAXOR, footer), "the opening line is not itself a crit")
        assertTrue(CritMeter.inCombat)
        assertEquals("Crit 12.0B · power 6 · 2.00B per power", CritMeter.onChat(crit, footer))
        // A phase produces many crits: reading one must not shut the window.
        assertEquals("Crit 12.0B · power 6 · 2.00B per power", CritMeter.onChat(crit, footer))

        CritMeter.onChat("[BOSS] Goldor: WHO DARES?", footer)
        assertFalse(CritMeter.inCombat)
        assertNull(CritMeter.onChat(crit, footer), "after the window closed")
    }

    /**
     * A crit arriving in the next floor's entrance must not be read against a fight that ended on the
     * previous one, so the window is shut where the run ends.
     *
     * This drives `DungeonSession.reset()` rather than [CritMeter.reset] — one of the very few
     * wiring lines in this repository that a test can actually reach, since that function only
     * mutates object state and needs no `Minecraft`. Deleting the `CritMeter.reset()` call from it is
     * what this fails on; asserting against [CritMeter.reset] directly would have proved only that
     * assignment works.
     */
    @Test
    fun `the window does not survive the run it opened in`() {
        CritMeter.onChat(MAXOR) { null }
        assertTrue(CritMeter.inCombat)

        DungeonSession.reset()

        assertFalse(CritMeter.inCombat)
        assertNull(CritMeter.onChat("Your Explosive Shot hit 4 enemies for 48,000,000,000.0 damage!") { null })
    }

    /**
     * The instrumentation, and the only thing that will ever confirm any string in [CritMeter].
     *
     * It reports outside the combat window on purpose: "the window never opened" is one of the two
     * ways this feature silently does nothing, and a diagnostic that only ran once the window was
     * open could not report it.
     */
    @Test
    fun `a crit line that did not parse is reported, and an ordinary line is not`() {
        val odd = "Your Explosive Shot obliterated 4 enemies for 48,000,000,000.0 damage!"

        assertEquals(odd, CritMeter.nearMiss(odd))
        assertNull(CritMeter.nearMiss("Your Explosive Shot hit 4 enemies for 48,000,000,000.0 damage!"))
        assertNull(CritMeter.nearMiss("Party > [MVP+] Bob: nice one"))
    }
}
