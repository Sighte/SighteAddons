package sighteaddons

/**
 * The dungeon events Hypixel announces in chat, as a pure `String -> Event?`.
 *
 * Everything the mod knew about a party used to be *inferred*: a death from the tab row flipping to
 * `DEAD` a second or more after the fact, a secret from a counter rising within 40 ticks of your own
 * click. Chat carries some of the same facts stated outright, with a name attached, at the tick they
 * happen. Where a chat line exists, it is the better source; where it does not, the inference stays
 * exactly as it was. **This object replaces nothing on its own** — it parses, and the two call sites
 * ([SighteAddons.onChat] into [ContributionTracker] and [SecretTracker]) decide what a fact is worth.
 *
 * ## Why a pure parser
 *
 * The same seam [SecretTracker.parseSecrets], [PartyTracker.TAB] and [Pseudonym.row] already use: a
 * function over a raw string can be driven from a test with the exact bytes Hypixel sends, while a
 * listener registered against `ClientReceiveMessageEvents` cannot be driven from anything this
 * repository can run. So the matching is here and testable, and the wiring is at the call site and
 * is not.
 *
 * ## What is not verified, and cannot be verified here
 *
 * **That these strings are the strings Hypixel actually sends.** Loom's dev client has no valid
 * session (`CLAUDE.md`), so every shape below comes from published open-source mods that do run
 * against the live server, cited per pattern — the same standard [SecretTracker.SECRET_SKULLS] is
 * held to. A shape that is subtly wrong does not fail loudly; it silently matches nothing, and the
 * mod goes on inferring exactly as it did before. That is the benign direction, and it is the reason
 * the near-miss log below exists: it is how a real run tells the next session which of these is
 * wrong, instead of the next session guessing again.
 *
 * **That the listener delivers these lines at all** — on the right tick, with `overlay` false, before
 * or after the action bar update describing the same event. [SecretTracker] cares about that ordering
 * and cannot learn it from here; `chat_secret` and `secret` both carry their tick into the debug log
 * so one real floor answers it.
 *
 * ## Anchoring is the security property
 *
 * Every pattern is anchored at both ends against the **stripped** line. Hypixel prefixes anything a
 * player typed (`Party > [MVP+] Name: ...`, `[MVP+] Name: ...`), so an anchored match cannot be
 * produced by a teammate typing it — the same reason [SighteAddons.RUN_END] is anchored, and the same
 * failure it prevents. Nothing here trusts a substring.
 */
internal object ChatEvents {
    /**
     * What Hypixel calls the local player in the lines it addresses to them. Resolved to a real name
     * at the call site, which is the only place that knows one.
     *
     * The one hole, and it is not worth machinery: a player may legitimately be called `You` (3-16
     * characters, `[A-Za-z0-9_]`), and `☠ You died and became a ghost.` is then genuinely ambiguous —
     * Hypixel writes the same sentence for both. Published mods have the same hole
     * (`Cowlection`'s `DungeonsListener` maps the literal `You` to the client's own name and stops
     * there). It costs one misattributed death in a party containing a player named `You`.
     */
    const val SELF = "You"

    /**
     * A dungeon event, already parsed. [player] is a Minecraft name or [SELF]; the events that have
     * no actor carry none.
     *
     * Deliberately *not* an enum with a name field: the four kinds are consumed by three different
     * subsystems and only [Death] and [SecretFound] currently change a number that leaves the
     * machine. A sealed hierarchy makes a call site that forgets one a compile error.
     */
    sealed interface Event {
        /**
         * ` ☠ <name> ... and became a ghost.`
         *
         * The five shapes Hypixel uses (`Cowlection`, `DungeonsListener.java`, which documents them
         * as a list and matches them with one pattern) differ only in the middle:
         * `was killed by <mob>`, `died`, `fell to their death with help from <mob>`,
         * `disconnected from the Dungeon`, and the second-person `You were killed by <mob>`. Matching
         * the two ends rather than enumerating the causes is why a cause Hypixel adds next season
         * still reads as a death.
         */
        data class Death(val player: String) : Event

        /** ` ❣ <name> was revived...`. Source: `Cowlection`, `DUNGEON_REVIVED_PATTERN`. */
        data class Revived(val player: String) : Event

        /**
         * `<name> opened a WITHER door!`
         *
         * Source: `UnclaimedBloom6/IllegalMap` (`^(?:\[[^\]]+\] )*\w+ opened a WITHER door!`) and
         * `inglettronald/DulkirMod`. The repeatable rank prefix is IllegalMap's and is kept for the
         * same reason [PartyTracker.TAB]'s is: a pattern that fails on every MVP+ player fails on
         * most players.
         */
        data class WitherDoor(val player: String) : Event

        /**
         * `The BLOOD DOOR has been opened!` — the one door event with no name on it, because Hypixel
         * does not put one there. Sourced from four independent mods (`IllegalMap`, `DulkirMod`,
         * `OdinLegacy`, `Harry282/Skyblock-Client`), all matching it as a whole-line literal.
         */
        data object BloodDoor : Event

