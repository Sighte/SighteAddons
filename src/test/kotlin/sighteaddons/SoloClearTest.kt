package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The three decisions behind an announcement that lands in a channel other people read, all of which
 * fail *plausibly* when they are wrong:
 *
 *  - **was this run solo.** A party run announced as a solo clear is a claim about other people's work,
 *    and nothing in the message would look wrong.
 *  - **was it a record.** `pb` is this side's claim and the receiver relays it without checking, because
 *    it keeps no history to check against. A false one is read once and cannot be taken back.
 *  - **what leaves the machine.** The payload is the whole of it, and a field this mod cannot answer
 *    for must be absent rather than invented — the receiver spells an absent field `?`.
 *
 * [SoloClear.onRunEnd] and the release it arms are not reachable here: they read the live session,
 * append to the game directory and open a socket. What they *decide* is in the pure functions below —
 * the gate, the payload, the floor tag, the fold and the three chat patterns — which is the same seam
 * [RunReport.reportedFloor] and [DungeonTab.read] are split along.
 */
class SoloClearTest {
    @BeforeEach
    fun clean() = SoloClear.reset()

    @Test
    fun `a run nobody else was ever in is solo`() {
        repeat(3) { SoloClear.observe(1) }
        assertTrue(SoloClear.solo)
    }

    /**
     * **The direction BlackAddons gets wrong, and the reason for two flags.** Its tracker latches solo
     * true on the first reading that shows one player — but the tab list fills in as the run loads, so
     * a party of five reads as one player for the first ticks and stays latched for the whole floor.
     */
    @Test
    fun `a teammate who loads a second late still makes it a party run`() {
        SoloClear.observe(1)
        SoloClear.observe(5)
        assertFalse(SoloClear.solo, "the roster showed a second person at some point in the run")

        // And it does not come back when they die out of the list, or when Hypixel stops listing them.
        SoloClear.observe(1)
        assertFalse(SoloClear.solo)
    }

    /**
     * A size of 0 is "the rows did not parse", not "nobody was there". Announcing on the strength of
     * having seen nobody is how a run with an unreadable tab list becomes a solo clear.
     */
    @Test
    fun `a run whose roster was never readable is not announced`() {
        repeat(5) { SoloClear.observe(0) }
        assertFalse(SoloClear.solo)
    }

    @Test
    fun `the flags belong to one run`() {
        SoloClear.observe(5)
        SoloClear.reset()
        SoloClear.observe(1)
        assertTrue(SoloClear.solo, "the previous run's company is not this run's")
    }

    /**
     * `E` and not `Entrance`, which is the spelling the whole stack agrees on — the receiver's own floor
     * pattern is `?|E|[FM][1-7]`, and this is the same mapping `floorname-001` is about on the `/runs`
     * side. `?` for a floor that was never seen, exactly as [RunReport.reportedFloor] answers.
     */
    @Test
    fun `the entrance is E and an unknown floor stays a question mark`() {
        assertEquals("E", SoloClear.floorTag("Entrance"))
        assertEquals("?", SoloClear.floorTag(null))
        assertEquals("M7", SoloClear.floorTag("M7"))
        assertEquals("F3", SoloClear.floorTag("F3"))
    }

    @Test
    fun `the payload carries what this mod knows and nothing it does not`() {
        val body = SoloClear.payload(
            "Sighte", "M7", "06m 32s", secrets = 42, deaths = 0, score = 305, prince = true, pb = true,
        )

        assertEquals("Sighte", body["player"].asString)
        assertEquals("M7", body["floor"].asString)
        assertEquals("06m 32s", body["time"].asString)
        assertEquals(42, body["secrets"].asInt)
        assertEquals(0, body["deaths"].asInt)
        assertTrue(body["pb"].asBoolean)
        assertTrue(body["prince"].asBoolean, "his line was seen, so this is measured")
        // Hypixel's own score, handed over as a component so the receiver shows it by name without
        // having to learn the field.
        assertEquals(305, body["score_components"].asJsonObject["score"].asInt)
        // Not tracked anywhere in this mod. The receiver prints `?` for a field it was not given, which
        // is true; a `false` invented here would say the mimic survived.
        for (key in listOf("crypts", "mimic")) {
            assertFalse(body.has(key), "$key is not something this mod can answer for")
        }
    }

