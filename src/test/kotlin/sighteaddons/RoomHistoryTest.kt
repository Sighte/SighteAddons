package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The records table in `/sa` is derived from the history file, never stored — so the fold from lines
 * to records is what has to be right.
 *
 * The second half of this class is the other question the file asks: not what a line means, but
 * **whether a line gets written at all**. [RoomHistory.onRoomCleared] and [RoomHistory.onSecretRun]
 * both need a live `Minecraft` to find out who the local player is and so cannot be driven from
 * here; the decisions they make cannot — [RoomHistory.ownClear] and [RoomHistory.ownSecretRun] take
 * the room and the names and read no clock and no client, the same seam `onPresence` and `onSecret`
 * are tested through. What is *not* covered here is the wiring: that `onRoomCleared` calls
 * `ownClear` with the top player it just computed and the name of the player holding the mouse, and
 * that `ClearPopup.show` fires under the same answer. No command in this repository can observe it.
 */
class RoomHistoryTest {
    private fun line(room: String, kind: String, ticks: Int, ts: Long) =
        """{"ts":$ts,"floor":"M5","room":"$room","kind":"$kind","ticks":$ticks,"seconds":${ticks / 20.0}}"""

    @Test
    fun `record is the minimum, with run count and latest timestamp`() {
        val records = RoomHistory.fold(
            sequenceOf(
                line("Catwalk", "clear", 200, 1_000),
                line("Catwalk", "clear", 120, 2_000),
                line("Catwalk", "clear", 160, 3_000),
                line("Catwalk", "secrets", 900, 3_000),
                line("Water Board", "clear", 824, 4_000),
            ),
        )

        val catwalk = records.byKey.getValue("Catwalk|clear")
        assertEquals(120, catwalk.ticks) // best, not last and not first
        assertEquals(3, catwalk.runs)
        assertEquals(3_000L, catwalk.lastTs) // latest, so "last" cannot show an older run
        // Clear and secrets stay separate keys — a room can be cleared without its secrets.
        assertEquals(900, records.byKey.getValue("Catwalk|secrets").ticks)
        assertEquals(1, records.byKey.getValue("Water Board|clear").runs)
        // Every valid line raises exactly one run count, which is the line total the screen shows.
        assertEquals(5, records.entries)
    }

    @Test
    fun `a teammate's secrets read as a dash, never as zero`() {
        assertEquals("(7 rooms · 12 secrets)", RoomHistory.breakdown(7, 12))
        // Zero is a claim about a teammate this client cannot make: it never saw the rooms they
        // were in alone, so an unknown count must not read like an empty one.
        assertEquals("(9 rooms · – secrets)", RoomHistory.breakdown(9, null))
        assertEquals("(0 rooms · 0 secrets)", RoomHistory.breakdown(0, 0))
    }

    @Test
    fun `a retired kind is kept in the file but gets no row in the table`() {
        val records = RoomHistory.fold(
            sequenceOf(
                line("Catwalk", RoomHistory.CLEAR, 120, 1_000),
                line("Water Board", RoomHistory.SECRETS, 300, 2_000),
                // The pre-0.5 kind. Its ticks measured something else entirely, so nothing reads it
                // any more — but the line stays, and a room that has only this must not become a row
                // of dashes with a run count of 0.
                line("Old Room", "secrets", 900, 3_000),
            ),
        )

        assertEquals(3, records.entries) // every line still counted as history
        assertEquals(
            listOf("Catwalk", "Water Board"),
            RoomHistory.roomsWithRecords(records.byKey.keys).sorted(),
        )
    }

    /**
     * The detail line in `/sa` draws a room's progression, so the fold has to keep every attempt and
     * not just their minimum. Floor and PB flag have been written since the first version.
     */
    @Test
    fun `every attempt is kept, in file order, with its floor and pb flag`() {
        val records = RoomHistory.fold(
            sequenceOf(
                """{"ts":1000,"floor":"F7","room":"Catwalk","kind":"clear","ticks":200,"pb":true}""",
                """{"ts":2000,"floor":"M7","room":"Catwalk","kind":"clear","ticks":120,"pb":true}""",
                """{"ts":3000,"floor":"F7","room":"Catwalk","kind":"clear","ticks":160,"pb":false}""",
                // Pre-0.5 line: no pb field, and floor was not always known.
                line("Catwalk", "clear", 300, 4_000),
            ),
        )

        val attempts = records.attempts.getValue("Catwalk|clear")
        assertEquals(listOf(200, 120, 160, 300), attempts.map { it.ticks }) // oldest first, none folded away
        assertEquals(listOf("F7", "M7", "F7", "M5"), attempts.map { it.floor })
        assertEquals(listOf(true, true, false, false), attempts.map { it.pb }) // a missing flag is not a PB
        // The record is still the minimum over exactly those attempts.
        assertEquals(120, records.byKey.getValue("Catwalk|clear").ticks)
        assertEquals(attempts.size, records.byKey.getValue("Catwalk|clear").runs)
    }

    @Test
    fun `unreadable lines are counted, not fatal`() {
        val records = RoomHistory.fold(
            sequenceOf(line("Catwalk", "clear", 120, 1_000), "", "{not json", """{"room":"X"}"""),
        )

        assertEquals(1, records.byKey.size)
        assertEquals(1, records.entries)
        assertEquals(2, records.malformed) // the blank line is skipped, not counted
    }

    // --- whose record is it? (recordowner-001) ---

    private val min = ContributionTracker.MIN_TICKS

