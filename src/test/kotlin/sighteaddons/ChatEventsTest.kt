package sighteaddons

import net.minecraft.ChatFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The chat parser, driven with the line shapes published mods match against the live server.
 *
 * **Where these strings come from matters more than usual, so it is stated per case.** Loom's dev
 * client cannot reach Hypixel (`CLAUDE.md`), so no test here can establish that Hypixel sends these —
 * only that [ChatEvents] reads them the way the mods that do run against Hypixel read them. The
 * sources are `SkyHanni`'s `DungeonChatFilter`, `Cowlection`'s `DungeonsListener`,
 * `UnclaimedBloom6/IllegalMap`, `inglettronald/DulkirMod` and `odtheking/OdinLegacy`, all cited again
 * on the pattern they justify in [ChatEvents].
 *
 * Several cases carry the raw `§`-coded form and strip it here with the same
 * [ChatFormatting.stripFormatting] the listener uses, rather than being written out pre-stripped.
 * That is deliberate: the codes sit *inside* these sentences — `(.*) §r§ffound a §r§dWither
 * Essence§r§f!` is three codes in one line — and a test fed the tidy version would pass against a
 * parser that could never match anything real.
 *
 * **What is not covered here, and cannot be**: that the listener is handed these lines at all, with
 * `overlay` false, on the tick Hypixel sent them; and that a death read from chat and the same death
 * read from the tab list land in the order [ContributionTracker.onDeath] assumes. The first is
 * unobservable without a session; the second is measurable only on a real floor, which is what
 * `chat_secret`, `death` and `chat_unparsed` exist in the debug log for.
 */
class ChatEventsTest {
    private fun parse(raw: String) = ChatEvents.parse(ChatFormatting.stripFormatting(raw).orEmpty())

    /** Same path the listener takes: strip once, then hand the stripped line on. */
    private fun nearMiss(raw: String) = ChatEvents.nearMiss(ChatFormatting.stripFormatting(raw).orEmpty())

    /**
     * `Cowlection`'s `DungeonsListener` documents exactly these five and matches all of them with
     * one pattern anchored on the two ends. Enumerating the causes instead would have meant a
     * silent miss the first time Hypixel adds one, which is the failure mode this whole class is
     * arranged against.
     */
    @Test
    fun `reads every death shape Hypixel writes`() {
        assertEquals(
            ChatEvents.Event.Death("Sighte"),
            parse("§c ☠ §r§7Sighte was killed by §r§cSkeleton Grunt§r§7 and became a ghost.§r"),
        )
        assertEquals(ChatEvents.Event.Death("Sighte"), parse(" ☠ Sighte died and became a ghost."))
        assertEquals(
            ChatEvents.Event.Death("Sighte"),
            parse(" ☠ Sighte fell to their death with help from Bonzo and became a ghost."),
        )
        assertEquals(
            ChatEvents.Event.Death("Sighte"),
            parse(" ☠ Sighte disconnected from the Dungeon and became a ghost."),
        )
        // Second person, and the reason ChatEvents.SELF exists at all.
        assertEquals(ChatEvents.Event.Death("You"), parse(" ☠ You were killed by Watcher and became a ghost."))
    }

    /**
     * The leading `☠` is what makes a death message unforgeable, and anchoring is what makes the
     * `☠` mean anything. Hypixel prefixes everything a player types with `Name: ` or `Party > `, so
     * a match anchored at the start cannot be produced by a teammate — the same property
     * `SighteAddons.RUN_END` is anchored for, and the same attack: a death charged to somebody
     * because a troll typed it is a permanent number in an append-only store.
     */
    @Test
    fun `a party member typing a death message does not kill anybody`() {
        assertNull(parse("Party > [MVP+] Troll: ☠ Sighte was killed by Bonzo and became a ghost."))
        assertNull(parse("[MVP+] Troll: ☠ Sighte was killed by Bonzo and became a ghost."))
        // Nor by appending it to something that does start correctly.
        assertNull(parse(" ☠ Sighte was killed by Bonzo and became a ghost. and became a ghost"))
    }

    /** `Cowlection`, `DUNGEON_REVIVED_PATTERN` — the tail varies and is deliberately not matched. */
    @Test
    fun `reads a revive`() {
        assertEquals(ChatEvents.Event.Revived("Sighte"), parse(" ❣ Sighte was revived by Healer!"))
        assertEquals(ChatEvents.Event.Revived("Sighte"), parse(" ❣ Sighte was revived!"))
        assertNull(parse(" ❣ Sighte is feeling better"))
    }

