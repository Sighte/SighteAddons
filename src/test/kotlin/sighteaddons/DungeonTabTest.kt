package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The tab list is the only place the client can read a **true** secret count, and the two lines that
 * carry it share a prefix and differ by one character. If [DungeonTab.SECRET_COUNT] ever starts
 * matching the percentage line the summary prints `10 of 42` on a floor where 29 were found, which
 * is a confidently wrong number rather than a missing one — strictly the worse failure, and the one
 * these fixtures exist for.
 *
 * The seam is [DungeonTab.read], which takes the rows instead of a `Minecraft`, for the reason
 * [DungeonSession.observeSidebar] does. The rows below are Hypixel's formats as cited in
 * [DungeonTab]'s KDoc from Odin's `DungeonListener.kt` and Skyblocker's `DungeonScore.java` — the
 * dev client cannot log in to Hypixel, so **no row here was observed on a real floor** and that is
 * exactly what `tab_secrets` in a debug session is for.
 */
class DungeonTabTest {
    @BeforeEach
    fun clean() = DungeonTab.reset()

    /** Hypixel indents these lines; [PartyTracker.update] trims the row before this ever sees it. */
    private fun tab(vararg extra: String) = listOf(
        "[102] [MVP+] Notch (Berserk VII)",
        "Ult Cooldown: 12s",
        "Revives: 3",
        null,
        "[300] Some_One1 (Healer XXV)",
        "Dungeon Info",
        "Team Deaths: 0",
        "Puzzles: (3)",
    ) + extra.toList()

    @Test
    fun `reads the party-wide count and the percentage as two different numbers`() {
        val seen = DungeonTab.read(tab("Secrets Found: 29", "Secrets Found: 80.6%"))!!
        assertEquals(29, seen.found)
        assertEquals(80.6, seen.percent)
    }

    /**
     * The defect this class is mostly here to make unrepresentable. Both lines begin
     * `Secrets Found: `, so an unanchored count pattern takes `42` off `42.5%` and reports a floor
     * total that is neither the count nor the percentage.
     */
    @Test
    fun `the percentage line is never read as a count`() {
        val percentOnly = DungeonTab.read(tab("Secrets Found: 42.5%"))!!
        assertNull(percentOnly.found, "42.5% is not a count of 42")
        assertEquals(42.5, percentOnly.percent)

        // And the other way round: a bare count has no percentage hiding in it.
        val countOnly = DungeonTab.read(tab("Secrets Found: 15"))!!
        assertEquals(15, countOnly.found)
        assertNull(countOnly.percent)
    }

    /** Skyblocker's pattern is `\d+\.?\d*`, so Hypixel writes the percentage with or without a decimal. */
    @Test
    fun `a whole-number percentage is still a percentage`() {
        assertEquals(100.0, DungeonTab.read(tab("Secrets Found: 100%"))!!.percent)
        assertNull(DungeonTab.read(tab("Secrets Found: 100%"))!!.found)
    }

    /**
     * The ordinary case outside a dungeon, and the case a real floor has to refute. Null rather than
     * a zeroed reading: nothing seen is not the same claim as nothing found, which is the same
     * distinction [RoomHistory.breakdown] draws between a dash and a 0.
     */
    @Test
    fun `no secret line at all reads as nothing seen, not as zero`() {
        assertNull(DungeonTab.read(tab()))
        assertNull(DungeonTab.read(emptyList()))
        assertNull(DungeonTab.read(listOf(null, null)))
    }

    /** Skyblocker probes for a `Discoveries:` line because the index moves; scanning cannot care. */
    @Test
    fun `the count is found wherever it sits in the list`() {
        val early = DungeonTab.read(listOf("Secrets Found: 7") + tab())!!
        val late = DungeonTab.read(tab("Discoveries: 4", "Secrets Found: 7"))!!
        assertEquals(7, early.found)
        assertEquals(7, late.found)
    }

    @Test
    fun `observe keeps the highest reading of the run, not the last`() {
        DungeonTab.observe(tab("Secrets Found: 12", "Secrets Found: 33.3%"))
        assertEquals(12, DungeonTab.secretsFound)
        DungeonTab.observe(tab("Secrets Found: 29", "Secrets Found: 80.6%"))
        assertEquals(29, DungeonTab.secretsFound)
        assertEquals(80.6, DungeonTab.secretsPercent)

        // The run ends and Hypixel takes the dungeon rows out of the tab list. printSummary runs off
        // the run-end chat line, which can arrive after that — so a later empty list must not erase
        // the figure the summary is about to print.
        DungeonTab.observe(tab())
        assertEquals(29, DungeonTab.secretsFound)
        // Nor may a lower reading replace it. Within a run the counter only rises, so a fall is the
        // tab list being rebuilt rather than secrets being un-found.
        DungeonTab.observe(tab("Secrets Found: 3", "Secrets Found: 8.3%"))
        assertEquals(29, DungeonTab.secretsFound)
        assertEquals(80.6, DungeonTab.secretsPercent, "the percentage belongs to the count it was read with")
    }

