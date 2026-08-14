package sighteaddons

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.io.path.name

/**
 * One room's measured clear time, exactly as the receiver's `roomstats.py` folded it: the mean over
 * [n] visits, in ticks.
 *
 * [n] is per *metric*, not per room — a visit routinely times the clear and says nothing about the
 * secrets — so it is the only honest measure of how much this average is worth, and it is what
 * [ContributionTracker.blend] shrinks against.
 */
data class RoomSample(val n: Int, val avgTicks: Double)

/**
 * The measured per-room clear times the weighting blends its seed estimates toward, as resolved for
 * this session. [RoomScores.NONE] is the "nothing measured yet" answer and is not an error.
 *
 * **The metric is [METRIC] — `clearStay` — and picking the wrong one would be silent.** The receiver
 * folds four averages per room and only one of them answers the question the user asked ("how fast
 * does the average player clear this room, KEINE SECRETS"):
 *
 * - `clear` is the same span under the schema-4 anchor, which stamped `enterTick` on the first
 *   *sighting* of anybody. That is an upper bound a walk-through inflates — 145 s for a 1x1 in the
 *   receiver's own example — not a duration.
 * - `afterClear` is checkmark to green: the secret hunt *after* the clear, which is precisely what
 *   "KEINE SECRETS" excludes.
 * - `secretRun` is first secret to last, a secret time and not a clear time at all.
 * - `clearStay` is the span under the stay anchor `clear-001` ships (`RunReport.SCHEMA` 5), and is
 *   the only one of the four that is a clear duration.
 *
 * `roomstats.py` never mixes `clear` and `clearStay` into one mean, so reading the wrong key here
 * would not fail — it would quietly weight rooms by a number that means something else.
 */
