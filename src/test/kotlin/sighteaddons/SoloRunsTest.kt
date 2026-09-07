package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.ui.screens.SoloRundown

/**
 * The file format and the reading of it. A record that does not survive its own round trip is a run
 * that vanishes from the tab after a relaunch, and a store one bad file can break is not a store.
 */
class SoloRunsTest {
    @Test
    fun `a record survives the round trip`() {
        val sample = SoloRuns.sample()
        val back = SoloRuns.decode(SoloRuns.encode(sample))
        assertNotNull(back)
        assertEquals(sample, back)
    }

    @Test
    fun `the fold keeps what it can read, newest first`() {
        val older = SoloRuns.sample(now = 1_000L)
        val newer = SoloRuns.sample(now = 2_000L)
        val bodies = sequenceOf(
            SoloRuns.encode(older).toString(),
            "{ not json",
            """{"v":1,"ts":5,"floor":"M7"}""",
            SoloRuns.encode(newer).toString(),
        )
        val folded = SoloRuns.fold(bodies)
        assertEquals(listOf(2_000L, 1_000L), folded.map { it.ts })
    }

    @Test
    fun `the best 300 ignores runs that never got there`() {
        val reached = SoloRuns.sample(now = 1L)
        val short = reached.copy(ts = 2L, score = reached.score.copy(marks = reached.score.marks.filter { it.threshold == 270 }))
        assertEquals(4620, SoloRuns.bestTo300(listOf(short, reached), "M7"))
        assertNull(SoloRuns.bestTo300(listOf(short), "M7"))
        assertNull(SoloRuns.bestTo300(listOf(reached), "F7"))
    }

    @Test
    fun `stops fold the doorway brush and carry the corridor`() {
        val record = SoloRuns.sample()
        val stops = SoloRundown.stops(record)
        // Atlas is two cells walked back and forth; the 20-tick return to (2,4) before the puzzle is a brush.
        val atlas = stops.first { it.room.name == "Atlas" }
        assertEquals(40, atlas.enter)
        assertEquals(540, atlas.leave, "the brush of the first cell on the way out is folded into the stay")
        assertEquals(480, atlas.clear, "the checkmark landed inside this stop")
        // Crypt is entered at 900 and its second cell left at 1400; the brush back at 1400-1430 folds in.
        val crypt = stops.first { it.room.name == "Crypt" }
        assertEquals(900 to 1430, crypt.enter to crypt.leave)
        // The way back to the blood room passes through rooms already done: those are stops too, without a clear.
        val back = stops.filter { it.enter >= 4200 }
        assertTrue(back.all { it.clear == null })
        val blood = stops.last()
        assertEquals("Blood", blood.room.name)
        assertEquals(1, stops.count { it.room.type == RoomType.ENTRANCE })
    }

    /**
     * The manual post: what the record says about itself once posted, and what leaves the machine. The
     * link is the one field a player types, so what is accepted is stated rather than left to a regex.
     */
    @Test
    fun `a posted run keeps its link, and only a youtube link is a link`() {
        val sample = SoloRuns.sample()
        val posted = sample.copy(postedTs = 5L, video = "https://youtu.be/abc")
        assertEquals(posted, SoloRuns.decode(SoloRuns.encode(posted)))
        assertNull(SoloRuns.decode(SoloRuns.encode(sample))!!.postedTs)
        assertEquals(5, sample.score.crypts, "bonus 7 with a mimic is five crypts")

        assertEquals("https://youtu.be/abc", SoloPost.videoLink(" youtu.be/abc "))
        assertEquals("https://www.youtube.com/watch?v=x_1-2&t=5s", SoloPost.videoLink("http://www.youtube.com/watch?v=x_1-2&t=5s"))
        assertNull(SoloPost.videoLink(""))
        assertNull(SoloPost.videoLink("https://example.com/x"))
        assertNull(SoloPost.videoLink("https://youtu.be/a b"))

        val body = SoloPost.payload(sample, "Sighte", sample.to300!!, pb = true, video = "https://youtu.be/abc")
        assertEquals("03m 51s", body["time"].asString, "Hypixel's clock at the crossing, as the announcement says it")
        assertEquals(307, body["score_components"].asJsonObject["score"].asInt)
        assertTrue(body["mimic"].asBoolean)
        assertNull(body["prince"], "not seen, so not claimed")
        assertEquals(5, body["crypts"].asInt)
        assertEquals("https://youtu.be/abc", body["video"].asString)
        assertTrue(body["pb"].asBoolean)
        // The clear score, as a component: the receiver prints every key in there as its own field.
        assertEquals("14.32", body["score_components"].asJsonObject["clear score"].asString)
        assertEquals("14.32", SoloRundown.listRows(listOf(sample), now = sample.ts).single().pts)
        assertEquals("14.32", SoloRundown.summary(sample).first { it.first == "clear score" }.second)
    }

