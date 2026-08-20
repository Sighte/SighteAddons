package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.io.path.name

/**
 * The best time for a whole run, per floor and per party size — and the one thing this mod sends
 * anywhere the moment it happens.
 *
 * ### Why this is not [SplitPbs]
 *
 * [SplitPbs] already holds a `total` per floor, and it is a different number on purpose:
 *
 *  - **Its clock is ours.** `total` spans Mort's line to `☠ Defeated`, because it has to sit in the
 *    same column as `blood clear` and `terminals` and be comparable with Odin's file. That clock
 *    starts inside the run, so it is systematically short of the time Hypixel prints, and two players
 *    cannot compare it.
 *  - **Its key has no party in it.** A split is a split whoever was standing there. A *run* time with
 *    no party size on it is the largest party's time and nothing else — a solo best would be invisible
 *    the day it is beaten by a five-man, which is the day a leaderboard stops meaning anything.
 *
 * So this is a second, smaller store with a different key and a different unit, and the two never
 * compare a number with each other. [SplitPbs] argues the same separation against [RoomHistory]; this
 * is that argument one level up.
 *
 * ### What each half needs
 *
 * The ranked half needs one chat line and a floor name, and takes both from Hypixel — so it works with
 * the splits panel switched off, which is the state a player who never wanted the panel is in. The
 * own-clock fallback needs the measured Mort→Defeated span, and that only exists while [Splits] is
 * armed; with the panel off, a run whose summary block never printed simply has no comparable time, and
 * nothing is recorded rather than something being guessed.
 *
 * ### Hypixel's clock is the record, ours is the fallback
 *
 * `Clear Time: 06m 32s` out of the end-of-run block is the only time two people can compare, so it is
 * what a record is made of and what a leaderboard row carries. When that line never arrives — the
 * player left, or Hypixel worded it differently — the run still gets a record, from our own
 * Mort→Defeated span, and it is **kept in its own key space** and marked `own` in everything that
 * leaves the machine. [SoloClear]'s two stores make exactly this distinction and for the stated
 * reason: comparing a run timed one way against a record timed the other hands out records for the
 * clock rather than for the run.
 *
 * ### The file is the record, the POST is a notification about it
 *
 * `runpbs.jsonl` is append-only and holds nothing but personal bests: the current record for a key is
 * the minimum over it, the same shape [SoloClear] and [RoomHistory] use, and for the same reason — a
 * file that rewrites its own history cannot be trusted about it. It is written before anything is sent
 * and whatever the sending does, so a server that is down costs the leaderboard row and never the
 * record.
 *
 * **The leaderboard does not exist yet, and that is why there is an outbox.** Every PB that is due to
 * be sent is written to `pbs/pb-<millis>.json` first and only removed once the receiver has taken it.
 * A `404` from a route nobody has written yet is a deferral, not a loss: the files sit there, the next
 * launch tries again ([flush]), and the first leaderboard that answers gets every PB set before it
 * existed. That is the whole difference between "send it when it happens" and "have it on the board".
 *
 * **Off unless [Config.runPbs] is on**, which is [Config.uploadName]'s argument verbatim: a leaderboard
 * needs a name to put on it, and the choice to be on one is not a default anybody can be opted into.
 * The record is kept either way — it is a number in a file on the player's own disk, exactly like a
 * split record — and nothing at all leaves while the switch is off, including the outbox.
 */
object RunPbs {

    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/runpbs.jsonl")

    /** Where a PB waits until the receiver has it. One file per row, oldest first by name. */
    private val OUTBOX = FabricLoader.getInstance().configDir.resolve("sighteaddons/pbs")

    private const val ROUTE = "/v1/run_pb"

    /** A PB row is a few hundred bytes; anything near this is a bug worth looking at by hand. */
    private const val MAX_BYTES = 64L * 1024

    /** Which clock timed a run. The two are never compared — see the class comment. */
    enum class Clock { HYPIXEL, OWN;

        val key: String get() = name.lowercase()
    }

    /** What [decide] did, for the debug log and for `RunPbsTest`. */
    sealed interface Result {
        /** Nothing was on record for this floor and party size before. */
        data object First : Result

