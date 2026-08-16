package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What each tick of a run counts as, and what the two counters add up to over a sequence of them.
 *
 * This is the half of `idletime-001` that can be verified without a game. The other half — that
 * [SighteAddons.onTick] calls [IdleTime.tick] once per tick with the room the local player is
 * actually standing in, and that [SighteAddons.renderHud] draws [IdleTime.line] behind
 * `Config.showIdle` — needs a live `Minecraft` and is unverified here, exactly like every other
 * wiring line in this repository.
 *
 * The definitions being pinned are the receiver's, from `SETUP.md` section 4, and they are the
 * contract both halves of the pair are written against: `idleTicks` is time inside an already
 * cleared room (a `preCleared` one counts) with no secret run active, `navTicks` is time inside no
 * room at all. Anything that changes what these tests assert changes what a stored number means in
 * an append-only profile, and is a schema question rather than a refactor.
 *
 * [IdleTime] is an `object` with run-long state, so every test resets it first — the same rule
 * `ContributionTrackerTest` follows.
 */
class IdleTimeTest {
    @BeforeEach
    fun clean() = IdleTime.reset()

    private fun room() = TrackedRoom(RoomType.ROOM, emptySet(), emptySet())

    /** A room somebody cleared during this run. */
    private fun clearedRoom() = room().also { it.clearedAtTick = 400 }

    @Test
    fun `no room at all is navigation`() {
        assertEquals(IdleTime.Where.NAVIGATING, IdleTime.classify(null))

        repeat(30) { IdleTime.tick(null) }
        assertEquals(30, IdleTime.navTicks)
        assertEquals(0, IdleTime.idleTicks)
    }

    @Test
    fun `standing in a cleared room with nothing running is idle`() {
        val done = clearedRoom()
        assertEquals(IdleTime.Where.IDLE, IdleTime.classify(done))

        repeat(45) { IdleTime.tick(done) }
        assertEquals(45, IdleTime.idleTicks)
        assertEquals(0, IdleTime.navTicks)
    }

    /**
     * The contract names `preCleared` explicitly, so it is checked explicitly rather than inherited
     * from `ContributionTracker.discover` happening to stamp `clearedAtTick` on such a room today.
     * If that stamp ever moves, the entrance and the fairy room — the most ordinary standing-around
     * there is — must not drop out of `idleTicks` silently.
     */
    @Test
    fun `a preCleared room counts even without a clear tick`() {
        val entrance = room().also { it.preCleared = true }

        assertEquals(IdleTime.Where.IDLE, IdleTime.classify(entrance))
        IdleTime.tick(entrance)
        assertEquals(1, IdleTime.idleTicks)
        assertEquals(0, IdleTime.navTicks)
    }

    /** A room still being fought through is work, and neither counter may move for it. */
    @Test
    fun `a room that is not cleared yet is neither idle nor navigation`() {
        val fighting = room()

        assertEquals(IdleTime.Where.WORKING, IdleTime.classify(fighting))
        repeat(100) { IdleTime.tick(fighting) }
        assertEquals(0, IdleTime.idleTicks)
        assertEquals(0, IdleTime.navTicks)
    }

    /**
     * The `no secret run active` half of the definition, driven through the real
     * [TrackedRoom.onSecret] rather than by assigning the flag: hunting secrets in a room whose
     * checkmark has already landed is the most ordinary thing after a clear, and counting it as
     * idling would make the number say the opposite of what happened.
     */
    @Test
    fun `an open secret run in a cleared room is work`() {
        val done = clearedRoom()
        done.readBar(0) // a trusted 0/5: the room was untouched when we arrived
        assertEquals(TrackedRoom.SecretRun.STARTED, done.onSecret(previous = 0, found = 1, max = 5, at = 500))

        assertEquals(IdleTime.Where.WORKING, IdleTime.classify(done))
        repeat(60) { IdleTime.tick(done) }
        assertEquals(0, IdleTime.idleTicks)

        // Last secret lands: the room is done in both senses and standing in it is idling again.
        assertEquals(TrackedRoom.SecretRun.DONE, done.onSecret(previous = 4, found = 5, max = 5, at = 900))
        assertEquals(IdleTime.Where.IDLE, IdleTime.classify(done))
        IdleTime.tick(done)
        assertEquals(1, IdleTime.idleTicks)
    }

