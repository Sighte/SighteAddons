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
 *  - **The file** is permanent and local. Every solo run is appended, and a personal best is just the
 *    minimum over it — the same design as [RoomHistory], for the same reason: a file that rewrites its
 *    own history cannot be trusted about it.
 *  - **The announcement** is one `POST /v1/solo_clear` to the receiver, which relays it into a Discord
 *    channel and stores nothing. One attempt, no queue, no retry. That mirrors the receiver's own
 *    decision about the same message: an announcement whose moment has passed is not worth resending,
 *    and the run itself is already recorded — here and in [RunReport].
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

    /** Only a run that showed a roster, and never showed a second person in it. */
    val solo get() = seenAlone && !withCompany

    /** Called from [DungeonSession.reset]: the flags are per run, like everything else there. */
    fun reset() {
        seenAlone = false
        withCompany = false
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
     * Best time per floor, in two units that are never compared with each other.
     *
     * **Hypixel's seconds and our ticks are different measurements of the same run**, and mixing them
     * under one key is the failure the [RoomHistory.SECRETS] rename exists to avoid:
     * [DungeonSession.runTicks] starts at calibration rather than at the door, so it is systematically
     * short of the official time. Comparing a run timed one way against a record timed the other would
     * hand out records for the clock rather than for the run.
     *
     * So a run is compared against the record in *its own* unit: Hypixel's when the tab list gave one,
     * ours when it did not. A player whose tab list starts carrying the row mid-history simply starts a
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
     * The body the receiver reads. Pure over what it is handed, like [RunReport.build].
     *
     * Only the fields this mod can actually answer for. Crypts, the Prince and the Mimic are **not**
     * sent, because nothing here tracks them — the receiver prints `?` for a field it was not given,
     * which is the true answer, and a `false` we invented would be a claim about the run.
     */
    internal fun payload(
        player: String,
        floor: String,
        time: String,
        secrets: Int?,
        deaths: Int,
        pb: Boolean,
    ): JsonObject = JsonObject().apply {
        addProperty("player", player)
        addProperty("floor", floor)
        addProperty("time", time)
        // Absent rather than 0 when the tab list never carried the row: no secrets found and no count
        // read are different facts, and the receiver spells the second one `?`.
        secrets?.let { addProperty("secrets", it) }
        addProperty("deaths", deaths)
        addProperty("pb", pb)
    }

    /**
     * One finished solo run: appended to the file, then announced if the switch is on.
     *
     * Called from the run-end headline and from nowhere else — the same single call site that may claim
     * `complete = true` for [RunReport]. The paths that write a report on the way out of a floor
     * (`JOIN`, `DISCONNECT`) deliberately do not reach here: a run that was left is not a clear.
     */
    fun onRunEnd() {
        if (!Config.soloClears || !solo) return
        val ticks = DungeonSession.runTicks
        if (ticks <= 0) return
        // The name captured during the run, for [RunReport.uploader]'s reason: the client's own field
        // is not reliably readable on every path a run can end on.
        val player = PartyTracker.localName ?: return

        val floor = floorTag(DungeonSession.floor)
        val official = DungeonTab.elapsed
        val seconds = official?.let(DungeonTab::seconds)
        ensureLoaded()

        // Compared in the unit it was measured in — see [bestSeconds].
        val previous = if (seconds != null) bestSeconds[floor] else bestTicks[floor]
        val current = seconds ?: ticks
        val pb = previous == null || current < previous

        val secrets = DungeonTab.secretsFound
        val deaths = ContributionTracker.deaths
        // Before the announcement, and whatever the announcement does: the file is the record and the
        // message is a notification about it. A Discord outage must not cost the history.
        append(floor, ticks, seconds, secrets, deaths, pb, System.currentTimeMillis())
        bestTicks[floor] = minOf(bestTicks[floor] ?: ticks, ticks)
        if (seconds != null) bestSeconds[floor] = minOf(bestSeconds[floor] ?: seconds, seconds)

        DebugLog.event(
            "solo_clear",
            "floor" to floor, "pb" to pb, "ticks" to ticks,
            // `-` when Hypixel's row was never read, which is the one thing a real session has to
            // settle here: our clock is the fallback, not the intent. See [DungeonTab.ELAPSED].
            "hypixelTime" to (official ?: "-"),
            "secrets" to (secrets ?: -1), "deaths" to deaths,
        )
        post(payload(player, floor, official ?: DungeonGrid.formatTicks(ticks), secrets, deaths, pb).toString())
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
     * Appends one line. `seconds` is absent when the tab list never gave Hypixel's time, which is what
     * keeps the two units apart in [fold] rather than defaulting one into the other.
     */
    private fun append(
        floor: String,
        ticks: Int,
        seconds: Int?,
        secrets: Int?,
        deaths: Int,
        pb: Boolean,
        ts: Long,
    ) {
        val obj = JsonObject()
        obj.addProperty("floor", floor)
        obj.addProperty("ticks", ticks)
        seconds?.let { obj.addProperty("seconds", it) }
        secrets?.let { obj.addProperty("secrets", it) }
        obj.addProperty("deaths", deaths)
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
                val floor = obj["floor"].asString
                val tick = obj["ticks"].asInt
                ticks[floor] = minOf(ticks[floor] ?: tick, tick)
                obj["seconds"]?.asInt?.let { seconds[floor] = minOf(seconds[floor] ?: it, it) }
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
