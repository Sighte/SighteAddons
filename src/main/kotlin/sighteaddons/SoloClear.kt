package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * Announces a solo clear in Discord, and keeps every one of them in an append-only file.
 *
 * Two halves that are deliberately not the same thing:
 *
 *  - **The file** is permanent and local. Every solo run that passed the gate is appended, and a
 *    personal best is just the minimum over it — the same design as [RoomHistory], for the same
 *    reason: a file that rewrites its own history cannot be trusted about it.
 *  - **The announcement** is one `POST /v1/solo_clear` to the receiver, which relays it into a Discord
 *    channel and stores nothing. One attempt, no queue, no retry. That mirrors the receiver's own
 *    decision about the same message: an announcement whose moment has passed is not worth resending,
 *    and the run itself is already recorded — here and in [RunReport].
 *
 * **With a score gate, [tryAnnounce] is the whole trigger, and it is tried on every tick of the run** —
 * clear phase, boss and the moments after the run alike, for as long as the sidebar still says Catacombs.
 * That is how the upstream mod does it (`SoloClearsTracker.tick()` runs unconditionally and checks
 * `inDungeons()`), and copying it closely is deliberate: the two numbers that can satisfy the gate arrive
 * at opposite ends of the run.
 *
 *  - **[LiveScore.computedScore]**, the score the run is *on track* for, recomputed as the run goes.
 *  - **Hypixel's own `Team Score:`**, which arrives with the summary block and states the run's real
 *    score outright. It may only *raise* the projection, and only when it clears the gate by itself —
 *    upstream's rule verbatim, and [gateScore] is that rule.
 *
 * **The number it must not read is the one on the screen.** [LiveScore.score] is Hypixel's live score,
 * exact and identified, and it cannot reach 300 before the boss dies because the boss room is part of a
 * floor's score. Two solo F7s on 2026-08-20 stopped at 265 and 242 at the blood door, five solo runs are
 * on record and none ever read 270, and the whole visible symptom was a switch that was on and a channel
 * that stayed empty. A gate on that number is not strict, it is unreachable; see [LiveScore]'s header for
 * the measurement.
 *
 * **Without a gate** ([Config.soloClearMinScore] `0`) the run-end headline announces every solo clear
 * instead: [onRunEnd] arms and [release] sends.
 *
 * **Times are Hypixel's wherever it states one** — the sidebar's while the run is live, the summary
 * block's at the end. [DungeonSession.runTicks] is the last resort and starts at calibration rather than
 * at the door, which is exactly why it is last. Which clock was used reaches the debug log.
 *
 * **`pb` is this side's claim and the receiver takes it as one.** Nothing is stored on the box, so it
 * has no history to check a record against; a floor's best time exists only on the machine that played
 * it, which is this one. Without `pb: true` the message reads `**SOLO CLEAR**`, so the failure mode of
 * getting this wrong is an understated line rather than a false record.
 *
 * **Off unless [Config.soloClears] is on**, and that switch is independent of [Config.upload] — see
 * there. A run report is anonymous and read by an analysis agent; this puts a name and a time in front
 * of people.
 */
object SoloClear {
    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/soloclears.jsonl")

    private const val ROUTE = "/v1/solo_clear"

    /** Hypixel indents these lines; [SighteAddons.onChat] hands them over stripped but not trimmed. */
    private const val LEAD = """^\s*"""

    /**
     * `Team Score: 305 (S+)` — the run's official score, and the only thing the gate may read.
     *
     * The trailing `.*` is deliberate: the grade in brackets is decoration this mod has no use for, and
     * anchoring against it would make the pattern fail the day Hypixel words it differently. The number
     * is what the gate needs and the number is all that is captured.
     */
    internal val SCORE = Regex("""${LEAD}Team Score: (\d+).*$""", RegexOption.IGNORE_CASE)