    /**
     * A **discarded** run is not an active one, so a cleared room whose run was thrown away counts
     * as idle even while its leftovers are still being collected. That is the specification's
     * over-count and not a defect of this file: "no secret run active" is the phrase the receiver's
     * `SETUP.md` writes both halves against, and softening it here would be exactly the divergence
     * the receiver-first ordering exists to prevent. Recorded in `idletime-001`'s notes.
     *
     * **The run has to have *started* for this to guard anything**, which is the whole reason the
     * abandoned case is used rather than the leftovers one below: a run that was refused outright
     * leaves `secretRunStart` null, so it reads closed on the first clause and says nothing about
     * whether the discard flag is honoured. That first version of this test passed while the flag
     * was ignored entirely — probe E, uncaught, and the same shape of guard-in-name-only
     * `ownClear`'s five conditions have produced twice.
     */
    @Test
    fun `a secret run that was started and then abandoned leaves the room idle`() {
        val abandoned = clearedRoom()
        abandoned.readBar(0)
        assertEquals(TrackedRoom.SecretRun.STARTED, abandoned.onSecret(previous = 0, found = 1, max = 5, at = 500))
        assertEquals(IdleTime.Where.WORKING, IdleTime.classify(abandoned), "while it is open, this is work")

        // The party moved on and the room went quiet: the run is dropped rather than closed at
        // whatever the room reaches later, and standing there is standing there.
        assertTrue(abandoned.expireSecretRun(now = 1500, abandonTicks = 400))
        assertEquals(IdleTime.Where.IDLE, IdleTime.classify(abandoned))

        IdleTime.tick(abandoned)
        assertEquals(1, IdleTime.idleTicks)
    }

    /**
     * The other discard: a run that was never ours to time. Walking into a room already at 3/5
     * refuses the run outright, so nothing is open and standing there is idle.
     */
    @Test
    fun `somebody else's leftovers are not an open run`() {
        val leftovers = clearedRoom()
        leftovers.readBar(3) // walked in at 3/5: not ours to time
        assertEquals(TrackedRoom.SecretRun.DISCARDED, leftovers.onSecret(previous = 3, found = 4, max = 5, at = 500))

        assertEquals(IdleTime.Where.IDLE, IdleTime.classify(leftovers))
    }

    /** Both counters advance independently over a run that mixes all three states. */
    @Test
    fun `a run splits into the three kinds of tick`() {
        val fighting = room()
        val done = clearedRoom()

        repeat(10) { IdleTime.tick(null) }      // walking to the room
        repeat(200) { IdleTime.tick(fighting) } // clearing it
        repeat(40) { IdleTime.tick(done) }      // standing in it afterwards
        repeat(5) { IdleTime.tick(null) }       // walking on

        assertEquals(40, IdleTime.idleTicks)
        assertEquals(15, IdleTime.navTicks)
    }

    /**
     * Per run, like the clock they are counted against — [DungeonSession.reset] calls this. A run
     * that inherited the previous floor's idle time would upload a number describing two runs, and
     * `profiles/` is append-only.
     */
    @Test
    fun `the counters are forgotten between runs`() {
        IdleTime.tick(null)
        IdleTime.tick(clearedRoom())
        IdleTime.reset()

        assertEquals(0, IdleTime.idleTicks)
        assertEquals(0, IdleTime.navTicks)
    }

    /**
     * And the reset is *wired*, not merely available. [DungeonSession.reset] is where a run ends —
     * it runs on `JOIN`, after the report for the run being left has been written — so a counter
     * that is not cleared there follows the player into the next dungeon and uploads a number
     * describing two floors. `profiles/` is append-only and that line could never be corrected.
     *
     * Calls the real `reset()` on purpose, which `CLAUDE.md` warns resets half the mod: that
     * blast radius *is* the thing being checked, and the objects it touches all rebuild their state
     * in their own `@BeforeEach`. `DungeonSessionTest`'s `reset forgets the floor` does the same.
     */
    @Test
    fun `the end of a run clears them`() {
        IdleTime.tick(null)
        IdleTime.tick(clearedRoom())

        DungeonSession.reset()

        assertEquals(0, IdleTime.idleTicks, "the next run must not inherit this one's idle time")
        assertEquals(0, IdleTime.navTicks)
    }

    /**
     * Two numbers, never one, and never a percentage of a run that is still in progress. 490 ticks
     * is 24.5 s and 1340 is 67.0 s, in the same `m:ss.t` the rest of the readout uses.
     */
    @Test
    fun `the line carries both numbers, counting up`() {
        assertEquals("Idle  0:00.0  ·  Nav  0:00.0", IdleTime.line(0, 0))
        assertEquals("Idle  0:24.5  ·  Nav  1:07.0", IdleTime.line(490, 1340))
    }
}