        /** A record fell. [previous] is what it was, in the same clock's seconds. */
        data class Beat(val previous: Float) : Result

        /** The standing record held. */
        data class Missed(val best: Float) : Result
    }

    // --- Per run ----------------------------------------------------------------------------

    /**
     * The largest party the run ever showed, floored at one.
     *
     * The largest and not the last, for [SoloClear.observe]'s reason turned around: the tab list is
     * built as the run loads, so a five-man reads as one player for the first ticks. Taking the
     * maximum cannot file a party run as a solo record; taking the latest reading could.
     */
    private var players = 1

    /** The floor the total was measured on, captured with the total. */
    private var floorTag: String? = null

    /** Mort → `☠ Defeated`, in milliseconds, or 0 when that span never closed. */
    private var totalMs = 0L

    /** The same run in server ticks, carried in the payload as context and never compared. */
    private var totalTicks = 0L

    /** Hypixel's own `06m 32s`, once the summary block has printed it. */
    private var official: String? = null

    /** One decision per run, whichever path made it. */
    private var filed = false

    // --- The store --------------------------------------------------------------------------

    /** `M7|1|hypixel` to best seconds. The clock is *in* the key, so the two never meet. */
    private val best = HashMap<String, Float>()
    private var loaded = false

    /** The key a record hangs off. Floor, party size, clock — in the order a person would read them. */
    internal fun key(floor: String, players: Int, clock: Clock): String =
        "$floor|$players|${clock.key}"

    /** The standing record for one floor, party size and clock, or null. */
    fun best(floor: String, players: Int, clock: Clock): Float? {
        ensureLoaded()
        return best[key(floor, players, clock)]
    }

    /** How many records are on file. For the `/sa` data section. */
    fun count(): Int {
        ensureLoaded()
        return best.size
    }

    // --- The run's inputs -------------------------------------------------------------------

    /** From [PartyTracker.update], the same reading [SoloClear.observe] gets. */
    fun observe(partySize: Int) {
        if (partySize > players) players = partySize
    }

    /**
     * The run's own total, handed over by [Splits] as its last span closes.
     *
     * Taken from there rather than measured again: [Splits] owns the chain, the Mort line and the
     * `☠ Defeated` line, and a second measurement of the same span is a second thing to keep in step.
     * It arrives *before* the summary block, which is why nothing is decided here unless Hypixel's line
     * has somehow already been seen.
     */
    fun onTotal(floor: String, totalMs: Long, totalTicks: Long) {
        if (totalMs <= 0L) return
        this.floorTag = floor
        this.totalMs = totalMs
        this.totalTicks = totalTicks
        decide()
    }

    /**
     * Every stripped chat line, for the one sentence that carries the official time.
     *
     * [SoloClear.CLEAR_TIME] rather than a second pattern: there is one definition of each Hypixel
     * string in this mod, and this is the same sentence that object already reads. Two copies of it
     * would be two things to fix the day Hypixel rewords it, and the one that was missed would fail
     * silently.
     */
    fun onChatLine(text: String) {
        SoloClear.CLEAR_TIME.matchEntire(text)?.let {
            official = it.groupValues[1]
            // **The floor, if the chain never handed one over.** [Splits] only arms while its own
            // switch is on, so a player with the panel off would otherwise get no run record at all —
            // and the ranked half of this feature needs nothing from that chain but a floor name. The
            // sidebar still has it here: [DungeonSession.reset] is what clears it, and that is later.
            if (floorTag == null) {
                floorTag = SoloClear.floorTag(DungeonSession.floor).takeIf { tag -> tag != "?" }
            }
            decide()
        }
    }

    /**
     * Called from [DungeonSession.reset]: the last moment a run can still be filed.
     *
     * This is the own-clock fallback and the only place it happens. A run whose summary block never
     * printed still produced a measured span, and the honest thing is to record it in its own unit
     * rather than to throw it away — so the record exists, the row says `own`, and nobody's leaderboard
     * ranks it against a Hypixel time.
     *
     * The floor was captured earlier — by [onTotal] or by [onChatLine] — and never read
     * here: [DungeonSession.floor] is already `null` by the time this runs. That is
     * [SoloClear.refusalFloor]'s lesson, and here it would cost a record rather than a log line:
     * anything that reads the floor after the reset has cleared it files everything under `?`.
     */
    fun reset() {
        decide(fallback = true)
        players = 1
        floorTag = null
        totalMs = 0L
        totalTicks = 0L
        official = null
        filed = false
    }

