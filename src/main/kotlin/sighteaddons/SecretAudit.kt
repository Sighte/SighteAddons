package sighteaddons

/**
 * What the live secret tracker claimed, held against what Hypixel says actually happened.
 *
 * ## Why this can exist now, and could not before
 *
 * [SecretTracker] attributes a secret to the local player from a coincidence: the room's action-bar
 * counter went up within `OWN_WINDOW` ticks of something this client did. That is an
 * inference, and until [SecretApi] there was no second source to check it against — the action bar
 * says *somebody* found one, never who, and no per-player figure exists anywhere the client can read.
 * The lifetime achievement gives one, twice a run, and the rise between the two readings is the
 * player's true count. So the estimate and the truth now sit side by side for the first time.
 *
 * **`ownsecrets-001` is the open question this answers.** Not one line of that attribution has ever
 * been checked against a known-good number; the suite covers the logic and nothing covers whether the
 * logic is right about a real floor. Every run with a key configured now writes a `secret_audit` line
 * into the debug log, and a handful of floors turn the question into a measurement.
 *
 * ## Which direction of error is which
 *
 * **Under-counting is the designed direction.** A bat killed from range is never credited
 * (`AttackEntityCallback` is a melee swing), a secret item outside `SECRET_ITEMS` is
 * not recognised, and a counter that rises while you are between rooms belongs to nobody. Every one
 * of those loses you a secret you found, and that is the safe way for this to be wrong.
 *
 * **Over-counting is a defect.** It means the tracker credited the local player with a secret a
 * teammate found — the exact failure `ownSecrets == secretsFound` and the HUD's no-fallback rule
 * exist to prevent — and it is the one outcome that should be read as a bug report rather than as a
 * measurement. Hence [Verdict.OVER] is its own answer and is stated in a colour that is not the
 * colour of the ordinary case.
 *
 * Pure, and holds no state: it takes the two numbers and says what they mean. The reading itself
 * belongs to [SecretApi] and the sentence to [RoomHistory.auditLine].
 */
object SecretAudit {

    enum class Verdict {
        /** The tracker and Hypixel agree exactly. */
        EXACT,

        /** The tracker credited fewer than Hypixel counted — the designed direction. */
        MISSED,

        /** The tracker credited more than Hypixel counted. A defect, not a measurement. */
        OVER,

        /** Hypixel answered for somebody, but not for the local player. Nothing to compare. */
        UNKNOWN,
    }

    /**
     * One run's comparison.
     *
     * [floorTracked] is [DungeonTab]'s party-wide reading and [floorActual] the sum of what the API
     * returned. They are only comparable when every party member answered — one missing player makes
     * the API sum short by however much they found — so [complete] is what says whether that half of
     * the audit means anything, and nothing here compares them on its own.
     */
    class Result(
        /** What the live tracker credited the local player: the run's `ownSecrets`. */
        val tracked: Int,
        /** What Hypixel says the local player found, or null if it did not answer for them. */
        val actual: Int?,
        /** [DungeonTab.secretsFound], the floor's own party-wide total, or null if never read. */
        val floorTracked: Int?,
        /** The API's party-wide total, which is only the players it answered for. */
        val floorActual: Int,
        /** How many players the API answered for, of how many were asked. */
        val answered: Int,
        val asked: Int,
    ) {

        /** Signed: positive is over-credited, negative is missed. Null when there is no truth to use. */
        val delta: Int? get() = actual?.let { tracked - it }

        /** Whether every player asked about came back, which is what makes the floor totals comparable. */
        val complete: Boolean get() = answered == asked && asked > 0

        val verdict: Verdict
            get() = when {
                actual == null -> Verdict.UNKNOWN
                tracked == actual -> Verdict.EXACT
                tracked < actual -> Verdict.MISSED
                else -> Verdict.OVER
            }
    }

    /**
     * [counts] is what [SecretApi.settle] returned; [tracked] and [floorTracked] are the client's own
     * numbers, captured at the moment the summary was printed rather than read here — by the time this
     * runs the player may already be in the next floor, which is the same reason
     * [RoomHistory.printSummary] captures its estimate before the call.
     */
    fun of(
        tracked: Int,
        counts: Map<String, Int>,
        self: String?,
        floorTracked: Int?,
        asked: Int,
    ) = Result(
        tracked = tracked,
        actual = self?.let { counts[it] },
        floorTracked = floorTracked,
        floorActual = counts.values.sum(),
        answered = counts.size,
        asked = asked,
    )
}