    /** A room, with [player] present on every tick of `[from, from + count)`, as `tick` sees them. */
    private fun room(): TrackedRoom = TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0)))

    private fun TrackedRoom.stay(player: String, from: Int, count: Int) {
        repeat(count) {
            ticks.merge(player, 1, Int::plus)
            onPresence(player, from + it)
        }
    }

    /**
     * The happy path, and the one thing this change must not cost: a room you were in from the
     * start and did more of than anybody still writes its line.
     */
    @Test
    fun `a room you were in from the start and did the most of is yours`() {
        val room = room()
        room.stay("Me", from = 1000, count = min * 4)
        room.stay("Mate", from = 1000 + min, count = min)
        room.clearedAtTick = 1000 + min * 4

        assertTrue(RoomHistory.ownClear(room, self = "Me", topPlayer = "Me"))
    }

    /**
     * Defect C, reported by the user: arriving as the checkmark lands used to write a ~1.5 s clear
     * that beats every honest record for that room, permanently — the old bar was one second of
     * presence and nothing else.
     */
    @Test
    fun `walking in as the checkmark lands is not a record`() {
        val room = room()
        room.stay("Mate", from = 1000, count = min * 5)
        room.stay("Me", from = 1000 + min * 4, count = min)
        room.clearedAtTick = 1000 + min * 5

        // Both halves refuse it independently, which is why they are two conditions and not one:
        // the mate is top by time, and this client was not there when the room's clock started.
        assertFalse(RoomHistory.ownClear(room, self = "Me", topPlayer = "Mate"))
        // Even if hash order had handed the late arrival the top slot, it is still not from the
        // start — so the second half is not decoration on the first.
        assertFalse(RoomHistory.ownClear(room, self = "Me", topPlayer = "Me"))
    }

    /**
     * The other direction, and the reason "most ticks" is required as well as "from the start". A
     * player who was there first and then stood back while somebody else did the room contributed
     * the arrival and not the clear.
     */
    @Test
    fun `being there first is not enough if somebody else did the room`() {
        val room = room()
        room.stay("Me", from = 1000, count = min * 2)
        room.stay("Mate", from = 1000, count = min * 6)
        room.clearedAtTick = 1000 + min * 6

        assertFalse(RoomHistory.ownClear(room, self = "Me", topPlayer = "Mate"))
        assertTrue(RoomHistory.ownClear(room, self = "Mate", topPlayer = "Mate"))
    }

    /**
     * The three ways the question has no answer at all. Each returns `false` rather than falling
     * through to a record, because the file is append-only and a wrong line in it is forever.
     */
    @Test
    fun `an unanswerable clear is not a record`() {
        val anchored = room()
        anchored.stay("Me", from = 1000, count = min * 2)
        anchored.clearedAtTick = 1000 + min * 2

        // The local player could not be resolved.
        assertFalse(RoomHistory.ownClear(anchored, self = null, topPlayer = "Me"))

        // The room never anchored: nothing says when its clock started, so nothing can say who was
        // there for it. A pre-cleared room is this case too — it keeps a null anchor for its life.
        val unanchored = room()
        unanchored.stay("Me", from = 1000, count = min / 2)
        unanchored.clearedAtTick = 1000 + min
        assertNull(unanchored.enteredAtTick)
        assertFalse(RoomHistory.ownClear(unanchored, self = "Me", topPlayer = "Me"))

        // No clear tick, so there is no moment to ask "were you still here" about.
        val unstamped = room()
        unstamped.stay("Me", from = 1000, count = min * 2)
        assertFalse(RoomHistory.ownClear(unstamped, self = "Me", topPlayer = "Me"))
    }

    /** Below the one-second bar the predicate refuses on its own, without help from the caller. */
    @Test
    fun `the presence floor is enforced by the gate itself`() {
        val room = room()
        room.stay("Me", from = 1000, count = min - 1)
        room.stay("Mate", from = 1000, count = min * 4)
        room.clearedAtTick = 1000 + min * 4

        assertFalse(RoomHistory.ownClear(room, self = "Me", topPlayer = "Me"))
    }

    /**
     * Defect A, reported by the user: *"wenn ich in einen Raum komme wo jemand secrets macht, und
     * ich nur 1 oder 2 mache bekomme ich trotzdem die PB gutgeschrieben"*. `onSecretRun` recorded
     * the run unconditionally with [TrackedRoom.ownSecrets] sitting one field away, so a party-wide
     * measurement was filed as a personal best.
     *
     * The user chose the strict end knowing what it costs: `ownSecrets` is counted conservatively —
     * an own click or the wither-essence chat line, nothing else — so party records become rare.
     */
    @Test
    fun `a secret run is yours only when every secret in it was`() {
        val all = room().also { it.secretsFound = 5; it.ownSecrets = 5 }
        assertTrue(RoomHistory.ownSecretRun(all))

        // One short is not "mostly yours", it is somebody else's run with your name on it.
        val almost = room().also { it.secretsFound = 5; it.ownSecrets = 4 }
        assertFalse(RoomHistory.ownSecretRun(almost))

        val theirs = room().also { it.secretsFound = 5; it.ownSecrets = 1 }
        assertFalse(RoomHistory.ownSecretRun(theirs))
    }

    /**
     * `0 == 0` is true and must not be the answer here. A room where nothing was ever counted has no
     * run to own, and a vacuous truth is exactly the shape of bug this whole feature is removing.
     */
    @Test
    fun `a room where nothing was counted owns nothing`() {
        assertFalse(RoomHistory.ownSecretRun(room()))
        assertFalse(RoomHistory.ownSecretRun(room().also { it.ownSecrets = 3 }))
    }
}
