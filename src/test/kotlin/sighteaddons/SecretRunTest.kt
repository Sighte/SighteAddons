package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The secret run is a record that lands in a permanent, append-only file, so every case that could
 * write a *wrong* time matters more than the happy path: a room joined half-finished, a party that
 * walks out, a counter that jumps. All of them must produce no record at all rather than a fast one.
 */
class SecretRunTest {
    /**
     * A room whose first trusted action bar reading was [firstBar] — 0 by default, i.e. walked into
     * clean, which is the only state a run may start from.
     *
     * The reading is now a precondition of starting a run at all, so every case that expects a run
     * to start has to supply one. Nothing here is relaxed by it: each case still asserts exactly
     * what it asserted before, on a room whose observed history now says what it always meant.
     */
    private fun room(firstBar: Int = 0) =
        TrackedRoom(RoomType.ROOM, emptySet(), emptySet()).apply { readBar(firstBar) }

    /** The old helper's shape: a room no action bar has ever been read for. */
    private fun unobserved() = TrackedRoom(RoomType.ROOM, emptySet(), emptySet())

    @Test
    fun `timed from the room's first secret to its last`() {
        val room = room()
        assertEquals(TrackedRoom.SecretRun.STARTED, room.onSecret(previous = 0, found = 1, max = 3, at = 100))
        assertEquals(TrackedRoom.SecretRun.RUNNING, room.onSecret(previous = 1, found = 2, max = 3, at = 260))
        assertNull(room.secretRunTicks) // still open, so nothing is recordable yet
        assertEquals(TrackedRoom.SecretRun.DONE, room.onSecret(previous = 2, found = 3, max = 3, at = 400))

        // 100 -> 400, not the time since walking into the room, and not since the run started.
        assertEquals(300, room.secretRunTicks)
        assertEquals(TrackedRoom.SecretRun.IGNORED, room.onSecret(previous = 3, found = 4, max = 4, at = 500))
        assertEquals(300, room.secretRunTicks)
    }

    @Test
    fun `a room joined half-finished is somebody else's run`() {
        val room = room()
        // Walking in at 2/5 would otherwise time the last three secrets and call it the room record.
        assertEquals(TrackedRoom.SecretRun.DISCARDED, room.onSecret(previous = 2, found = 3, max = 5, at = 100))
        assertEquals(TrackedRoom.SecretRun.IGNORED, room.onSecret(previous = 3, found = 4, max = 5, at = 200))
        assertEquals(TrackedRoom.SecretRun.IGNORED, room.onSecret(previous = 4, found = 5, max = 5, at = 300))
        assertNull(room.secretRunTicks)
    }

    @Test
    fun `a run with no span between its ends is not a time`() {
        // One secret is taken and finished in the same instant.
        val single = room()
        assertEquals(TrackedRoom.SecretRun.DISCARDED, single.onSecret(previous = 0, found = 1, max = 1, at = 100))
        assertNull(single.secretRunTicks)

        // Two secrets landing in one bar update would record 0:00.0 and never be beaten again.
        val jumped = room()
        assertEquals(TrackedRoom.SecretRun.DISCARDED, jumped.onSecret(previous = 0, found = 4, max = 4, at = 100))
        assertNull(jumped.secretRunTicks)
    }

    @Test
    fun `ten quiet seconds discard the run rather than stretch it`() {
        val room = room()
        room.onSecret(previous = 0, found = 1, max = 4, at = 100)

        assertFalse(room.expireSecretRun(now = 300, abandonTicks = 200)) // exactly 10 s is still alive
        assertEquals(200, room.secretRunElapsed(300)) // the HUD keeps counting meanwhile
        assertTrue(room.expireSecretRun(now = 301, abandonTicks = 200))
        assertNull(room.secretRunTicks)
        assertNull(room.secretRunElapsed(400)) // back to dashes, not a stale clock

        // Coming back and finishing the room half a minute later must not resurrect it: those
        // secrets were never taken in one go, which is the only thing this metric claims.
        assertEquals(TrackedRoom.SecretRun.IGNORED, room.onSecret(previous = 1, found = 4, max = 4, at = 900))
        assertNull(room.secretRunTicks)
        // Discarded once, so the caller logs it once and not on every tick that follows.
        assertFalse(room.expireSecretRun(now = 1_000, abandonTicks = 200))
    }

    @Test
    fun `a finished run never expires`() {
        val room = room()
        room.onSecret(previous = 0, found = 1, max = 2, at = 100)
        room.onSecret(previous = 1, found = 2, max = 2, at = 200)

        assertEquals(100, room.secretRunTicks)
        assertFalse(room.expireSecretRun(now = 9_000, abandonTicks = 200))
        assertEquals(100, room.secretRunElapsed(9_000)) // the finished time, not a clock still running
    }

    @Test
    fun `an untouched room has no run at all`() {
        val room = room()
        assertNull(room.secretRunElapsed(500))
        assertFalse(room.expireSecretRun(now = 5_000, abandonTicks = 200))
        assertNull(room.secretRunTicks)
    }

