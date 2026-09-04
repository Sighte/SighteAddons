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
 * - **no player is privileged**, because the client that privileges its own player computes a table
 *   nobody else in the party can reproduce — the defect this file's design exists to prevent
 * - a room's secrets are handed out whole, so the party total stays right while every individual is an
 *   estimate
 * - a real count from Hypixel replaces the estimate rather than adding to it
 * - a player Hypixel never answered for keeps the estimate and is *marked*, because an estimate that
 *   looks like a measurement is worse than no estimate
 */
class ClearScoreTest {

    private val min = ContributionTracker.MIN_TICKS
    private val quarter = ContributionTracker.SECRET_POINTS

    /**
     * The estimate: the right total, split over everybody who was in the room, by time.
     *
     * The alone case is the one that changed meaning. It used to hand its secrets to nobody, because
     * the local player was excluded from the split and there was no one else — a quarter per find
     * quietly leaving the party total. It now pays the one person who was there, which is both the
     * only available answer and the true one often enough: attribution is a floor, not a total.
     */
    @Test
    fun `a room's secrets go to everybody who was in it, by time`() {
        val room = ClearScore.Room(mapOf("Me" to 6 * min, "A" to 3 * min, "B" to min), secretsFound = 3)
        val split = ClearScore.secretPoints(listOf(room), min)

        assertEquals(3 * quarter, split.values.sum(), 1e-9, "the party's count is known: all of it is handed out")
        assertEquals(0.45, split["Me"]!!, 1e-9, "six of the room's ten player-units")
        assertEquals(0.225, split["A"]!!, 1e-9)
        assertEquals(0.075, split["B"]!!, 1e-9)

        // A room only one player was in pays that player, rather than dropping the find on the floor.
        val alone = ClearScore.secretPoints(
            listOf(ClearScore.Room(mapOf("Me" to 10 * min), secretsFound = 2)), min,
        )
        assertEquals(2 * quarter, alone["Me"]!!, 1e-9)

        // The tick floor still filters: somebody who walked through while somebody else worked gets
        // nothing, and the whole quarter goes to the one who stayed.
        val brief = ClearScore.secretPoints(
            listOf(ClearScore.Room(mapOf("Me" to 10 * min, "A" to 1), secretsFound = 1)), min,
        )
        assertNull(brief["A"], "a walk-through is not a find")
        assertEquals(quarter, brief["Me"]!!, 1e-9)

        // Nobody past the floor still gets the points, on `award`'s own fallback: a room somebody ran
        // through was still a room somebody was in, and dropping the quarter would make the party total
        // wrong in order to keep a threshold.
        val fleeting = ClearScore.secretPoints(
            listOf(ClearScore.Room(mapOf("Me" to 1, "A" to 1), secretsFound = 1)), min,
        )
        assertEquals(quarter, fleeting.values.sum(), 1e-9)
        assertEquals(0.125, fleeting["Me"]!!, 1e-9)

        // A room with no secrets found in it, and a room this client never saw anybody in, both
        // contribute nothing. The second has no answer that could be invented for it.
        assertTrue(
            ClearScore.secretPoints(
                listOf(ClearScore.Room(mapOf("Me" to min, "A" to min), secretsFound = 0)), min,
            ).isEmpty(),
        )
        assertTrue(ClearScore.secretPoints(listOf(ClearScore.Room(emptyMap(), secretsFound = 3)), min).isEmpty())
    }

    /**
     * **The regression guard for the whole change.** Equal time in a room is equal points, whoever the
     * two players are — there is no seat at the table that pays better than another.
     *
     * This is what makes two copies of the mod in one party agree. The previous split paid the local
     * player their proven finds and handed the remainder to everybody else, so the same room produced
     * one table on your screen and a different one on your teammate's, and neither was wrong. Any
     * re-introduction of a `self` argument here fails this case.
     */
    @Test
    fun `two players with the same time in a room get the same points`() {
        val split = ClearScore.secretPoints(
            listOf(ClearScore.Room(mapOf("Me" to 5 * min, "Mate" to 5 * min), secretsFound = 4)), min,
        )
        assertEquals(split["Mate"]!!, split["Me"]!!, 1e-9, "no player is privileged by who is computing")
        assertEquals(0.5, split["Me"]!!, 1e-9)
    }

    /**
     * The correction: a real count replaces the estimate, for everybody it exists for.
     *
     * The local player's own row is settled from the API too, and that is the case worth stating —
     * their attributed count is a floor rather than a total, which is what [SecretAudit] exists to
     * measure, so a measurement has to outrank the inference here as well.
     */
    @Test
    fun `a real count replaces the estimate, and an unanswered player keeps it and is marked`() {
        val roster = listOf("Me", "A", "B")
        val clear = mapOf("Me" to 4.0, "A" to 2.0, "B" to 1.0)
        val secrets = mapOf("Me" to 2 * quarter, "A" to 3 * quarter, "B" to quarter)

        val live = ClearScore.live(roster, clear, secrets)
        assertEquals(listOf("Me", "A", "B"), live.map { it.name }, "highest first")
        assertEquals(4.5, live.row("Me").points, 1e-9, "clear plus this player's share of the secrets")
        assertTrue(live.row("Me").estimated, "live, the local player's figure is an estimate like every other")
        assertTrue(live.row("A").estimated)

        // Hypixel answers for you and for A, and not for B. Your real count is three — one more than
        // the split gave you — and A's is one, against an estimate of three.
        val settled = ClearScore.settled(roster, clear, secrets, mapOf("Me" to 3, "A" to 1))
        assertEquals(4.0 + 3 * quarter, settled.row("Me").points, 1e-9, "the measurement, not the estimate")
        assertFalse(settled.row("Me").estimated)
        assertEquals(2.0 + quarter, settled.row("A").points, 1e-9, "the estimate of 0.75 is discarded, not added to")
        assertFalse(settled.row("A").estimated)
        assertEquals(1.0 + quarter, settled.row("B").points, 1e-9, "no answer, so the estimate stands")
        assertTrue(settled.row("B").estimated, "and says so — this is where the mark separates two kinds of row")

        // The keyless path: nothing to settle with is exactly the live pass, which is what makes the
        // callback safe to run unconditionally.
        assertEquals(
            live.map { it.name to it.points },
            ClearScore.settled(roster, clear, secrets, emptyMap()).map { it.name to it.points },
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
        )
        assertEquals(listOf("Bob", "Ann", "Zoe", "Cal"), rows.map { it.name })
        assertEquals(0.0, rows.row("Cal").points, 1e-9, "in the roster, in the table, at zero")
        assertFalse(rows.row("Cal").estimated, "nothing estimated about a zero")
    }

    private fun List<ClearScore.Row>.row(name: String): ClearScore.Row = first { it.name == name }
}