        /**
         * The Watcher's greeting, which is the other way the blood room can be seen to start.
         *
         * Eight set pieces, one per visit type, taken verbatim from Odin's `BLOOD_OPEN_REGEX`
         * (`SplitsManager.kt`) — the same alternation, minus the `The BLOOD DOOR has been opened!`
         * branch, which is [BloodDoor] here and stays its own event because it is its own fact and is
         * already logged as one. [BloodClear] takes whichever of the two arrives first, exactly as
         * Odin does by folding them into one `Split`.
         */
        data object BloodOpen : Event

        /**
         * `[BOSS] The Watcher: You have proven yourself. You may pass.` — the blood room is done.
         *
         * The end of Odin's `Blood Clear` split, and the only line that states it. The map's
         * checkmark says the same thing a moment later but says it about the room; this says it
         * about the fight.
         */
        data object BloodDone : Event

        /**
         * `PUZZLE SOLVED! <name> <what they did>!`
         *
         * Source: `SkyHanni`'s `DungeonChatFilter.puzzlePatterns`, which carries two full examples
         * (`... wasn't fooled by <mob>! Good job!`, `... tied Tic Tac Toe! Good job!`). Only the
         * prefix and the name are matched: the tail is per-puzzle prose and enumerating it would make
         * this fail on the puzzles nobody has written down.
         */
        data class PuzzleSolved(val player: String) : Event

        /**
         * `PUZZLE FAIL! <name> <what they did>` — and the Quiz, which announces its failures through
         * Oruo instead and emits no `PUZZLE FAIL!` at all.
         *
         * Source: `OdinLegacy`'s `AutoGFS`, which treats the two as one event in a single alternation
         * (`^PUZZLE FAIL! (\w{1,16}) .+$|^\[STATUE\] Oruo the Omniscient: (\w{1,16}) chose the wrong
         * answer!...$`), and `Cowlection`, which matches the prefix.
         */
        data class PuzzleFailed(val player: String) : Event

        /**
         * `<name> found a Wither Essence! Everyone gains an extra essence!`
         *
         * **The only secret Hypixel names a finder for.** Chests, levers, item pickups and the
         * redstone key are announced nowhere — the action bar's room counter is the only signal, and
         * it says *somebody*, never who. So this narrows the inference in [SecretTracker.isOwn]
         * rather than removing it: essence secrets become a fact about a named player, everything
         * else stays a 40-tick coincidence.
         *
         * Source: `SkyHanni`'s `DungeonChatFilter.pickupPatterns` for the third-person form and
         * `pickupMessages` for the second-person one. That both forms exist there, verbatim, is also
         * the evidence that no equivalent line exists for the other secret types: that file is a
         * catalogue of dungeon chat and it has no other `found a` shape.
         */
        data class SecretFound(val player: String) : Event
    }

    /**
     * Hypixel's own leading space, kept optional. It is real — every published pattern for the two
     * symbol lines includes it (`Cowlection` anchors on `"^ ☠ "`) — and tolerating its absence costs
     * nothing, because the symbol is what makes the line unforgeable, not the space in front of it.
     */
    private const val LEAD = """^\s*"""

    /** Minecraft's name shape, bounded the way `OdinLegacy` bounds it. Same alphabet as [Pseudonym]. */
    private const val NAME = """(\w{1,16})"""

    /** Repeatable rank prefix, `IllegalMap`'s. `[MVP+]`, `[VIP]`, an emblem — zero or more of them. */
    private const val RANKS = """(?:\[[^\]]+] )*"""

    private val DEATH = Regex("""$LEAD☠ $NAME .+ and became a ghost\.$""")
    private val REVIVED = Regex("""$LEAD❣ $NAME was revived.*$""")
    private val WITHER_DOOR = Regex("""$LEAD$RANKS$NAME opened a WITHER door!$""")
    private val BLOOD_DOOR = Regex("""${LEAD}The BLOOD DOOR has been opened!$""")

    /** Only the server writes `[BOSS] `, which is what both Watcher patterns below rest on. */
    private const val WATCHER = """\[BOSS] The Watcher:"""

    private val BLOOD_OPEN = Regex(
        "$LEAD$WATCHER (?:" +
            """Congratulations, you made it through the Entrance\.|""" +
            """Ah, you've finally arrived\.|""" +
            """Ah, we meet again\.\.\.|""" +
            """So you made it this far\.\.\. interesting\.|""" +
            """You've managed to scratch and claw your way here, eh\?|""" +
            """I'm starting to get tired of seeing you around here\.\.\.|""" +
            """Oh\.\. hello\?|""" +
            """Things feel a little more roomy now, eh\?""" +
            ")$",
    )

    private val BLOOD_DONE = Regex("""$LEAD$WATCHER You have proven yourself\. You may pass\.$""")
    private val PUZZLE_SOLVED = Regex("""${LEAD}PUZZLE SOLVED! $NAME .+$""")
    private val PUZZLE_FAILED = Regex("""${LEAD}PUZZLE FAIL! $NAME .+$""")
    private val QUIZ_WRONG = Regex("""$LEAD\[STATUE] Oruo the Omniscient: $NAME chose the wrong answer!.*$""")
    private val SECRET = Regex("""$LEAD$RANKS$NAME found a Wither Essence!.*$""")