    /**
     * The Prince is absent, not `false`, when his line never came — and so is the score when the gate is
     * off and nothing released on it. Same rule as the counts: silence is not a claim.
     */
    @Test
    fun `an unseen prince and an unknown score are absent, not denied`() {
        val body = SoloClear.payload(
            "Sighte", "M7", "6:32", secrets = 3, deaths = 0, score = null, prince = false, pb = false,
        )
        assertFalse(body.has("prince"))
        assertFalse(body.has("score_components"))
    }

    /**
     * **The gate, and the one asymmetry in it that is deliberate.** Above 0 an unknown score *fails*:
     * a threshold that cannot be evaluated has not been met. Passing on unknown would turn one wrong
     * regex into a channel that announces every run — the failure that looks like the feature working.
     */
    @Test
    fun `an unknown score fails a gate and passes no gate`() {
        assertTrue(SoloClear.passes(null, 0), "no gate needs no score")
        assertFalse(SoloClear.passes(null, 300), "a gate that cannot be evaluated is not met")
        assertTrue(SoloClear.passes(300, 300))
        assertTrue(SoloClear.passes(312, 300))
        assertFalse(SoloClear.passes(299, 300))
        assertTrue(SoloClear.passes(270, 270))
    }

    /**
     * **The rule an earlier version of this file got wrong, at the cost of a real solo M7.**
     *
     * The live score usually crosses the threshold in the last rooms or never; Hypixel's `Team Score:`
     * states the S+ outright once the run is over. Judging on the live number alone refuses runs that
     * qualified, and the refusal looks exactly like a feature that is switched off. Upstream feeds both
     * into one comparison and takes the higher.
     */
    @Test
    fun `the gate is judged on whichever score is higher`() {
        assertEquals(304, SoloClear.best(268, 304), "the run qualified; the live reading never said so")
        assertEquals(304, SoloClear.best(304, 268))
        assertEquals(268, SoloClear.best(268, null), "mid-run there is no chat score yet")
        assertEquals(304, SoloClear.best(null, 304), "and a score that was never readable live still counts")
        assertNull(SoloClear.best(null, null))
        // Which is what the gate then sees.
        assertTrue(SoloClear.passes(SoloClear.best(268, 304), 300))
        assertFalse(SoloClear.passes(SoloClear.best(268, 291), 300))
    }

    /**
     * Hypixel states the score outright a few lines under the run-end headline. That is where the 300
     * comes from — [DungeonScore] computes a live estimate for a screen, and a channel is not gated on
     * an estimate.
     */
    @Test
    fun `the official score is read off the summary line, grade and all`() {
        assertEquals("305", SoloClear.SCORE.matchEntire(" Team Score: 305 (S+)")!!.groupValues[1])
        assertEquals("270", SoloClear.SCORE.matchEntire("Team Score: 270 (S)")!!.groupValues[1])
        // The grade is decoration; anchoring against it would break the day Hypixel rewords it.
        assertEquals("94", SoloClear.SCORE.matchEntire(" Team Score: 94")!!.groupValues[1])
        // Somebody else's score, in a sentence, is not the run's.
        assertNull(SoloClear.SCORE.matchEntire("Notch: my Team Score: 300 lol"))
    }

    /**
     * The better of the two official times: the tab list can already be empty at run end, while this
     * line is printed at that exact moment.
     */
    @Test
    fun `the official clear time is read off the same block`() {
        assertEquals("06m 32s", SoloClear.CLEAR_TIME.matchEntire(" Clear Time: 06m 32s")!!.groupValues[1])
        assertEquals("6:32", SoloClear.CLEAR_TIME.matchEntire("Elapsed Time: 6:32")!!.groupValues[1])
        assertNull(SoloClear.CLEAR_TIME.matchEntire(" Clear Time: soon"))
    }

    @Test
    fun `the prince is only claimed on his own line`() {
        assertTrue(SoloClear.PRINCE.matchEntire(" A Prince falls. +1 Bonus Score") != null)
        assertNull(SoloClear.PRINCE.matchEntire("Notch: A Prince falls. +1 Bonus Score"))
    }

