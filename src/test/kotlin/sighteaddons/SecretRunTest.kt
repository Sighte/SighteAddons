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
    private fun room() = TrackedRoom(RoomType.ROOM, emptySet(), emptySet())

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
}