    /**
     * `IllegalMap`'s pattern carries the repeatable rank prefix and `DulkirMod`'s does not. The
     * prefix version is kept for the reason `PartyTracker.TAB` keeps one: a pattern that fails on
     * every MVP+ player fails on most players, and the failure is silent.
     */
    @Test
    fun `reads who opened a wither door, with or without a rank`() {
        assertEquals(ChatEvents.Event.WitherDoor("Sighte"), parse("Sighte opened a WITHER door!"))
        assertEquals(ChatEvents.Event.WitherDoor("Sighte"), parse("§c[MVP§d+§c] Sighte opened a WITHER door!"))
        assertEquals(ChatEvents.Event.WitherDoor("Sighte"), parse("[MVP+] [Emblem] Sighte opened a WITHER door!"))
        // Not the reminder Hypixel prints when you are holding the key, which names nobody.
        assertNull(parse("§e§lRIGHT CLICK §r§7on §r§7a §r§8WITHER §r§7door§r§7 to open it."))
    }

    /**
     * The one door event with no name on it. Four independent mods match it as a whole-line literal
     * and none of them extracts a player, because Hypixel does not put one there — so this reports
     * that it happened and attributes nothing, rather than guessing from who was nearby.
     */
    @Test
    fun `reads the blood door, which names nobody`() {
        assertEquals(ChatEvents.Event.BloodDoor, parse("§cThe BLOOD DOOR has been opened!§r"))
        assertNull(parse("Sighte: The BLOOD DOOR has been opened!"))
    }

    /**
     * Both examples are `SkyHanni`'s, verbatim including the letter-by-letter rainbow on "Good job".
     * Only the prefix and the name are matched: the middle is per-puzzle prose, and there are more
     * puzzles than anybody has written the sentences down for.
     */
    @Test
    fun `reads who solved a puzzle`() {
        assertEquals(
            ChatEvents.Event.PuzzleSolved("Sighte"),
            parse(
                "§a§lPUZZLE SOLVED! Sighte §r§ewasn't fooled by §r§cBonzo§r§e! " +
                    "§r§4G§r§co§r§6o§r§ed§r§a §r§2j§r§bo§r§3b§r§5!",
            ),
        )
        assertEquals(
            ChatEvents.Event.PuzzleSolved("Sighte"),
            parse(
                "§a§lPUZZLE SOLVED! Sighte §r§etied Tic Tac Toe! " +
                    "§r§4G§r§co§r§6o§r§ed§r§a §r§2j§r§bo§r§3b§r§5!",
            ),
        )
    }

    /**
     * Two shapes, one event. The Quiz announces its failures through Oruo and emits no
     * `PUZZLE FAIL!` at all — `OdinLegacy`'s `AutoGFS` folds the two into a single alternation for
     * exactly that reason, and a reader of the debug log wants "who failed a puzzle" rather than
     * "which sentence Hypixel chose".
     */
    @Test
    fun `reads who failed a puzzle, including the quiz`() {
        assertEquals(
            ChatEvents.Event.PuzzleFailed("Sighte"),
            parse("PUZZLE FAIL! Sighte killed a blaze in the wrong order! Yikes!"),
        )
        assertEquals(
            ChatEvents.Event.PuzzleFailed("Sighte"),
            parse(
                "§4[STATUE] Oruo the Omniscient§r§f: §rSighte §r§fchose the wrong answer! " +
                    "I shall never forget this moment of misrememberance.",
            ),
        )
    }

    /**
     * **The only secret Hypixel names a finder for.** Chests, levers, item pickups and the redstone
     * key are announced nowhere, which is why `SecretTracker.isOwn` still exists and why this
     * narrows that inference instead of removing it.
     *
     * That the third-person and second-person forms are both in `SkyHanni`'s catalogue, and that no
     * other `found a` shape is, is also the strongest available evidence for the negative claim
     * above — a file whose whole job is listing dungeon chat.
     */
    @Test
    fun `reads who found a wither essence`() {
        assertEquals(
            ChatEvents.Event.SecretFound("Sighte"),
            parse("Sighte §r§ffound a §r§dWither Essence§r§f! Everyone gains an extra essence!"),
        )
        assertEquals(
            ChatEvents.Event.SecretFound("Sighte"),
            parse("§b[MVP§c+§b] Sighte §r§ffound a §r§dWither Essence§r§f! Everyone gains an extra essence!"),
        )
        assertEquals(
            ChatEvents.Event.SecretFound("You"),
            parse("§fYou found a §r§dWither Essence§r§f! Everyone gains an extra essence!"),
        )
    }