    /**
     * Files the run if there is something comparable to file, and sends it if it is a record.
     *
     * Hypixel's time wins whenever it is there; ours is only reached at [reset] with [fallback] set.
     * Both are refused if the number does not parse or is not positive — a zero-second M7 is a broken
     * pattern, and an unbeatable record filed from one cannot be undone by playing better.
     */
    private fun decide(fallback: Boolean = false): Result? {
        if (filed) return null
        val floor = floorTag ?: return null
        val timed = timed(official, totalMs, fallback) ?: return null

        filed = true
        val result = evaluate(floor, players, timed.clock, timed.seconds)
        val previous = (result as? Result.Beat)?.previous
        DebugLog.event(
            "run_pb",
            "floor" to floor, "players" to players, "clock" to timed.clock.key, "seconds" to timed.seconds,
            "previous" to (previous ?: -1f), "pb" to (result !is Result.Missed),
            "totalMs" to totalMs, "sent" to Config.runPbs,
        )
        if (result is Result.Missed) return result

        commit(floor, players, timed.clock, timed.seconds)
        val ts = System.currentTimeMillis()
        // The file first, and whatever the network does with the rest of this: the record is local and
        // the leaderboard row is a notification about it.
        append(floor, players, timed.clock, timed.seconds, previous, ts)
        if (Config.runPbs) {
            offer(
                payload(
                    PartyTracker.localName, Config.installId, floor, players, timed.clock, timed.seconds,
                    official, totalMs, totalTicks, previous, ts, TelemetryUpload.modVersion(),
                ),
            )
        }
        return result
    }

    /** A time and the clock that produced it. */
    internal class Timed(val seconds: Float, val clock: Clock)

    /**
     * Which clock this run is timed by and what it says, or null when there is nothing comparable.
     *
     * The whole of the decision the class comment describes, in one pure function so that the run path
     * and `RunPbsTest` cannot disagree about it. Hypixel's statement wins whenever it parses; ours is
     * only reachable with [fallback], which is [reset] and nowhere else.
     *
     * A non-positive time is refused rather than recorded — [SplitPbs.record]'s reason: an unbeatable
     * record filed from a broken pattern cannot be undone by playing better.
     */
    internal fun timed(officialTime: String?, totalMs: Long, fallback: Boolean): Timed? {
        if (officialTime != null) {
            val seconds = DungeonTab.seconds(officialTime)?.toFloat() ?: return null
            return if (seconds > 0f) Timed(seconds, Clock.HYPIXEL) else null
        }
        if (!fallback || totalMs <= 0L) return null
        val seconds = totalMs / 1000f
        return if (seconds > 0f) Timed(seconds, Clock.OWN) else null
    }

    /** What [seconds] would be against the standing record. Reads the store, changes nothing. */
    internal fun evaluate(floor: String, players: Int, clock: Clock, seconds: Float): Result {
        ensureLoaded()
        val previous = best[key(floor, players, clock)]
        return when {
            previous == null -> Result.First
            seconds < previous -> Result.Beat(previous)
            else -> Result.Missed(previous)
        }
    }

    /** Makes [seconds] the standing record. Separate from [evaluate] so a `Missed` cannot write. */
    internal fun commit(floor: String, players: Int, clock: Clock, seconds: Float) {
        ensureLoaded()
        best[key(floor, players, clock)] = seconds
    }

    // --- The row that leaves -----------------------------------------------------------------