    @Test
    fun `reset forgets the run`() {
        DungeonTab.observe(tab("Secrets Found: 29", "Secrets Found: 80.6%"))
        DungeonTab.reset()
        assertNull(DungeonTab.secretsFound)
        assertNull(DungeonTab.secretsPercent)
    }

    /**
     * The near-miss log. Narrow on purpose — the prefix is matched literally, so nothing a player
     * typed and no player's name can reach it, which is what makes logging the row verbatim safe.
     */
    @Test
    fun `a secret line in an unexpected format is reported, an ordinary one is not`() {
        assertEquals(listOf("Secrets Found: 12/15"), DungeonTab.unparsed(tab("Secrets Found: 12/15")))
        assertTrue(DungeonTab.unparsed(tab("Secrets Found: 29", "Secrets Found: 80.6%")).isEmpty())
        // Not a near miss and must never become one: a party row carries a player's name.
        assertTrue(DungeonTab.unparsed(tab("[102] [MVP+] Notch (Berserk VII)")).isEmpty())
    }

    /**
     * Hypixel's own clock, which is the number [SoloClear] announces. Ours is close but starts at
     * calibration, so it cannot be the one two players compare in a channel.
     */
    @Test
    fun `the run's official time is read in both spellings and both formats`() {
        DungeonTab.readElapsed(tab("Time: 06m 32s"))
        assertEquals("06m 32s", DungeonTab.elapsed)

        DungeonTab.reset()
        DungeonTab.readElapsed(tab("Time Elapsed: 6:32"))
        assertEquals("6:32", DungeonTab.elapsed)
    }

    /**
     * **The reading that matters is taken at the run-end headline, and by then Hypixel may already have
     * pulled the dungeon rows.** A row that stopped advancing would otherwise shorten the run it is
     * announcing — the one failure here that produces a plausible number rather than none.
     */
    @Test
    fun `the furthest reading of the run wins, not the last one`() {
        DungeonTab.readElapsed(tab("Time: 06m 32s"))
        DungeonTab.readElapsed(tab("Time: 02m 10s"))
        assertEquals("06m 32s", DungeonTab.elapsed)

        // Comparison is on the parsed seconds, so it survives the two formats disagreeing about width.
        DungeonTab.readElapsed(tab("Time: 11:04"))
        assertEquals("11:04", DungeonTab.elapsed)
    }

    /**
     * `Time:` is a far more ordinary prefix than `Secrets Found:`, so the value has to look like a
     * duration and the row has to be nothing but the row. A wrong match here is a wrong time in a
     * Discord channel.
     */
    @Test
    fun `a row that merely mentions a time is not the run's time`() {
        DungeonTab.readElapsed(
            tab("Time: soon", "Ult Cooldown: 12s", "Time: 06m 32s left", "Sneak Time: 4:00", "Time: :32"),
        )
        assertNull(DungeonTab.elapsed)
    }

    /**
     * **`59s` is the shape the first minute of every run has**, measured on the M7s of 2026-08-19 —
     * `Time Elapsed: 59s`, no minute part. Requiring the minutes made exactly the window a fast
     * clear-phase score is reached in unreadable, and the fallback clock took over silently.
     */
    @Test
    fun `every shape Hypixel writes a duration in`() {
        assertEquals(392, DungeonTab.seconds("06m 32s"))
        assertEquals(392, DungeonTab.seconds("6:32"))
        assertEquals(664, DungeonTab.seconds("11:04"))
        assertEquals(59, DungeonTab.seconds("59s"), "under a minute there is no minute part")
        assertEquals(60, DungeonTab.seconds("01m 00s"))
        assertEquals(3723, DungeonTab.seconds("1h 02m 03s"))
        assertNull(DungeonTab.seconds("soon"))
        // The tab list carries a second `Time:` row that reads N/A. It is not a duration.
        assertNull(DungeonTab.seconds("N/A"))
    }

    @Test
    fun `the tab's other Time row reads N over A and is never taken for a time`() {
        DungeonTab.readElapsed(tab("Time: N/A", "Time: 59s"))
        assertEquals("59s", DungeonTab.elapsed)
    }

    /**
     * Odin's `DungeonUtils.totalSecrets`, which is the only way to learn how many secrets the floor
     * has at all. Derived and approximate, which is why it is logged rather than printed.
     */
    @Test
    fun `the floor total is back-derived from the count and its percentage`() {
        assertEquals(36, DungeonTab.floorSecrets(29, 80.6))
        assertEquals(10, DungeonTab.floorSecrets(5, 50.0))
        // Both of Odin's guards. A zero on either side gives no equation to solve, and dividing by
        // the percentage without the guard is an infinity that would print as a floor total.
        assertNull(DungeonTab.floorSecrets(0, 80.6))
        assertNull(DungeonTab.floorSecrets(29, 0.0))
        assertNull(DungeonTab.floorSecrets(null, 80.6))
        assertNull(DungeonTab.floorSecrets(29, null))
    }
}
