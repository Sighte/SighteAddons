package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The chain arithmetic: which line closes which span, what the spans come to, and what a run that is
 * still going reads as.
 *
 * **This is the half of the splits port that can be checked without a game, and it is the half that
 * would otherwise be guesswork.** The spans are credited to the *earlier* of two marks, exactly one
 * row can be running at a time, the total is measured from the first mark rather than from the
 * countdown, and master mode shares F-floor lines while keeping its own records — four rules that are
 * each one index away from being wrong, and none of which announces itself on screen: a panel with the
 * labels shifted by one still shows ten plausible times.
 *
 * The other half is not reachable from here and is not pretended to be: that the twenty-odd strings in
 * `DungeonSplits` are the strings Hypixel sends. They came from Odin, which does run against the live
 * server, and `split_missing` in a debug session is what will confirm or correct them — the same
 * standing `ChatEvents` and `StormTimer` have, for the same reason.
 */
class SplitsTest {

    /** [Splits] and [SplitPbs] are objects with process state, so each case starts clean. */
    @BeforeEach
    fun clearRun() {
        Splits.reset()
        SplitPbs.clear()
        Config.splits = true
    }

    // The lines, as Hypixel sends them. Named so a case reads as a run rather than as a wall of prose.
    private val mort = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private val bloodDoor = "The BLOOD DOOR has been opened!"
    private val watcherHello = "[BOSS] The Watcher: Ah, you've finally arrived."
    private val watcherPass = "[BOSS] The Watcher: You have proven yourself. You may pass."
    private val maxor = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private val storm = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private val goldorDomain = "[BOSS] Goldor: Who dares trespass into my domain?"
    private val coreOpening = "The Core entrance is opening!"
    private val necronHello = "[BOSS] Necron: You went further than any human before, congratulations."
    private val necronDone = "[BOSS] Necron: All this, for nothing..."
    private val defeated = " ☠ Defeated Necron in 5m 28s"

    /** Puts a floor on the sidebar and arms a chain on it, the way a real run does. */
    private fun startRun(floor: String, atMs: Long = 1_000L, atTicks: Long = 100L) {
        DungeonSession.observeSidebar(listOf("The Catacombs ($floor)"))
        Splits.onChat(Splits.START_LINE, atMs, atTicks)
    }

    private fun readout(nowMs: Long = 0L, ticks: Long = 0L) = Splits.readout(nowMs, ticks)

    private fun rowNamed(name: String, nowMs: Long = 0L, ticks: Long = 0L) =
        readout(nowMs, ticks)?.rows?.firstOrNull { it.name == name }