    /**
     * Parses one **already stripped** chat line. Null for everything else, which is almost every
     * line — the caller strips once and hands the same string to [SighteAddons.RUN_END].
     *
     * Stripping is the caller's job on purpose: `§` codes sit *inside* these strings and not only
     * around them (`SkyHanni` records `(.*) §r§ffound a §r§dWither Essence§r§f!` — three codes in one
     * sentence), so every pattern here would otherwise have to carry `(?:§.)*` between every pair of
     * words. Exactly the reasoning in [SecretTracker.FORMATTING], applied one layer up because two
     * consumers now share the stripped line.
     */
    fun parse(stripped: String): Event? {
        // matchEntire, though every pattern already carries its own anchors: the anchors are there to
        // be read — they are the property this whole object rests on — and this is what keeps them
        // true if one is ever edited away. A trailing newline is rejected rather than tolerated,
        // which is the safe direction for a line that decides a permanent number.
        //
        // Measured rather than assumed, by mutation: removing the `^` from [LEAD] alone fails
        // nothing, and switching this to `find` alone fails nothing — either mechanism holds the
        // line on its own. Both together fail `a party member typing a death message does not kill
        // anybody` and `reads the blood door, which names nobody`. So this is genuine redundancy and
        // not a guard sitting on top of a dead one, which is the thing worth knowing about it.
        DEATH.matchEntire(stripped)?.let { return Event.Death(it.groupValues[1]) }
        REVIVED.matchEntire(stripped)?.let { return Event.Revived(it.groupValues[1]) }
        if (BLOOD_DOOR.matchEntire(stripped) != null) return Event.BloodDoor
        // Done before open: the two are disjoint, but the end of the split is the line that must
        // never be missed, and reading it first says so.
        if (BLOOD_DONE.matchEntire(stripped) != null) return Event.BloodDone
        if (BLOOD_OPEN.matchEntire(stripped) != null) return Event.BloodOpen
        WITHER_DOOR.matchEntire(stripped)?.let { return Event.WitherDoor(it.groupValues[1]) }
        PUZZLE_SOLVED.matchEntire(stripped)?.let { return Event.PuzzleSolved(it.groupValues[1]) }
        PUZZLE_FAILED.matchEntire(stripped)?.let { return Event.PuzzleFailed(it.groupValues[1]) }
        QUIZ_WRONG.matchEntire(stripped)?.let { return Event.PuzzleFailed(it.groupValues[1]) }
        SECRET.matchEntire(stripped)?.let { return Event.SecretFound(it.groupValues[1]) }
        return null
    }

    /**
     * Openers that identify a Hypixel broadcast on their own, for [nearMiss].
     *
     * Each one is unreachable from player-typed chat, because Hypixel puts `Name: ` or `Party > ` in
     * front of anything a player says — so a line beginning with one of these came from the server.
     * That is what makes it safe to write a redacted copy of the line into the debug log.
     */
    private val OPENERS = listOf("☠", "❣", "PUZZLE SOLVED!", "PUZZLE FAIL!", "[STATUE] Oruo", "[BOSS] The Watcher")

    /**
     * A line that is unmistakably one of ours and that [parse] did not understand, or null.
     *
     * **This is the feature's own instrumentation, and the only way any of the above gets confirmed.**
     * Nothing here can check a pattern against Hypixel; one real floor with this logged can, and it
     * reports the difference precisely — a `chat_unparsed` line in a session file is a pattern that
     * needs correcting, an empty log is patterns that held.
     *
     * Narrow on purpose, in the direction that costs coverage rather than privacy. Three of the nine
     * shapes ([Event.WitherDoor], [Event.SecretFound], [Event.BloodDoor]) begin with a player name or
     * with ordinary words, so no opener can distinguish them from something a teammate typed — they
     * get no near-miss reporting at all, and a broken pattern for those is found the slow way. Chat
     * is the one stream in this game that carries strangers' conversation, and a diagnostic that
     * logged it would be a worse defect than the one it was added to find.
     *
     * Expect Oruo's flavour prose in it — the statue prints nine set pieces per quiz room and only
     * one of them is an event. Those lines are noise this deliberately keeps rather than filters,
     * because the filter would have to be a second list of Hypixel strings nobody here can verify
     * either, and nine lines a run against a 20 000-event cap is not a cost.
     *
     * The caller still redacts what this returns ([Pseudonym.row]) before it reaches the log.
     */
    fun nearMiss(stripped: String): String? {
        if (OPENERS.none { stripped.trimStart().startsWith(it) }) return null
        // The line as received, not the trimmed copy the opener was tested against. Hypixel's leading
        // space is exactly the kind of detail a pattern gets wrong, so a diagnostic that quietly
        // removed it would be hiding one of the things it exists to show.
        return if (parse(stripped) == null) stripped else null
    }
}
