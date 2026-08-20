package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The two things about a run record that nothing else can check.
 *
 * 1. **Which clock, and which record it counts against.** Hypixel's `Clear Time:` and our
 *    Mort→Defeated span are different measurements of the same run, and the party size decides who a
 *    time is even competing with. Get the key wrong and a five-man time stands as a solo record —
 *    which looks exactly like a very good solo run, forever, because a record cannot be beaten off the
 *    board by playing better.
 * 2. **The payload.** The leaderboard route does not exist yet, so this body *is* the contract the
 *    receiver will be written against. A field renamed here after that is written is a `400` on every
 *    row, and [RunPbs] keeps the rejected ones rather than the runs.
 *
 * [Config] cannot be loaded here — its path is a `FabricLoader` call in a field initialiser — so the
 * identity and the version are handed in, exactly as `RunReportTest` hands them to [RunReport.build].
 */
class RunPbsTest {

    /** No file, no network: the store starts empty and stays in memory. */
    @BeforeEach
    fun clean() = RunPbs.useEmptyStore()

    /**
     * A key survives the round trip, and one that cannot is skipped rather than guessed at.
     *
     * The key *is* the record — the store is a map from it to seconds, which is what makes "the minimum
     * over the file, per key" one line — so the `/sa` runs table has to be able to read all three fields
     * back out of it. A wrong parse here is a five-man record filed under `solo` on a screen, which is
     * the same failure the key exists to prevent, one layer up.
     *
     * `runpbs.jsonl` is hand-editable, so the refusals matter as much as the round trip: a row this
     * cannot read costs that row and never the table.
     */
    @Test
    fun `a record key reads back as its three fields`() {
        val record = RunPbs.record(RunPbs.key("M7", 4, RunPbs.Clock.HYPIXEL), 348f)
        assertEquals("M7", record?.floor)
        assertEquals(4, record?.players)
        assertEquals(RunPbs.Clock.HYPIXEL, record?.clock)
        assertEquals(348f, record?.seconds)

        assertEquals(RunPbs.Clock.OWN, RunPbs.record(RunPbs.key("F7", 1, RunPbs.Clock.OWN), 400f)?.clock)

        // A missing half, a party size that is not one, and a clock nothing writes any more.
        assertNull(RunPbs.record("M7|4", 348f))
        assertNull(RunPbs.record("M7|many|hypixel", 348f))
        assertNull(RunPbs.record("M7|4|stopwatch", 348f))
        assertNull(RunPbs.record("M7|0|hypixel", 348f), "a party of nobody is not a party")
    }

    /**
     * Hypixel's clock is the record, ours is only the fallback, and neither ever meets the other.
     *
     * The fallback flag is [RunPbs.reset]'s: while a run is live, a missing `Clear Time:` line means
     * *not yet*, and filing our own span at that moment would beat the official time to the record
     * with a number measured differently. Only the reset — the last moment a run exists at all — may
     * fall back, and what it files lands in its own key space.
     */
    @Test
    fun `hypixel's time is the record and ours is the fallback`() {
        // Mid-run, with no official time yet: nothing is comparable, so nothing is filed.
        assertEquals(null, RunPbs.timed(officialTime = null, totalMs = 401_300, fallback = false))

        // The same run at reset, still without the line: our span, marked as ours.
        val own = RunPbs.timed(officialTime = null, totalMs = 401_300, fallback = true)
        assertEquals(RunPbs.Clock.OWN, own?.clock)
        assertEquals(401.3f, own?.seconds)

        // And with the line, in both spellings Hypixel uses, whether or not the reset has come.
        for (fallback in listOf(false, true)) {
            val stated = RunPbs.timed("06m 32s", totalMs = 401_300, fallback = fallback)
            assertEquals(RunPbs.Clock.HYPIXEL, stated?.clock, "hypixel's line wins whenever it is there")
            assertEquals(392f, stated?.seconds)
            assertEquals(392f, RunPbs.timed("6:32", 401_300, fallback)?.seconds, "the colon spelling too")
        }

        // A line that does not parse is not a reason to fall back to a different clock: the run is
        // filed by whichever clock stated it, and an unreadable statement is not our span.
        assertEquals(null, RunPbs.timed("no time at all", totalMs = 401_300, fallback = true))
        // And a span of nothing is refused outright — SplitPbs.record's reason, one level up.
        assertEquals(null, RunPbs.timed(officialTime = null, totalMs = 0, fallback = true))
    }

