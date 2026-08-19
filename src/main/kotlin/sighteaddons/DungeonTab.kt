package sighteaddons

/**
 * The dungeon totals Hypixel puts in the tab list, which is the only place the **true** secret count
 * for a floor is written down anywhere the client can read it.
 *
 * ## Why this exists
 *
 * The run summary used to print `rooms.sumOf { it.ownSecrets }` under the label "secrets", and that
 * number measures something far narrower than the label. Hypixel's per-room counter lives in the
 * **action bar** and reports only the room the player is standing in, so a room nobody was inside
 * when a secret was taken contributes nothing at all. Measured on two real 0.12.0 party M7 floors
 * (`session-1786757639277.jsonl`, replayed by `build/secretcount.py`):
 *
 * - run 1 — **19 rooms cleared, 5 of them ever produced a secret reading**, 19 secrets seen in those
 *   five, 10 attributed to the local player; the summary printed **10**.
 * - run 2 — 18 cleared, 3 produced readings, 10 seen, 4 attributed; the summary printed **4**.
 *
 * The tab list carries the party-wide total for the whole floor and does not care where anybody is
 * standing. That is the number this object reads.
 *
 * ## What is NOT here, and it is the important half
 *
 * **There is no per-player secret count anywhere the client can read.** Not in the tab list, not in
 * the sidebar, not in the tab footer, not in chat. This was settled from source rather than guessed,
 * and the strongest evidence is that two independently written mods both pay for a network call
 * instead:
 *
 * - Skyblocker, `skyblock/dungeon/secrets/SecretsTracker.java` — snapshots every party member's
 *   **lifetime** `skyblock_treasure_hunter` achievement over `Http.sendHypixelRequest("player", …)`
 *   at `DUNGEON_STARTED` and again at `DUNGEON_ENDED`, and reports the difference:
 *   `int secretsPlayerFound = secretsNow.secrets() - startingSecrets.secrets();`
 * - Odin, `features/impl/dungeon/SecretsCounter.kt` — the same shape, `RequestUtils.pullSecrets(name)`
 *   into a `secretsBaseline` map at Mort's "Good luck." line.
 *
 * Both fall back to `N/A` when the API fails, because there is nothing local to fall back to. And the
 * per-player tab rows themselves carry no secrets: Skyblocker's `DungeonPlayerWidget` spells out what
 * a player's four slots hold — name and class, `Ult Cooldown:`, `Revives:`, blank. Odin, Skyblocker
 * and NoammAddons all extract exactly level, name, class and class level from that row and nothing
 * else, which is what [PartyTracker.TAB] already does here.
 *
 * So a per-player figure would need a Hypixel API key and two snapshots per run, which is a different
 * feature in a different place. What the summary can honestly show is the local player's **provable**
 * count against the floor's **true** party-wide one — see [RoomHistory.breakdown].
 *
 * ## The formats, cited rather than invented
 *
 * Odin, `utils/skyblock/dungeon/DungeonListener.kt`, verbatim:
 *
 * ```
 * private val secretPercentRegex = Regex("^ Secrets Found: ([\\d.]+)%$")
 * private val secretCountRegex   = Regex("^ Secrets Found: (\\d+)$")
 * ```
 *
 * Skyblocker reads the percentage with `Pattern.compile("Secrets Found: (?<secper>\\d+\\.?\\d*)%")`
 * at tab index 44 (`DungeonScore.getSecretsPercentage`) and the raw count at index 31 — or 32, or 30,
 * depending on whether a `Discoveries:` line is present and whether the run has started
 * (`DungeonSecretWidget`). **That the index moves is exactly why this object scans every row instead
 * of indexing**, which is also what Odin and NoammAddons do.
 *
 * The two lines share the identical `Secrets Found: ` prefix and differ only by the trailing `%`, so
 * reading the count with an unanchored pattern would silently take `42` off `42.5%`. [matchEntire]
 * against both patterns is what makes them disjoint here.
 *
 * One deliberate difference from Odin: its anchors carry the leading space these lines are indented
 * with, and the rows this object is handed have already been `trim()`ed by [PartyTracker.update].
 * `matchEntire` still anchors both ends; the indent is the only thing given up, and no player name
 * can reach these patterns anyway (a Minecraft name has no spaces and no colon).
 */
object DungeonTab {
    /** `Secrets Found: 15` — the party's total for the floor so far. */
    internal val SECRET_COUNT = Regex("""Secrets Found: (\d+)""")

    /** `Secrets Found: 42.5%` — the same total as a fraction of the floor's secrets. */
    internal val SECRET_PERCENT = Regex("""Secrets Found: (\d+(?:\.\d+)?)%""")