    /**
     * The body the receiver reads. Pure over what it is handed, like [RunReport.build] and
     * [SoloClear.payload].
     *
     * `time` is the string Hypixel printed when it printed one, because a leaderboard shows a time to
     * people and `06m 32s` is what they saw in their own chat. `seconds` beside it is the number to
     * sort on, and `timeSource` is what stops a row timed by us from being ranked against one timed by
     * Hypixel. Both identities travel: [Config.installId] is what a board is keyed by across a name
     * change, and the name is what it can print.
     *
     * `previous` is absent on a first-ever record rather than being a zero or a sentinel, exactly as
     * [SplitPbs.Result.First] is not `9999f`.
     */
    internal fun payload(
        player: String?,
        installId: String,
        floor: String,
        players: Int,
        clock: Clock,
        seconds: Float,
        time: String?,
        totalMs: Long,
        totalTicks: Long,
        previous: Float?,
        ts: Long,
        modVersion: String,
    ): JsonObject = JsonObject().apply {
        // Absent rather than empty when the tab list never named the local player: an anonymous row is
        // a row the board can still rank by [installId], and an invented name is a claim about who ran
        // it. [SoloClear.payload] refuses the same field for the same reason.
        player?.let { addProperty("player", it) }
        addProperty("installId", installId)
        addProperty("floor", floor)
        addProperty("players", players)
        time?.let { addProperty("time", it) }
        addProperty("seconds", seconds)
        addProperty("timeSource", clock.key)
        // Context, never a record: our own span and the same run in ticks. A receiver that wants to
        // know how far the two clocks disagree has both numbers here and needs no second endpoint.
        if (totalMs > 0L) addProperty("totalMs", totalMs)
        if (totalTicks > 0L) addProperty("totalTicks", totalTicks)
        previous?.let { addProperty("previous", it) }
        addProperty("ts", ts)
        addProperty("modVersion", modVersion)
    }

