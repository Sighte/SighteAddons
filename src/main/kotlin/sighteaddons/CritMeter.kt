package sighteaddons

import java.util.Locale

/**
 * The Explosive Shot crit, read out of chat and divided by the Blessing of Power that produced it.
 *
 * Hypixel announces the hit — `Your Explosive Shot hit 5 enemies for 61,000,000,000.0 damage!` — and
 * that number on its own is not comparable to anything: the same bow, the same player and the same
 * gear produce a different figure in a run with Power VII than in one with Power IV. The blessings a
 * floor rolled are in the tab list footer, so the two can be put together, and **the quotient is the
 * number this feature exists for**. The raw damage is shown next to it because it is the one the
 * player watched land.
 *
 * ## Where this came from, and what deliberately did not come with it
 *
 * The regex, the combat window and the arithmetic are ported from a `CC0-1.0` Fabric mod the user
 * supplied (`fabric.mod.json` → `"license": "CC0-1.0"`, so reuse is unencumbered). Three things in
 * that mod are **not** here and must not be added later:
 *
 * - **It POSTed every crit** — player name, crit value, power and ratio — to a third-party endpoint
 *   on each hit, with no toggle and no disclosure. Nothing in this file performs or prepares any
 *   network call. Crit values do not leave the machine at all: they are not in [RunReport], so they
 *   are not in a run report either, and putting them there would be a receiver change first.
 * - **It typed into chat by itself** — a `/msg` to a configured victim and a `pc` party line per
 *   crit. This mod sends no packets ([SighteAddons]'s class comment is the standing promise) and a
 *   readout that spams a party is the player's problem rather than the mod's. [line] is printed
 *   locally with `addClientSystemMessage`, which no server sees.
 * - **Its code.** It was Java against intermediary names for another Minecraft version; this is
 *   Kotlin against Mojmap for 26.1.2, written in the idiom of the files around it.
 *
 * ## What is a hypothesis here, and how it gets settled
 *
 * **Every string below.** They were read out of a decompiled jar, not out of a log this repository
 * has: `grep -ril "explosive shot|blessing of power"` over `docs/evidence/` and the fifteen real
 * session logs on disk finds nothing, because no build has ever looked for these lines. So this is
 * exactly as correct as that mod is, and the failure is the quiet one — a shape that is subtly wrong
 * matches nothing and the readout simply never appears. [nearMiss] is the instrumentation that turns
 * that silence into a measurement: a line that is unmistakably Hypixel's own crit announcement and
 * that [perTarget] did not understand is written to the debug log, so one real M7 says which of
 * these is wrong instead of the next session guessing again. The same standard [ChatEvents] is held
 * to, and the same reason its `chat_unparsed` exists.
 *
 * ## Anchoring is the security property, as everywhere else that reads chat
 *
 * Hypixel prefixes anything a player typed (`Party > [MVP+] Name: ...`), so a pattern anchored at
 * the **start** of the line cannot be produced by a teammate typing it. All four patterns here are,
 * including the two combat-window ones — which is stricter than the mod this came from, where the
 * window was opened by a bare `contains("[BOSS] Maxor")` that any party member could have said.
 * A forged window would not cost much on its own (the crit line still has to match), but a guard
 * that only works when nobody is being annoying is not a guard.
 *
 * Pure except for [inCombat]. [perTarget], [power], [romanToInt] and [line] are functions of their
 * arguments and are what `CritMeterTest` drives; [onChat] takes the footer as a lambda so the whole
 * decision — window, parse, power, arithmetic, wording — runs in a test without a `Minecraft`. What
 * stays unverified is the wiring: that Fabric delivers these lines, that the tab-list accessor
 * returns a footer at all, and that the footer's blessing lines look like the ones assumed below.
 */
internal object CritMeter {
    /**
     * Hypixel's own damage announcement.
     *
     * `enem(?:y|ies)` because one enemy is singular, and the damage carries thousands separators and
     * an optional decimal. Not anchored at the end, deliberately: the source mod matched a prefix and
     * a trailing token appearing in some later season should cost the tail, not the reading. The
     * start anchor is the one that carries the forgery argument and it is not optional.
     */
    private val CRIT = Regex("""^Your Explosive Shot hit (\d+) enem(?:y|ies) for ([\d,]+(?:\.\d+)?) damage[!.].*$""")

