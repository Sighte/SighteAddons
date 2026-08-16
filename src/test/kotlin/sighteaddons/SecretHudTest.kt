package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the live readout says for a given tracker state.
 *
 * This is the half of `secrethud-001` that can be verified without a game. The other half — that
 * [SighteAddons.renderHud] calls [SecretHud.line] with the room the player is standing in and the
 * rooms this run has visited, once per frame, behind `Config.showSecrets` — needs a live
 * `Minecraft` and is unverified here, exactly like every other wiring line in this repository.
 *
 * Nothing below drives [SecretTracker]: this feature does not decide *whether* a secret is yours,
 * it only displays the answer. Attribution is `ownsecrets-001` and is deliberately untouched.
 */
class SecretHudTest {
    /** A room with a database entry saying it holds [secrets], and [own] of them credited to us. */
    private fun room(secrets: Int, own: Int = 0) =
        TrackedRoom(RoomType.ROOM, emptySet(), emptySet()).also {
            it.info = RoomInfo(name = "Fixture", type = "NORMAL", shape = "1x1", secrets = secrets, crypts = 0)
            it.ownSecrets = own
        }

    /** A room the database does not know: identified by nothing, so its total is not a number. */
    private fun unknownRoom(own: Int = 0) =
        TrackedRoom(RoomType.ROOM, emptySet(), emptySet()).also { it.ownSecrets = own }

    @Test
    fun `two numbers - this room and the run`() {
        val here = room(secrets = 5, own = 2)
        val line = SecretHud.line(here, listOf(here, room(secrets = 3, own = 3), room(secrets = 4, own = 1)))

        assertEquals("Your secrets  2/5 room  ·  6 run", line)
    }

    /**
     * The room half is a *subset* of the run half, never an addition to it. `roomAt` and
     * `visitedRooms` read the same map, so the current room is already in the list — counting it
     * twice would make the run total climb every time the player stood still in a room they had
     * found something in.
     */
    @Test
    fun `the current room is not counted twice`() {
        val here = room(secrets = 5, own = 2)
        assertEquals("Your secrets  2/5 room  ·  2 run", SecretHud.line(here, listOf(here)))
    }

    /** Standing in no room at all, or in one the room database has no entry for. */
    @Test
    fun `an unknown room's total is dashes, not a zero`() {
        assertEquals("Your secrets  --/-- room  ·  0 run", SecretHud.line(null, emptyList()))

        // The run total still reads: it does not depend on where the player is standing.
        val unknown = unknownRoom(own = 1)
        assertEquals("Your secrets  --/-- room  ·  4 run", SecretHud.line(unknown, listOf(unknown, room(5, 3))))
    }

    /**
     * A room the database says has no secrets is `0/0`, and that is a different statement from
     * `--/--`. Fairy rooms and the entrance are the ordinary case, and a readout that spelled
     * "nothing to find here" the same way it spells "we could not identify this room" would make the
     * second look solved.
     */
    @Test
    fun `a room with no secrets is zero of zero`() {
        assertEquals("Your secrets  0/0 room  ·  0 run", SecretHud.line(room(secrets = 0), emptyList()))
    }

    /**
     * The line under-counts and must not pretend otherwise: a room whose five secrets were all found
     * by teammates reads `0/5`, not `5/5` and not a dash. That is the state `ownsecrets-001` exists
     * to improve, and this test is what would fail if somebody "fixed" the display instead — by
     * falling back to [TrackedRoom.secretsFound] when nothing was attributed, say, which would claim
     * somebody else's work for the local player.
     */
    @Test
    fun `secrets somebody else found are not claimed for you`() {
        val theirs = room(secrets = 5, own = 0).also { it.secretsFound = 5 }
        val line = SecretHud.line(theirs, listOf(theirs))

        assertEquals("Your secrets  0/5 room  ·  0 run", line)
        assertTrue(line.contains("0/5"), "the party's count must never stand in for the local player's")
    }
}
