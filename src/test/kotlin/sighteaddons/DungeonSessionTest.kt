package sighteaddons

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * What the session remembers about the floor, and for how long.
 *
 * This class exists because of `floorloss-001`: `inDungeon` used to answer its question by
 * *assigning* the floor, so every tick outside a dungeon overwrote a known floor with null and the
 * two report paths that fire after leaving — `ClientPlayConnectionEvents.JOIN` and
 * `ClientPlayConnectionEvents.DISCONNECT` — could only ever file `?`. Measured on the box on
 * 2026-08-15: 20 of 22 uploaded reports carried `?`, including all three schema 5 ones.
 *
 * The seam is [DungeonSession.observeSidebar], which takes the sidebar lines instead of a
 * `Minecraft`. Everything `inDungeon` does beyond it is reading those lines off a live client, which
 * this repository cannot build; the regex and the remember/answer decision — the whole of the defect
 * and the whole of the fix — are on this side of the seam and run here on real sidebar strings.
 *
 * The floor is process-wide state on an `object`, so it is put back to null around every case. That
 * is done by reflection rather than by `DungeonSession.reset()`, which resets half the mod — except
 * in the one case that is *about* `reset()`.
 */
class DungeonSessionTest {
    /**
     * A dungeon sidebar, as Hypixel writes it. `sidebarLines` has already run
     * `ChatFormatting.stripFormatting` over these by the time the regex sees them, so the fixtures
     * are the stripped form; the `⏣` is not a formatting code and does survive.
     */
    private fun dungeonSidebar(floor: String) = listOf(
        "12/08/26",
        "⏣ The Catacombs ($floor)",
        "Keys: ■ 1 Crypts: 0",
        "Time Elapsed: 05m 12s",
        "Cleared: 43% (78)",
    )

    /** Anywhere that is not a dungeon. Nothing here matches, which is the point. */
    private fun hubSidebar() = listOf(
        "12/08/26",
        "⏣ Hub",
        "Purse: 1,204,331",
        "Bits: 0",
    )

    private fun setFloor(value: String?) {
        val field = DungeonSession::class.java.getDeclaredField("floor")
        field.isAccessible = true
        field.set(DungeonSession, value)
    }

    @BeforeEach
    fun clearBefore() = setFloor(null)

    @AfterEach
    fun clearAfter() = setFloor(null)

    /** The detector still detects: nothing below is worth anything if this stops working. */
    @Test
    fun `a floor named on the sidebar is seen and remembered`() {
        assertTrue(DungeonSession.observeSidebar(dungeonSidebar("F7")), "F7 sidebar is a dungeon")
        assertEquals("F7", DungeonSession.floor)
        assertEquals(7, DungeonSession.floorNumber, "inBoss reads this and only this")
    }

    /**
     * Master mode is its own floor and is kept as its own string. `floorNumber` cannot tell `M7` from
     * `F7` — that is why the report carries the string and not the digit, and why the one real floor
     * ever uploaded reads `M7`.
     */
    @Test
    fun `master mode is remembered as master mode`() {
        assertTrue(DungeonSession.observeSidebar(dungeonSidebar("M7")))
        assertEquals("M7", DungeonSession.floor)
    }

    /**
     * **The defect, stated as a test.** Leaving the floor stops the session — and does not take the
     * fact the report needs with it. Both halves are asserted in one case on purpose: they are the
     * two jobs the old single assignment did at once, and a fix that keeps the floor by making the
     * predicate answer `true` outside a dungeon would be far worse than the `?` it replaced, because
     * that predicate gates the entire session state machine in `SighteAddons.onTick`.
     *
     * Ten hub ticks rather than one, because the old code cleared the floor on *every* tick and a
     * fix that only survived the first would pass a single-tick case. A real warp out is seconds of
     * them before `JOIN` arrives.
     */
    @Test
    fun `the floor survives leaving the dungeon, and the check still answers about now`() {
        DungeonSession.observeSidebar(dungeonSidebar("F7"))
        repeat(10) {
            assertFalse(DungeonSession.observeSidebar(hubSidebar()), "the hub is not a dungeon")
        }
        assertEquals("F7", DungeonSession.floor, "the report is written after this point")
    }

    /**
     * An empty sidebar is the loading screen between servers, and it is the state the client is
     * actually in for the tick or two around a warp. It reads as "not in a dungeon" and forgets
     * nothing, same as any other non-dungeon sidebar.
     */
    @Test
    fun `no sidebar at all is not a dungeon and forgets nothing`() {
        DungeonSession.observeSidebar(dungeonSidebar("F5"))
        assertFalse(DungeonSession.observeSidebar(emptyList()))
        assertEquals("F5", DungeonSession.floor)
    }