    /** Anything Hypixel wrote under this prefix that neither pattern accepted. See [observe]. */
    private const val PREFIX = "Secrets Found:"

    /**
     * `Time: 06m 32s` — Hypixel's own clock for the run, in either spelling it uses.
     *
     * **Hypixel's number and not ours, which is the entire reason to read it.** [DungeonSession.runTicks]
     * starts at calibration rather than at the door and is therefore a little short of the official
     * time; that is fine for a room record, which is a difference of ticks within one run, and wrong
     * for [SoloClear], which puts a time in a channel where two players compare theirs. A leaderboard
     * built on two different clocks compares nothing.
     *
     * Narrow on both ends. `matchEntire` plus a value that has to look like a duration is what keeps
     * `Time:` — a prefix far more ordinary than `Secrets Found:` — from taking something else off the
     * list.
     *
     * **The dungeon tab carries two `Time:` rows and one of them reads `Time: N/A`** (measured on the
     * M7s of 2026-08-19). Neither half of this is an accident that needs handling: `N/A` is not a
     * duration and never matches, and [readElapsed] keeps the furthest reading, so the row that is
     * filled in wins over the row that is not.
     */
    internal val ELAPSED = Regex(
        DungeonSession.TIME_VALUE.pattern.let { """(?i)Time(?: Elapsed)?: ($it)""" },
    )

    /** One reading of the tab list. Either half can be missing; the pair rarely is. */
    internal data class Secrets(val found: Int?, val percent: Double?)

    /**
     * Highest count seen this run, not the last one.
     *
     * The counter only ever rises within a run, and `printSummary` runs off the run-end chat line —
     * by which point Hypixel may already have taken the dungeon rows out of the tab list. Keeping the
     * maximum means the summary reports what the floor reached rather than whatever the tab happened
     * to say at that instant, and it costs nothing while the run is live.
     */
    var secretsFound: Int? = null
        private set

    /** The percentage seen alongside [secretsFound], so the two stay one consistent reading. */
    var secretsPercent: Double? = null
        private set

    /**
     * Hypixel's elapsed time for this run, verbatim, or null if the row was never read.
     *
     * The longest reading rather than the last, for [secretsFound]'s reason and one more: the run-end
     * headline is what asks for this, and by then Hypixel may have taken the dungeon rows out of the
     * list — so the last read can be a row that stopped advancing, while the longest is the furthest
     * the run ever got. Comparison is on [seconds], never on the string.
     */
    var elapsed: String? = null
        private set

    private var elapsedSeconds = -1

    /** Only suppresses repeat logging; `observe` runs once a second all run long. */
    private var loggedUnparsed = false

    /** Whether the tab list has said `Solo`/`Party (1)` this run. Latched, like the sidebar's half. */
    var solo = false
        private set

    fun reset() {
        secretsFound = null
        secretsPercent = null
        elapsed = null
        elapsedSeconds = -1
        solo = false
        loggedUnparsed = false
    }

    /**
     * `06m 32s` or `6:32` as whole seconds, or null for anything else.
     *
     * Only ever used to decide which of two readings is later, so a form this does not understand
     * costs nothing — the reading is simply not preferred over the one already held.
     */
    internal fun seconds(text: String): Int? {
        HOURS_MINUTES_SECONDS.matchEntire(text)?.let { match ->
            val (h, m, sec) = match.destructured
            return h.toIntOrNull().orZero() * 3600 + m.toIntOrNull().orZero() * 60 + sec.toInt()
        }
        COLON.matchEntire(text)?.let {
            return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }
        return null
    }

    private fun Int?.orZero() = this ?: 0

    /**
     * `59s`, `01m 00s`, `1h 02m 03s` — the hour and minute parts are optional because Hypixel omits
     * them, and the first sixty seconds of a run are the ones that were being lost.
     */
    private val HOURS_MINUTES_SECONDS = Regex("""(?:(\d{1,2})h ?)?(?:(\d{1,2})m ?)?(\d{1,2})s""")
    private val COLON = Regex("""(\d{1,3}):(\d{2})""")

    /**
     * Every row of the sorted tab list, as [PartyTracker.update] already built it.
     *
     * Pure, and that is the same seam [PartyTracker.assign], [SecretTracker.parseSecrets] and
     * [DungeonSession.observeSidebar] are extracted along: the scan is the decidable part and needs
     * no client, while getting the rows needs a live connection and cannot be tested here at all.
     *
     * Returns null when the tab list carries no secret line, which is the ordinary case outside a
     * dungeon and the case a real floor has to refute for this feature to mean anything.
     */
    internal fun read(rows: List<String?>): Secrets? {
        var found: Int? = null
        var percent: Double? = null
        for (row in rows) {
            val text = row ?: continue
            // Percent first only for readability: matchEntire makes the two disjoint, so the order
            // cannot change the answer. `15` has no `%` and `42.5%` leaves `.5%` unconsumed.
            SECRET_PERCENT.matchEntire(text)?.let { percent = it.groupValues[1].toDoubleOrNull() }
            SECRET_COUNT.matchEntire(text)?.let { found = it.groupValues[1].toIntOrNull() }
        }
        return if (found == null && percent == null) null else Secrets(found, percent)
    }

