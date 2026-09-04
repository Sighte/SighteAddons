package sighteaddons

/**
 * The standings, in two passes: a live estimate while the floor runs, and the true figure once the run
 * is over and Hypixel has been asked.
 *
 * ### The problem this solves
 *
 * A player's score has two halves. The clear half is known exactly and immediately —
 * [ContributionTracker.award] splits a room's weight across the members who were in it by the ticks each
 * spent there, and that is a fact this client can see. The secret half is not: attribution only works
 * for the local player, because the two signals it runs on are *your* interaction window and a chat line
 * naming *you*.
 *
 * ### Why the live pass treats everybody the same
 *
 * It used to pay the local player their *proven* secrets ([ContributionTracker.onOwnSecret], a quarter
 * per attributed find) and hand only the remainder to the others as a guess. That is the more accurate
 * answer for one row of the table and the wrong shape for the table, for a reason that only shows up
 * with two copies of this mod in one party: **each client proved a different player.** On your screen
 * you were counted and your teammate estimated; on theirs, the reverse. Two people looking at the same
 * run saw two different sets of numbers, and neither was wrong — the asymmetry was in the method, not
 * in the measurement, so nothing about it could ever converge or be reconciled.
 *
 * So [secretPoints] gives a room's whole secret count to everybody who was in it, split by the same
 * tick shares the clear is split by, with no player singled out. The result is a **pure function of
 * `(room.ticks, room.secretsFound)`** — two clients that observed the same rooms compute the same
 * standings, and where they still differ, the difference is something they genuinely saw differently
 * rather than an artefact of which seat they were in.
 *
 * **What it costs is the local player's own certainty, and the table says so.** Their row is now an
 * estimate like every other and carries the same mark. That is the honest reading: a number built by
 * splitting a party's finds over a party is an estimate whoever it belongs to, and one row that is
 * exact among four that are not cannot be told apart from four that are — the mark is what makes the
 * distinction legible, and it was previously spent on saying "everyone but me".
 *
 * **This does not make two clients agree, it only stops them from disagreeing by construction.**
 * `room.ticks` comes from map decorations whose teammate identities are assigned by order,
 * [ContributionTracker.weightOf] depends on a scores document resolved once per launch, and a room
 * only one client ever entered has `secretsFound` on that client alone. Those are measurement
 * differences and they remain.
 *
 * **A room only the local player was in now pays them** instead of dropping its secrets on the floor,
 * which is the one accuracy change that runs in the player's favour. Under the old split those finds
 * had nowhere to go: attribution said they were not yours and there was nobody else to hand them to.
 * That reading was never evidence of a missing teammate — [SecretAudit] exists because attribution is
 * known to *under*-count, a secret walked over being credited to nobody — so the count was right and
 * only the name on it was missing.
 *
 * ### After the run, the truth exists
 *
 * [SecretApi] takes each member's lifetime `skyblock_treasure_hunter` before and after, and the rise is
 * that player's real count for the run. [settled] throws the estimate away for everybody it has a real
 * answer for and pays [ContributionTracker.SECRET_POINTS] per actual secret. A player Hypixel did not
 * answer for keeps the estimate and is marked as one — which is where the mark earns its keep, because
 * there the two kinds of row really do sit side by side.
 *
 * ### Deliberately free of Minecraft and of state
 *
 * Maps in, list out, no mutation, no client. Every case worth checking — a room nobody but one player
 * was in, a room nobody stayed in long, a player who never answers, a tie — is checkable without a
 * dungeon, which matters because all of it is invisible when it is wrong: a standing is a plausible
 * number whatever it is made of.
 */
object ClearScore {

    /**
     * One visited room, as scoring sees it: who was in it and for how long, and how many secrets were
     * found in it.
     *
     * [secretsFound] is the party-wide count this client read off the action bar, not a per-player
     * figure and not a remainder — nothing is subtracted from it before it gets here. A projection of
     * [TrackedRoom] rather than the room itself, so this file cannot reach for anything else on it and
     * cannot be pulled into the tracking layer's lifecycle.
     */
    class Room(val ticks: Map<String, Int>, val secretsFound: Int)