    /**
     * The lines this listener sees all day. The run-end headline is here because `onChat` hands the
     * same stripped string to both this and `SighteAddons.RUN_END`, and a parser that claimed one of
     * them would be reordering the most dangerous path in the mod.
     */
    @Test
    fun `ignores ordinary chat`() {
        assertNull(parse(""))
        assertNull(parse("§9Party §8> §b[MVP§d+§b] Sighte§f: gg"))
        assertNull(parse("§6§lThe Catacombs - Floor VII"))
        assertNull(parse("§e[NPC] §bMort§f: §rHere, I found this map when I first entered the dungeon."))
        // The action bar's secret counter, which is a different listener and a different parser.
        assertNull(parse("§c1500/1500❤     §b1/5 Secrets     §b3000/3000✎ Mana"))
    }

    /**
     * The near-miss log is this feature's own instrumentation, and the only way any pattern above
     * ever gets confirmed against Hypixel. It reports a line that unmistakably came from the server
     * and that [ChatEvents.parse] did not understand.
     */
    @Test
    fun `reports a line that opens like ours and did not parse`() {
        // A death cause in a shape the pattern misses would look like this, and this is how the next
        // session finds out rather than guessing again.
        assertEquals(" ☠ Sighte was voided.", nearMiss(" ☠ Sighte was voided."))
        assertEquals(
            "[STATUE] Oruo the Omniscient: Sighte thinks the answer is A!",
            nearMiss("[STATUE] Oruo the Omniscient: Sighte thinks the answer is A!"),
        )
        // A line that parsed is not a near miss.
        assertNull(nearMiss(" ☠ Sighte died and became a ghost."))
    }

    /**
     * The two ends of Odin's `Blood Clear` split, which is what the blood room's personal best is
     * measured between.
     *
     * All eight greetings, because they are one alternation copied from another codebase and a
     * mistyped branch in the middle of it is invisible — it would simply never match, on the one
     * visit type that produces it, and the blood clock would start at the door line instead. Odin
     * takes whichever arrives first, so that failure is silent by construction.
     */
    @Test
    fun `reads both ends of the blood room`() {
        for (greeting in listOf(
            "Congratulations, you made it through the Entrance.",
            "Ah, you've finally arrived.",
            "Ah, we meet again...",
            "So you made it this far... interesting.",
            "You've managed to scratch and claw your way here, eh?",
            "I'm starting to get tired of seeing you around here...",
            "Oh.. hello?",
            "Things feel a little more roomy now, eh?",
        )) {
            assertEquals(
                ChatEvents.Event.BloodOpen, parse("[BOSS] The Watcher: $greeting"),
                "greeting: $greeting",
            )
        }

        assertEquals(
            ChatEvents.Event.BloodDone,
            parse("§c[BOSS] The Watcher§f: You have proven yourself. You may pass.§r"),
        )

        // The Watcher talks during the fight as well, and none of it ends the split.
        assertNull(parse("[BOSS] The Watcher: Ah, you're one of those, are you?"))
        // And a party member cannot end it by typing it: Hypixel puts their name in front.
        assertNull(parse("Sighte: [BOSS] The Watcher: You have proven yourself. You may pass."))
    }

    /**
     * The deliberate hole, pinned rather than described. Three shapes begin with a player name or
     * with ordinary words, so nothing distinguishes a broken one from something a teammate typed —
     * and a diagnostic that logged strangers' conversation to find a regex bug would be a worse
     * defect than the bug. Those get no near-miss reporting, and this is the test that says so out
     * loud instead of leaving a future reader to conclude the feature forgot them.
     */
    @Test
    fun `says nothing about lines it cannot tell from a player talking`() {
        assertNull(nearMiss("Sighte opened a WITHER gate!"))
        assertNull(nearMiss("Sighte found a Wither Essence"))
        assertNull(nearMiss("The BLOOD DOOR was opened!"))
        assertNull(nearMiss("§9Party §8> §b[MVP§d+§b] Sighte§f: gg"))
    }
}
