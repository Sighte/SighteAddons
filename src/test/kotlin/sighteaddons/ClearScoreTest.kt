package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two-pass standings, which are arithmetic about people and therefore invisible when wrong.
 *
 * A score is a plausible number whatever it is made of. Nobody looking at `2.75` next to a name can
 * tell whether the secrets in it were counted, guessed, double-counted, or handed to the wrong player —
 * so the things that must not happen are pinned here rather than reasoned about:
 *
 * - the local player never receives a *guess* on top of their counted secrets, which would pay them twice
 * - a room's unattributed secrets are handed out whole, so the party total stays right while every
 *   individual is an estimate
 * - a real count from Hypixel replaces the guess rather than adding to it, for teammates and for the
 *   local player alike
 * - a player Hypixel never answered for keeps the estimate and is *marked*, because an estimate that
 *   looks like a measurement is worse than no estimate
 */
class ClearScoreTest {

    private val min = ContributionTracker.MIN_TICKS
    private val quarter = ContributionTracker.SECRET_POINTS

    /**
     * The guess: the right total, split over the people who could have found them, and never over the
     * one person whose count is already known.
     */
    @Test
    fun `somebody else's secrets go to the others, by time, and never to you`() {
        // You were in the room longest; three secrets were found that attribution says were not yours.
        val room = ClearScore.Room(mapOf("Me" to 100 * min, "A" to 3 * min, "B" to min), secretsByOthers = 3)
        val guess = ClearScore.guessedSecretPoints(listOf(room), self = "Me", minTicks = min)

        assertNull(guess["Me"], "your own secrets are counted, so a guess on top would pay you twice")
        assertEquals(3 * quarter, guess.values.sum(), 1e-9, "the party's count is known: all of it is handed out")
        assertEquals(0.5625, guess["A"]!!, 1e-9, "three quarters of the other players' time")
        assertEquals(0.1875, guess["B"]!!, 1e-9)

        // A room only you were in has nobody to hand them to. That reading means one of the two numbers
        // behind it is wrong, and inventing a recipient would not make it right.
        val alone = ClearScore.guessedSecretPoints(
            listOf(ClearScore.Room(mapOf("Me" to 10 * min), secretsByOthers = 2)), "Me", min,
        )
        assertTrue(alone.isEmpty())

        // Nobody past the tick floor still gets the points, on `award`'s own fallback: a room somebody
        // ran through was still a room somebody was in, and dropping the quarter would make the party
        // total wrong in order to keep a threshold.
        val brief = ClearScore.guessedSecretPoints(
            listOf(ClearScore.Room(mapOf("Me" to 10 * min, "A" to 1), secretsByOthers = 1)), "Me", min,
        )
        assertEquals(quarter, brief["A"]!!, 1e-9)

        // And a room whose secrets were all yours contributes nothing at all.
        assertTrue(
            ClearScore.guessedSecretPoints(
                listOf(ClearScore.Room(mapOf("Me" to min, "A" to min), secretsByOthers = 0)), "Me", min,
            ).isEmpty(),
        )
    }

    /**
     * The correction: a real count replaces the estimate, for everybody it exists for.
     *
     * The local player's own row is settled from the API too, and that is the case worth stating — their
     * attributed count is a floor rather than a total, which is what [SecretAudit] exists to measure, so
     * a measurement has to outrank the inference here as well.
     */
    @Test
    fun `a real count replaces the guess, and an unanswered player keeps it and is marked`() {
        val roster = listOf("Me", "A", "B")
        val clear = mapOf("Me" to 4.0, "A" to 2.0, "B" to 1.0)
        val own = mapOf("Me" to 2 * quarter)
        val guessed = mapOf("A" to 3 * quarter, "B" to quarter)

        val live = ClearScore.live(roster, clear, own, guessed)
        assertEquals(listOf("Me", "A", "B"), live.map { it.name }, "highest first")
        assertEquals(4.5, live.row("Me").points, 1e-9, "clear plus your two counted secrets")
        assertFalse(live.row("Me").estimated, "your own figure is not a guess")
        assertTrue(live.row("A").estimated, "a teammate's is")

        // Hypixel answers for you and for A, and not for B. Your real count is three — one more than
        // attribution could prove — and A's is one, against a guess of three.
        val settled = ClearScore.settled(roster, clear, own, guessed, mapOf("Me" to 3, "A" to 1))
        assertEquals(4.0 + 3 * quarter, settled.row("Me").points, 1e-9, "the measurement, not the floor")
        assertFalse(settled.row("Me").estimated)
        assertEquals(2.0 + quarter, settled.row("A").points, 1e-9, "the guess of 0.75 is discarded, not added to")
        assertFalse(settled.row("A").estimated)
        assertEquals(1.0 + quarter, settled.row("B").points, 1e-9, "no answer, so the estimate stands")
        assertTrue(settled.row("B").estimated, "and says so")

        // The keyless path: nothing to settle with is exactly the live pass, which is what makes the
        // callback safe to run unconditionally.
        assertEquals(
            live.map { it.name to it.points },
            ClearScore.settled(roster, clear, own, guessed, emptyMap()).map { it.name to it.points },
        )
    }

    /**
     * Order, including the tie — a whole party on one point each is an ordinary early floor, and rows
     * that swap places between frames for no visible reason are a defect a reader cannot diagnose.
     */
    @Test
    fun `the order is by points and then by name, and a silent roster member is a zero`() {
        val rows = ClearScore.live(
            listOf("Zoe", "Ann", "Bob", "Cal"),
            mapOf("Zoe" to 1.0, "Ann" to 1.0, "Bob" to 2.0),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(listOf("Bob", "Ann", "Zoe", "Cal"), rows.map { it.name })
        assertEquals(0.0, rows.row("Cal").points, 1e-9, "in the roster, in the table, at zero")
        assertFalse(rows.row("Cal").estimated)
    }

    private fun List<ClearScore.Row>.row(name: String): ClearScore.Row = first { it.name == name }
}