    /**
     * No secret count read and no secrets found are different facts. Sending `0` would make the second
     * claim out of the first, and the receiver has no way to tell them apart afterwards.
     */
    @Test
    fun `a secret count the tab list never gave is absent, not zero`() {
        val body = SoloClear.payload(
            "Sighte", "F7", "6:32", secrets = null, deaths = 1, score = null, prince = false, pb = false,
        )
        assertFalse(body.has("secrets"))
        assertFalse(body["pb"].asBoolean)
        assertEquals(1, body["deaths"].asInt)
    }

    /**
     * The record is the minimum over the file, per floor — the same design as [RoomHistory.fold], and the
     * one number a wrong fold turns into a false `**NEW SOLO PB**`.
     */
    @Test
    fun `a record is the minimum over the file, one per floor`() {
        val bests = SoloClear.fold(
            sequenceOf(
                """{"floor":"M7","ticks":9000,"seconds":460,"pb":true,"ts":1}""",
                """{"floor":"M7","ticks":8200,"seconds":410,"pb":true,"ts":2}""",
                """{"floor":"M7","ticks":8800,"seconds":448,"pb":false,"ts":3}""",
                """{"floor":"F7","ticks":7000,"seconds":352,"pb":true,"ts":4}""",
            ),
        )
        assertEquals(410, bests.bySeconds["M7|clear"])
        assertEquals(8200, bests.byTicks["M7|clear"])
        assertEquals(352, bests.bySeconds["F7|clear"])
        assertEquals(4, bests.read)
    }

    /**
     * **The record split that matters most, now that the gate fires mid-run.** `score300` is how long it
     * took to reach 300; `clear` is the whole floor. The first is always the smaller number, so filing both
     * under one key would make every gated run a record and every clear look beaten.
     */
    @Test
    fun `a time to 300 never competes with a clear time`() {
        val bests = SoloClear.fold(
            sequenceOf(
                """{"floor":"M7","metric":"clear","ticks":9000,"seconds":455,"ts":1}""",
                """{"floor":"M7","metric":"score300","ticks":5200,"seconds":263,"ts":2}""",
                """{"floor":"M7","metric":"score270","ticks":4400,"seconds":222,"ts":3}""",
            ),
        )
        assertEquals(455, bests.bySeconds["M7|clear"], "the clear keeps its own record")
        assertEquals(263, bests.bySeconds["M7|score300"])
        // Two thresholds are two metrics as well: a time to 270 is not a time to 300.
        assertEquals(222, bests.bySeconds["M7|score270"])
        assertEquals("score300", SoloClear.metricFor(300))
        assertEquals("clear", SoloClear.metricFor(0))
    }

    /**
     * **Hypixel's seconds and our ticks are two measurements of the same run and are never compared with
     * each other.** [DungeonSession.runTicks] starts at calibration rather than at the door, so it is
     * systematically short of the official time — a run timed one way, judged against a record timed the
     * other, would win or lose on the clock instead of on the run. A line from before the tab-list row
     * was read therefore contributes to the tick record only.
     */
    @Test
    fun `a line without hypixel's time contributes to the tick record only`() {
        val bests = SoloClear.fold(
            sequenceOf(
                """{"floor":"M7","ticks":8000,"pb":true,"ts":1}""",
                """{"floor":"M7","ticks":9000,"seconds":455,"pb":true,"ts":2}""",
            ),
        )
        assertEquals(8000, bests.byTicks["M7|clear"], "both lines carry ticks")
        assertEquals(455, bests.bySeconds["M7|clear"], "only the second one carries seconds")
        assertNull(bests.bySeconds["F7|clear"])
    }

    /**
     * One unreadable line must not cost the record. The file is append-only and never rewritten, so a
     * torn last line — a crash mid-write — would otherwise make the whole history unreadable at the next
     * launch, which is the failure this file's design exists to avoid.
     */
    @Test
    fun `a torn line is skipped and the rest of the file still yields a record`() {
        val bests = SoloClear.fold(
            sequenceOf(
                """{"floor":"M7","ticks":8200,"seconds":410,"pb":true,"ts":1}""",
                "",
                """{"floor":"M7","ticks":81""",
                """{"ticks":7000,"seconds":300}""",
                """{"floor":"M7","ticks":7900,"seconds":399,"pb":true,"ts":2}""",
            ),
        )
        assertEquals(399, bests.bySeconds["M7|clear"])
        assertEquals(7900, bests.byTicks["M7|clear"])
        assertEquals(2, bests.read, "the blank, the torn line and the one without a floor are not entries")
    }
}