    @Test
    fun `a span is credited to the line that opened it, not the one that closed it`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)
        Splits.onChat(watcherPass, 90_000L, 1_790L)

        // Mort to the blood door is `blood open`, not `blood clear`. Getting this backwards is the one
        // mistake that leaves every number on screen looking entirely reasonable.
        assertEquals(20_000L, rowNamed("blood open", 90_000L, 1_790L)?.ms)
        assertEquals(60_000L, rowNamed("blood clear", 90_000L, 1_790L)?.ms)
        // Server ticks come off the same marks, so the two columns describe the same spans.
        assertEquals(390L, rowNamed("blood open", 90_000L, 1_790L)?.ticks)
        assertEquals(1_200L, rowNamed("blood clear", 90_000L, 1_790L)?.ticks)
    }

    @Test
    fun `the running span is measured against now and everything behind it is absent`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)

        val live = readout(45_000L, 890L)
        assertNotNull(live)
        assertEquals(1, live!!.runningRow, "blood clear is the span in progress")
        assertEquals(15_000L, live.rows[1].ms, "measured against the clock, not against a mark")
        assertTrue(live.rows[2].ms < 0, "portal entry has not started, so it has no time at all")
        assertEquals(35_000L, live.totalMs, "the total runs from the first mark to now")
    }

    @Test
    fun `the first of the two blood signals wins`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)
        // The Watcher greets the party a moment after the door opens. Taking the later of the two would
        // shorten every blood clear by that gap — BloodClear.onOpen states the same rule for the pair.
        Splits.onChat(watcherHello, 30_400L, 598L)
        Splits.onChat(watcherPass, 90_000L, 1_790L)

        assertEquals(60_000L, rowNamed("blood clear", 90_000L, 1_790L)?.ms)
    }

    @Test
    fun `a split fires once`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(mort, 20_000L, 400L)

        // The second Mort line must not restart the run: the total is measured from the first mark.
        assertEquals(20_000L, readout(30_000L, 600L)?.totalMs)
    }

    @Test
    fun `boss entry is the first three spans summed, and the entrance has none`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)
        Splits.onChat(watcherPass, 90_000L, 1_790L)
        Splits.onChat(maxor, 94_000L, 1_870L)

        val f7 = readout(94_000L, 1_870L)!!
        assertTrue(f7.hasBossEntry)
        assertEquals(84_000L, f7.bossEntryMs, "mort's line to maxor's first")

        // The entrance chain is the three shared splits and a total. Odin's own test here is
        // `size > 3`, which is true of that chain and puts a `Boss Entry` row on the one floor with no
        // boss — where it is the whole run under a second name.
        Splits.reset()
        startRun("Entrance")
        Splits.onChat(mort, 10_000L, 200L)
        assertEquals(false, readout(20_000L, 400L)?.hasBossEntry)
    }

    @Test
    fun `master mode uses the F floor lines and keeps its own records`() {
        startRun("M7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)
        Splits.onChat(watcherPass, 90_000L, 1_790L)
        // Master mode's bosses say what F7's say, so the chain has to be F7's.
        Splits.onChat(maxor, 94_000L, 1_870L)
        Splits.onChat(storm, 120_000L, 2_390L)

        assertEquals(26_000L, rowNamed("maxor", 120_000L, 2_390L)?.ms)
        // The record goes under M7 and not under F7: an M7 blood clear and an F7 one are not the same
        // run, which is why Odin keys its personal bests on the floor and not on the number.
        assertEquals(20f, SplitPbs.get("M7", "blood open"))
        assertNull(SplitPbs.get("F7", "blood open"))
    }

    @Test
    fun `a full F7 files every span and the total`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)
        Splits.onChat(watcherPass, 90_000L, 1_790L)
        Splits.onChat(maxor, 94_000L, 1_870L)
        Splits.onChat(storm, 120_000L, 2_390L)
        Splits.onChat(goldorDomain, 168_000L, 3_350L)
        Splits.onChat(coreOpening, 248_000L, 4_940L)
        Splits.onChat(necronHello, 256_000L, 5_100L)
        Splits.onChat(necronDone, 286_000L, 5_700L)
        Splits.onChat(defeated, 290_000L, 5_780L)

        val done = readout(999_999L, 99_999L)!!
        assertTrue(done.finished, "a finished run has no span in progress")
        assertEquals(280_000L, done.totalMs, "the total is the first mark to the last, not to now")
        // The total is filed under its own name, which is what Odin does on the final line as well.
        assertEquals(280f, SplitPbs.get("F7", "total"))
        assertEquals(30f, SplitPbs.get("F7", "necron"))
        // `cleared` is Necron's last line to Hypixel's own kill line, and it is the shortest span here.
        assertEquals(4f, SplitPbs.get("F7", "cleared"))
    }

    @Test
    fun `a line a player could have typed times nothing`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        Splits.onChat(bloodDoor, 30_000L, 590L)
        Splits.onChat(watcherPass, 90_000L, 1_790L)

        // Hypixel puts `Name: ` in front of anything a player says, so an anchored whole-line match
        // cannot be produced by a teammate. Without the anchors this would file a record.
        Splits.onChat("Party > [MVP+] Someone: $maxor", 94_000L, 1_870L)
        assertTrue(rowNamed("portal entry", 100_000L, 2_000L)!!.running, "maxor has not spoken")
        assertNull(SplitPbs.get("F7", "portal entry"))
    }

    @Test
    fun `nothing is timed while the feature is off`() {
        Config.splits = false
        try {
            startRun("F7")
            Splits.onChat(mort, 10_000L, 200L)
            // Odin goes on filing personal bests with its module switched off. Refusing to arm is what
            // makes one switch cover the panel, the chat lines and the store at once.
            assertNull(readout(20_000L, 400L))
            assertNull(SplitPbs.get("F7", "blood open"))
        } finally {
            Config.splits = true
        }
    }

    @Test
    fun `the chain does not survive the run it was armed in`() {
        startRun("F7")
        Splits.onChat(mort, 10_000L, 200L)
        assertNotNull(readout(20_000L, 400L))

        // The wiring line in DungeonSession.reset, not the logic: a chain left standing would take the
        // next floor's first chat line and file its span under the previous floor's records. Deleting
        // that one call is what this case exists to fail on.
        DungeonSession.reset()
        assertNull(readout(20_000L, 400L))
    }
}
