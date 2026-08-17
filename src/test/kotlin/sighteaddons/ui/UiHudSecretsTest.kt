package sighteaddons.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sighteaddons.RoomInfo
import sighteaddons.RoomType
import sighteaddons.TrackedRoom
import sighteaddons.ui.hud.HudSnapshot

/**
 * The one rule the HUD's secret counts are held to: they are **attributed**, never merely found.
 *
 * This is what `SecretHudTest` was. The class it drove, `SecretHud`, was the pre-redesign corner
 * readout and had not been called from anywhere in `src/main` since phase 3a moved the line into
 * `HudRoot` — only its own test still referred to it. Deleting it would have taken the rule with it,
 * and the rule is one CLAUDE.md names explicitly: falling back to [TrackedRoom.secretsFound] when
 * `ownSecrets` is zero writes the party's secrets onto the local player's line. So the assertions
 * moved to where the number is produced now, which is [HudSnapshot].
 *
 * Two functions rather than a rendered string, because that is all there is left to test:
 * `HudSnapshot.build` needs a live `Minecraft` and `HudRoot` needs a graphics buffer. Between them
 * sit these two, and a fallback would have to be written into one of them.
 */
class UiHudSecretsTest {

    /** A room the database knows, with [own] of its [secrets] credited to the local player. */
    private fun room(secrets: Int, own: Int = 0, found: Int = 0) =
        TrackedRoom(RoomType.ROOM, emptySet(), emptySet()).also {
            it.info = RoomInfo(name = "Fixture", type = "NORMAL", shape = "1x1", secrets = secrets, crypts = 0)
            it.ownSecrets = own
            it.secretsFound = found
        }

    /**
     * The room the player is standing in, and the run behind them.
     *
     * The run total is a plain sum over the rooms this run visited, which is where the "your secrets"
     * figure on the HUD's run totals comes from.
     */
    @Test
    fun `the room's count and the run's come from the same signal`() {
        val here = room(secrets = 5, own = 2)
        val visited = listOf(here, room(secrets = 3, own = 3), room(secrets = 4, own = 1))

        assertEquals(2, HudSnapshot.roomOwnSecrets(here))
        assertEquals(6, HudSnapshot.runOwnSecrets(visited))
    }

    /**
     * The room is a *subset* of the run, never an addition to it. `roomAt` and `visitedRooms` read the
     * same map, so the current room is already in the list — counting it twice would make the run total
     * climb every time the player stood still in a room they had found something in.
     */
    @Test
    fun `the current room is not counted twice`() {
        val here = room(secrets = 5, own = 2)
        assertEquals(2, HudSnapshot.runOwnSecrets(listOf(here)))
    }

    /** Standing in no room at all is zero attributed secrets, not an error and not the run's total. */
    @Test
    fun `no room under the player is zero, and the run still reads`() {
        assertEquals(0, HudSnapshot.roomOwnSecrets(null))
        assertEquals(3, HudSnapshot.runOwnSecrets(listOf(room(5, 3))))
        assertEquals(0, HudSnapshot.runOwnSecrets(emptyList()))
    }

    /**
     * The count under-counts and must not pretend otherwise: a room whose five secrets were all found
     * by teammates is `0` to the local player, not `5`.
     *
     * This is the test that fails if somebody "fixes" the readout instead — by falling back to
     * [TrackedRoom.secretsFound] when nothing was attributed, say, which would claim somebody else's
     * work for the local player and is exactly what CLAUDE.md forbids. Widening what counts as yours
     * is `ownsecrets-001`, and it happens in `SecretTracker`, not here.
     */
    @Test
    fun `secrets somebody else found are not claimed for you`() {
        val theirs = room(secrets = 5, own = 0, found = 5)

        assertEquals(0, HudSnapshot.roomOwnSecrets(theirs), "the party's count must never stand in")
        assertEquals(0, HudSnapshot.runOwnSecrets(listOf(theirs)))
    }
}