    /**
     * Maxor's first line opens the window; Goldor's and Mort's close it.
     *
     * The window exists because Explosive Shot lands all over a floor and only the Maxor phase
     * figures anybody compares. It is a substring test in the source mod and a start-anchored one
     * here; `[BOSS] X:` and `[NPC] X:` are how Hypixel opens an NPC line, and a player saying the
     * same words arrives behind their own name.
     *
     * Maxor is matched without the colon because his opening line is the announcement of the phase
     * rather than speech, and the close patterns keep theirs because that is what the source mod
     * matched and a bare `[NPC] Mort` appears in the entrance as well as at the end.
     */
    private val COMBAT_OPEN = Regex("""^\[BOSS] Maxor\b.*$""")
    private val COMBAT_CLOSE = Regex("""^(?:\[BOSS] Goldor:|\[NPC] Mort:).*$""")

    /**
     * `Blessing of Power VII` in the tab list footer, and the numeral has to follow the words.
     *
     * The source mod took the first `[IVXLCDM]+` run anywhere on the line, which would have read a
     * numeral out of unrelated text sharing the row. Requiring it after the name costs nothing if
     * the footer looks the way it is assumed to and fails visibly rather than wrongly if it does not.
     */
    private val POWER = Regex("""Blessing of Power\s+([IVXLCDM]+)\b""")

    /** Whether the footer line is a Blessing of Time at all. Its level is not read — see [power]. */
    private const val TIME = "Blessing of Time"

    /**
     * What one Blessing of Time is worth in Power units, carried over from the source mod as a
     * constant it hard-coded and did not explain.
     *
     * **Unverified and inherited.** Nothing in this repository can check it, and unlike the strings
     * it will never announce itself as wrong: a bad constant produces a plausible quotient. It is
     * here so the number matches what the user already compares against; if it turns out to be
     * wrong, this is the one line to change.
     */
    private const val TIME_WORTH = 2.5

    private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    /** Whether the Maxor window is open. Set only from [onChat], on the client thread. */
    var inCombat = false
        private set

    /** Called from [DungeonSession.reset]: the window belongs to the run it was opened in. */
    fun reset() {
        inCombat = false
    }

    /**
     * Hypixel's own normalisation, applied here rather than to the shared stripped line in
     * [SighteAddons.onChat].
     *
     * Non-breaking spaces and doubled spaces are what the source mod found in these lines, and
     * collapsing them is required for the patterns above to match. It is **not** done to the line
     * `ChatEvents` sees: that parser's leading-space handling and its `chat_unparsed` diagnostic both
     * depend on receiving the line as it arrived, and quietly reshaping it under them is exactly the
     * kind of shared-mutation change that makes two consumers disagree about what a line said.
     *
     * ` ` is spelled as an escape and not as itself: Java's `\s` does not match a non-breaking
     * space, so this replacement is load-bearing rather than cosmetic, and a literal one in the
     * source is invisible to a reader and to a tidy-up.
     */
    private val WHITESPACE = Regex("""\s+""")

    internal fun normalize(stripped: String): String =
        stripped.replace(' ', ' ').replace(WHITESPACE, " ").trim()

    /**
     * Damage **per target** for a crit line, or null.
     *
     * Explosive Shot splits its damage across everything it hits, so the announced figure describes
     * the shot and the quotient describes the hit. Dividing is what makes a crit on five enemies
     * comparable to one on three, and it is the arithmetic the source mod did before anything else.
     *
     * A line claiming zero enemies is rejected rather than divided by: Hypixel should never send one
     * and a readout of `Infinity` would be worse than no readout.
     */
    internal fun perTarget(line: String): Double? {
        val m = CRIT.matchEntire(normalize(line)) ?: return null
        val enemies = m.groupValues[1].toIntOrNull() ?: return null
        if (enemies <= 0) return null
        val damage = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        return damage / enemies
    }

    /**
     * Strict roman numeral, or null for anything that is not one.
     *
     * Strict rather than the source mod's lenient loop, which threw a `NullPointerException` on an
     * unexpected character — inside a chat callback, which is a crash the player would see as the
     * game hitching on a boss. Null flows into "no power read" and the readout says so.
     */
    internal fun romanToInt(s: String): Int? {
        if (s.isEmpty()) return null
        var total = 0
        for (i in s.indices) {
            val current = ROMAN[s[i]] ?: return null
            val next = if (i + 1 < s.length) ROMAN[s[i + 1]] ?: return null else 0
            total += if (current < next) -current else current
        }
        return if (total > 0) total else null
    }

