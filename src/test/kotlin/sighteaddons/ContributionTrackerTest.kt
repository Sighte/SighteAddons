package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
    /** [ContributionTracker] is an object with run-long state, and half of these tests write to it. */
    @BeforeEach
    fun clean() = ContributionTracker.reset()

    private fun room() = TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0)))

    /**
     * A room of [segments] cells, optionally named — which is what gives it a [RoomInfo] and so the
     * secret count and database kind the weighting reads.
     */
    private fun roomOf(
        type: RoomType = RoomType.ROOM,
        segments: Int = 1,
        info: RoomInfo? = null,
    ): TrackedRoom {
        val cells = (0 until segments).map { Pos(it, 0) }.toSet()
        return TrackedRoom(type, cells, cells).also { it.info = info; it.name = info?.name }
    }

    private fun info(type: String, secrets: Int = 0) =
        RoomInfo(name = "Fixture", type = type, shape = "1x1", secrets = secrets, crypts = 0)

    /** The cheapest room there is: a named, empty, single-segment 1x1. The baseline everything else
     *  is compared against, and the room the flat count used to make indistinguishable. */
    private fun plain() = roomOf(info = info("NORMAL"))

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

    // --- ClearPoints: what a room is worth (clearpoints-001) ---

    /**
     * The feature in one line: Hypixel's own score counts rooms, so clearing a puzzle and walking
     * into an empty 1x1 are worth the same to it. That is the flat weighting ClearPoints exists to
     * replace, and until now this mod reproduced it exactly.
     */
    @Test
    fun `a puzzle is worth more than an empty 1x1`() {
        assertTrue(
            ContributionTracker.weightOf(roomOf(info = info("PUZZLE"))) >
                ContributionTracker.weightOf(plain()),
        )
    }

    @Test
    fun `a secret-heavy room is worth more than an empty one`() {
        val heavy = ContributionTracker.weightOf(roomOf(info = info("NORMAL", secrets = 8)))
        val one = ContributionTracker.weightOf(roomOf(info = info("NORMAL", secrets = 1)))
        assertTrue(heavy > one, "eight secrets is more room than one")
        assertTrue(one > ContributionTracker.weightOf(plain()), "and one secret is more than none")
    }

    @Test
    fun `a four-segment room is worth more than a 1x1 of the same kind`() {
        assertTrue(
            ContributionTracker.weightOf(roomOf(segments = 4, info = info("NORMAL"))) >
                ContributionTracker.weightOf(plain()),
        )
    }

    /**
     * Rarity is not work. A rare room is unusual to *get*, not harder to clear, and whatever makes
     * it worth entering is its secret count — which is already paid for. Pinned because "RARE looks
     * special, give it a bonus" is the obvious next edit and it would be measuring the wrong thing.
     */
    @Test
    fun `a rare room is not paid for being rare`() {
        assertEquals(
            ContributionTracker.weightOf(roomOf(info = info("NORMAL", secrets = 4))),
            ContributionTracker.weightOf(roomOf(info = info("RARE", secrets = 4))),
        )
    }

    /**
     * Secrets are paid for out of the room database and never out of [TrackedRoom.secretsFound].
     * That counter is the party's live progress through the room, and it is not what is being
     * weighted — the room is. Pinned in both directions because both edits are plausible and neither
     * is caught by the fixtures on its own: *adding* the live counter to the weight, and
     * *substituting* it for the database's count.
     */
    @Test
    fun `the live secret counter is not what a room is worth`() {
        val collected = roomOf(info = info("NORMAL", secrets = 0))
        collected.secretsFound = 8
        assertEquals(
            ContributionTracker.weightOf(plain()),
            ContributionTracker.weightOf(collected),
            "eight secrets found in a room the database says holds none may not add anything",
        )

        val stocked = roomOf(info = info("NORMAL", secrets = 8))
        assertTrue(
            ContributionTracker.weightOf(stocked) > ContributionTracker.weightOf(collected),
            "and the database's count has to be the one that pays, with nothing found yet",
        )
    }

    /**
     * Why that exclusion is load-bearing rather than a preference. [ContributionTracker.onCleared]
     * fires when the checkmark appears, and the party is usually still collecting the room's secrets
     * at that moment — two of five, here. So the live counter at award time is a race against when
     * the last mob happened to drop, and reading it would make the same room worth a different
     * amount on every run. The credit that lands is the whole room, as the database describes it.
     */
    @Test
    fun `the credit is the whole room even though the checkmark lands mid-collection`() {
        val room = roomOf(info = info("NORMAL", secrets = 5))
        room.secretsFound = 2
        room.ticks["Fighter"] = 400
        ContributionTracker.onCleared(room)

        val whole = ContributionTracker.weightOf(roomOf(info = info("NORMAL", secrets = 5)))
        assertEquals(
            whole,
            ContributionTracker.pointsByPlayer()["Fighter"] ?: 0.0,
            1e-9,
            "the room was credited at what it was part-way through, not at what it is",
        )
    }

    /**
     * A room is worth the same however far the run has got. That is the shape of the floor exclusion
     * argued in [ContributionTracker.weightOf]'s KDoc: a factor that is constant across a run scales
     * every player's total by the same number and therefore separates nobody, and the weighting is a
     * property of the room rather than of the run it appeared in.
     *
     * **What this does not pin, stated so nobody reads more into it than is here.** It covers the
     * run state this test moves directly — rooms already cleared and credited, and the room's own
     * progress through its clear. It does *not* cover `DungeonSession.floorNumber`: measured, a floor
     * multiplier passes this case untouched. The floor is guarded by `a room is worth the same on
     * every floor` instead, and the two are deliberately separate because they fail on different
     * edits.
     */
    @Test
    fun `a room is worth the same however far the run has got`() {
        val room = roomOf(segments = 2, info = info("PUZZLE", secrets = 3))
        val fresh = ContributionTracker.weightOf(room)

        repeat(4) {
            val other = roomOf(info = info("NORMAL", secrets = 2))
            other.ticks["Fighter"] = 300
            ContributionTracker.onCleared(other)
        }
        room.ticks["Fighter"] = 200
        room.secretsFound = 3
        room.deaths = 2
        room.clearedAtTick = 900

        assertEquals(fresh, ContributionTracker.weightOf(room), "the run moved; the room did not")
    }

    /**
     * Writes [DungeonSession.floor] the only way a test can: it is `private set` and written only by
     * `inDungeon(Minecraft)`, which needs a client this repository cannot build. Reflection reaches
     * the backing field of the `object`, and `floorNumber` then reads back the real digit.
     *
     * The only reflection in this suite, and it is confined to this one exclusion. It is here rather
     * than as a production seam because a seam on [DungeonSession] would be a shape the mod does not
     * otherwise need, added for a test — a bigger change to `src/main` than the exclusion is worth.
     * The cost is that a rename of `floor` breaks this test at runtime rather than at compile time;
     * the assertion on `floorNumber` below is what converts that into a loud failure instead of a
     * silent pass, and it is the reason this is a guard and not decoration.
     */
    private fun setFloor(value: String?) {
        val field = DungeonSession::class.java.getDeclaredField("floor")
        field.isAccessible = true
        field.set(DungeonSession, value)
    }

    /**
     * **The floor is not a multiplier**, which is the third of the three exclusions [
     * ContributionTracker.weightOf]'s KDoc argues for and the one that used to rest on the argument
     * alone. A floor factor is constant across every room of a run, so it scales every player's
     * total by the same number and separates nobody; points are only ever compared within one run.
     *
     * Three floors, because there are two different edits to catch and they read different things.
     * `F1` against `F7` catches a factor drawn from [DungeonSession.floorNumber] — the obvious one,
     * since `floorNumber` is right there. `F7` against `M7` catches one drawn from the floor
     * *string*, which is how a master-mode bonus would have to be written, and `floorNumber` cannot
     * tell those two apart. `Entrance` is the case that reads back null, which is what every other
     * test in this file runs under: it is asserted last so this case says out loud that the null
     * reading is one of the values checked rather than the only one.
     *
     * **This asserts that the setup worked before it asserts the invariant.** Without the
     * `floorNumber` checks the test would pass whether or not the field was ever written, which is
     * exactly the guard-in-name-only shape an earlier session believed was the only shape available
     * here. It is not: with them, adding `val floorFactor = 1.0 + (DungeonSession.floorNumber ?: 0)
     * * 0.1` to [ContributionTracker.weightOf] fails this case, and — measured — fails nothing else
     * in the suite.
     *
     * Both the weight and the credit are checked, because a floor factor could be applied in
     * [ContributionTracker.weightOf] or between it and the split in `award`. [ContributionTracker]
     * is reset between floors so the credited totals are comparable; [DungeonSession] is *not*
     * reset, because `DungeonSession.reset()` resets half the mod. The floor is put back to null in
     * a `finally` instead — the suite runs sequentially in one JVM and a left-behind floor would
     * leak into every test after this one.
     */
    @Test
    fun `a room is worth the same on every floor`() {
        fun creditOn(floor: String, expectedNumber: Int?): Double {
            setFloor(floor)
            assertEquals(
                expectedNumber,
                DungeonSession.floorNumber,
                "the floor was not actually set — this test would pass either way",
            )
            ContributionTracker.reset()
            val room = roomOf(segments = 2, info = info("PUZZLE", secrets = 3))
            val weight = ContributionTracker.weightOf(room)
            room.ticks["Fighter"] = 400
            ContributionTracker.onCleared(room)
            assertEquals(
                weight,
                ContributionTracker.pointsByPlayer()["Fighter"] ?: 0.0,
                1e-9,
                "the whole room went to its only occupant, on $floor as anywhere",
            )
            return weight
        }

        try {
            val onF1 = creditOn("F1", 1)
            assertEquals(onF1, creditOn("F7", 7), 1e-9, "a room got heavier on a deeper floor")
            assertEquals(onF1, creditOn("M7", 7), 1e-9, "a room got heavier in master mode")
            assertEquals(onF1, creditOn("Entrance", null), 1e-9, "no floor is not a discount either")
        } finally {
            setFloor(null)
        }
    }

    /**
     * No room becomes worthless, so a run's points can never come out below the flat count they
     * replace. That includes the rooms nothing is known about: an unidentified room is still a room
     * somebody cleared, and paying it nothing would quietly hand its clear to no one.
     */
    @Test
    fun `every room is still worth at least the point it used to be`() {
        for (type in RoomType.entries) {
            assertTrue(
                ContributionTracker.weightOf(roomOf(type = type)) >= 1.0,
                "an unnamed $type room fell below one point",
            )
        }
        for (type in listOf("NORMAL", "PUZZLE", "TRAP", "RARE", "CHAMPION", "BLOOD", "ENTRANCE", "FAIRY")) {
            assertTrue(
                ContributionTracker.weightOf(roomOf(info = info(type))) >= 1.0,
                "a $type room out of the database fell below one point",
            )
        }
    }

    /**
     * `rooms.json` says `CHAMPION`, the map colour says `MINIBOSS`, and they are the same room. A
     * room must not be worth different amounts depending on whether its chunk had streamed in yet,
     * which is a race and not a property of the room.
     */
    @Test
    fun `the database and the map agree on what a miniboss is`() {
        assertEquals(
            ContributionTracker.weightOf(roomOf(info = info("CHAMPION"))),
            ContributionTracker.weightOf(roomOf(type = RoomType.MINIBOSS)),
        )
    }

    /**
     * An unnamed room falls back to the map colour, so it keeps its kind but loses its secrets.
     * Less than it should be, never nothing — and worth saying out loud, because it means the same
     * room can be worth two different amounts across two runs depending on chunk streaming.
     */
    @Test
    fun `an unnamed room keeps its kind and loses only its secrets`() {
        val named = ContributionTracker.weightOf(roomOf(type = RoomType.PUZZLE, info = info("PUZZLE", secrets = 2)))
        val unnamed = ContributionTracker.weightOf(roomOf(type = RoomType.PUZZLE))
        assertTrue(unnamed < named, "the secret bonus needs the database")
        assertTrue(unnamed > ContributionTracker.weightOf(roomOf(type = RoomType.ROOM)), "the kind does not")
    }

    // --- ClearPoints: the unit the score is not measured in ---

    /**
     * **The hazard this feature had to answer.** `unattributed` used to be
     * `roomsCleared - pointsByPlayer().values.sum()`, which was only ever right because a room was
     * worth exactly 1.0: a count and a score were numerically interchangeable. Weighted, the score
     * runs above the count, the subtraction goes negative, and the clamp in
     * [ContributionTracker.settle] reports `0.0` on every run forever — no exception, no `400`, no
     * log line. A field that stops saying anything and looks perfect while doing it.
     *
     * So the run below has a genuinely unattributed room *and* a points total larger than its room
     * count, and both assertions matter: the second is the condition that would have silenced the
     * first.
     */
    @Test
    fun `weighting cannot silence the unattributed count`() {
        val puzzle = roomOf(info = info("PUZZLE", secrets = 3))
        puzzle.ticks["Fighter"] = 400
        ContributionTracker.onCleared(puzzle)

        val big = roomOf(segments = 4, info = info("NORMAL", secrets = 6))
        big.ticks["Fighter"] = 400
        ContributionTracker.onCleared(big)

        // Cleared with nobody ever seen in it: one room the party got no credit for.
        ContributionTracker.onCleared(plain())

        assertEquals(3, ContributionTracker.roomsCleared)
        assertEquals(1.0, ContributionTracker.unattributed())

        val scored = ContributionTracker.pointsByPlayer().values.sum()
        assertTrue(
            scored > ContributionTracker.roomsCleared,
            "the weighting is too weak to reproduce the hazard: this run has to outscore its rooms",
        )
        assertTrue(
            ContributionTracker.settle(ContributionTracker.roomsCleared - scored) == 0.0,
            "and the old expression has to be the thing that goes quiet, or this test proves nothing",
        )
    }

    /**
     * `unattributed` counts rooms, not points, and that is what makes it readable against
     * `roomsCleared` — which is exactly how the receiver reads it (`agent/AGENT-PROMPT.md`: the gap
     * between the two is its only diagnostic for a broken decoration→player mapping). A ratio of a
     * score to a count would be a number with no meaning at either end.
     */
    @Test
    fun `an expensive room nobody was in is one unattributed room, not its weight in points`() {
        val expensive = roomOf(segments = 4, info = info("PUZZLE", secrets = 10))
        assertTrue(ContributionTracker.weightOf(expensive) >= 4.0, "fixture is meant to be a heavy room")

        ContributionTracker.onCleared(expensive)
        assertEquals(1.0, ContributionTracker.unattributed())
        assertEquals(1, ContributionTracker.roomsCleared)
    }

    /**
     * The behaviour `residue-001` bought, kept: a run in which every cleared room was attributed
     * reports a clean zero. It is now structural — nothing is subtracted, so no residue can arise —
     * rather than rounded back to zero after the fact.
     */
    @Test
    fun `a run where every room was attributed reports nothing unattributed`() {
        repeat(3) {
            val cleared = roomOf(info = info("NORMAL", secrets = 2))
            cleared.ticks["A"] = 300
            cleared.ticks["B"] = 300
            cleared.ticks["C"] = 300
            ContributionTracker.onCleared(cleared)
        }
        assertEquals(0.0, ContributionTracker.unattributed())
        assertEquals("0.0", ContributionTracker.unattributed().toString(), "and with no exponent")
    }

    /**
     * The brief visitor is not the empty room. Somebody seen for less than [ContributionTracker
     * .MIN_TICKS] misses the ordinary split, and `award` falls back to raw presence rather than
     * dropping the room — so the room *is* attributed and must not be counted. Solo runs live on
     * this path: the empty 1x1s that clear the moment you step in, three of them in one M7.
     */
    @Test
    fun `a room somebody only passed through is still attributed`() {
        val brief = plain()
        brief.ticks["Solo"] = 4
        ContributionTracker.onCleared(brief)

        assertEquals(0.0, ContributionTracker.unattributed(), "the fallback credited somebody")
        assertEquals(1, ContributionTracker.pointsByPlayer().size)
    }

    /** Run state is per run. A count that survived `reset` would age into every later run. */
    @Test
    fun `the unattributed count does not survive the run`() {
        ContributionTracker.onCleared(plain())
        assertEquals(1.0, ContributionTracker.unattributed())

        ContributionTracker.reset()
        assertEquals(0.0, ContributionTracker.unattributed())
        assertEquals(0, ContributionTracker.roomsCleared)
    }

    /**
     * A pre-cleared room was never ours to clear: `discover` sets `pointsAwarded` on it so `award`
     * can never fire, and it never reaches [ContributionTracker.onCleared] at all. Pinned at the
     * seam anyway — if it ever did, it must not be counted as a room the party failed to be
     * credited for.
     */
    @Test
    fun `a pre-cleared room is not an unattributed one`() {
        val entrance = plain()
        entrance.preCleared = true
        entrance.pointsAwarded = true
        ContributionTracker.onCleared(entrance)

        assertEquals(0.0, ContributionTracker.unattributed())
        assertTrue(ContributionTracker.pointsByPlayer().isEmpty())
    }
}