    /** The list's chips and sorts: the dashes never lead, and time puts the runs that got there first. */
    @Test
    fun `the list filters by chip and sorts with the absent ones last`() {
        val base = SoloRuns.sample()
        val m7 = base                                                            // 300 at 4620, 14.32 pts
        val f7 = base.copy(ts = base.ts + 1, floor = "F7", clearScore = 20.0,
            score = base.score.copy(marks = base.score.marks.filter { it.threshold == 270 }))  // only 270
        val none = base.copy(ts = base.ts + 2, clearScore = null, score = base.score.copy(marks = emptyList(), projectedHigh = 250))
        val rows = SoloRundown.listRows(listOf(none, f7, m7), now = base.ts)

        assertEquals(mapOf(SoloRundown.Filter.ALL to 3, SoloRundown.Filter.F7 to 1, SoloRundown.Filter.M7 to 2, SoloRundown.Filter.REACHED to 1), SoloRundown.counts(rows))
        assertEquals(listOf(f7.ts), rows.filter(SoloRundown.Filter.F7::matches).map { it.ts })

        fun order(by: SoloRundown.Sort, desc: Boolean) = SoloRundown.sort(rows, by, desc).map { it.ts }
        assertEquals(listOf(none.ts, f7.ts, m7.ts), order(SoloRundown.Sort.DATE, desc = true), "newest first")
        assertEquals(listOf(m7.ts, f7.ts, none.ts), order(SoloRundown.Sort.TIME, desc = false), "300 before 270 before nothing")
        assertEquals(listOf(f7.ts, m7.ts, none.ts), order(SoloRundown.Sort.TIME, desc = true), "reversed, the dash still last")
        assertEquals(listOf(f7.ts, m7.ts, none.ts), order(SoloRundown.Sort.POINTS, desc = true))
        assertEquals(listOf(m7.ts, f7.ts, none.ts), order(SoloRundown.Sort.POINTS, desc = false), "a missing number sorts last both ways")
        assertEquals(listOf(m7.ts, f7.ts, none.ts), order(SoloRundown.Sort.SCORE, desc = true))
    }

    /** The picture that goes to Discord: a real PNG of the floor's size, with the names wrapped into their cells. */
    @Test
    fun `the map renders to a png and names wrap inside a cell`() {
        val png = SoloMapImage.render(SoloRuns.sample())
        assertTrue(png.size > 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte() && png[3] == 'G'.code.toByte())
        val image = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(6 * SoloMapImage.CELL + 2 * SoloMapImage.PAD, image.width)
        assertEquals(6 * SoloMapImage.CELL + 2 * SoloMapImage.PAD, image.height)

        val measure: (String) -> Int = { it.length * 7 }
        assertEquals(listOf("Shadow", "Assassin"), SoloMapImage.wrap("Shadow Assassin", 60, measure))
        assertEquals(listOf("Water Board"), SoloMapImage.wrap("Water Board", 80, measure))
        assertEquals(listOf("Lots Of", "Floors"), SoloMapImage.wrap("Lots Of Floors", 60, measure))
        assertEquals(3, SoloMapImage.wrap("one two three four five six", 40, measure).size)
        assertTrue(SoloMapImage.wrap("one two three four five six", 40, measure).last().endsWith("…"))
        assertEquals(Cell(4, 3), SoloMapImage.anchorOf(setOf(Cell(3, 3), Cell(4, 3), Cell(4, 2))), "the L is named in its corner")
    }

    /**
     * The backfill for runs filed before the clear score existed: `award`'s rule for a party of one,
     * over the rooms in the file. Flat weights, so the arithmetic is the thing under test.
     */
    @Test
    fun `an older run gets its clear score from its rooms, and only when something cleared`() {
        val sample = SoloRuns.sample()
        // Ten rooms cleared during the run with the player seen in them; the entrance never clears.
        val scored = SoloRuns.clearScoreOf(sample) { 1.0 }
        assertNotNull(scored)
        assertEquals(10.0, scored!!.first, 1e-9)
        // 6 + 5 + 4 + 3 + 4 secrets, a quarter each, on top.
        assertEquals(10.0 + 22 * 0.25, scored.second, 1e-9)

        // A room nobody was seen in earns nothing, like an unattributed room live.
        val ghost = sample.copy(layout = sample.layout.copy(rooms = sample.layout.rooms.map {
            if (it.name == "Atlas") it.copy(ownTicks = null) else it
        }))
        assertEquals(9.0, SoloRuns.clearScoreOf(ghost) { 1.0 }!!.first, 1e-9)

        // An entrance walked into and left: no room cleared, no score rather than a zero.
        val aborted = sample.copy(layout = sample.layout.copy(rooms = sample.layout.rooms.map { it.copy(clearTick = null) }))
        assertNull(SoloRuns.clearScoreOf(aborted) { 1.0 })

        val old = sample.copy(clearScore = null, standing = null)
        val written = ArrayList<SoloRuns.Record>()
        val list = mutableListOf(old, sample, aborted.copy(clearScore = null, standing = null))
        assertEquals(1, SoloRuns.backfill(list, { 2.0 }, { written.add(it) }), "one lacked the number and had something to score")
        assertEquals(20.0, list[0].clearScore)
        assertEquals(14.32, list[1].clearScore, "a run that recorded its own figure keeps it")
        assertNull(list[2].clearScore)
        assertEquals(listOf(20.0), written.map { it.clearScore })
    }

    @Test
    fun `the list row names the time to 300 and the best`() {
        val rows = SoloRundown.listRows(listOf(SoloRuns.sample()), now = SoloRuns.sample().ts)
        val row = rows.single()
        assertEquals(300, row.reached)
        // Hypixel's clock at the crossing, not our 4620 ticks: the announcement quotes the same clock.
        assertEquals("3:51", row.time)
        val unclocked = SoloRuns.sample().let { r -> r.copy(score = r.score.copy(marks = r.score.marks.map { it.copy(clock = null) })) }
        assertEquals(DungeonGrid.formatTicks(4620), SoloRundown.listRows(listOf(unclocked), now = unclocked.ts).single().time)
        assertTrue(row.pb)
        assertTrue(row.label.startsWith("M7 · "))
    }
}