    /**
     * Rows that begin with [PREFIX] and that [read] refused. Narrow on purpose: a row matching this
     * literal prefix cannot be a player's chat or a player's name, so its text is safe to log
     * verbatim — which is the whole point, since the reason to log it is that the format is not what
     * we thought and nothing can be assumed about where anything sits in it.
     *
     * The counterpart to [ChatEvents.nearMiss], and it must not be widened for the same reason:
     * `observe` runs once a second for a twenty-minute floor.
     */
    internal fun unparsed(rows: List<String?>): List<String> = rows.filterNotNull()
        .filter { it.startsWith(PREFIX) }
        .filter { SECRET_COUNT.matchEntire(it) == null && SECRET_PERCENT.matchEntire(it) == null }

    /**
     * Takes one reading and keeps it if it is the highest so far.
     *
     * **What a real floor has to show for this to be believed**, the way `secret_room_first_bar` was
     * added for the secret-run gate: a `tab_secrets` event at all. If none ever appears, the tab list
     * does not carry these rows on this version and the summary falls straight back to the old
     * number — no wrong figure is printed either way. A `tab_secrets_unparsed` event says the rows
     * are there and these two patterns are wrong, which is the other thing worth knowing and the one
     * that cannot be inferred from absence.
     */
    fun observe(rows: List<String?>) {
        if (!loggedUnparsed) {
            val misses = unparsed(rows)
            if (misses.isNotEmpty()) {
                loggedUnparsed = true
                DebugLog.event("tab_secrets_unparsed", "rows" to misses.toString())
            }
        }

        readElapsed(rows)
        if (rows.any { it != null && DungeonSession.SOLO.containsMatchIn(it) }) solo = true

        val seen = read(rows) ?: return
        val found = seen.found ?: return
        val previous = secretsFound
        if (previous != null && found <= previous) return
        secretsFound = found
        secretsPercent = seen.percent
        DebugLog.event(
            "tab_secrets",
            "found" to found, "percent" to (seen.percent ?: -1.0),
            // The derived floor total, logged rather than displayed. It is the one number that says
            // how many secrets the party did NOT find, and a real floor is what settles whether the
            // derivation is worth showing.
            "floorSecrets" to (floorSecrets(found, seen.percent) ?: -1),
            "tabRows" to rows.size,
        )
    }

    /**
     * Keeps the furthest elapsed time the tab list has shown this run.
     *
     * Separate from [read] because it is not part of the secret reading and must not be able to
     * suppress it: [observe] returns early when there is no secret row, and a run whose secrets
     * Hypixel never listed still has a time worth announcing.
     */
    internal fun readElapsed(rows: List<String?>) {
        for (row in rows) {
            val text = row ?: continue
            val value = ELAPSED.matchEntire(text)?.groupValues?.get(1) ?: continue
            val seconds = seconds(value) ?: continue
            if (seconds <= elapsedSeconds) continue
            val first = elapsed == null
            elapsed = value
            elapsedSeconds = seconds
            // Once per run, not once per second: the point is whether this row exists at all on this
            // Hypixel version, which the first sighting answers. See [ELAPSED].
            if (first) DebugLog.event("tab_time", "elapsed" to value, "seconds" to seconds)
        }
    }

    /**
     * The floor's total secrets, back-derived from the count and the percentage of it.
     *
     * Odin's derivation, `DungeonUtils.totalSecrets`, verbatim:
     * `floor(100 / secretPercentage * secretCount + 0.5).toInt()` — a rounded reciprocal, guarded on
     * both inputs being non-zero.
     *
     * **Approximate, and that is why it is logged and not printed.** Hypixel rounds the percentage to
     * one decimal, so the reciprocal is only as good as that rounding — at a count of 1 and a
     * percentage of 2.8 the true total is anywhere in 35..37. Nothing in this repository can check it
     * against a floor's real secret total, so it stays a debug field until a real floor says
     * otherwise.
     */
    internal fun floorSecrets(found: Int?, percent: Double?): Int? {
        if (found == null || found == 0) return null
        if (percent == null || percent <= 0.0) return null
        return Math.floor(100.0 / percent * found + 0.5).toInt()
    }
}
