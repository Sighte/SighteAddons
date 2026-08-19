package sighteaddons

/**
 * Which chat lines end which part of a dungeon run, transliterated from Odin's `SplitsManager.kt`.
 *
 * ### A table of Hypixel strings, and nothing else
 *
 * Same category of file as [ChatEvents]: no Minecraft types, no state, no decisions — just the
 * sentences Hypixel prints and the name of the span each one closes. [Splits] is what turns them into
 * times. Kept separate from [ChatEvents] because that object answers "what happened" for three
 * unrelated subsystems, while every line here exists only to advance one chain, and a run's chain is
 * ordered where an event is not.
 *
 * ### Why a transliteration rather than a rewrite
 *
 * These are twenty-odd strings that nothing in this repository can verify — the same standing as
 * [ChatEvents]' patterns and [SecretTracker.SECRET_SKULLS]. What *can* be done is keep them
 * recognisably Odin's, so that when Odin corrects one (it runs against the live server and this does
 * not) the difference is a line-by-line diff rather than an archaeology exercise. So the order, the
 * grouping and the wording below are Odin's, and the deviations are the two named here:
 *
 *  - **Names carry no legacy colour codes and are lower case.** This UI is monochrome by rule
 *    ([sighteaddons.ui.theme.Palette]) and its labels are lower case everywhere. The colour was never
 *    data; Odin's `Blood Clear` and this `blood clear` are the same split, and [SplitPbs.nameOfOdinKey]
 *    is the one place that has to know it.
 *  - **Every pattern is anchored at both ends and matched with `matchEntire`.** Odin matches with
 *    `Regex.matches`, which is already a full-string match, so half of its patterns carry no anchors
 *    and behave identically. Here they are written out for [ChatEvents.parse]'s stated reason: the
 *    anchors are the property that stops a teammate typing a boss line from setting a personal best,
 *    and a property nobody can see in the source is one that gets edited away.
 *
 * ### The shape of a run
 *
 * Three splits in front of every floor, one behind it, and the floor's own bosses in between:
 *
 * ```
 * blood open -> blood clear -> portal entry -> ...floor splits... -> total
 * ```
 *
 * The name on a row is the split that *opens* the span, not the one that closes it — `blood open` is
 * the time from Mort's line to the blood door, not the time to Mort's line. That is Odin's convention
 * and [BloodClear] already documents it as the reason its own span looks the way it does. **Split 0 is
 * the start marker**: it has a name and a row like the others, but nothing precedes it, so it is what
 * the whole run is timed from.
 */
internal object DungeonSplits {

    /**
     * One span, named by the line that opens it.
     *
     * [patterns] rather than a single [Regex] because two of these are genuinely two sentences.
     * `blood clear` opens on whichever of the blood door line and the Watcher's greeting arrives first
     * — Odin folds them into one alternation and takes the first match, and either really can be the
     * one that lands. An alternation would say the same thing; a list says it in a form where the two
     * halves keep their own names ([ChatEvents.BLOOD_OPEN], [ChatEvents.BLOOD_DOOR]) and there is
     * still exactly one definition of each Hypixel string in this mod.
     */
    data class Split(val name: String, val patterns: List<Regex>) {
        constructor(name: String, vararg patterns: Regex) : this(name, patterns.toList())

        /**
         * [name] as the HUD prints it, built once with the table.
         *
         * [sighteaddons.ui.components.Labels] draws uppercase and says outright that callers uppercase
         * their own strings, because `uppercase()` allocates and the panel redraws ten of these every
         * frame. The tables here are built at class-load and never change, so this is the one place the
         * conversion can happen no times per frame instead of ten.
         */
        val label: String = name.uppercase()

        /** Whether [stripped] is this split's line. Full-string, for [DungeonSplits]' second reason. */
        fun matches(stripped: String): Boolean = patterns.any { it.matchEntire(stripped) != null }
    }

    /** The name of the span every group ends on, and the one row the HUD never draws. */
    const val TOTAL = "total"

    /** The aggregate row, which is not a [Split] at all — see [Splits.Readout.bossEntryMs]. */
    const val BOSS_ENTRY = "boss entry"

    /** [BOSS_ENTRY] as the panel prints it. A constant for [Split.label]'s reason. */
    const val BOSS_ENTRY_LABEL = "BOSS ENTRY"

    /** [ChatEvents]' own leading-space tolerance, kept for the reason given there. */
    private const val LEAD = """^\s*"""

    /**
     * Mort's two opening lines, either of which means the run has started.
     *
     * Odin's `MORT_REGEX`, which it also reuses for its tick timers — the same two sentences, and the
     * alternation is his. This is the run's zero point: everything on the HUD is measured from it, so a
     * floor where neither line arrives produces no splits at all rather than splits timed from
     * somewhere else. [Splits] logs that case instead of guessing.
     */
    private val MORT = listOf(
        Regex("""$LEAD\[NPC] Mort: Here, I found this map when I first entered the dungeon\.$"""),
        Regex("""$LEAD\[NPC] Mort: Right-click the Orb for spells, and Left-click \(or Drop\) to use your Ultimate!$"""),
    )

    /**
     * Hypixel's own "you killed it" line, with its record marker tolerated.
     *
     * Odin's, including the `0?` in front of the time and the optional new-record suffix — the groups
     * are unused here (the span is measured, not read out of the sentence) but the shape has to still
     * match a run that set a record, which is exactly the run somebody cares about.
     */
    private val DEFEATED = Regex("""$LEAD☠ Defeated (.+) in 0?([\dhms ]+?)\s*(\(NEW RECORD!\))?$""")

