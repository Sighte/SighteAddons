package sighteaddons.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.RoomHistory
import sighteaddons.ui.screens.StatsOverview

/**
 * The stats overview, which is the half of that page nobody can check by looking at it.
 *
 * A wrong aggregate is invisible: 0:41.0 over three runs and 0:41.0 over three hundred are the same
 * six characters, and a median that quietly included the blood room would look exactly like one that
 * did not. Both are the failures this class exists for — the sample floor, and the rule that the three
 * history kinds are never mixed into one figure.
 */
class StatsOverviewTest {

    private val now = 1_700_000_000_000L
    private val week = StatsOverview.RECENT_DAYS * 24L * 60L * 60L * 1000L

    private fun attempt(ticks: Int, ts: Long = 0L, floor: String = "M7", pb: Boolean = false) =
        RoomHistory.Attempt(ticks, ts, floor, pb)

    /** Records folded out of the attempts, exactly as `RoomHistory.fold` would have folded them. */
    private fun overview(
        attempts: Map<String, List<RoomHistory.Attempt>>,
        knownRooms: Int = 200,
    ): StatsOverview.Overview {
        val records = attempts.mapValues { (_, list) ->
            RoomHistory.Record(list.minOf { it.ticks }, list.size, list.maxOf { it.ts })
        }
        return StatsOverview.of(
            records,
            { room, kind -> attempts["$room|$kind"] ?: emptyList() },
            knownRooms,
            now,
        )
    }

    private fun StatsOverview.Overview.line(section: String, label: String): StatsOverview.Line =
        sections.first { it.title == section }.lines.first { it.label == label }

    private fun StatsOverview.Overview.section(title: String): StatsOverview.Section =
        sections.first { it.title == title }

    /**
     * The whole point of [StatsOverview.MIN_SAMPLE], in the three shapes it produces.
     *
     * The median here is the *upper* middle element, so on four attempts it is the third fastest of
     * four — which is why the floor exists at all, and why the assertion on five checks the value and
     * not only the word.
     */
    @Test
    fun `a figure below the sample floor names the fastest rather than a median`() {
        val one = StatsOverview.middle("clear", listOf(attempt(100)))
        assertEquals(Format.ticks(100), one.value)
        assertEquals("the only one", one.meta)
        assertTrue(one.thin)

        val few = StatsOverview.middle("clear", listOf(400, 100, 300, 200).map { attempt(it) })
        assertEquals(Format.ticks(100), few.value, "the fastest, not the third of four")
        assertEquals("fastest of 4", few.meta)
        assertTrue(few.thin)

        val many = StatsOverview.middle("clear", listOf(500, 100, 400, 200, 300).map { attempt(it) })
        assertEquals(Format.ticks(300), many.value, "the upper middle of five")
        assertEquals("median of 5", many.meta)
        assertFalse(many.thin, "five is enough to stand behind")

        assertEquals(Format.MISSING, StatsOverview.middle("clear", emptyList()).value)
        assertEquals("nothing recorded", StatsOverview.middle("clear", emptyList()).meta)
    }

    /**
     * The rule the history's own contract turns on: a `clear`, a `secretrun` and a `bloodclear` are
     * three measurements and never one.
     *
     * A blood room is a two-minute boss fight and an ordinary room is forty seconds, so a median that
     * folded them together would be a different number wearing the same word — and it would look
     * entirely plausible.
     */
    @Test
    fun `the three kinds are counted and timed apart`() {
        val page = overview(
            mapOf(
                "Water Board|${RoomHistory.CLEAR}" to List(6) { attempt(800) },
                "Blood|${RoomHistory.BLOOD}" to List(6) { attempt(2600) },
            ),
        )

        assertEquals("6", page.line("history", "clears").value)
        assertEquals("6", page.line("history", "blood rooms").value)
        assertEquals("0", page.line("history", "secret runs").value)
        assertEquals("none yet", page.line("history", "secret runs").meta)

        assertEquals(Format.ticks(800), page.line("times", "clear").value)
        assertEquals(Format.ticks(2600), page.line("times", "blood room").value)
        assertTrue(
            page.section("times").lines.none { it.label == "secret run" },
            "a kind with no attempts gets no time line rather than a zero",
        )
    }