    /** One line per record, and nothing but records. See the class comment. */
    private fun append(
        floor: String,
        players: Int,
        clock: Clock,
        seconds: Float,
        previous: Float?,
        ts: Long,
    ) {
        val obj = JsonObject().apply {
            addProperty("floor", floor)
            addProperty("players", players)
            addProperty("clock", clock.key)
            addProperty("seconds", seconds)
            previous?.let { addProperty("previous", it) }
            addProperty("ts", ts)
            addProperty("modVersion", TelemetryUpload.modVersion())
        }
        try {
            Files.createDirectories(FILE.parent)
            Files.writeString(
                FILE, obj.toString() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not append the run record to {}", FILE, e)
        }
    }

    /**
     * Puts one row in the outbox and tries to hand it over now.
     *
     * Written before it is sent, so a failed send is a file that is still there. The immediate attempt
     * is what makes a PB arrive while the player is still looking at the summary block; the file is
     * what makes it arrive at all once the route exists.
     */
    private fun offer(body: JsonObject) {
        val file = try {
            Files.createDirectories(OUTBOX)
            // Millis in the name, fixed width, so a directory listing is already in the right order —
            // TelemetryUpload's queue orders itself the same way and for the same reason.
            val path = OUTBOX.resolve("pb-${System.currentTimeMillis()}.json")
            Files.writeString(path, body.toString(), StandardOpenOption.CREATE_NEW)
            path
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not queue the run record for the leaderboard", e)
            return
        }
        Thread({ walk(listOf(file)) }, "sighteaddons-runpb").apply { isDaemon = true }.start()
    }

    /**
     * Sends whatever is still in the outbox, once, at launch.
     *
     * Its own pass rather than a fourth queue inside [TelemetryUpload], because that one gives up
     * before it starts when [Config.upload] is off — and these two switches are independent in both
     * directions by design ([Config.soloClears] says so in full). A player with telemetry off and a
     * leaderboard on must still get their rows.
     */
    fun flush() {
        if (!Config.runPbs) return
        Thread({
            try {
                if (!Files.isDirectory(OUTBOX)) return@Thread
                val pending = Files.list(OUTBOX).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.name.startsWith("pb-") }.toList()
                }.sortedBy { it.name }
                if (pending.isNotEmpty()) walk(pending)
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Could not send the queued run records", e)
            }
        }, "sighteaddons-runpb-flush").apply { isDaemon = true }.start()
    }

    /**
     * Posts each file and does with it what the answer says.
     *
     * The outcome table is [TelemetryUpload.outcome]'s, which is the one place in this mod that decides
     * what a status code means for a *queue* — including that `400` is about this file and `401` about
     * every file. A `404` lands in `RETRY`, which is exactly right while the route is still being
     * written: the row waits for the leaderboard instead of being thrown away by it.
     */
    private fun walk(files: List<Path>) {
        val endpoint = TelemetryUpload.endpoint() ?: run {
            SighteAddons.LOGGER.warn("No upload endpoint; run records are recorded but not sent")
            return
        }
        val (base, token) = endpoint
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        for (file in files) {
            try {
                if (Files.size(file) > MAX_BYTES) {
                    SighteAddons.LOGGER.warn("Run record {} is too large to send; left in place", file.name)
                    continue
                }
                when (TelemetryUpload.outcome(post(client, base + ROUTE, token, Files.readString(file)))) {
                    TelemetryUpload.Outcome.DONE -> {
                        Files.deleteIfExists(file)
                        SighteAddons.LOGGER.info("Run record {} is on the leaderboard", file.name)
                    }
                    TelemetryUpload.Outcome.REJECTED -> {
                        // Kept, not deleted, and out of the queue: it will never be accepted as it
                        // stands, and oldest-first ordering means one such file in front would hold up
                        // every row behind it forever. Same call TelemetryUpload makes.
                        val out = OUTBOX.resolve("rejected")
                        Files.createDirectories(out)
                        Files.move(file, out.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
                        SighteAddons.LOGGER.warn("Run record {} was rejected; moved to rejected/", file.name)
                    }
                    TelemetryUpload.Outcome.RETRY ->
                        SighteAddons.LOGGER.info("Run record {} waits for the leaderboard", file.name)
                    TelemetryUpload.Outcome.STOP -> {
                        SighteAddons.LOGGER.warn("The leaderboard rejected the token; giving up for this launch")
                        return
                    }
                }
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Could not send run record {}", file.name, e)
            }
        }
    }

    private fun post(client: HttpClient, url: String, token: String, body: String): Int {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("X-Mod-Version", TelemetryUpload.modVersion())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    // --- Reading the file back ---------------------------------------------------------------

    /**
     * Rebuilds the records out of the file, once per launch.
     *
     * The minimum over the lines, per key, which is what makes an append-only file a record store:
     * nothing here trusts the last line to be the best one, so a row appended by an older version, a
     * hand-edited line or two runs filed out of order all still produce the same answer.
     *
     * Defensive line by line, for [ConfigMigration.intOr]'s reason: one malformed row costs that row.
     */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.isRegularFile(FILE)) return
        try {
            Files.readAllLines(FILE).forEach { line ->
                if (line.isBlank()) return@forEach
                val obj = try {
                    JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
                } catch (e: Exception) {
                    null
                } ?: return@forEach
                val floor = ConfigMigration.stringOr(obj, "floor", "").takeIf { it.isNotBlank() } ?: return@forEach
                val players = ConfigMigration.intOr(obj, "players", 0).takeIf { it > 0 } ?: return@forEach
                val clock = Clock.entries.firstOrNull {
                    it.key == ConfigMigration.stringOr(obj, "clock", "")
                } ?: return@forEach
                val seconds = seconds(obj) ?: return@forEach
                val at = key(floor, players, clock)
                val standing = best[at]
                if (standing == null || seconds < standing) best[at] = seconds
            }
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not read {}", FILE, e)
        }
    }

    /** A positive number, or null for a value of any other shape. */
    private fun seconds(obj: JsonObject): Float? {
        val value = obj.get("seconds") ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return try {
            value.asFloat.takeIf { it > 0f }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Everything, for a test that has to start from nothing.
     *
     * Records are `object` state that outlives one test, which is what `CLAUDE.md` says about
     * [ContributionTracker] and [RunReport] — this is the seam that makes it resettable without going
     * near [DungeonSession.reset], which tears down half the mod.
     */
    internal fun forget() {
        best.clear()
        loaded = false
        players = 1
        floorTag = null
        totalMs = 0L
        totalTicks = 0L
        official = null
        filed = false
    }

    /** For a test: pretend the file has been read, so nothing on disk can reach the store. */
    internal fun useEmptyStore() {
        forget()
        loaded = true
    }

}