    /**
     * The defect the user reported, verbatim as the code saw it: *"wenn ich 'verspätet' in einen
     * Raum komme der bereits von jemand anderem gecleart wird"*.
     *
     * `secretsFound` is this client's observation and is 0 for every room until a bar has been read,
     * so a room already at 3/10 hands `onSecret` `previous = 0, found = 3` — which used to look
     * exactly like a party that had just taken three in front of you. The run started, the remaining
     * seven were timed, and that time was announced and filed as the room's record.
     */
    @Test
    fun `a room entered part-way through is not the local player's run`() {
        val late = room(firstBar = 3)
        assertEquals(TrackedRoom.SecretRun.DISCARDED, late.onSecret(previous = 0, found = 3, max = 10, at = 100))
        // And it stays discarded for the rest of the room, so the next rise cannot start a second,
        // even faster run off the tail of the same one.
        assertEquals(TrackedRoom.SecretRun.IGNORED, late.onSecret(previous = 3, found = 4, max = 10, at = 140))
        assertEquals(TrackedRoom.SecretRun.IGNORED, late.onSecret(previous = 9, found = 10, max = 10, at = 900))
        assertNull(late.secretRunTicks)

        // One secret already gone is enough. There is no threshold below which somebody else's head
        // start is small enough to time.
        val barelyLate = room(firstBar = 1)
        assertEquals(
            TrackedRoom.SecretRun.DISCARDED,
            barelyLate.onSecret(previous = 0, found = 2, max = 5, at = 100),
        )
        assertNull(barelyLate.secretRunTicks)
    }

    /**
     * A run may only start from a reading that was actually taken. Nothing vouches for the state of
     * a room whose bar has never been read — the counter could be at nine of ten — and a permanent
     * file is the wrong place to assume the generous answer.
     */
    @Test
    fun `a room whose bar was never read cannot start a run`() {
        val room = unobserved()
        assertNull(room.firstBarFound)
        assertEquals(TrackedRoom.SecretRun.DISCARDED, room.onSecret(previous = 0, found = 1, max = 4, at = 100))
        assertNull(room.secretRunTicks)
    }

    /**
     * *First* reading, not latest. If a later reading could overwrite it, every room would end up
     * recorded as having been entered at whatever it last showed, and the guard above would answer
     * about the end of the room rather than about walking into it.
     */
    @Test
    fun `the first bar reading is the one kept`() {
        val room = unobserved()
        assertTrue(room.readBar(0).first) // the caller logs this one
        assertEquals(0, room.firstBarFound)
        assertFalse(room.readBar(4).first) // and never again
        assertEquals(0, room.firstBarFound)

        // The room is still startable, because the reading that counted said untouched.
        assertEquals(TrackedRoom.SecretRun.STARTED, room.onSecret(previous = 0, found = 1, max = 4, at = 100))

        // Mirror image: a late entry cannot be rescued by a later reading of 0 either, which cannot
        // happen on a real bar but is the shape a "just re-read it" fix would take.
        val late = unobserved()
        assertTrue(late.readBar(2).first)
        assertFalse(late.readBar(0).first)
        assertEquals(2, late.firstBarFound)
        assertEquals(TrackedRoom.SecretRun.DISCARDED, late.onSecret(previous = 0, found = 3, max = 6, at = 100))
    }

    /**
     * The ordering guard, and the reason [TrackedRoom.readBar] is one function instead of the three
     * statements it used to be at the call site.
     *
     * A `0/10` reading is **not a rise**, so anything that tests for a rise first never sees it —
     * and that reading is the only one that can ever say the room was untouched when we arrived. Get
     * this backwards and the first reading on record becomes the `1/10` that follows, no room ever
     * looks clean, and *every* secret run in the game is silently discarded. Nothing else in this
     * suite can catch that, because the alternative shape lives in a method needing a live client.
     */
    @Test
    fun `an untouched room is observed even though nothing rose`() {
        val room = unobserved()
        val reading = room.readBar(0)

        assertTrue(reading.first, "the first reading is the first reading whether or not it rose")
        assertFalse(reading.rose, "0 out of 10 is not a secret being found")
        assertEquals(0, room.firstBarFound, "and it is the reading that decides the room is clean")
        assertEquals(0, room.secretsFound)

        // Which is exactly what makes the run startable when the first secret does land.
        val next = room.readBar(1)
        assertFalse(next.first)
        assertTrue(next.rose)
        assertEquals(0, next.previous)
        assertEquals(1, room.secretsFound)
        assertEquals(
            TrackedRoom.SecretRun.STARTED,
            room.onSecret(previous = next.previous, found = 1, max = 4, at = 100),
        )
    }

    /**
     * The counter never goes backwards and a repeat is not a find. The action bar repeats the same
     * line every tick you stand in the room, and a stale bar from the room you just left can read
     * lower than the one you are in.
     */
    @Test
    fun `a reading that does not rise moves nothing`() {
        val room = unobserved()
        room.readBar(0)
        room.readBar(3)
        assertEquals(3, room.secretsFound)

        val repeat = room.readBar(3)
        assertFalse(repeat.rose)
        assertEquals(3, repeat.previous)
        assertEquals(3, room.secretsFound)

        val stale = room.readBar(1)
        assertFalse(stale.rose)
        assertEquals(3, room.secretsFound, "the counter never goes backwards")
    }
}
