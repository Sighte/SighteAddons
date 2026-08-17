package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What Storm's timer decides, for lines and tick counts given to it directly.
 *
 * This is the half of `stormtimer-001` that can be verified without a game: which lines start a
 * countdown, what is on screen after N ticks, where `SHOOT NOW` begins and ends, and what the `/sa`
 * rows do to the two tick counts. The other half — that Fabric delivers Storm's line to
 * `SighteAddons.onChat`, that `client.level.gameTime` is advancing while it does, and that
 * `renderHud` is reached during a boss phase — needs a live `Minecraft` in an M7.
 *
 * **Two things below are pinned as behaviour and are not claims about Hypixel.** The strings came out
 * of a decompiled third-party mod; nothing in `docs/evidence/` or the twenty session logs on disk
 * contains "Storm", because no build has ever looked. And 138/20 are that mod's unexplained
 * constants. So these cases pin what the code does with the assumed shape, `StormTimer.nearMiss` is
 * what will say whether the shape was right, and the tick counts are settings precisely because no
 * test here can be evidence for them.
 */
class StormTimerTest {
    /** [StormTimer.startTick] is object state, so each case starts with no countdown running. */
    @BeforeEach
    fun stopTimer() = StormTimer.reset()

    private val ENERGY = "[BOSS] Storm: ENERGY HEED MY CALL!"
    private val THUNDER = "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!"

    /**
     * Both of Storm's lines start a countdown, and the anchoring every chat pattern here rests on:
     * Hypixel puts a name in front of anything a player typed, so a teammate pasting the line arrives
     * behind their own and cannot start your clock mid-fight.
     */
    @Test
    fun `either of Storm's two lines starts the clock, and nobody else can`() {
        assertTrue(StormTimer.onChat(ENERGY) { 1000L })
        assertEquals(1000L, StormTimer.startTick)

        assertTrue(StormTimer.onChat(THUNDER) { 2000L })
        assertEquals(2000L, StormTimer.startTick, "the second cast restarts it rather than being ignored")

        assertFalse(StormTimer.onChat("Party > [MVP+] Bob: $ENERGY") { 3000L })
        assertEquals(2000L, StormTimer.startTick)
    }

    /**
     * No world time is no countdown. Starting one from an invented origin would put a clock on screen
     * that is indistinguishable from a correct one and wrong by however long the level had been
     * loaded.
     */
    @Test
    fun `a trigger with no world time does not start a countdown`() {
        assertFalse(StormTimer.onChat(ENERGY) { null })
        assertNull(StormTimer.startTick)
    }

    /**
     * The arithmetic, which is the whole feature and the only part of it a game is not needed for.
     *
     * Boundaries both ways round: the last countdown frame still counts down, the tick the countdown
     * expires on is already `SHOOT NOW`, the last frame of the hold is still `SHOOT NOW`, and the one
     * after it draws nothing at all — which is where the source mod called `stop()`.
     */
    @Test
    fun `the countdown runs, then SHOOT NOW holds, then nothing`() {
        fun at(elapsed: Long) = StormTimer.readout(elapsed, 138, 20)?.text

        assertEquals("Storm  6.9s", at(0))
        assertEquals("Storm  0.1s", at(137), "the last countdown frame still counts down")
        assertEquals("SHOOT NOW", at(138), "the tick it expires on is already the window")
        assertEquals("SHOOT NOW", at(157), "the last frame of the hold")
        assertNull(at(158), "one tick later the timer has stopped")
        assertNull(at(10_000))
    }

