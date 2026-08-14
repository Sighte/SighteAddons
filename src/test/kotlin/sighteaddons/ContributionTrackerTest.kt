package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The clear anchor, which is [TrackedRoom.enteredAtTick] and the whole of schema 5.
 *
 * The defect being fixed came out of the receiver's own numbers, not out of reasoning: `enterTick`
 * used to be stamped the first time *any* party member was seen in a room, with no minimum stay, so
 * a split party where one member crosses a room early and the rest clear it much later reported the
 * gap between those two events as the room's clear time — 145 s for a 1x1 in the receiver's
 * `roomstats.py` docstring. The number was never a duration, only an upper bound on one.
 *
 * [ContributionTracker.tick] itself needs a `Minecraft` and a `MapItemSavedData` and so cannot be
 * driven from here. The anchor deliberately does not: it lives on [TrackedRoom] as two pure
 * functions over `(player, tick)`, which is the same seam `onSecret` and `expireSecretRun` are
 * tested through. What is *not* covered here is the wiring — that `tick` calls them with the run
 * clock, once per member per tick — and no command in this repository can observe that.
 */
class ContributionTrackerTest {
    private fun room() = TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0)))

    /**
     * [player] seen on every tick of `[from, from + count)`, the way [ContributionTracker.tick] sees
     * them: the run-long tick total and the current stay are updated together, because a helper that
     * fed only one of the two would not be modelling a real tick.
     */
    private fun TrackedRoom.stay(player: String, from: Int, count: Int) {
        repeat(count) {
            ticks.merge(player, 1, Int::plus)
            onPresence(player, from + it)
        }
    }

    private val min = ContributionTracker.MIN_TICKS

    // --- the defect ---

    @Test
    fun `a walk-through does not anchor the room`() {
        val room = room()
        // Somebody crosses on the way elsewhere: half a second, then gone.
        room.stay("Passerby", from = 100, count = min / 2)
        assertNull(room.enteredAtTick, "half a second of presence is not the room's clock starting")

        // The party that actually fights it arrives much later.
        room.stay("Fighter", from = 3000, count = min)
        assertEquals(3000, room.enteredAtTick)
    }

    /**
     * The case the old anchor got wrong and the receiver could not detect: the room clears long after
     * a walk-through and nobody ever stays. Under schema 4 this reported `3000 - 100` — 145 s — as
     * the clear. A null anchor is the right answer; the receiver drops the visit rather than
     * averaging it.
     */
    @Test
    fun `a clear long after a walk-through reports no anchor at all`() {
        val room = room()
        room.stay("Passerby", from = 100, count = min / 2)

        assertFalse(room.anchorOnClear(3000), "nothing in the window, so nothing to anchor on")
        assertNull(room.enteredAtTick)
    }

    // --- what the anchor is ---

    /**
     * The threshold is how we find out a stay was real, not when the room's clock started. Anchoring
     * at the tick it qualified would shorten every clear in the data by a flat second — a bias, and
     * one nothing downstream could see.
     */
    @Test
    fun `the anchor is the start of the qualifying stay, not the tick it qualified`() {
        val room = room()
        room.stay("Fighter", from = 500, count = min)
        assertEquals(500, room.enteredAtTick)
    }

    /** Reported exactly on the tick the stay reaches the threshold — not before, not a tick late. */
    @Test
    fun `the room anchors on the tick the stay becomes long enough`() {
        val room = room()
        repeat(min - 1) { assertFalse(room.onPresence("Fighter", 500 + it)) }
        assertNull(room.enteredAtTick)

        assertTrue(room.onPresence("Fighter", 500 + min - 1), "the threshold tick is the one that anchors")
        assertEquals(500, room.enteredAtTick)
        // And exactly once, so the caller's log line cannot fire twice for one room.
        assertFalse(room.onPresence("Fighter", 500 + min))
    }

    @Test
    fun `the first qualifying stay wins, not the longest and not the last`() {
        val room = room()
        room.stay("First", from = 800, count = min)
        room.stay("Second", from = 700, count = min * 4)
        // Second was in here earlier and four times as long, but First's stay qualified first and the
        // anchor is not a competition — it is the moment the room started being worked on.
        assertEquals(800, room.enteredAtTick)
    }

    // --- stays, not totals ---

    /**
     * The distinction that makes this different from the tick totals attribution already keeps: those
     * are cumulative for the whole run, so a member who walks through twice would reach the threshold
     * on presence they never spent in one go.
     */
    @Test
    fun `two walk-throughs by one member never add up to a stay`() {
        val room = room()
        room.stay("Wanderer", from = 100, count = min / 2)
        room.stay("Wanderer", from = 2000, count = min / 2)
        assertNull(room.enteredAtTick, "ten ticks now and ten ticks later is not a second in the room")
        assertEquals(min, room.ticks["Wanderer"], "the tick total still counts both, as attribution needs")
    }

    @Test
    fun `a member who passes through and comes back is anchored on the return`() {
        val room = room()
        room.stay("Wanderer", from = 100, count = min / 2)
        room.stay("Wanderer", from = 3000, count = min)
        assertEquals(3000, room.enteredAtTick, "the walk-through is not where this room's clear began")
    }

    /**
     * Our own blind spot must not split a stay. [PartyTracker.positions] reports no teammate
     * positions at all for the 10-20 ticks around a death, when the marker count and the tab roster
     * disagree — deliberately, since a wrong position is worse than none. A stay that survives that
     * gap is one stay.
     */
    @Test
    fun `a gap shorter than the threshold does not split a stay`() {
        val room = room()
        room.stay("Fighter", from = 400, count = 5)
        // Last seen at 404, next seen at 425: twenty ticks with no sighting at all, which is the
        // widest roster-skew window PartyTracker documents.
        room.stay("Fighter", from = 425, count = min - 5)
        assertEquals(400, room.enteredAtTick, "the stay began at 400 and we merely stopped seeing it")
    }

    @Test
    fun `a gap longer than the threshold starts a new stay`() {
        val room = room()
        room.stay("Wanderer", from = 400, count = 5)
        // Twenty-one missed ticks: one more than the tolerance, so this is leaving and coming back.
        room.stay("Wanderer", from = 426, count = min)
        assertEquals(426, room.enteredAtTick)
    }

    // --- the fallback, and its ceiling ---

    /**
     * Empty 1x1s clear the moment somebody steps in — three of them in one M7, by the count in
     * `ContributionTracker.award`. Dropping every room that clears inside a second would not make the
     * server's average sparse, it would make it high: the fastest rooms, and only those, would be
     * missing from it.
     */
    @Test
    fun `a room cleared before anyone qualifies is still anchored`() {
        val room = room()
        room.stay("Fighter", from = 900, count = 4)
        assertNull(room.enteredAtTick)

        assertTrue(room.anchorOnClear(904))
        assertEquals(900, room.enteredAtTick, "four ticks in the room, and that is the whole clear")
    }

    /**
     * The property that makes the fallback safe to have at all: it reaches back one second and no
     * further, so however flaky the decoration stream was, it cannot resurrect an old walk-through as
     * a long clear. Every span it can produce is under a second by construction.
     */
    @Test
    fun `the fallback can never manufacture a clear longer than the threshold`() {
        for (age in 0..min * 3) {
            val room = room()
            room.stay("Fighter", from = 5000 - age, count = 1)
            val anchored = room.anchorOnClear(5000)
            val enter = room.enteredAtTick
            if (anchored) {
                assertTrue(5000 - enter!! <= min, "fallback produced a $age-tick span at age $age")
            } else {
                assertNull(enter)
                assertTrue(age > min, "a stay $age ticks old is inside the window and should have anchored")
            }
        }
    }

    @Test
    fun `the fallback takes the earliest stay still inside the window`() {
        val room = room()
        room.stay("Late", from = 995, count = 1)
        room.stay("Early", from = 990, count = 1)
        room.anchorOnClear(1000)
        assertEquals(990, room.enteredAtTick, "the room's clock started with the first of the two")
    }

    @Test
    fun `the fallback never overrides an anchor a real stay already earned`() {
        val room = room()
        room.stay("Fighter", from = 600, count = min)
        assertEquals(600, room.enteredAtTick)

        assertFalse(room.anchorOnClear(4000))
        assertEquals(600, room.enteredAtTick)
    }

    // --- the ordering invariant ---

    /**
     * `enterTick <= clearTick` whenever both are set. A stay that only became long enough after the
     * checkmark cannot have contributed to it, and on the server the pair would run backwards — the
     * receiver drops those, so the cost is a lost sample rather than a wrong one, but a room is
     * anchored by the work that cleared it or not at all.
     */
    @Test
    fun `nothing anchors a room after it was cleared`() {
        val room = room()
        room.clearedAtTick = 1000

        room.stay("Latecomer", from = 1100, count = min * 5)
        assertNull(room.enteredAtTick)
        assertEquals(min * 5, room.ticks["Latecomer"], "presence still counts; it just anchors nothing")
    }

    /**
     * A room that already carried a checkmark when we arrived is cleared from birth, so it keeps a
     * null anchor for its whole life. That is the honest answer — there is no clear of ours to time —
     * and the receiver skips pre-cleared rooms in `roomstats.py` regardless.
     */
    @Test
    fun `a pre-cleared room never reports a clear span`() {
        val room = room()
        room.preCleared = true
        room.clearedAtTick = 50

        room.stay("Fighter", from = 60, count = min * 2)
        assertNull(room.enteredAtTick)
    }

    // --- the anchor is per room ---

    @Test
    fun `stays are not shared between rooms`() {
        val first = room()
        val second = room()
        first.stay("Fighter", from = 200, count = min)
        second.stay("Fighter", from = 900, count = min)
        assertEquals(200, first.enteredAtTick)
        assertEquals(900, second.enteredAtTick)
    }
}
