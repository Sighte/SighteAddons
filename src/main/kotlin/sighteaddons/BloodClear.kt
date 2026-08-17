package sighteaddons

/**
 * How long the blood room took, measured the way Odin measures it.
 *
 * **This is a run split, not a room clear, and the difference is the whole reason it exists.** The
 * ordinary [RoomHistory.CLEAR] metric is *your* ticks inside a room, which is the right question for
 * a room you clear yourself and the wrong one for the blood room: everybody fights the Watcher
 * together, so the number that means anything is how long the party was in there, and the number the
 * old metric produced was how long you personally stood in the room. Measured in this install's own
 * history before it was deleted: a 26-tick "record" — 1.3 seconds of standing in an M7 blood room
 * somebody else was clearing — against 774 ticks for an M1 that was genuinely fought. No honest
 * blood clear could ever have beaten that, so the room had a personal best it could never show again.
 *
 * Odin's `SplitsManager` builds the dungeon splits as a chain, and the elapsed time between two
 * consecutive entries is credited to the *earlier* one's name. Its `Blood Clear` therefore runs from
 * `BLOOD_OPEN_REGEX` to the Watcher's pass line, and this is that span:
 *
 *  - **start** — the first of [ChatEvents.Event.BloodDoor] and [ChatEvents.Event.BloodOpen]. Odin
 *    folds both into one alternation and takes whichever matches first; the door line and the
 *    Watcher's greeting are two sightings of the same moment and either can be the one that lands.
 *  - **end** — [ChatEvents.Event.BloodDone], `You have proven yourself. You may pass.`
 *
 * Counted in run ticks rather than wall-clock milliseconds, which is the one deliberate difference
 * from Odin: every other time this mod stores is in ticks, and a blood clear that could not be
 * compared against them would be a number in its own private unit.
 *
 * **No ownership gate**, unlike [RoomHistory.ownClear] and [RoomHistory.ownSecretRun]. That is not an
 * oversight in the rule those two enforce — it is what the rule is about. They gate a claim that
 * *you* did something; this measures what the *floor* did, the same way [TrackedRoom.secretRunTicks]
 * measures a room's secret run without asking whose hands took the secrets. There is nothing here to
 * misattribute, so there is nothing to gate.
 */
object BloodClear {

    /** Run tick the blood room opened, or null before it has. */
    private var openedAt: Int? = null

    /** Once per run: the Watcher can speak again, and a second pass line must not file a second record. */
    private var recorded = false

    /**
     * The blood room opened. [at] is the run tick.
     *
     * First signal wins. The door line and the greeting arrive within a tick or two of each other and
     * Odin's alternation keeps whichever it saw first (`if (currentSplit.time != 0L) return`), so
     * taking the later of the two would quietly shorten every blood clear by that gap.
     */
    fun onOpen(at: Int) {
        if (openedAt != null) return
        openedAt = at
        DebugLog.event("blood_open", "at" to at)
    }

    /** The Watcher let the party pass. [at] is the run tick. */
    fun onDone(at: Int) {
        if (recorded) return
        val opened = openedAt
        if (opened == null) {
            // Neither opening line arrived, so there is no span to measure. Logged rather than
            // guessed at from the room's own clock: a blood clear timed from the wrong start is worse
            // than one that is missing, because it becomes a record.
            DebugLog.event("blood_done_unopened", "at" to at)
            return
        }
        recorded = true

        val ticks = at - opened
        val room = ContributionTracker.visitedRooms().firstOrNull { it.type == RoomType.BLOOD }
        DebugLog.event(
            "blood_clear",
            "at" to at, "openedAt" to opened, "ticks" to ticks, "room" to room?.name,
        )
        // A non-positive span means the two lines arrived out of order or on the same tick, which
        // nothing here can turn into a time. The room is missing only if the map never showed a blood
        // room at all; both are diagnostics, not conditions to work around.
        if (ticks <= 0 || room == null) return

        RoomHistory.onBloodCleared(room, ticks)
    }

    /** Called from [DungeonSession.reset], so no run inherits the previous floor's blood room. */
    fun reset() {
        openedAt = null
        recorded = false
    }
}
