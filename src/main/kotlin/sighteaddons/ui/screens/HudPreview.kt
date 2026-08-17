package sighteaddons.ui.screens

import sighteaddons.ui.Format
import sighteaddons.ui.hud.HudSnapshot

/**
 * A scripted run, for looking at the HUD without one.
 *
 * `runClient` has no valid session and cannot reach Hypixel, so every event the HUD animates — a room
 * transition, a secret picked up, a personal best set, a personal best missed — is unreachable in a
 * development client. Without this they could only ever be reviewed by playing a real M7 with an
 * unreleased build, which is the opposite of how a UI should be checked.
 *
 * The sequence deliberately includes a *missed* record as well as a beaten one. The rule that positive
 * events earn animation and negative events are stated quietly is only verifiable if both are on
 * screen in the same loop.
 *
 * Times are derived from the elapsed clock rather than stored, so the preview is smooth at any frame
 * rate for the same reason the real HUD is.
 */
internal object HudPreview {

    /** One full pass through the script. */
    const val CYCLE_MS = 18_000.0

    private const val TICKS_PER_MS = 20.0 / 1000.0

    // The script, in milliseconds.
    private const val FIRST_ROOM_AT = 0.0
    private const val FIRST_SECRET_AT = 2_500.0
    private const val SECRET_EVERY = 700.0
    private const val FIRST_CLEAR_AT = 8_000.0
    private const val SECOND_ROOM_AT = 9_000.0

    private const val WATER_BOARD_BEST = 900        // 0:45.0
    private const val CATWALK_BEST = 300            // 0:15.0

    fun at(elapsedMs: Double): HudSnapshot {
        val ms = elapsedMs % CYCLE_MS
        val runTicks = (ms * TICKS_PER_MS).toInt()

        return if (ms < SECOND_ROOM_AT) firstRoom(ms, runTicks) else secondRoom(ms, runTicks)
    }

    /** A puzzle being solved, secrets arriving one at a time, ending in a record. */
    private fun firstRoom(ms: Double, runTicks: Int): HudSnapshot {
        val inRoom = ((ms - FIRST_ROOM_AT) * TICKS_PER_MS).toInt()
        val found = if (ms < FIRST_SECRET_AT) 0 else {
            (((ms - FIRST_SECRET_AT) / SECRET_EVERY).toInt() + 1).coerceAtMost(6)
        }
        val secretRun = if (found == 0) Format.NONE else ((ms - FIRST_SECRET_AT) * TICKS_PER_MS).toInt()

        return HudSnapshot(
            inDungeon = true,
            floor = "M7",
            runTicks = runTicks,
            roomsCleared = 12,
            roomName = "Water Board",
            roomType = "PUZZLE",
            roomTicks = inRoom,
            roomClearedAt = if (ms >= FIRST_CLEAR_AT) runTicks else Format.NONE,
            secretsFound = found,
            secretsTotal = 6,
            ownSecrets = (found + 1) / 2,
            secretRunTicks = secretRun,
            // Beating this one is what triggers the shimmer when the row lands in history below.
            clearBest = WATER_BOARD_BEST,
            secretBest = 260,
            runOwnSecrets = 14 + (found + 1) / 2,
            idleTicks = 240,
            navTicks = 1_340,
            history = arrayOf(
                HudSnapshot.Row("Tic Tac Toe", 456, 900, false),
                HudSnapshot.Row("Nordwand", 620, 700, false),
            ),
            standings = STANDINGS,
        )
    }

    /**
     * An ordinary room that is running slower than its record.
     *
     * The point of the second half: the delta reads `+`, the chevron points down, and nothing moves.
     */
    private fun secondRoom(ms: Double, runTicks: Int): HudSnapshot {
        val inRoom = ((ms - SECOND_ROOM_AT) * TICKS_PER_MS).toInt()
        val clearTicks = ((FIRST_CLEAR_AT - FIRST_ROOM_AT) * TICKS_PER_MS).toInt()

        return HudSnapshot(
            inDungeon = true,
            floor = "M7",
            runTicks = runTicks,
            roomsCleared = 13,
            roomName = "Catwalk",
            roomType = "NORMAL",
            roomTicks = inRoom,
            roomClearedAt = Format.NONE,
            secretsFound = 1,
            secretsTotal = 4,
            ownSecrets = 1,
            secretRunTicks = Format.NONE,
            clearBest = CATWALK_BEST,
            secretBest = Format.NONE,
            runOwnSecrets = 17,
            idleTicks = 300,
            navTicks = 1_520,
            history = arrayOf(
                // The room just finished, and it beat its record — this is the row that shimmers.
                HudSnapshot.Row("Water Board", clearTicks, runTicks, true),
                HudSnapshot.Row("Tic Tac Toe", 456, 900, false),
                HudSnapshot.Row("Nordwand", 620, 700, false),
            ),
            standings = STANDINGS,
        )
    }

    private val STANDINGS = arrayOf(
        HudSnapshot.Standing("Sighte", 8.50, Format.NONE),
        HudSnapshot.Standing("Nordwand", 6.00, Format.NONE),
        HudSnapshot.Standing("Tanksalot", 2.25, Format.NONE),
    )
}
