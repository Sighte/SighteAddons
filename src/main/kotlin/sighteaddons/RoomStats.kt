package sighteaddons

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

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
 * Where the measured room averages come from, in three layers, of which **this mod implements the
 * bottom two**.
 *
 * 1. **Freshly fetched.** Not built, and deliberately not stubbed: the receiver's `do_GET` answers
 *    `/health` and 404s everything else, so there is no endpoint to call. The endpoint is a receiver
 *    feature and the receiver always ships first — see `scores-fetch-001` in `feature_list.json`.
 *    When it exists, the fetch writes [cachePath] and calls [use]; nothing else here moves.
 * 2. **The cached file at [cachePath]**, read once per session if it is there. Today nothing writes
 *    it, so in practice it is a file a player or a session can drop in by hand — which is also how
 *    the layer is exercised before layer 1 exists.
 * 3. **[RoomScores.NONE]**, i.e. the seed estimates in [ContributionTracker] and nothing else.
 *
 * **Absent is the ordinary case, not an error**, and it has to yield exactly the seeds — a room with
 * no measurement must not be silently treated as a fast one. Malformed falls back the same way, for
 * the same reason.
 *
 * The resolution is cached for the session on purpose. Points are compared *between party members*
 * within one run, so a weight that changed mid-run would make two players' standings disagree about
 * a room they both watched. One resolution per launch, recorded by [RoomScores.generatedTs] in the
 * debug log and in `history.jsonl`, is what keeps a past run's numbers explainable after the scores
 * have moved on.
 */
object RoomStats {
    /** Same name as the receiver's file, because it is the receiver's file. */
    const val FILE_NAME = "roomstats.json"

    private var resolved: RoomScores? = null

    /** Layer 2's location: beside `history.jsonl`, where a later fetch would write its cache. */
    fun cachePath(): Path = FabricLoader.getInstance().configDir.resolve("sighteaddons/$FILE_NAME")

    /** The scores in force for this session, resolved on first use and then fixed. */
    val scores: RoomScores
        get() = resolved ?: resolve().also { resolved = it }

    /**
     * Installs a resolution directly — the seam layer 1 will use once the endpoint exists, and the
     * one the tests use so a weight never depends on whether the machine running them happens to
     * have a cache file. Passing null makes the next read resolve again from disk.
     */
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
        SighteAddons.LOGGER.info(
            "Room scores: {} rooms, {} with a {} average (generatedTs {})",
            scores.rooms, scores.sampled, RoomScores.METRIC, scores.generatedTs,
        )
        DebugLog.event(
            "room_scores",
            "source" to if (scores === RoomScores.NONE) "seeded" else "cache",
            "generatedTs" to scores.generatedTs,
            "rooms" to scores.rooms,
            "sampled" to scores.sampled,
            "medianTicks" to scores.medianTicks,
        )
        return scores
    }
}