    /** One row of the standings: what it says, and whether any of it is a guess. */
    class Row(val name: String, val points: Double, val estimated: Boolean)

    /**
     * Every room's secrets, spread over the people who were in it to find them.
     *
     * Nobody is excluded and nobody is singled out — see the file header for why the local player used
     * to be, and what having them be cost. Somebody standing in a room for a minute is a likelier
     * finder than somebody who passed through it, so the tick shares are the best available answer;
     * the party total per room stays exactly `secretsFound * SECRET_POINTS` while every individual in
     * it is an estimate.
     *
     * The tick floor is [ContributionTracker.MIN_TICKS], falling back to raw presence — the same two
     * steps [ContributionTracker.award] takes with a room's clear, for the same reason: a room where
     * nobody stayed long still had somebody in it, and dropping the points on the floor makes the party
     * total wrong in order to keep a threshold.
     *
     * A room this client never saw anybody in pays nobody, and there is no third step that could fix
     * that: the room's secrets were found by someone this client cannot name.
     */
    fun secretPoints(rooms: List<Room>, minTicks: Int): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (room in rooms) {
            if (room.secretsFound <= 0) continue
            val worth = room.secretsFound * ContributionTracker.SECRET_POINTS
            val split = DungeonGrid.splitPoints(room.ticks, worth, minTicks)
                .ifEmpty { DungeonGrid.splitPoints(room.ticks, worth, minTicks = 1) }
            for ((name, points) in split) out.merge(name, points, Double::plus)
        }
        return out
    }

    /**
     * The standings as they stand mid-run: every player's clear points plus their share of the secrets
     * found in the rooms they were in.
     *
     * A roster entry missing from both maps is a player at zero rather than a player who is absent —
     * an early floor where somebody has not reached a room yet is ordinary, and a table that drops
     * them would be a table whose length changes.
     */
    fun live(
        roster: List<String>,
        clear: Map<String, Double>,
        secrets: Map<String, Double>,
    ): List<Row> = roster.map { name ->
        Row(
            name,
            (clear[name] ?: 0.0) + (secrets[name] ?: 0.0),
            estimated = (secrets[name] ?: 0.0) > 0.0,
        )
    }.ordered()

    /**
     * The standings with [counts] — [SecretApi.delta]'s per-player truth — written in.
     *
     * An answered player's secret points are rebuilt from their real count, which discards the
     * estimate rather than adding to it. An unanswered player keeps whatever the live pass gave them
     * and is still marked. That marking is the whole of the mark's job now: live, every row with
     * secrets in it carries one and it separates nothing, but here the answered and the unanswered sit
     * in one table and the difference between a measurement and an inference is exactly what a reader
     * needs to see.
     *
     * `counts` empty is the keyless path and returns exactly what [live] would, which is what makes
     * this safe to call unconditionally.
     */
    fun settled(
        roster: List<String>,
        clear: Map<String, Double>,
        secrets: Map<String, Double>,
        counts: Map<String, Int>,
    ): List<Row> = roster.map { name ->
        val actual = counts[name]
        val secretHalf = if (actual != null) {
            actual * ContributionTracker.SECRET_POINTS
        } else {
            secrets[name] ?: 0.0
        }
        Row(
            name,
            (clear[name] ?: 0.0) + secretHalf,
            estimated = actual == null && (secrets[name] ?: 0.0) > 0.0,
        )
    }.ordered()

    /**
     * Highest first, and by name where two are equal.
     *
     * The tiebreak is not cosmetic. Two players on the same score is ordinary — an early floor where
     * everybody has cleared one room — and an order that depends on the roster's iteration would make
     * the panel swap two rows between frames for no reason a reader can see.
     */
    private fun List<Row>.ordered(): List<Row> = sortedWith(
        compareByDescending<Row> { it.points }.thenBy { it.name },
    )
}