    /**
     * `Clear Time: 06m 32s` from the same block, in either spelling and either format.
     *
     * The better of the two official times: [DungeonTab.ELAPSED] reads the tab list, which Hypixel may
     * already have emptied by the time the run ends, while this line is printed *at* that moment.
     */
    internal val CLEAR_TIME = Regex(
        """${LEAD}(?:Clear|Elapsed) Time: (\d{1,2}m ?\d{1,2}s|\d{1,3}:\d{2})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** `A Prince falls. +1 Bonus Score`, announced mid-run rather than in the summary block. */
    internal val PRINCE = Regex("""${LEAD}A Prince falls\..*$""")

    /**
     * Was anybody else in the party this run.
     *
     * **Two flags rather than one, and the sticky one is [withCompany].** BlackAddons latches *solo*
     * true the first time the list shows one player, which is the dangerous direction: the tab list is
     * built as the run loads, so a party of five reads as one player for the first ticks and would
     * latch solo forever. Latching the opposite way cannot go wrong the same way — a teammate who
     * appears at any point in the run is a teammate, and one who never appears never existed.
     */
    private var seenAlone = false
    private var withCompany = false

    /**
     * Was this a solo run.
     *
     * **Two independent signals, as upstream has it, plus the veto this side already had.** Hypixel states
     * it outright — `Solo` / `Party (1)` on the sidebar *and* in the tab list, which is exactly what
     * `SoloClearsTracker` gates on — and the roster derived from the tab rows agrees or refuses.
     *
     * Positive on either statement, negative on a roster that ever showed company. A run the game called
     * solo but whose tab list held five people is a contradiction, and refusing is the safe side of it.
     */
    val solo get() = !withCompany && (seenAlone || (DungeonSession.sidebarSolo && DungeonTab.solo))

    /**
     * What the headline captured, held until the gate can be decided. Everything in it is read at the
     * headline and not at the release, because the score line arrives after Hypixel has begun tearing
     * the run down and the tab list may already be empty by then.
     */
    internal class Pending(
        val player: String,
        val floor: String,
        val ticks: Int,
        val secrets: Int?,
        val deaths: Int,
    )

    private var pending: Pending? = null
    private var score: Int? = null
    private var clearTime: String? = null
    private var princeSeen = false

    /**
     * Did the boss actually die this run.
     *
     * **Because a failed run prints the same summary block as a cleared one.** The headline matches, a
     * `Clear Time:` arrives, a `Team Score:` arrives — Hypixel writes the block when the floor kills you
     * too, and that is how `soloclears.jsonl` came to hold a ten-second M7 with one death in it,
     * announced to the channel as a clear. `☠ Defeated <boss> in <time>` is the one line only a kill
     * produces, and [DungeonSplits.DEFEATED] is already the single definition of it.
     *
     * **It arrives after `Team Score:`, not before** — measured on a real M7, all three on the same tick,
     * in the order headline, score, defeated. So this cannot be a check made at the headline or at the
     * score line: the defeated line has to be a release trigger of its own, which is why [onChatLine]
     * calls [release] on it.
     */
    private var cleared = false

    /**
     * Has the run-end headline been seen this run.
     *
     * **Because the projection keeps moving after the run is over.** Hypixel rewrites the sidebar as it
     * tears a finished run down — the same rewrite that took the live score from 242 to 174 on the F7 of
     * 2026-08-20 — and `completed / cleared%` fed with those numbers can climb. A gate that fired on it
     * would announce a clear-phase time for a clear phase that had already ended, possibly badly.
     *
     * So from the headline on, only Hypixel's own `Team Score:` may satisfy the gate. That number is a
     * statement about the run that ended; the projection is no longer about anything.
     */
    private var runOver = false

    /**
     * Already announced this run, whichever path did it. One run is one message: the score gate is
     * crossed once but read every tick after that, so without this the channel would get a line per tick
     * for the rest of the floor.
     */
    private var announced = false

    /**
     * The floors a score gate applies to, as the upstream mod restricts them.
     *
     * **Not a taste call.** 300 is S+ everywhere, and on F1 a solo run reaches it as a matter of course —
     * a gate that fires there is a gate that fires on every run. The two floors where an S+ solo is worth
     * announcing are these. Widen the set here if that stops being true; nothing else has to change.
     */
    internal val GATED_FLOORS = setOf("F7", "M7")

    /**
     * Which refusal in [onScore] was the last one, for the one line [reset] writes.
     *
     * **A gate that does not fire is indistinguishable from a broken one**, and that cost a whole solo
     * session to learn: the run was solo, on M7, with the switch on, and the only visible fact was the
     * absence of a message. A constant per refusal costs nothing per tick and turns the absence into a
     * sentence.
     */
    private var refusal = "not in a run"

    /**
     * The floor the refusal happened on, captured at the refusal.
     *
     * [DungeonSession.reset] clears `floor` before this object's own reset runs, so reading it there gave
     * `?` on every single line — which is worse than useless in a diagnostic that exists to say which
     * floor was not announced.
     */
    private var refusalFloor = "?"

    /** Called from [DungeonSession.reset]: everything here is per run. */
    fun reset() {
        // Evidence, and the only place it can be collected: a run that was still armed at reset never
        // saw a `Team Score:` line, so the pattern is wrong or Hypixel stopped printing it. Without
        // this line the symptom is an announcement that silently never happens.
        if (pending != null) {
            DebugLog.event(
                "solo_clear_unreleased", "gate" to Config.soloClearMinScore, "score" to (score ?: -1),
                // Which half was missing. `cleared: false` on an ungated run is the failed-run case
                // working as intended; `true` with a gate set is the live path having been in charge.
                "cleared" to cleared,
            )
        }
        // The line that was missing. Written whenever the feature was on and said nothing, with how close
        // the run got: `high` against `gate` is the whole diagnosis, and `why` names the refusal that
        // stood last. Read [LiveScore.high] before [DungeonSession.reset] clears it — this runs first
        // there, and that ordering is the only reason this can be read at all.
        // Only for a run that happened. A reset outside a dungeon — a lobby hop, a server change — is not
        // a run that failed to be announced, and three of those per session drowned the one line that was.
        if (Config.soloClears && !announced && refusal != "not in a run") {
            DebugLog.event(
                "solo_clear_missed",
                // `projected` first, because it is the number the gate refused: `short` is measured off
                // it and off nothing else. `high` rides along as Hypixel's own maximum — the two of them
                // 30 apart on a solo run is the fact that put the gate on the projection.
                "why" to refusal, "gate" to Config.soloClearMinScore,
                "projected" to LiveScore.projectedHigh, "high" to LiveScore.high,
                "short" to (Config.soloClearMinScore - LiveScore.projectedHigh).coerceAtLeast(0),
                "solo" to solo, "floor" to refusalFloor, "cleared" to cleared,
            )
        }
        refusal = "not in a run"
        refusalFloor = "?"
        seenAlone = false
        withCompany = false
        pending = null
        score = null
        clearTime = null
        princeSeen = false
        cleared = false
        runOver = false
        announced = false
    }

    /**
     * One reading of the party, from [PartyTracker.update] — which only ever runs inside a dungeon, so
     * a lobby roster cannot reach this.
     *
     * A size of 0 says the rows did not parse yet and decides nothing. That is why [solo] needs
     * [seenAlone] at all: a run whose tab list was never readable is not announced, rather than being
     * announced as solo on the strength of having seen nobody.
     */
    fun observe(partySize: Int) {
        when {
            partySize > 1 -> withCompany = true
            partySize == 1 -> seenAlone = true
        }
    }

    /**
     * Every chat line, stripped, from [SighteAddons.onChat] — before the headline check there, so these
     * three patterns keep arriving whatever that path decides to return early on.
     *
     * The score line is what releases an armed run, so this is the other half of [onRunEnd] and not a
     * side channel.
     */
    fun onChatLine(text: String) {
        if (PRINCE.matchEntire(text) != null) princeSeen = true
        CLEAR_TIME.matchEntire(text)?.let { clearTime = it.groupValues[1] }
        // The proof of a clear, and therefore a release trigger: it is the last of the three lines to
        // arrive, so an armed run that is waiting on it is waiting here. Reused rather than re-written —
        // one definition of each Hypixel line, the same rule [RunPbs] follows for [CLEAR_TIME].
        if (DungeonSplits.DEFEATED.matchEntire(text) != null) {
            cleared = true
            release()
        }
        SCORE.matchEntire(text)?.let {
            score = it.groupValues[1].toIntOrNull()
            // **The calibration line.** Hypixel's own final score next to the last one [LiveScore] read,
            // and where that one came from. It is the only place the two can be compared, and on the
            // computed path it is the only thing that would ever reveal the Paul offset.
            DebugLog.event(
                "run_score", "score" to (score ?: -1),
                "live" to (LiveScore.score ?: -1), "source" to LiveScore.source.name.lowercase(),
                // The identification line. Whichever of these two the number on the left agrees with is
                // the one a live gate may be built on; the other is a field to delete.
                "computed" to (LiveScore.computedScore ?: -1), "high" to LiveScore.high,
            )
            // The same decision the tick makes, with the number that just arrived. Upstream lets its
            // `chatScore` into the very same comparison; nothing here is a second code path.
            tryAnnounce()
            release()
        }
    }

    /**
     * Best time per floor, in two units that are never compared with each other.
     *
     * **Hypixel's seconds and our ticks are different measurements of the same run**, and mixing them
     * under one key is the failure the [RoomHistory.SECRETS] rename exists to avoid:
     * [DungeonSession.runTicks] starts at calibration rather than at the door, so it is systematically
     * short of the official time. Comparing a run timed one way against a record timed the other would
     * hand out records for the clock rather than for the run.
     *
     * So a run is compared against the record in *its own* unit: Hypixel's when it gave one, ours when
     * it did not. A player whose runs start carrying the official time mid-history simply starts a
     * fresh record in the better unit, which is the honest outcome — and the announced time is always
     * the one the comparison used.
     */
    private val bestSeconds = HashMap<String, Int>()
    private val bestTicks = HashMap<String, Int>()
    private var loaded = false

    /**
     * The floor as the announcement spells it. `Entrance` is `E` everywhere else in this stack — the
     * receiver's own floor pattern is `?|E|[FM][1-7]` — and `?` for a run whose floor was never seen,
     * which is the same answer [RunReport.reportedFloor] gives.
     */
    internal fun floorTag(floor: String?): String = when (floor) {
        null -> "?"
        "Entrance" -> "E"
        else -> floor
    }

    /**
     * Whether a run of this score may be announced, and the one decision that must never be *assumed*.
     *
     * A gate of 0 announces every solo clear and needs no score at all. Above that, **an unknown score
     * fails the gate**: a threshold that cannot be evaluated has not been met. The other way round
     * would turn one wrong regex into a channel that announces every run, which is the failure that
     * looks like the feature working.
     */
    internal fun passes(score: Int?, gate: Int): Boolean = when {
        gate <= 0 -> true
        score == null -> false
        else -> score >= gate
    }

    /**
     * The body the receiver reads. Pure over what it is handed, like [RunReport.build].
     *
     * Every field is either measured or absent. Crypts and the Mimic are **never** sent, because
     * nothing here tracks them — the receiver prints `?` for a field it was not given, which is the
     * true answer, and a `false` we invented would be a claim about the run. The Prince is sent only
     * when his line was actually seen, for exactly the same reason.
     */
    internal fun payload(
        player: String,
        floor: String,
        time: String,
        secrets: Int?,
        deaths: Int,
        score: Int?,
        prince: Boolean,
        pb: Boolean,
    ): JsonObject = JsonObject().apply {
        addProperty("player", player)
        addProperty("floor", floor)
        addProperty("time", time)
        // Absent rather than 0 when the tab list never carried the row: no secrets found and no count
        // read are different facts, and the receiver spells the second one `?`.
        secrets?.let { addProperty("secrets", it) }
        addProperty("deaths", deaths)
        // Hypixel's own number, passed through as a score component so the receiver shows it by name
        // without having to learn the field. Absent when the gate is off and the line never arrived.
        score?.let { add("score_components", JsonObject().apply { addProperty("score", it) }) }
        if (prince) addProperty("prince", true)
        addProperty("pb", pb)
    }

    /**
     * Arms the announcement with everything the run knows at its last readable moment.
     *
     * Called from the run-end headline and from nowhere else — the same single call site that may claim
     * `complete = true` for [RunReport]. The paths that write a report on the way out of a floor
     * (`JOIN`, `DISCONNECT`) deliberately do not reach here: a run that was left is not a clear.
     *
     * **The headline is not proof of a clear, only of an ending**, which is why this arms and [release]
     * decides. A floor that kills you prints the same block; [cleared] is the half that can tell them
     * apart, and it arrives two lines later.
     *
     * Releases immediately when the gate needs no score, so switching the gate off keeps the old
     * behaviour rather than making every announcement wait for a line it does not need.
     */
    fun onRunEnd() {
        // First, and above every early return: [runOver] is about the headline having arrived, not about
        // this run qualifying for anything. Setting it after a return is how the gate would go on reading
        // the projection through the teardown.
        runOver = true
        if (!Config.soloClears || !solo) return
        val ticks = DungeonSession.runTicks
        if (ticks <= 0) return
        // The name captured during the run, for [RunReport.uploader]'s reason: the client's own field
        // is not reliably readable on every path a run can end on.
        val player = PartyTracker.localName ?: return

        pending = Pending(
            player = player,
            floor = floorTag(DungeonSession.floor),
            ticks = ticks,
            secrets = DungeonTab.secretsFound,
            deaths = ContributionTracker.deaths,
        )
        release()
    }

    /**
     * Sends the armed run if the gate now allows it, and gives up on it if the gate refuses.
     *
     * Called twice per run in the ordinary case — once from the headline and once from the score line —
     * which is why [pending] is cleared before anything leaves: it is the guard against announcing one
     * run twice, the same shape [RunReport.reported] has.
     */
    /**
     * The metric a record is kept under. Two of them, and they must never meet.
     *
     * `score300` is *how long it took to reach 300*, which is a shorter number than the clear it belongs
     * to and a different question entirely. Filing both under one key would let every gated run beat
     * every full clear and be announced as a record - the exact failure the [RoomHistory.SECRETS] rename
     * exists to prevent. The threshold is in the name because 270 and 300 are not comparable either.
     */
    internal fun metricFor(gate: Int): String = if (gate > 0) "score$gate" else "clear"

    /**
     * The gate crossed **during the clear phase** - one reading of the live score, every tick.
     *
     * This is the announcement the user asked for: the time at the moment the run reached its score, with
     * the boss playing no part. It cannot come from the end-of-run `Team Score:` line, which arrives after
     * the boss and can no longer say *when*.
     *
     * Three refusals before anything is sent, and each is a case where firing would be a claim rather
     * than a measurement:
     *
     *  - **a floor outside [GATED_FLOORS]** - 300 on F1 is every run.
     *  - **no score** - the rows never parsed, so [LiveScore.computedScore] is null, the threshold is not
     *    evaluable, and an unevaluable threshold is not met ([passes]).
     *  - **already announced** - the score is crossed once and read for the rest of the floor.
     *
     * **There is deliberately no boss refusal**, though an earlier version of this comment claimed one
     * the code never had. The projection keeps climbing into the fight, the gate is about the run rather
     * than about one phase of it, and refusing there is how the 300 stop would go quiet again on the
     * runs that only cross it late. Upstream checks the phase no more closely.
     *
     * The time comes from the same instant as the score: the sidebar carries both, which is what keeps
     * them from describing two different moments.
     */
    fun tryAnnounce() {
        if (announced) return
        if (!Config.soloClears) return refuse("switched off")
        if (!solo) return refuse("not solo")
        val gate = Config.soloClearMinScore
        if (gate <= 0) return refuse("no gate, the run end owns it")
        val floor = floorTag(DungeonSession.floor)
        if (floor !in GATED_FLOORS) return refuse("floor $floor is not gated")

        // **The projection while the run is live, Hypixel's own word for it after** — [gateScore] and
        // [runOver]. What is deliberately absent from both branches is the sidebar's live score: it is
        // the score earned so far, it cannot reach 300 before the boss dies, and reading it here is what
        // kept the channel empty through five solo runs.
        val best = when {
            runOver -> score?.takeIf { passes(it, gate) }
            else -> gateScore(LiveScore.computedScore, score, gate)
        }
        if (best == null) {
            return refuse(if (runOver) "the run ended below the gate" else "no score could be computed")
        }
        if (!passes(best, gate)) return refuse("score $best below the gate")
        val player = PartyTracker.localName ?: return refuse("no name captured")

        send(
            player = player,
            floor = floor,
            ticks = DungeonSession.runTicks,
            secrets = DungeonTab.secretsFound,
            deaths = ContributionTracker.deaths,
            score = best,
            metric = metricFor(gate),
            // The sidebar's clock first: it is live and it is the screen the score came off, so the pair
            // describes one instant. Then the tab list's, then the summary block's `Clear Time:`, which is
            // the one that exists after the run has ended.
            official = DungeonSession.sidebarTime ?: DungeonTab.elapsed ?: clearTime,
        )
    }

    /**
     * The score the gate is judged on: the higher of the live reading and Hypixel's own end-of-run number,
     * or null when neither exists.
     *
     * **Named rather than inlined because it is the rule that was wrong.** Judging on the live score alone
     * refuses runs that qualified — the live number usually crosses the threshold late or not at all, while
     * `Team Score:` states the S+ outright once the run is over. Upstream feeds both into one comparison,
     * and this is that comparison.
     */
    internal fun best(live: Int?, chat: Int?): Int? = listOfNotNull(live, chat).maxOrNull()

    /**
     * The number the gate is judged on: the projection, which Hypixel's own score may raise but never
     * lower — **and only when that score clears the gate on its own.**
     *
     * Upstream's rule, transcribed: `if (chatScore >= 300 && chatScore > finalScore) finalScore =
     * chatScore`. The asymmetry is the point of it. `Team Score:` is the truth about the run, so a run
     * Hypixel calls S+ is announced whatever the formula estimated — but a chat score *below* the gate
     * must not be able to combine with an estimate that also fell short and drag the run over the line,
     * and it must never pull a qualifying projection back down into a refusal.
     */
    internal fun gateScore(projected: Int?, chat: Int?, gate: Int): Int? =
        best(projected, chat?.takeIf { passes(it, gate) })

    /** Records why this tick did not announce. Returns Unit so a refusal is one line at the call site. */
    private fun refuse(why: String) {
        refusal = why
        DungeonSession.floor?.let { refusalFloor = floorTag(it) }
    }

    /**
     * The run-end path, and it now only owns the ungated case.
     *
     * With a score gate set, reaching it is the event and [onScore] has already fired or the run did not
     * qualify - so the headline says nothing. Without one, every solo clear is announced and the run end
     * is the only moment that can know the clear is a clear.
     */
    private fun release() {
        val run = pending ?: return
        if (announced) {
            pending = null
            return
        }
        // Not a refusal worth logging as one: with a gate set, the live path is simply the one in charge.
        if (Config.soloClearMinScore > 0) return
        // **The run has to have been won.** See [cleared]: the summary block is printed for a defeat as
        // well, so the headline alone cannot tell one from the other, and the one announcement this
        // feature ever made was a death. Staying armed rather than clearing [pending] is what puts the
        // case in the log as `solo_clear_unreleased` with `cleared: false`.
        if (!cleared) return
        pending = null
        send(
            player = run.player,
            floor = run.floor,
            ticks = run.ticks,
            secrets = run.secrets,
            deaths = run.deaths,
            score = score,
            metric = metricFor(0),
            // Hypixel's own, then the tab list's, then ours - see [CLEAR_TIME].
            official = clearTime ?: DungeonTab.elapsed,
        )
    }

    /**
     * Records one announcement and puts it on the wire. The only place either happens.
     *
     * Records **per floor and per metric**: a time to 300 and a clear time are not the same measurement,
     * and neither are seconds and ticks - see [metricFor] and [bestSeconds].
     */
    private fun send(
        player: String,
        floor: String,
        ticks: Int,
        secrets: Int?,
        deaths: Int,
        score: Int?,
        metric: String,
        official: String?,
    ) {
        announced = true
        val seconds = official?.let(DungeonTab::seconds)
        ensureLoaded()

        val key = "$floor|$metric"
        val previous = if (seconds != null) bestSeconds[key] else bestTicks[key]
        val current = seconds ?: ticks
        val pb = previous == null || current < previous

        // Before the announcement, and whatever the announcement does: the file is the record and the
        // message is a notification about it. A Discord outage must not cost the history.
        append(floor, metric, ticks, seconds, secrets, deaths, score, pb, System.currentTimeMillis())
        bestTicks[key] = minOf(bestTicks[key] ?: ticks, ticks)
        if (seconds != null) bestSeconds[key] = minOf(bestSeconds[key] ?: seconds, seconds)

        DebugLog.event(
            "solo_clear",
            "floor" to floor, "metric" to metric, "pb" to pb, "score" to (score ?: -1),
            "scoreSource" to LiveScore.source.name.lowercase(), "ticks" to ticks,
            // Which clock the announced time came from. Ours is the fallback, never the intent.
            "time" to (official ?: "own clock"),
            "secrets" to (secrets ?: -1), "deaths" to deaths, "prince" to princeSeen,
        )
        post(
            payload(
                player, floor, official ?: DungeonGrid.formatTicks(ticks),
                secrets, deaths, score, princeSeen, pb,
            ).toString(),
        )
    }

    /**
     * Sends one announcement, off the client thread and exactly once.
     *
     * Everything the request needs is already a string by the time the thread starts, so nothing in
     * here touches game state — the run-end tick goes on without waiting for a network round trip, and
     * a slow box cannot stutter the client.
     *
     * A daemon thread, so quitting the game does not wait for it. Losing the announcement to a quit
     * costs the message and not the record: the line is in the file before this is called.
     */
    private fun post(body: String) {
        val endpoint = TelemetryUpload.endpoint()
        if (endpoint == null) {
            // Only reachable with a half-filled `upload.properties`, which is a typo the author has to
            // see rather than a state to work around.
            SighteAddons.LOGGER.warn("No upload endpoint; the solo clear was recorded but not announced")
            return
        }
        val (base, token) = endpoint
        Thread({
            try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                val request = HttpRequest.newBuilder(URI.create(base + ROUTE))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("X-Mod-Version", TelemetryUpload.modVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (code in 200..299) {
                    SighteAddons.LOGGER.info("Solo clear announced")
                } else {
                    // 502 is the receiver saying Discord refused it or that no webhook is configured; it
                    // deliberately does not say which, because the reason would quote the webhook URL.
                    SighteAddons.LOGGER.warn("Solo clear was not announced: HTTP {}", code)
                    failed("HTTP $code")
                }
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Solo clear was not announced", e)
                failed("no answer")
            }
        }, "sighteaddons-soloclear").apply { isDaemon = true }.start()
    }

    /**
     * The one thing said in chat, and only on failure.
     *
     * Silence means it worked, because the channel itself is the confirmation — a second line in chat
     * saying so would be the mod congratulating itself. A failure has no other visible symptom at all:
     * the player would be left looking at a channel that never got their run.
     */
    private fun failed(reason: String) = Chat.say(
        Chat.label("solo clear not announced")
            .append(Chat.meta(Chat.FIELD + reason))
            .append(Chat.meta(Chat.FIELD + "recorded locally")),
    )

    /**
     * Appends one line. `seconds` is absent when no official time was read, which is what keeps the two
     * units apart in [fold] rather than defaulting one into the other.
     */
    private fun append(
        floor: String,
        metric: String,
        ticks: Int,
        seconds: Int?,
        secrets: Int?,
        deaths: Int,
        score: Int?,
        pb: Boolean,
        ts: Long,
    ) {
        val obj = JsonObject()
        obj.addProperty("floor", floor)
        // What the number in this line measures. Read back by [fold] and the reason old lines can never
        // be compared against new ones by accident.
        obj.addProperty("metric", metric)
        obj.addProperty("ticks", ticks)
        seconds?.let { obj.addProperty("seconds", it) }
        score?.let { obj.addProperty("score", it) }
        secrets?.let { obj.addProperty("secrets", it) }
        obj.addProperty("deaths", deaths)
        obj.addProperty("scoreSource", LiveScore.source.name.lowercase())
        obj.addProperty("prince", princeSeen)
        obj.addProperty("pb", pb)
        obj.addProperty("ts", ts)
        obj.addProperty("modVersion", TelemetryUpload.modVersion())
        try {
            Files.createDirectories(FILE.parent)
            Files.writeString(
                FILE, obj.toString() + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            // Never a reason for the game to misbehave, and never a reason to skip the announcement
            // either: the run happened whether or not this machine could write it down.
            SighteAddons.LOGGER.error("Could not append the solo clear to {}", FILE, e)
        }
    }

    /** The two records per floor, and how many lines were readable. */
    internal data class Bests(
        val bySeconds: Map<String, Int>,
        val byTicks: Map<String, Int>,
        val read: Int,
    )

    /**
     * The records out of the file's lines. Pure, so it can be tested without a game directory — and it
     * is the half worth testing: a wrong minimum here is a false record in a channel.
     *
     * A line without `seconds` contributes to the tick record only, and vice versa. Unreadable lines
     * are skipped rather than throwing, the way [RoomHistory.fold] does it: an append-only file that
     * one bad line makes unreadable is not append-only in practice.
     */
    internal fun fold(lines: Sequence<String>): Bests {
        val seconds = HashMap<String, Int>()
        val ticks = HashMap<String, Int>()
        var read = 0
        for (line in lines) {
            if (line.isBlank()) continue
            try {
                val obj = JsonParser.parseString(line).asJsonObject
                // A line written before metrics existed is a clear time; that is what the code that wrote
                // it measured, and an append-only file does not get to reinterpret its own past.
                val key = "${obj["floor"].asString}|${obj["metric"]?.asString ?: "clear"}"
                val tick = obj["ticks"].asInt
                ticks[key] = minOf(ticks[key] ?: tick, tick)
                obj["seconds"]?.asInt?.let { seconds[key] = minOf(seconds[key] ?: it, it) }
                read++
            } catch (_: Exception) {
                // Skipped rather than fatal, and not counted: `read` only feeds a log line.
            }
        }
        return Bests(seconds, ticks, read)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.exists(FILE)) return
        try {
            val bests = Files.newBufferedReader(FILE).useLines { fold(it) }
            bestSeconds.putAll(bests.bySeconds)
            bestTicks.putAll(bests.byTicks)
            SighteAddons.LOGGER.info("Solo clears: {} entries over {} floors", bests.read, bests.byTicks.size)
        } catch (e: Exception) {
            // A broken history must not cost the announcement. Nothing is truncated — we only append.
            SighteAddons.LOGGER.error("Could not read the solo clears {}", FILE, e)
        }
    }
}
