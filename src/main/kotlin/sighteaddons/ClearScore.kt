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
 * naming *you*. So a teammate's secrets were worth nothing at all, and the standings compared your
 * clears-plus-secrets against their clears alone — asymmetric, in your favour, and nothing said so.
 *
 * ### Two passes, because two different things are knowable at two different times
 *
 * **Live, the party's own count is known and only its owner is not.** A room's action bar carries
 * `secretsFound` for the party; [ContributionTracker.TrackedRoom.ownSecrets] is the part this client can
 * prove was yours. The difference is a number of secrets that certainly *were* found and certainly were
 * not yours — so the count is right and only the name on it is a guess. [guessedSecretPoints] hands each
 * one to the other players who were in that room, split by the same tick shares the clear is split by,
 * which is the best available answer: somebody standing in a room for a minute is a likelier finder than
 * somebody who passed through it. The party total stays correct even while every individual is an
 * estimate.
 *
 * **After the run, the truth exists.** [SecretApi] takes each member's lifetime
 * `skyblock_treasure_hunter` before and after, and the rise is that player's real count for the run.
 * [settled] throws the guess away for everybody it has a real answer for and pays
 * [ContributionTracker.SECRET_POINTS] per actual secret. A player Hypixel did not answer for keeps the
 * estimate and is marked as one.
 *
 * The local player's own row is settled the same way, from the API rather than from attribution, and
 * that is deliberate: [SecretAudit] exists because the live tracker is known to *under*-count — a secret
 * walked over is credited to nobody — and where there is a measurement, an inference should not outrank
 * it. Which means the local player's final points can rise at the end of a run, and by exactly the
 * margin the audit line reports.
 *
 * ### Deliberately free of Minecraft and of state
 *
 * Maps in, list out, no mutation, no client. Every case worth checking — a secret found in a room the
 * finder is the only other person in, a room nobody but you was in, a player who never answers, a tie —
 * is checkable without a dungeon, which matters because all of it is invisible when it is wrong: a
 * standing is a plausible number whatever it is made of.
 */
object ClearScore {

    /**
     * One visited room, as scoring sees it: who was in it and for how long, and how many of its secrets
     * were found by somebody other than the local player.
     *
     * A projection of [ContributionTracker.TrackedRoom] rather than the room itself, so this file cannot
     * reach for anything else on it and cannot be pulled into the tracking layer's lifecycle. The caller
     * does the one subtraction, because the caller is the only place that knows both halves are readings
     * of the same room.
     */
    class Room(val ticks: Map<String, Int>, val secretsByOthers: Int)

    /** One row of the standings: what it says, and whether any of it is a guess. */
    class Row(val name: String, val points: Double, val estimated: Boolean)

    /**
     * The secrets somebody else found, spread over the people who could have found them.
     *
     * [self] is excluded from every split: their share of the room's secrets is not a guess but a
     * counted, proven figure, and adding an estimate on top of it would pay them twice for the same
     * finds. The exclusion happens before the split rather than after, so the whole of a room's
     * unattributed secrets goes to the other players instead of a fraction of it evaporating.
     *
     * The tick floor is [ContributionTracker.MIN_TICKS], falling back to raw presence — the same two
     * steps [ContributionTracker.award] takes with a room's clear, for the same reason: a room where
     * nobody stayed long still had somebody in it, and dropping the points on the floor makes the party
     * total wrong in order to keep a threshold.
     *
     * A room whose only occupant was [self] hands its unattributed secrets to nobody. That case is real
     * and is the honest answer: the count says a secret was found and attribution says it was not yours,
     * and if there was nobody else in the room then one of those two readings is wrong. Guessing a name
     * would not fix it, and the final pass replaces the whole figure anyway.
     */
    fun guessedSecretPoints(rooms: List<Room>, self: String?, minTicks: Int): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (room in rooms) {
            if (room.secretsByOthers <= 0) continue
            val others = if (self == null) room.ticks else room.ticks.filterKeys { it != self }
            if (others.isEmpty()) continue
            val worth = room.secretsByOthers * ContributionTracker.SECRET_POINTS
            val split = DungeonGrid.splitPoints(others, worth, minTicks)
                .ifEmpty { DungeonGrid.splitPoints(others, worth, minTicks = 1) }
            for ((name, points) in split) out.merge(name, points, Double::plus)
        }
        return out
    }

    /**
     * The standings as they stand mid-run: every player's clear points, plus their own proven secrets or
     * their guessed share of everybody else's.
     *
     * [own] and [guessed] cannot overlap — the first is only ever the local player and the second never
     * is — so they are added rather than chosen between, and a roster entry missing from all three maps
     * is a player at zero rather than a player who is absent.
     */
    fun live(
        roster: List<String>,
        clear: Map<String, Double>,
        own: Map<String, Double>,
        guessed: Map<String, Double>,
    ): List<Row> = roster.map { name ->
        Row(
            name,
            (clear[name] ?: 0.0) + (own[name] ?: 0.0) + (guessed[name] ?: 0.0),
            estimated = (guessed[name] ?: 0.0) > 0.0,
        )
    }.ordered()

    /**
     * The standings with [counts] — [SecretApi.delta]'s per-player truth — written in.
     *
     * An answered player's secret points are rebuilt from their real count, which discards both the
     * guess and, for the local player, the proven-but-incomplete attribution. An unanswered player keeps
     * whatever the live pass gave them and is still marked estimated, which for the local player means
     * not marked at all: their figure was never a guess.
     *
     * `counts` empty is the keyless path and returns exactly what [live] would, which is what makes this
     * safe to call unconditionally.
     */
    fun settled(
        roster: List<String>,
        clear: Map<String, Double>,
        own: Map<String, Double>,
        guessed: Map<String, Double>,
        counts: Map<String, Int>,
    ): List<Row> = roster.map { name ->
        val actual = counts[name]
        val secrets = if (actual != null) {
            actual * ContributionTracker.SECRET_POINTS
        } else {
            (own[name] ?: 0.0) + (guessed[name] ?: 0.0)
        }
        Row(
            name,
            (clear[name] ?: 0.0) + secrets,
            estimated = actual == null && (guessed[name] ?: 0.0) > 0.0,
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
