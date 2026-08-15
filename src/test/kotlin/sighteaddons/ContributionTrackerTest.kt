package sighteaddons

import org.junit.jupiter.api.AfterEach
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
    /**
     * [ContributionTracker] is an object with run-long state, and half of these tests write to it.
     *
     * [RoomStats] is pinned to [RoomScores.NONE] as well, and that is not tidiness. Left alone it
     * resolves from `configDir/sighteaddons/roomstats.json` on first use — a path outside this
     * repository, which `config/` is gitignored under and which a real install or an earlier
     * `runClient` may well have written. A weight that depended on whether the machine running the
     * suite happened to have a cache file would be a test that passes for the author and fails for
     * the next session, so the seed layer is chosen explicitly here and [ContributionTracker.blend]
     * is exercised directly by the cases below instead.
     */
    @BeforeEach
    fun clean() {
        ContributionTracker.reset()
        RoomStats.use(RoomScores.NONE)
    }

    /** Back to resolving normally, so nothing here leaks into a test that means to read a file. */
    @AfterEach
    fun release() = RoomStats.use(null)

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

    /** [name] matters only to the rooms the user seeded individually: `Ice Fill` and `Water Board`. */
    private fun info(type: String, secrets: Int = 0, name: String = "Fixture") =
        RoomInfo(name = name, type = type, shape = "1x1", secrets = secrets, crypts = 0)

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
     * Empty 1x1s clear the moment somebody steps in — one of the ten rooms cleared on the one real
     * M7 there is (`Duncan`, entered at tick 2990 and cleared at 2996; see
     * `docs/evidence/session-1786719912927/`). Dropping every room that clears inside a second would
     * not make the server's average sparse, it would make it high: the fastest rooms, and only
     * those, would be missing from it. Earlier revisions of this KDoc estimated three per M7 from a
     * comment in `ContributionTracker.award`; the measured rate is one in ten.
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

    /**
     * **Replaces `a four-segment room is worth more than a 1x1 of the same kind`, and it asserts the
     * opposite on purpose.** That case pinned `SEGMENT_POINTS`, one of the five constants
     * `clearpoints-002` deletes; keeping it would have pinned the behaviour the user replaced. The
     * assertion is stronger, not weaker — an exact equality where there was an inequality.
     *
     * Size stops being *declared* and becomes *emergent*. A 1x4 really is worth more than a 1x1, but
     * it has to earn that by measuring slow, because crossing four segments takes time — and if it
     * turns out a particular 1x4 clears as fast as a 1x1, then it was never worth more and the old
     * constant was paying it for its shape. Same for kind: `TRAP`, `CHAMPION` and `BLOOD` are worth
     * exactly an ordinary room until the data says otherwise. `PUZZLE` is the one exception and it is
     * a seed rather than a bonus — see `the seed table is the user's table`.
     */
    @Test
    fun `size and kind are no longer paid for directly`() {
        val plain = ContributionTracker.weightOf(plain())
        assertEquals(
            plain,
            ContributionTracker.weightOf(roomOf(segments = 4, info = info("NORMAL"))),
            1e-9,
            "a four-segment room has to earn its points by measuring slow, not by being big",
        )
        for (kind in listOf("TRAP", "CHAMPION", "BLOOD", "RARE", "ENTRANCE", "FAIRY")) {
            assertEquals(
                plain,
                ContributionTracker.weightOf(roomOf(info = info(kind))),
                1e-9,
                "a $kind room is still being paid a bonus for its kind",
            )
        }
    }

    /**
     * The seed table, verbatim from the user on 2026-08-14. These are estimates and they are meant
     * to be — what makes them shippable is that `the measurement overrules the seed in both
     * directions` moves them without a code change. Pinned because the two named rooms are the whole
     * of the hand-tuning that survives, and because their keys have to be `rooms.json`'s spelling:
     * `Ice Fill` and `Water Board`, two words each. A typo does not fail, it silently misses.
     */
    @Test
    fun `the seed table is the user's table`() {
        fun seed(name: String, type: String = "PUZZLE") =
            ContributionTracker.seedOf(roomOf(info = info(type, name = name)))

        assertEquals(2.0, seed("Ice Fill"), 1e-9)
        assertEquals(1.5, seed("Water Board"), 1e-9)
        assertEquals(1.0, seed("Quiz"), 1e-9)
        for (puzzle in listOf(
            "Boulder", "Creeper Beams", "Higher Blaze", "Ice Path",
            "Lower Blaze", "Teleport Maze", "Three Weirdos", "Tic Tac Toe",
        )) {
            assertEquals(1.0, seed(puzzle), 1e-9, "$puzzle is an unnamed puzzle and seeds at 1.0")
        }
        assertEquals(0.75, seed("Admin", type = "NORMAL"), 1e-9)
        assertEquals(0.75, seed("Old Trap", type = "TRAP"), 1e-9)
        // The seed is the whole of a room's base with nothing measured, so it is also the weight of
        // a secretless room — which is what the user's table is a table of.
        assertEquals(2.0, ContributionTracker.weightOf(roomOf(info = info("PUZZLE", name = "Ice Fill"))), 1e-9)
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
     * **Replaces `every room is still worth at least the point it used to be`.** That case pinned
     * `BASE_POINTS = 1.0`, and the user's model moves the ordinary base to 0.75 — so the old floor
     * is gone deliberately and a run's points no longer bound its room count from above. What
     * survives, and what actually mattered about it, is that **no room is ever worthless**: a room
     * nobody can identify, or one that measures faster than anything else in the dungeon, is still a
     * room somebody cleared, and paying it nothing would quietly hand its clear to no one.
     *
     * `MIN_BASE` (0.25) is the floor with a measurement in play; with none, the floor is the
     * ordinary seed. Both are checked, because the second is what ships today and the first is what
     * ships the moment a cache file appears.
     */
    @Test
    fun `no room is ever worthless`() {
        for (type in RoomType.entries) {
            assertTrue(
                ContributionTracker.weightOf(roomOf(type = type)) >= 0.75,
                "an unnamed $type room fell below the ordinary seed with nothing measured",
            )
        }
        for (type in listOf("NORMAL", "PUZZLE", "TRAP", "RARE", "CHAMPION", "BLOOD", "ENTRANCE", "FAIRY")) {
            assertTrue(
                ContributionTracker.weightOf(roomOf(info = info(type))) >= 0.75,
                "a $type room out of the database fell below the ordinary seed",
            )
        }
        // And with the fastest measurement the clamp allows, against the slowest possible seed.
        val floor = ContributionTracker.blend(0.75, RoomSample(n = 1_000_000, avgTicks = 1.0), 10_000.0)
        assertTrue(floor >= 0.25, "the clamp let a room fall below MIN_BASE: $floor")
        assertTrue(floor > 0.0, "no room may ever be worth nothing at all")
    }

    /**
     * `rooms.json` says `CHAMPION`, the map colour says `MINIBOSS`, and they are the same room. A
     * room must not be worth different amounts depending on whether its chunk had streamed in yet,
     * which is a race and not a property of the room.
     *
     * Since `clearpoints-002` this holds for a stronger reason than it used to: neither vocabulary
     * is paid for the miniboss at all, so there is no bonus left to disagree about. Kept because it
     * still fails on the obvious regression — reintroducing a kind bonus in one vocabulary only —
     * and joined by the `PUZZLE` case, which is the one word the two vocabularies now *have* to
     * agree on, since it is the only kind the seed table reads.
     */
    @Test
    fun `the database and the map agree on what a room's kind is`() {
        assertEquals(
            ContributionTracker.weightOf(roomOf(info = info("CHAMPION"))),
            ContributionTracker.weightOf(roomOf(type = RoomType.MINIBOSS)),
        )
        assertEquals(
            ContributionTracker.weightOf(roomOf(info = info("PUZZLE"))),
            ContributionTracker.weightOf(roomOf(type = RoomType.PUZZLE)),
            "a puzzle whose chunk has not streamed in yet must seed as a puzzle",
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

    // --- ClearPoints: the seed is a prior, not a constant (clearpoints-002) ---

    /**
     * The blend, driven directly. [ContributionTracker.blend] takes its sample and its median rather
     * than reading [RoomStats], so every property below is checked without a scores file on disk and
     * without depending on what any particular snapshot happens to contain.
     */
    private fun blend(seed: Double, n: Int, avgTicks: Double, median: Double = MEDIAN) =
        ContributionTracker.blend(seed, RoomSample(n, avgTicks), median)

    /** A plausible middle of the distribution: 5 s, which is where the box's real numbers cluster. */
    private val MEDIAN = 100.0

    /**
     * **The case that has to be exactly right, because getting it wrong is silent.** A room nothing
     * has been measured about is worth its seed and not a penny less — it must never be treated as a
     * fast room, which is what any "default the missing average to zero" shortcut would do. Four
     * ways of having no measurement, all of which occur: no entry for the room, an entry the
     * receiver wrote with `n: 0`, no median because nothing anywhere is measured, and a room with no
     * name to look up at all.
     */
    @Test
    fun `nothing measured is exactly the seed, not a fast room`() {
        assertEquals(1.5, ContributionTracker.blend(1.5, null, MEDIAN), 0.0, "no entry for the room")
        assertEquals(1.5, blend(1.5, n = 0, avgTicks = 10.0), 0.0, "an entry with no samples behind it")
        assertEquals(1.5, ContributionTracker.blend(1.5, RoomSample(5, 10.0), null), 0.0, "nothing measured at all")
        assertEquals(1.5, ContributionTracker.blend(1.5, RoomSample(5, 10.0), 0.0), 0.0, "a median of zero")

        // And through the real path: with the seed layer in force, every room is its seed.
        assertEquals(0.75, ContributionTracker.weightOf(plain()), 1e-9)
        assertEquals(2.0, ContributionTracker.weightOf(roomOf(info = info("PUZZLE", name = "Ice Fill"))), 1e-9)
    }

    /**
     * The user-visible behaviour in one line: *"abhaengig davon wie schnell der durchschnittliche
     * Spieler diesen Raum cleart geht dieser Wert hoch oder auch runter"*. Two rooms with the same
     * seed and the same sample count, separated only by how long they measure.
     */
    @Test
    fun `a slow room outscores a fast one`() {
        val slow = blend(0.75, n = 40, avgTicks = MEDIAN * 6)
        val fast = blend(0.75, n = 40, avgTicks = MEDIAN / 6)
        assertTrue(slow > fast, "the slower room has to be worth more: $slow vs $fast")
        assertTrue(slow > 0.75, "and a room slower than the median has to go up")
        assertTrue(fast < 0.75, "while a faster one goes down — 'oder auch runter' is half the model")
    }

    /**
     * **The whole point of the feature.** The seeds are estimates the user called estimates, and
     * they ship anyway because the measurement is allowed to overrule them once it has earned it —
     * in *both* directions. If `Ice Fill` turns out to be a ten-second puzzle it stops being worth
     * 2.0, and no code change is involved. A model that could only ever raise a room would be a
     * ratchet, not a measurement.
     */
    @Test
    fun `the measurement overrules the seed in both directions`() {
        // Ice Fill's seed is 2.0 — the highest the user gave. Measured as a very fast room, at a
        // sample count nobody could argue with, it comes down below the ordinary base.
        val overruledDown = blend(2.0, n = 500, avgTicks = MEDIAN / 8)
        assertTrue(overruledDown < 0.75, "a fast Ice Fill stayed expensive on the strength of its seed alone")

        // And an ordinary room that measures like a puzzle rises past every puzzle seed.
        val overruledUp = blend(0.75, n = 500, avgTicks = MEDIAN * 15)
        assertTrue(overruledUp > 2.0, "a genuinely slow ordinary room could not outgrow the seed table")
    }

    /**
     * The blocker this feature carried, answered rather than argued: `roomstats.json` has nine rooms
     * with a clear sample and every one of them is `n = 1`, and weighting off a single observation
     * is worse than the considered estimate it would replace. So one observation moves a room by
     * under a tenth of the way — enough that the data is not ignored, far too little for one lucky
     * run to redefine a room.
     */
    @Test
    fun `a single observation barely moves a room`() {
        val seed = 0.75
        val moved = blend(seed, n = 1, avgTicks = MEDIAN * 100)
        val fully = blend(seed, n = 10_000_000, avgTicks = MEDIAN * 100)
        val movedBy = (moved - seed) / (fully - seed)
        assertTrue(movedBy < 0.10, "one sample moved the room ${"%.0f".format(movedBy * 100)}% of the way")
        assertTrue(movedBy > 0.05, "and it should not be ignored either")
    }

    /** The other end: with enough observations the seed is a rounding error. */
    @Test
    fun `a large sample is essentially the measurement`() {
        val measured = blend(0.75, n = 1_000_000, avgTicks = MEDIAN * 4)
        assertEquals(
            blend(0.75, n = 1_000_000, avgTicks = MEDIAN * 4),
            blend(2.0, n = 1_000_000, avgTicks = MEDIAN * 4),
            0.01,
            "at a million samples two very different seeds must land on the same number",
        )
        // Which is the calibration stated in TIME_EXPONENT's KDoc: four times the median clear time
        // is twice the ordinary base.
        assertEquals(1.5, measured, 0.01, "four times the median has to be worth twice the ordinary base")
        assertEquals(0.75, blend(2.0, n = 1_000_000, avgTicks = MEDIAN), 0.01, "and the median room is the ordinary one")
    }

    /**
     * **No cliff**, which is the property the brief called out by name: a room's worth must not jump
     * at any particular sample count. A shrinkage of the shape `w = n / (n + k)` is continuous in
     * `n`; a rule like "ignore anything under five samples, then trust it" is not, and would make
     * the fifth clear of a room worth more than the four before it put together.
     *
     * Checked as a property over the whole range rather than at a chosen point, and the property is
     * that **each step is no larger than the one before it**. That is the precise statement of "no
     * cliff" and it is strictly stronger than bounding the steps by some threshold: a rule like
     * "ignore anything under five samples, then trust it" would show a step at `n = 5` far larger
     * than the step at `n = 4`, and this fails on it wherever the cliff is put. Under `n / (n + k)`
     * the largest step is the first — the seed's own 1/(1+k) of the journey, about 9% here — and
     * every step after it is smaller. Which is what the first assertion pins.
     */
    @Test
    fun `a room's base never jumps as its sample count grows`() {
        val seed = 0.75
        val slow = MEDIAN * 9
        val total = blend(seed, n = 10_000_000, avgTicks = slow) - seed
        assertTrue(total > 0.5, "fixture has to actually travel somewhere for this to mean anything")

        assertEquals(seed, ContributionTracker.blend(seed, RoomSample(0, slow), MEDIAN), 0.0, "n = 0 is the seed")
        val first = blend(seed, n = 1, avgTicks = slow) - seed
        assertTrue(first <= total / 8.0, "the very first sample moved the room ${first / total} of the way")

        var previous = seed
        var lastStep = Double.MAX_VALUE
        for (n in 1..2000) {
            val here = blend(seed, n = n, avgTicks = slow)
            val step = here - previous
            assertTrue(step >= 0.0, "the base fell going from ${n - 1} to $n samples")
            assertTrue(
                step <= lastStep + 1e-12,
                "the step at $n samples ($step) is bigger than the one before it ($lastStep) — a cliff",
            )
            previous = here
            lastStep = step
        }
    }

    /**
     * The clamp, both ends. One 36-second outlier — the real `Altar` figure on the box, against a
     * distribution whose median is a couple of seconds — cannot make a room worth ten however many
     * times it is observed, and the fastest room in the dungeon cannot fall to nothing.
     *
     * Deliberately checked at an absurd sample count, because the clamp is what stands between the
     * model and a genuinely wrong average. Shrinkage alone would only delay it.
     */
    @Test
    fun `no measurement however extreme can run away with a room`() {
        for (n in listOf(1, 10, 100, 10_000, 10_000_000)) {
            for (ticks in listOf(0.01, 1.0, MEDIAN, MEDIAN * 1_000, MEDIAN * 1_000_000)) {
                val base = blend(0.75, n = n, avgTicks = ticks)
                assertTrue(base in 0.25..2.5, "n=$n avgTicks=$ticks produced a base of $base")
            }
        }
        // And through the whole weight, secrets included, so the ceiling on a room is stateable:
        // MAX_BASE plus a quarter for each of the ten secrets the fattest room in rooms.json holds.
        val worst = ContributionTracker.blend(2.0, RoomSample(10_000_000, MEDIAN * 10_000), MEDIAN) + 10 * 0.25
        assertTrue(worst <= 5.0, "the most a single room can be worth is $worst")
    }

    /**
     * A measurement is keyed by the database's name, so a room whose chunk never streamed in has
     * nothing to look up and stays on the seed its map colour implies. Worth stating because it
     * means the same room can be worth two different amounts across two runs — the same
     * chunk-streaming race the secret bonus already has, now reaching the base as well.
     */
    @Test
    fun `an unnamed room can carry no measurement`() {
        // Three fast rooms and one slow one, so the median is genuinely below `Fixture`. A snapshot
        // holding a single room could not express "slow" at all — that room would *be* the median.
        RoomStats.use(
            RoomScores.parse(
                """{"generatedTs":7,"rooms":[
                     {"name":"Fixture","clearStay":{"n":400,"avgTicks":4000.0}},
                     {"name":"Quick A","clearStay":{"n":400,"avgTicks":40.0}},
                     {"name":"Quick B","clearStay":{"n":400,"avgTicks":50.0}},
                     {"name":"Quick C","clearStay":{"n":400,"avgTicks":60.0}}]}""",
            ),
        )

        val named = ContributionTracker.weightOf(roomOf(type = RoomType.PUZZLE, info = info("PUZZLE")))
        val unnamed = ContributionTracker.weightOf(roomOf(type = RoomType.PUZZLE))
        assertTrue(named > unnamed, "the named room's slow measurement did not reach it: $named vs $unnamed")
        assertEquals(1.0, unnamed, 1e-9, "and the unnamed one is exactly the puzzle seed")
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
        // Relative to the cheapest room rather than an absolute figure: what this case needs is a
        // room worth several rooms, and an absolute number would have to be retuned every time the
        // model moves — which is exactly how it came to say 4.0 against a room now worth 3.5.
        assertTrue(
            ContributionTracker.weightOf(expensive) > 2 * ContributionTracker.weightOf(plain()),
            "fixture is meant to be a heavy room",
        )

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
     * this path, and the one real M7 there is proves it end to end: `Duncan` emitted an
     * `unattributed` event and the raw-presence fallback then credited its whole 1.25 to the single
     * player anyway, so that run has one `unattributed` event and zero unattributed rooms
     * (`docs/evidence/session-1786719912927/`).
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

    /**
     * Two sources, one death — `chat-001`.
     *
     * The tab row flipping to `DEAD` was the only death signal this mod had, and it is late twice
     * over: the row trails the event, and [PartyTracker.update] only reads it once a second. Chat
     * states the same death outright on the tick it happens. Both paths are kept, because a death
     * message this client never received still shows up in tab, and losing a death is worse than
     * recording it late. So the two must not both charge it, and this is the seam that decides.
     *
     * [ContributionTracker.onDeath] takes the tick rather than reading [DungeonSession.runTicks],
     * which is what makes this testable at all: the clock is `private set` and only `tickClock`
     * moves it, so a version that read the clock could only ever be exercised at tick zero. Same
     * seam as `onPresence` and `onSecret`.
     */
    @Test
    fun `chat and the tab list do not both charge one death`() {
        assertTrue(ContributionTracker.onDeath("Alice", 100, ContributionTracker.DeathSource.CHAT))
        // The tab poll notices a second later. Same death.
        assertFalse(ContributionTracker.onDeath("Alice", 120, ContributionTracker.DeathSource.TAB))
        assertEquals(1, ContributionTracker.deaths)
    }

    /** Order is not guaranteed — the poll can land first — and the guard is symmetric either way. */
    @Test
    fun `whichever source arrives first is the one that charges it`() {
        assertTrue(ContributionTracker.onDeath("Alice", 120, ContributionTracker.DeathSource.TAB))
        assertFalse(ContributionTracker.onDeath("Alice", 121, ContributionTracker.DeathSource.CHAT))
        assertEquals(1, ContributionTracker.deaths)
    }

    /**
     * The window is a guard, not the mechanism. What actually separates two deaths is the revive
     * between them: a dead player cannot die again without one, so clearing the entry on ` ❣ ` makes
     * a genuine second death countable however fast it follows the first — which no window on its
     * own could do without also letting a duplicate through.
     */
    @Test
    fun `a revive makes the next death a new death`() {
        assertTrue(ContributionTracker.onDeath("Alice", 100, ContributionTracker.DeathSource.CHAT))
        ContributionTracker.onRevive("Alice")
        // Well inside DEATH_DEDUP_TICKS, and still a real second death.
        assertTrue(ContributionTracker.onDeath("Alice", 110, ContributionTracker.DeathSource.CHAT))
        assertEquals(2, ContributionTracker.deaths)
    }

    /** Two players dying together are two deaths; the guard is per player, not per tick. */
    @Test
    fun `two members dying at once are two deaths`() {
        assertTrue(ContributionTracker.onDeath("Alice", 100, ContributionTracker.DeathSource.CHAT))
        assertTrue(ContributionTracker.onDeath("Bob", 100, ContributionTracker.DeathSource.CHAT))
        assertEquals(2, ContributionTracker.deaths)
    }

    /**
     * The window itself, at both edges and below zero.
     *
     * The negative case is why this is a range and not a `<=`: a 20-tick tab poll can in principle
     * report a death with an earlier timestamp than one already charged, and a `<=` would then drop
     * the *later* death in favour of the earlier one — silently, and in the direction that loses
     * data. Nothing here has been observed on a real floor; the range costs one character.
     */
    @Test
    fun `the duplicate window covers the lag between the two reports and nothing else`() {
        assertFalse(ContributionTracker.isDuplicateDeath(previous = null, at = 100))
        assertTrue(ContributionTracker.isDuplicateDeath(previous = 100, at = 100))
        assertTrue(
            ContributionTracker.isDuplicateDeath(
                previous = 100, at = 100 + ContributionTracker.DEATH_DEDUP_TICKS,
            ),
        )
        assertFalse(
            ContributionTracker.isDuplicateDeath(
                previous = 100, at = 101 + ContributionTracker.DEATH_DEDUP_TICKS,
            ),
        )
        assertFalse(ContributionTracker.isDuplicateDeath(previous = 100, at = 99))
    }

    /** Death state is run state. A charge that survived `reset` would suppress the next run's first. */
    @Test
    fun `a death does not survive the run`() {
        assertTrue(ContributionTracker.onDeath("Alice", 100, ContributionTracker.DeathSource.CHAT))
        ContributionTracker.reset()

        assertEquals(0, ContributionTracker.deaths)
        assertTrue(ContributionTracker.onDeath("Alice", 100, ContributionTracker.DeathSource.CHAT))
        assertEquals(1, ContributionTracker.deaths)
    }

    // --- was I here for this? (recordowner-001) ---

    /**
     * [TrackedRoom.presentFromStart] is the half [TrackedRoom.ticks] cannot supply. A tick total is
     * a quantity with no position in it: forty ticks says nothing about *which* forty, so the player
     * who walks in as the checkmark lands accumulates them exactly as fast as the player who was
     * there from the first swing.
     *
     * This is the seam the clear record hangs off, so the cases below are about arrivals rather than
     * about totals.
     */
    @Test
    fun `a member who was there before the room's clock started was there from the start`() {
        val room = room()
        room.stay("Fighter", from = 1000, count = min * 3)
        assertEquals(1000, room.enteredAtTick)

        assertTrue(room.presentFromStart("Fighter", at = 1000 + min * 3))
    }

    /**
     * The user's second report: *"wenn ich 'verspätet' in einen Raum komme der bereits von jemand
     * anderem gecleart wird"*. The latecomer has more than the one second the old bar asked for and
     * is still not the person who did the room.
     */
    @Test
    fun `a member who arrived after the clock started was not there from the start`() {
        val room = room()
        room.stay("Fighter", from = 1000, count = min * 5)
        // Walks in near the end and stays past the threshold — enough ticks, wrong ticks.
        room.stay("Latecomer", from = 1000 + min * 4, count = min)
        assertEquals(1000, room.enteredAtTick, "the room's clock is still the fighter's arrival")

        val clearedAt = 1000 + min * 5
        assertTrue(room.presentFromStart("Fighter", clearedAt))
        assertFalse(room.presentFromStart("Latecomer", clearedAt))
        // And it is not a tick-count problem: the latecomer is well past the eligibility bar.
        assertTrue((room.ticks["Latecomer"] ?: 0) >= min)
    }

    /**
     * "From the start" is not a fact about the past alone — the stay has to still be running when
     * the checkmark lands. Only the most recent stay per member is kept, so a member who arrived
     * first, left, and never came back would otherwise keep looking like the person who was here for
     * it. The tolerance is the one `onPresence` continues a stay with, deliberately: a second,
     * quieter notion of "still here" is how two guards start disagreeing.
     */
    @Test
    fun `a member who left before the checkmark was not there for it`() {
        val room = room()
        room.stay("Fighter", from = 1000, count = min * 2)
        assertEquals(1000, room.enteredAtTick)
        val lastSeen = 1000 + min * 2 - 1

        // Inside the tolerance the stay is still the same stay — this is the roster-skew blind spot
        // around a death, not somebody leaving.
        assertTrue(room.presentFromStart("Fighter", at = lastSeen + 1 + min))
        // One tick further and `onPresence` would have begun a new stay, so this must agree.
        assertFalse(room.presentFromStart("Fighter", at = lastSeen + 2 + min))
    }

    /**
     * Coming back is a new stay, and a new stay begins after the room's clock did. The member did
     * more of the room than anyone, and still did not do it from the start — which is the case the
     * two halves of the gate are separate for.
     */
    @Test
    fun `a member who came back is measured from the return, not the first visit`() {
        val room = room()
        room.stay("Wanderer", from = 100, count = min / 2) // a walk-through, anchors nothing
        room.stay("Fighter", from = 1000, count = min * 4)
        room.stay("Wanderer", from = 1000 + min * 2, count = min * 2)
        assertEquals(1000, room.enteredAtTick)

        val clearedAt = 1000 + min * 4
        assertFalse(room.presentFromStart("Wanderer", clearedAt))
        assertTrue(room.presentFromStart("Fighter", clearedAt))
    }

    /**
     * A room with no anchor answers `false` for everybody, and that is a decision rather than a
     * fallthrough. A null [TrackedRoom.enteredAtTick] means either a pre-cleared room — already done
     * when we arrived, so there is no clear of ours to time — or a room where no stay reached the
     * threshold and none had begun inside the fallback window. In the second case the presence data
     * is too thin to say the room had a start at all, let alone who was there for it, and history is
     * append-only: an unrecorded room costs nothing, a bogus record is permanent.
     */
    @Test
    fun `a room that never anchored gives nobody a record`() {
        val room = room()
        room.stay("Passerby", from = 100, count = min / 2)
        assertNull(room.enteredAtTick)

        assertFalse(room.presentFromStart("Passerby", at = 200))
        assertFalse(room.presentFromStart("Nobody", at = 200))
    }

    /** A member never seen in the room is not "from the start" by absence of evidence. */
    @Test
    fun `a member with no stay at all was not there from the start`() {
        val room = room()
        room.stay("Fighter", from = 1000, count = min * 2)
        assertFalse(room.presentFromStart("Ghost", at = 1000 + min * 2))
    }
}