class RoomScores private constructor(
    /** The receiver's `generatedTs`, i.e. which fold of `profiles/` these numbers are. 0 when none. */
    val generatedTs: Long,
    /** Room entries the document carried, whether or not any of them had a usable [METRIC] sample. */
    val rooms: Int,
    private val byName: Map<String, RoomSample>,
) {
    /** Of [rooms], the ones that actually carry a [METRIC] average. Normally far fewer. */
    val sampled get() = byName.size

    /**
     * The middle of the measured distribution, and the point [ContributionTracker] normalises
     * against so a weight is a room's standing among rooms rather than a number of seconds. Null
     * when nothing is measured, which is the case that has to yield the seeds untouched.
     *
     * The median rather than the mean, because the distribution is not symmetric: the only real
     * numbers on the box today run from 0.75 s to 36.5 s, and one long room drags a mean far more
     * than it moves a median. The normaliser has to be robust before the clamp is, or the clamp
     * ends up doing the work.
     *
     * That 0.75–36.5 s range is the box's **`clear`** averages, not [METRIC] — `clearStay` has
     * `n = 0` for every room there, which is the whole reason this class has a null case. So the
     * argument for a median is calibrated on the very metric the class KDoc above says must never be
     * confused with the one it reads. It is the only spread that exists; naming it is the honest
     * version. See `TIME_EXPONENT` in [ContributionTracker], which rests on the same proxy.
     */
    val medianTicks: Double? = median(byName.values.map { it.avgTicks })

    fun of(name: String): RoomSample? = byName[name]

    companion object {
        /**
         * Layer 3: nothing measured. Every room is worth exactly its seed, which is the ordinary
         * first case rather than a degraded one — see [RoomStats].
         */
        val NONE = RoomScores(0L, 0, emptyMap())

        /** The one of the receiver's four averages that is a clear duration. See the class KDoc. */
        const val METRIC = "clearStay"

        /**
         * Parses the receiver's `roomstats.json` **verbatim** — the file this reads is the file
         * `roomstats.py` writes, with no conversion step in between. That is deliberate: a
         * derived format would need a transform at whatever point the file is produced, and a
         * transform is a thing that can be wrong in a way nothing here could detect.
         *
         * Returns null for anything it cannot read, and the caller falls back to [NONE]. Total on
         * purpose: this file arrives from outside the jar, and a weighting that throws on startup
         * because a cached download was truncated would cost the run rather than the weights.
         * Individual entries are skipped rather than fatal for the same reason — a room with no
         * sample is the normal case, and a room with a broken one is a room, not a document.
         */
        fun parse(json: String): RoomScores? {
            return try {
                val root = JsonParser.parseString(json).asJsonObject
                val entries = root.getAsJsonArray("rooms")
                    ?: throw IllegalArgumentException("no rooms array")
                val byName = HashMap<String, RoomSample>()
                var rooms = 0
                for (element in entries) {
                    val obj = element.asJsonObject
                    val name = obj["name"]?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    rooms++
                    val metric = obj.getAsJsonObject(METRIC) ?: continue
                    val n = metric["n"]?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                    val average = metric["avgTicks"]?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
                    // `n: 0` comes with `avgTicks: null` and is every room on the box today. Not a
                    // defect and not worth a log line — it is a room nobody has cleared under the
                    // stay anchor yet.
                    if (n < 1 || average <= 0.0) continue
                    byName[name] = RoomSample(n, average)
                }
                RoomScores(root["generatedTs"]?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L, rooms, byName)
            } catch (e: Exception) {
                SighteAddons.LOGGER.error("Room scores are not readable — falling back to the seed values", e)
                null
            }
        }

        private fun median(values: Collection<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}

/**
 * Where the measured room averages come from, in three layers, all three of which this mod now
 * implements.
 *
 * 1. **Freshly fetched** from the receiver's `GET /roomstats` at game start, on a daemon thread —
 *    [start]. What comes back is written to [cachePath] and installed for this session, and every
 *    way that can fail falls through to layer 2.
 * 2. **The cached file at [cachePath]**, read once per session if it is there: what layer 1 left
 *    behind at some previous launch, or a file dropped in by hand.
 * 3. **[RoomScores.NONE]**, i.e. the seed estimates in [ContributionTracker] and nothing else.
 *
 * **Absent is the ordinary case, not an error**, and it has to yield exactly the seeds — a room with
 * no measurement must not be silently treated as a fast one. Malformed falls back the same way, for
 * the same reason, and so does every failure of layer 1: no network, a dead host, a `503`, an HTML
 * error page with a `200` on it, a body that stops halfway. None of them is worth a stack trace and
 * none of them may cost the run.
 *
 * The resolution is cached for the session on purpose. Points are compared *between party members*
 * within one run, so a weight that changed mid-run would make two players' standings disagree about
 * a room they both watched. That is also why [adopt] refuses to install a fetch that arrives after
 * the session has already resolved: a document that lands late is written to the cache and takes
 * effect at the next launch, never in the middle of the run it would change. One resolution per
 * launch, recorded by [RoomScores.generatedTs] in the debug log and in `history.jsonl`, is what
 * keeps a past run's numbers explainable after the scores have moved on.
 */
object RoomStats {
    /** Same name as the receiver's file, because it is the receiver's file. */
    const val FILE_NAME = "roomstats.json"

    /**
     * The `ETag` of the document currently in [cachePath], beside it.
     *
     * A separate file rather than a field inside the cache, because the cache is the receiver's
     * bytes **verbatim** — [RoomScores.parse] reads the document the receiver writes, with no
     * conversion step, and a wrapper object here would be exactly the transform that class exists
     * to avoid.
     */
    const val ETAG_FILE_NAME = "roomstats.etag"

    /**
     * The receiver matches this path exactly: `/roomstats.json`, `/roomstats/` and `/roomstats?v=1`
     * are all `404`, as is a POST. Measured against the live box on 2026-08-14, not assumed.
     */
    const val PATH = "/roomstats"

    /**
     * The document is 107 KB today and grows with the number of rooms Hypixel has, not with the
     * number of runs. This is the only place the mod reads a body of unknown length off a network,
     * so it reads a bounded one — mirroring [TelemetryUpload]'s habit of never putting a file on the
     * wire the receiver would refuse.
     */
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** Long enough for a box that is up and slow, short enough that a black hole is not forever. */
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    private var resolved: RoomScores? = null

    /** Layer 2's location: beside `history.jsonl`, and where layer 1 writes its cache. */
    fun cachePath(): Path = cacheDir().resolve(FILE_NAME)

    private fun cacheDir(): Path = FabricLoader.getInstance().configDir.resolve(SighteAddons.ID)

    /**
     * The scores in force for this session, resolved on first use and then fixed.
     *
     * Synchronised because layer 1 runs on its own thread: the read below and [adopt] are the two
     * halves of the same decision — whether this session has committed to a set of numbers yet —
     * and a race between them is a run scored against two different weightings.
     */
    val scores: RoomScores
        @Synchronized get() = resolved ?: resolve().also { resolved = it }

    /**
     * Installs a resolution directly — what the tests use so a weight never depends on whether the
     * machine running them happens to have a cache file. Passing null makes the next read resolve
     * again from disk. Layer 1 goes through [adopt] instead, which will not overwrite a resolution
     * that is already in force.
     */
    @Synchronized
    internal fun use(scores: RoomScores?) {
        resolved = scores
    }

    /** Layer 2, as a pure function of a path, so the file handling is testable without a game. */
    internal fun read(path: Path): RoomScores? = try {
        if (Files.exists(path)) RoomScores.parse(Files.readString(path)) else null
    } catch (e: Exception) {
        SighteAddons.LOGGER.error("Could not read room scores {}", path, e)
        null
    }

    private fun resolve(): RoomScores {
        val scores = read(cachePath()) ?: RoomScores.NONE
        announce(scores, if (scores === RoomScores.NONE) "seeded" else "cache")
        return scores
    }

    private fun announce(scores: RoomScores, source: String) {
        SighteAddons.LOGGER.info(
            "Room scores ({}): {} rooms, {} with a {} average (generatedTs {})",
            source, scores.rooms, scores.sampled, RoomScores.METRIC, scores.generatedTs,
        )
        DebugLog.event(
            "room_scores",
            "source" to source,
            "generatedTs" to scores.generatedTs,
            "rooms" to scores.rooms,
            "sampled" to scores.sampled,
            "medianTicks" to scores.medianTicks,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Layer 1: the fetch.
    // ---------------------------------------------------------------------------------------------

    /** What one attempt at layer 1 came back with. Every one of the three is an ordinary outcome. */
    internal sealed interface Fetched {
        /** A `200` whose body parsed. The only outcome that may replace the cache. */
        class Fresh(val body: String, val etag: String?, val scores: RoomScores) : Fetched

        /** A `304`: the cached document is the current one, so there is nothing to write or install. */
        object Current : Fetched

        /** Everything else at all, named for the log rather than thrown. */
        class Failed(val reason: String) : Fetched
    }

    /**
     * Starts layer 1 and returns immediately.
     *
     * **The same pattern [TelemetryUpload.start] uses**, for the same reason and with the same
     * ceiling: a named daemon thread at game start, everything inside it wrapped, nothing on the
     * client thread and nothing during a run. Networking is never allowed to be why the game is slow
     * to start or slow to tick — and the worst case here, a host that accepts a connection and then
     * says nothing, costs a parked daemon thread and the seeds.
     *
     * Its own thread rather than a step inside the upload's: the upload hands over a whole backlog
     * of run reports and a 60 s timeout per file, and the scores would then arrive after the first
     * room of the evening rather than before it.
     *
     * **Gated on the `/sa` upload switch**, which is the conservative reading rather than a required
     * one. The switch is worded about *sending* reports and this request sends nothing but a
     * revalidation header — but it is the only control a player has over whether this mod talks to
     * the analysis server at all, and a launch that quietly contacts it anyway would make the
     * one-time disclosure less true than it reads. The cost is that such an install keeps its
     * cached scores, or the seeds, which is exactly the behaviour before this feature existed.
     */
    fun start() {
        if (!Config.upload) {
            SighteAddons.LOGGER.info("Room scores: not fetching, telemetry is off in /sa")
            return
        }
        Thread({
            try {
                val started = System.currentTimeMillis()
                val client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
                val outcome = refresh(client, TelemetryUpload.PUBLIC_URL, cacheDir())
                DebugLog.event(
                    "room_scores_fetch",
                    "outcome" to when (outcome) {
                        is Fetched.Fresh -> "fresh"
                        is Fetched.Current -> "current"
                        is Fetched.Failed -> "failed"
                    },
                    "reason" to (outcome as? Fetched.Failed)?.reason,
                    "generatedTs" to (outcome as? Fetched.Fresh)?.scores?.generatedTs,
                    "ms" to System.currentTimeMillis() - started,
                )
            } catch (e: Exception) {
                // Belt and braces: everything below already returns a Failed rather than throwing,
                // and an exception escaping here would still only reach this thread — but the room
                // weights are a nicety and the game is not.
                SighteAddons.LOGGER.warn("Room scores fetch failed; keeping the cached copy", e)
            }
        }, "sighteaddons-roomstats").apply { isDaemon = true }.start()
    }

    /**
     * One attempt: fetch, cache what came back, and install it if this session has not resolved yet.
     *
     * Takes its client, base URL and directory so the whole of layer 1 is exercisable without a game
     * directory and against a server that can be made to misbehave on purpose.
     */
    internal fun refresh(client: HttpClient, base: String, dir: Path): Fetched {
        val outcome = fetch(client, base, cachedEtag(dir))
        when (outcome) {
            is Fetched.Fresh -> install(dir, outcome)
            is Fetched.Current -> SighteAddons.LOGGER.info("Room scores are already current")
            // Info, not warn: a box that is down, a player with no connection and a receiver too old
            // to know this path are all ordinary, and all three cost nothing but freshness.
            is Fetched.Failed -> SighteAddons.LOGGER.info(
                "Room scores not fetched ({}); keeping the cached copy", outcome.reason,
            )
        }
        return outcome
    }

    /**
     * Writing the cache and taking the new numbers into use, under the same lock the resolution
     * holds. The lock is taken *after* the request rather than around it — a client thread asking
     * for a room's weight must never wait on a network — but the write and the adoption together
     * have to be atomic against [resolve], or a reader could catch the cache file mid-replacement.
     */
    @Synchronized
    private fun install(dir: Path, fresh: Fetched.Fresh) {
        // Order matters, and this is the cheap half of it: a cache that could not be written costs
        // the *next* launch, so the fetched numbers are still worth having for this one.
        store(dir, fresh.body, fresh.etag)
        adopt(fresh.scores)
    }

    /**
     * Asks the receiver for the document, sending [etag] as `If-None-Match` when there is one.
     *
     * **Total.** Everything that can go wrong on a network comes back as [Fetched.Failed]: no route,
     * a refused connection, a timeout, a `503` because the box has not folded a document yet, a
     * `404` from a receiver too old to serve this path, an HTML error page from a proxy with a `200`
     * on it, a body that stops halfway. The caller keeps whatever it already had.
     *
     * **A body only becomes the cache if it parses**, which is what keeps a proxy's error page out
     * of a file the next launch would read as scores.
     */
    internal fun fetch(
        client: HttpClient,
        base: String,
        etag: String?,
        maxBytes: Long = MAX_BYTES,
    ): Fetched = try {
        val request = HttpRequest.newBuilder(URI.create("$base$PATH"))
            .timeout(REQUEST_TIMEOUT)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        when (val code = response.statusCode()) {
            // The whole point of the ETag: 107 KB that did not change costs an empty reply.
            304 -> {
                response.body().close()
                Fetched.Current
            }
            200 -> {
                val body = body(response.body(), maxBytes)
                val scores = body?.let(RoomScores::parse)
                when {
                    body == null -> Fetched.Failed("over $maxBytes bytes")
                    scores == null -> Fetched.Failed("unreadable document")
                    else -> Fetched.Fresh(body, response.headers().firstValue("ETag").orElse(null), scores)
                }
            }
            else -> {
                response.body().close()
                Fetched.Failed("HTTP $code")
            }
        }
    } catch (e: Exception) {
        // Including the interrupted read of a truncated body, which arrives here as an IOException.
        Fetched.Failed(e.javaClass.simpleName + ": " + (e.message ?: ""))
    }

    /** Null when the body runs past [maxBytes] rather than reading it anyway. */
    private fun body(stream: InputStream, maxBytes: Long): String? = stream.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) return null
            out.write(buffer, 0, read)
        }
        // The receiver serves `application/json; charset=utf-8` and room names carry non-ASCII.
        out.toString(StandardCharsets.UTF_8)
    }

    /**
     * The tag to revalidate with, or null to ask for the whole document.
     *
     * **Null whenever the document itself is not there**, which is the one shape of this that could
     * wedge an install permanently: an `ETag` file left behind by a cache somebody deleted would
     * earn a `304`, and a `304` with no cached bytes behind it is a session on the seeds — at every
     * launch, until the receiver's document happened to change.
     */
    internal fun cachedEtag(dir: Path): String? = try {
        if (!Files.isRegularFile(dir.resolve(FILE_NAME))) {
            null
        } else {
            val tag = dir.resolve(ETAG_FILE_NAME)
            if (Files.isRegularFile(tag)) Files.readString(tag).trim().takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Exception) {
        SighteAddons.LOGGER.warn("Could not read the cached room scores ETag in {}", dir, e)
        null
    }

    /**
     * Caches the document **verbatim** — the receiver's own bytes, so the file this writes is the
     * file [RoomScores.parse] already reads and no transform sits between them.
     *
     * Through `.part` and a rename, the same shape [RunReport] publishes a run report with and the
     * same one the receiver's own fold uses to produce this document: a half-written cache is worse
     * than none, because it survives the launch that produced it and is read as scores by the next
     * one. `.part` is not a name anything reads.
     *
     * The document is written before the tag, and never the other way round. An `ETag` naming bytes
     * that are not on disk is the wedge [cachedEtag] guards against; this is the other half of that
     * guard, at the point where a crash between the two writes would create one.
     */
    internal fun store(dir: Path, body: String, etag: String?): Boolean = try {
        Files.createDirectories(dir)
        replace(dir.resolve(FILE_NAME), body)
        val tag = dir.resolve(ETAG_FILE_NAME)
        // No tag from the receiver means no revalidation next time, so the stale one has to go —
        // otherwise it would be offered for a document it no longer names.
        if (etag == null) Files.deleteIfExists(tag) else replace(tag, etag)
        true
    } catch (e: Exception) {
        // Costs the next launch its head start and nothing else, so it is a warning and not a
        // reason to abandon the numbers already in hand.
        SighteAddons.LOGGER.warn("Could not cache the room scores in {}", dir, e)
        false
    }

    /**
     * Deliberately a copy of `RunReport.replace` rather than a shared helper: that file is under
     * evaluation for `runloss-001` on another branch, and a refactor across it would be a second
     * feature riding on this one. Same shape, same reason, and worth unifying once both have landed.
     */
    private fun replace(file: Path, body: String) {
        val tmp = file.resolveSibling("${file.name}.part")
        Files.writeString(tmp, body)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Takes a freshly fetched document into use for this session, **unless the session has already
     * resolved**, and returns whether it did.
     *
     * That refusal is the feature, not a safety net. Points are compared between party members
     * within a run and a room's weight is read at the moment it is cleared, so scores that changed
     * halfway through would make one player's standing incomparable with the next player's — and
     * with its own first half. A document that arrives late is already in the cache by the time this
     * says no, so it is what the next launch resolves.
     */
    @Synchronized
    internal fun adopt(fresh: RoomScores): Boolean {
        if (resolved != null) return false
        resolved = fresh
        announce(fresh, "fetch")
        return true
    }
}