    /**
     * Where a run actually ends. `reset()` runs on `JOIN`, *after* the JOIN site has written the
     * report for the run being left, so this is the only clearing that can happen without costing a
     * report its floor.
     *
     * This is also the guard against the other failure this fix could have introduced: a floor that
     * is never forgotten would follow the player into the next dungeon and file the next run under
     * the previous floor. Entering or leaving a dungeon is a server transfer on Hypixel, so `JOIN`
     * and therefore this reset happen on both edges of every run.
     */
    @Test
    fun `reset forgets the floor`() {
        DungeonSession.observeSidebar(dungeonSidebar("M7"))
        assertEquals("M7", DungeonSession.floor)
        DungeonSession.reset()
        assertNull(DungeonSession.floor, "the next run must not inherit this one's floor")
        assertNull(DungeonSession.floorNumber)
    }

    /**
     * A later floor replaces an earlier one. Nothing can produce this today — the sidebar cannot
     * change floor without a server transfer, and a transfer resets — but it is what makes the field
     * mean "the last floor seen" rather than "the first", and the two readings only agree while that
     * stays true of Hypixel.
     */
    @Test
    fun `a floor seen later replaces the one before it`() {
        DungeonSession.observeSidebar(dungeonSidebar("F5"))
        DungeonSession.observeSidebar(dungeonSidebar("M7"))
        assertEquals("M7", DungeonSession.floor)
    }

    /**
     * The boss line that ends the clear phase, and the anchor that keeps it the server's to send.
     *
     * The M1 of 2026-08-17 is why this signal exists at all: the tick loop stopped at the missing
     * dungeon map before it ever evaluated the inherited coordinate thresholds, so a run's clear
     * phase never ended. Chat arrives whatever the map is doing.
     *
     * The negative cases are the ones that matter. `[BOSS] ` is a prefix only Hypixel writes — a
     * player saying the same words has their own name in front of them — and this latch stops room
     * sampling and takes the HUD off the screen for the rest of the run, so a party member being able
     * to set it by typing would be a real defect rather than a cosmetic one.
     */
    @Test
    fun `only the server can announce the boss`() {
        assertTrue(DungeonSession.isBossLine("[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable."))
        assertTrue(DungeonSession.isBossLine("[BOSS] Storm: ENERGY HEED MY CALL!"))
        assertTrue(DungeonSession.isBossLine("[BOSS] The Watcher: Well done. Time to face your fears."))

        assertFalse(
            DungeonSession.isBossLine("Sighte: [BOSS] Bonzo: hide the hud"),
            "a party member typing it is not the boss speaking",
        )
        assertFalse(
            DungeonSession.isBossLine("Party > [MVP+] Nordwand: [BOSS] gg"),
            "nor in party chat",
        )
        assertFalse(DungeonSession.isBossLine("[NPC] Mort: Here, I found this map."), "the entrance NPC is not a boss")
        assertFalse(DungeonSession.isBossLine("[BOSS]"), "the prefix alone says nothing")
    }

    /**
     * `runloss-001`'s path reads this field from a Netty event-loop thread — see
     * [RunReport.uploader], which measures that `DISCONNECT` is raised from `channelInactive` with no
     * hop back to the client thread — while the client thread is the one writing it.
     *
     * Two things are checked and they cover different failures. The `volatile` modifier is the real
     * guard: without it the write is not required to be visible to the reading thread at all, and no
     * test can reliably observe that absence, so the declaration is asserted directly. The read from
     * another thread is the cheaper claim — that reaching the floor needs nothing thread-confined,
     * no `Minecraft.getInstance()` — which is what a later "simplification" of
     * [RunReport.reportedFloor] into asking the client would break.
     */
    @Test
    fun `the floor is readable off the client thread`() {
        assertTrue(
            Modifier.isVolatile(DungeonSession::class.java.getDeclaredField("floor").modifiers),
            "DISCONNECT reads this from a Netty thread; the write must be visible to it",
        )
        DungeonSession.observeSidebar(dungeonSidebar("F7"))
        var seen: String? = null
        var thrown: Throwable? = null
        val thread = Thread {
            try {
                seen = RunReport.reportedFloor()
            } catch (e: Throwable) {
                thrown = e
            }
        }
        thread.start()
        thread.join()
        assertNull(thrown, "the disconnect path must not need the client thread to read the floor")
        assertEquals("F7", seen)
    }
}