    /**
     * A record is per floor, per party size and per clock, and only a better time writes.
     *
     * The party size is the half a leaderboard cannot do without: a solo M7 and a five-man M7 are not
     * the same event, and one key for both means the board only ever shows the largest party. The clock
     * is in the key for [SoloClear]'s stated reason — a run timed one way must not take a record from a
     * run timed the other.
     */
    @Test
    fun `records are kept per floor, party size and clock`() {
        val hypixel = RunPbs.Clock.HYPIXEL

        assertTrue(RunPbs.evaluate("M7", 1, hypixel, 392f) is RunPbs.Result.First)
        RunPbs.commit("M7", 1, hypixel, 392f)
        assertEquals(392f, RunPbs.best("M7", 1, hypixel))

        // A slower solo run does not touch it, and says what it was up against.
        val missed = RunPbs.evaluate("M7", 1, hypixel, 400f)
        assertEquals(392f, (missed as RunPbs.Result.Missed).best)
        assertEquals(392f, RunPbs.best("M7", 1, hypixel), "a missed record is not written")

        // A faster one takes it, and reports the record that fell.
        val beat = RunPbs.evaluate("M7", 1, hypixel, 380f)
        assertEquals(392f, (beat as RunPbs.Result.Beat).previous)
        RunPbs.commit("M7", 1, hypixel, 380f)
        assertEquals(380f, RunPbs.best("M7", 1, hypixel))

        // A five-man run of the same floor is a first record, not a beaten one — the whole point of
        // the party in the key. A 258 second five-man would otherwise stand as the solo best.
        assertTrue(RunPbs.evaluate("M7", 5, hypixel, 258f) is RunPbs.Result.First)
        RunPbs.commit("M7", 5, hypixel, 258f)
        assertEquals(380f, RunPbs.best("M7", 1, hypixel), "the solo record is untouched")

        // Our clock keeps its own board at the same key, so a slower own-clock time is still a first.
        assertTrue(RunPbs.evaluate("M7", 1, RunPbs.Clock.OWN, 401.3f) is RunPbs.Result.First)
        // And another floor is another record, whatever the time.
        assertTrue(RunPbs.evaluate("F7", 1, hypixel, 500f) is RunPbs.Result.First)
    }

    /**
     * The body the leaderboard will be written against.
     *
     * Named field by field on purpose: this is the one thing in the feature that another codebase has
     * to agree with, and the receiver half does not exist yet to disagree. `timeSource` is what stops a
     * row timed by us from being ranked against one Hypixel timed, and `previous` is absent on a first
     * record rather than being a zero somebody has to know to ignore.
     */
    @Test
    fun `the payload names every field the leaderboard reads`() {
        val body = RunPbs.payload(
            player = "Sighte",
            installId = "0f5e4a1c-1111-2222-3333-444455556666",
            floor = "M7",
            players = 1,
            clock = RunPbs.Clock.HYPIXEL,
            seconds = 392f,
            time = "06m 32s",
            totalMs = 401_300,
            totalTicks = 7_840,
            previous = 401.5f,
            ts = 1_787_000_000_000,
            modVersion = "0.17.0-dev14",
        )
        assertEquals("Sighte", body["player"].asString)
        assertEquals("0f5e4a1c-1111-2222-3333-444455556666", body["installId"].asString)
        assertEquals("M7", body["floor"].asString)
        assertEquals(1, body["players"].asInt)
        assertEquals("06m 32s", body["time"].asString)
        assertEquals(392f, body["seconds"].asFloat)
        assertEquals("hypixel", body["timeSource"].asString)
        assertEquals(401_300, body["totalMs"].asLong)
        assertEquals(7_840, body["totalTicks"].asLong)
        assertEquals(401.5f, body["previous"].asFloat)
        assertEquals(1_787_000_000_000, body["ts"].asLong)
        assertEquals("0.17.0-dev14", body["modVersion"].asString)

        // A first-ever record on our own clock, by a client that never read its own name: three fields
        // are absent rather than carrying an invented value, and `timeSource` says which board it is on.
        val first = RunPbs.payload(
            player = null,
            installId = "0f5e4a1c-1111-2222-3333-444455556666",
            floor = "F7",
            players = 4,
            clock = RunPbs.Clock.OWN,
            seconds = 401.3f,
            time = null,
            totalMs = 401_300,
            totalTicks = 0,
            previous = null,
            ts = 1_787_000_000_000,
            modVersion = "0.17.0-dev14",
        )
        assertFalse(first.has("player"), "no name is better than a made-up one")
        assertFalse(first.has("time"), "there is no official time to quote")
        assertFalse(first.has("previous"), "nothing fell")
        assertFalse(first.has("totalTicks"), "a tick count nobody read is not a zero")
        assertEquals("own", first["timeSource"].asString)
        assertEquals(4, first["players"].asInt)
    }
}