    /**
     * The floor's total Blessing of Power, read from the tab list [footer], or null if it says
     * nothing about blessings.
     *
     * Null and zero are different answers and are spelled differently on screen. A footer with no
     * blessing line at all means the reading failed — wrong screen, empty tab list, a footer format
     * that changed — and a quotient computed against it would be a division by zero dressed up as a
     * fact. A floor that genuinely rolled no blessings is not a case that arises in the phase this
     * window covers.
     *
     * Multiple lines are summed, and Blessing of Time contributes [TIME_WORTH] each, both as the
     * source mod did it. The Time *level* is deliberately not read: the constant is per line there
     * and inventing a multiplier here would be a second unverified number on top of the first.
     */
    internal fun power(footer: String): Double? {
        var total = 0.0
        var found = false
        for (raw in footer.lines()) {
            val line = normalize(raw)
            POWER.find(line)?.let { m ->
                romanToInt(m.groupValues[1])?.let {
                    total += it
                    found = true
                }
            }
            if (line.contains(TIME)) {
                total += TIME_WORTH
                found = true
            }
        }
        return if (found) total else null
    }

    /**
     * The line printed to the player's own chat. [perTarget] is raw damage on one target; [power] is
     * null when the footer could not be read.
     *
     * Billions throughout, because that is the unit an Explosive Shot crit is discussed in and a
     * twelve-digit number is unreadable at a glance. `Locale.ROOT` for the same reason
     * [SighteAddons.renderHud] uses it: a German default locale renders `2,7` and the readout would
     * stop matching every other number this mod prints.
     *
     * **Per power is the point and is written last**, where the eye lands. Without a power reading
     * the line still shows the crit rather than being suppressed — the raw number is what the player
     * just watched happen, and withholding it because the tab list was unreadable would make a
     * failure to read the footer look like a failure to crit.
     *
     * Fields are separated by [Chat.FIELD] like every other line this mod writes. It used to be the
     * same `·` with doubled spaces around it, which is one separator too many for a mod that speaks
     * from four files.
     */
    internal fun line(perTarget: Double, power: Double?): String {
        val crit = "%.1fB".format(Locale.ROOT, perTarget / 1e9)
        if (power == null || power <= 0.0) return "Crit $crit${Chat.FIELD}power unknown"
        return "Crit %s${Chat.FIELD}power %s${Chat.FIELD}%.2fB per power".format(
            Locale.ROOT,
            crit,
            "%.1f".format(Locale.ROOT, power).removeSuffix(".0"),
            perTarget / 1e9 / power,
        )
    }

    /**
     * One chat line, and the whole decision: window, match, power, wording. Returns the text to print
     * locally, or null for the overwhelming majority of lines, which are neither.
     *
     * [footer] is called **only** when a crit has already matched inside the window — reading the tab
     * list is a mixin accessor call and there is no reason to make it on every chat line in the game.
     * Passing it as a lambda is what lets a test drive this end to end without a live `Minecraft`,
     * the same seam [IdleTime] uses one layer down.
     *
     * The window is updated before the match is attempted, so Maxor's own opening line arriving in
     * the same batch as the first crit cannot lose it. [inCombat] is not reset by the crit itself:
     * a phase produces many.
     */
    fun onChat(stripped: String, footer: () -> String?): String? {
        val text = normalize(stripped)
        if (COMBAT_OPEN.matchEntire(text) != null) inCombat = true
        if (COMBAT_CLOSE.matchEntire(text) != null) inCombat = false
        if (!inCombat) return null
        val perTarget = perTarget(text) ?: return null
        return line(perTarget, footer()?.let(::power))
    }

    /**
     * A line that is unmistakably Hypixel's crit announcement and that [perTarget] did not
     * understand, or null. Written to the debug log by the caller.
     *
     * **This is the only way anything above gets confirmed.** The opener is server-only for the same
     * reason [ChatEvents.OPENERS] are: Hypixel puts `Name: ` in front of anything a player typed, so
     * a line starting with `Your Explosive Shot` came from the server and can be logged without
     * putting a stranger's conversation in a file. It carries no player name, so unlike a tab row it
     * needs no redaction — and it is checked outside the combat window on purpose, since "the window
     * never opened" is one of the two things that would make this feature silently do nothing.
     */
    fun nearMiss(stripped: String): String? {
        val line = normalize(stripped)
        if (!line.startsWith("Your Explosive Shot")) return null
        return if (perTarget(line) == null) line else null
    }
}