    /**
     * Three seconds and one second, the source mod's boundaries, expressed in ticks so they land
     * exactly. A countdown that never escalated would look completely correct and be useless at the
     * only moment it matters.
     *
     * The step is a named state and no longer an ARGB value: this UI is monochrome, so which four
     * things four states look like is `StormHud`'s decision and not this file's. Pinned as the four
     * constants rather than as "four distinct values", because with an enum the latter is true by
     * construction and would have stopped being a test.
     */
    @Test
    fun `the urgency steps up at three seconds and at one`() {
        fun urgencyAt(elapsed: Long) = StormTimer.readout(elapsed, 138, 20)?.urgency

        assertEquals(StormTimer.Urgency.CALM, urgencyAt(0))
        assertEquals(StormTimer.Urgency.CLOSING, urgencyAt(138 - 60))
        assertEquals(StormTimer.Urgency.IMMINENT, urgencyAt(138 - 20))
        assertEquals(StormTimer.Urgency.NOW, urgencyAt(138))

        assertEquals(StormTimer.Urgency.CALM, urgencyAt(138 - 61), "just above three seconds is still calm")
        assertEquals(StormTimer.Urgency.CLOSING, urgencyAt(138 - 21), "just above one second has not stepped yet")
    }

    /**
     * Both tick counts are honoured rather than being the defaults in disguise. This is the case that
     * makes the settings real: if the timer ignored them, a user correcting an inherited number would
     * see nothing change and have no way to tell that from the number having been right.
     */
    @Test
    fun `the two tick counts are what actually drive it`() {
        assertEquals("Storm  5.0s", StormTimer.readout(0, 100, 20)?.text)
        assertEquals("SHOOT NOW", StormTimer.readout(100, 100, 20)?.text)
        assertNull(StormTimer.readout(100, 100, 0)?.text, "a zero window never shows SHOOT NOW")
        assertEquals("SHOOT NOW", StormTimer.readout(139, 100, 40)?.text, "a longer window holds longer")
    }

    /**
     * Nothing running and a world time that went backwards are both nothing on screen. The second is
     * a level swap under a live countdown; a negative elapsed count would otherwise format as a
     * growing positive number and read as a timer counting *up*.
     */
    @Test
    fun `no countdown and a world time that went backwards both draw nothing`() {
        assertNull(StormTimer.readout(null, 138, 20))
        assertNull(StormTimer.readout(-1, 138, 20))
        assertNull(StormTimer.readoutAt(5000L), "nothing was ever started")
    }

    /**
     * A countdown must not survive into the next floor, so it is shut where the run ends.
     *
     * This drives `DungeonSession.reset()` and not [StormTimer.reset] — one of the few wiring lines
     * in this repository a test can reach, since that function only mutates object state. Deleting
     * the `StormTimer.reset()` call from it is what this fails on.
     */
    @Test
    fun `the countdown does not survive the run it started in`() {
        assertTrue(StormTimer.onChat(ENERGY) { 1000L })
        assertNotNull(StormTimer.readoutAt(1000L))

        DungeonSession.reset()

        assertNull(StormTimer.startTick)
        assertNull(StormTimer.readoutAt(1000L))
    }

    /**
     * The instrumentation, and the only thing that will ever confirm either string above.
     *
     * Every Storm line is reported and not just ones that look close, because the wording is exactly
     * what is in doubt — a filter narrow enough to catch only near-misses would be built out of the
     * assumption being tested.
     */
    @Test
    fun `a Storm line that is not a trigger is reported, and a trigger is not`() {
        val other = "[BOSS] Storm: I SHALL NOT BE DEFEATED!"

        assertEquals(other, StormTimer.nearMiss(other))
        assertNull(StormTimer.nearMiss(ENERGY))
        assertNull(StormTimer.nearMiss(THUNDER))
        assertNull(StormTimer.nearMiss("Party > [MVP+] Bob: storm incoming"))
    }

    /**
     * The `/sa` rows. A wrap and not a clamp: a value parked at an end with no way back would be a
     * setting the player can break by clicking, and these two exist precisely so a wrong inherited
     * number can be walked to the right one.
     */
    @Test
    fun `a tick row steps both ways and wraps rather than sticking`() {
        assertEquals(139, StormTimer.step(138, 1, 400, back = false))
        assertEquals(137, StormTimer.step(138, 1, 400, back = true))
        assertEquals(1, StormTimer.step(400, 1, 400, back = false))
        assertEquals(400, StormTimer.step(1, 1, 400, back = true))
    }
}