    /** Nothing recorded is not a page of zeroes — it is a page with nothing on it. */
    @Test
    fun `an empty history has no sections at all`() {
        assertTrue(StatsOverview.of(emptyMap(), { _, _ -> emptyList() }, 200, now).sections.isEmpty())
    }

    /**
     * Coverage is the one figure with a denominator, and the only one that gets a bar.
     *
     * Without a database there is no denominator, and a fraction of zero would draw an empty bar — a
     * claim that nothing has been recorded, which is the opposite of what a missing `rooms.json` means.
     */
    @Test
    fun `coverage is a ratio of rooms, and is no ratio at all without a database`() {
        val page = overview(
            mapOf(
                "A|${RoomHistory.CLEAR}" to listOf(attempt(100)),
                "B|${RoomHistory.SECRETS}" to listOf(attempt(100)),
            ),
            knownRooms = 8,
        )
        assertEquals("2 of 8", page.line("history", "rooms recorded").value)
        assertEquals(0.25f, page.line("history", "rooms recorded").fraction, 1e-6f)

        val blind = overview(mapOf("A|${RoomHistory.CLEAR}" to listOf(attempt(100))), knownRooms = 0)
        assertEquals("1", blind.line("history", "rooms recorded").value)
        assertTrue(blind.line("history", "rooms recorded").fraction < 0f, "no denominator, no bar")
    }

    /**
     * The recency window is half-open at its far edge, so a record set exactly a week ago is inside it
     * and one set a second earlier is not.
     */
    @Test
    fun `personal bests are counted in total and again for the last week`() {
        val page = overview(
            mapOf(
                "A|${RoomHistory.CLEAR}" to listOf(
                    attempt(100, ts = now - week, pb = true),
                    attempt(90, ts = now - week - 1, pb = true),
                    attempt(120, ts = now, pb = false),
                ),
            ),
        )
        assertEquals("2", page.line("records", "personal bests").value)
        assertEquals("1 in the last week", page.line("records", "personal bests").meta)
        assertEquals("today", page.line("records", "last recorded").value)
    }

    /**
     * A line of a kind nothing reads any more — the old `secrets`, retired when it became `secretrun` —
     * is still in the file and is still counted in the total. Left unnamed, a reader counting the
     * sections would come out short and have no way to find out why.
     */
    @Test
    fun `lines of a retired kind are named rather than folded away`() {
        val records = mapOf(
            "A|${RoomHistory.CLEAR}" to RoomHistory.Record(100, 2, 5L),
            "A|secrets" to RoomHistory.Record(500, 3, 5L),
        )
        val page = StatsOverview.of(
            records,
            { room, kind -> if (room == "A" && kind == RoomHistory.CLEAR) listOf(attempt(100), attempt(120)) else emptyList() },
            10,
            now,
        )
        assertEquals("5 lines", page.section("history").meta)
        assertEquals("3", page.line("history", "retired lines").value)
        assertEquals("2", page.line("history", "clears").value)
    }

    /** "?" is a line written before the floor was known, and it is a fact rather than a floor. */
    @Test
    fun `floors are ordered by how much is in them, with the unknown one named`() {
        val page = overview(
            mapOf(
                "A|${RoomHistory.CLEAR}" to listOf(
                    attempt(100, floor = "F7"),
                    attempt(100, floor = "M7"),
                    attempt(100, floor = "M7"),
                    attempt(100, floor = "?"),
                ),
            ),
        )
        val floors = page.section("floors").lines
        assertEquals(listOf("M7", "?", "F7"), floors.map { if (it.label == "unknown") "?" else it.label })
        assertEquals("2", floors.first().value)
        assertEquals("3 seen", page.section("floors").meta)
        assertEquals("floor not yet known", floors.first { it.label == "unknown" }.meta)
    }
}