    /**
     * The boss's first line on each floor, F1 through F7 — Odin's `entryRegexes`, in his order.
     *
     * On F4 and F5 this line *is* the `cleared` split: Thorn and Livid announce themselves and nothing
     * else on those floors is a span worth naming. That is not an omission to tidy up; it is what Odin
     * measures, and the F4/M4 and F5/M5 personal bests in a real config have exactly those keys.
     */
    private val ENTRY = listOf(
        Regex("""$LEAD\[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable\.$"""),
        Regex("""$LEAD\[BOSS] Scarf: This is where the journey ends for you, Adventurers\.$"""),
        Regex("""$LEAD\[BOSS] The Professor: I was burdened with terrible news recently\.\.\.$"""),
        Regex("""$LEAD\[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$"""),
        Regex("""$LEAD\[BOSS] Livid: Welcome, you've arrived right on time\. I am Livid, the Master of Shadows\.$"""),
        Regex("""$LEAD\[BOSS] Sadan: So you made it all the way here\.\.\. Now you wish to defy me\? Sadan\?!$"""),
        Regex("""$LEAD\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$"""),
    )

    /** The entrance has no boss, so its run is the three shared splits and the total. Odin's, empty. */
    private val ENTRANCE = emptyList<Split>()

    private val FLOOR_1 = listOf(
        Split("bonzo's sike", ENTRY[0]),
        Split("cleared", Regex("""$LEAD\[BOSS] Bonzo: Oh I'm dead!$""")),
    )

    private val FLOOR_2 = listOf(
        Split("scarf's minions", ENTRY[1]),
        Split("cleared", Regex("""$LEAD\[BOSS] Scarf: Did you forget\? I was taught by the best! Let's dance\.$""")),
    )

    private val FLOOR_3 = listOf(
        Split("the guardians", ENTRY[2]),
        Split("the professor", Regex("""$LEAD\[BOSS] The Professor: Oh\? You found my Guardians' one weakness\?$""")),
        Split("cleared", Regex("""$LEAD\[BOSS] The Professor: What\?! My Guardian power is unbeatable!$""")),
    )

    private val FLOOR_4 = listOf(Split("cleared", ENTRY[3]))

    private val FLOOR_5 = listOf(Split("cleared", ENTRY[4]))

    private val FLOOR_6 = listOf(
        Split("terracottas", ENTRY[5]),
        Split("giants", Regex("""$LEAD\[BOSS] Sadan: ENOUGH!$""")),
        Split("cleared", Regex("""$LEAD\[BOSS] Sadan: You did it\. I understand now, you have earned my respect\.$""")),
    )

    private val FLOOR_7 = listOf(
        Split("maxor", ENTRY[6]),
        Split("storm", Regex("""$LEAD\[BOSS] Storm: Pathetic Maxor, just like expected\.$""")),
        Split("terminals", Regex("""$LEAD\[BOSS] Goldor: Who dares trespass into my domain\?$""")),
        Split("goldor", Regex("""${LEAD}The Core entrance is opening!$""")),
        Split("necron", Regex("""$LEAD\[BOSS] Necron: You went further than any human before, congratulations\.$""")),
        Split("cleared", Regex("""$LEAD\[BOSS] Necron: All this, for nothing\.\.\.$""")),
    )

    /** Indexed by floor number, entrance first. Odin's `dungeonSplits`. */
    private val FLOORS = listOf(ENTRANCE, FLOOR_1, FLOOR_2, FLOOR_3, FLOOR_4, FLOOR_5, FLOOR_6, FLOOR_7)

    /**
     * The whole chain for a floor, or null for a floor tag this does not recognise.
     *
     * **Master mode reuses the F-floor chain**, which is Odin's `floor.floorNumber` and is correct: M7's
     * bosses say the same sentences F7's do. What master mode does *not* share is its records — that
     * split is [SplitPbs], which keys on the tag rather than the number, exactly as Odin keys its
     * personal bests on the enum ordinal.
     *
     * [floorTag] is [SoloClear.floorTag]'s spelling (`E`, `F1`…`M7`, `?`), so there is one answer in
     * this mod to "which floor was that" and this is not a second derivation of it.
     */
    fun chainFor(floorTag: String): List<Split>? {
        val floor = FLOORS.getOrNull(numberOf(floorTag) ?: return null) ?: return null
        return buildList {
            add(Split("blood open", MORT))
            add(Split("blood clear", ChatEvents.BLOOD_OPEN, ChatEvents.BLOOD_DOOR))
            add(Split("portal entry", ChatEvents.BLOOD_DONE))
            addAll(floor)
            add(Split(TOTAL, DEFEATED))
        }
    }

    /**
     * `E` to 0, `F4`/`M4` to 4, and null for anything that is not one of the fifteen floors.
     *
     * Not [DungeonSession.floorNumber]: that reads the last character of the sidebar's own spelling, so
     * `Entrance` gives it null rather than 0 — right for a field that means "the number in the floor's
     * name", wrong for choosing a chain the entrance has one of. This works off the tag, which is the
     * normalised form, and it is a pure function so `SplitsTest` can walk all fifteen.
     *
     * **The digit is bounded, and that is not defensiveness.** This is also the predicate
     * [SplitPbs.tagOfOdinFloorKey] asks whether a key in somebody else's config is a floor at all, so an
     * unbounded read would import `DungeonF8` as a floor with records and would map an `M0` onto the
     * entrance's chain. [FLOORS] is seven floors and an entrance; this says the same thing where a
     * caller can ask it.
     */
    internal fun numberOf(floorTag: String): Int? = when {
        floorTag == "E" -> 0
        floorTag.length != 2 -> null
        floorTag[0] != 'F' && floorTag[0] != 'M' -> null
        else -> floorTag[1].digitToIntOrNull()?.takeIf { it in 1..7 }
    }
}
