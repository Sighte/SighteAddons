package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layer 2 of [RoomStats]: the cached scores file on disk.
 *
 * The file this reads is the receiver's `roomstats.json` **verbatim**, with no conversion step, so
 * the fixtures below are shaped exactly like `roomstats.py`'s output — including the null-filled
 * metric blocks it writes for a room nobody has cleared yet, which is every room on the box today.
 *
 * Two failure modes are checked harder than the happy path, because both are silent. Reading the
 * wrong one of the receiver's four averages would weight rooms by a number that means something
 * else and nothing would say so; and a file that is absent or broken must fall back to the seeds
 * rather than throw, because a weighting that takes the mod down on startup costs the run.
 */
class RoomStatsTest {
    /**
     * A metric with no visits behind it, exactly as `roomstats.py` writes one — the nulls are its
     * output and not a shorthand for it, and today this is the `clearStay` block of all 83 rooms.
     */
    private val EMPTY = """{"n":0,"avgTicks":null,"avgSeconds":null,"minTicks":null,"maxTicks":null}"""

    /** One room as `roomstats.py` writes it: four metric blocks, most of them empty. */
    private fun room(
        name: String,
        clearStay: String = EMPTY,
        clear: String = EMPTY,
        afterClear: String = EMPTY,
        secretRun: String = EMPTY,
    ) = """
        {"name":"$name","type":"ROOM","shape":"1x1","visits":2,"floors":{"M7":2},
         "clear":$clear,"clearStay":$clearStay,"secretRun":$secretRun,"afterClear":$afterClear}
    """.trimIndent()

    private fun sampled(n: Int, avgTicks: Double) =
        """{"n":$n,"avgTicks":$avgTicks,"avgSeconds":${avgTicks / 20.0},"minTicks":1,"maxTicks":9}"""

    private fun document(vararg rooms: String) = """
        {"v":1,"generatedTs":1786709674349,"stayAnchorSchema":5,
         "runs":9,"visits":157,"incomplete":1,"unnamed":1,"preCleared":7,"malformed":0,
         "rooms":[${rooms.joinToString(",")}]}
    """.trimIndent()

    private fun write(dir: Path, body: String): Path =
        dir.resolve(RoomStats.FILE_NAME).also { Files.writeString(it, body) }

    /**
     * **The ordinary first case, and it is not an error.** Nothing writes the cache today — the
     * fetch that will is `scores-fetch-001`, which is blocked on the receiver serving the file at
     * all — so an absent file is what every install has, and it has to resolve to the seeds rather
     * than to a complaint or to a room silently treated as fast.
     */
    @Test
    fun `an absent scores file is not an error`(@TempDir dir: Path) {
        assertNull(RoomStats.read(dir.resolve(RoomStats.FILE_NAME)))
        // Which is what the resolution turns into: layer 3, every room worth exactly its seed.
        assertEquals(0, RoomScores.NONE.rooms)
        assertEquals(0, RoomScores.NONE.sampled)
        assertNull(RoomScores.NONE.medianTicks)
        assertEquals(0L, RoomScores.NONE.generatedTs)
    }

    @Test
    fun `the receiver's own document is read as it is written`(@TempDir dir: Path) {
        val path = write(dir, document(room("Ice Fill", clearStay = sampled(4, 300.0))))
        val scores = RoomStats.read(path)
        assertNotNull(scores)
        assertEquals(1786709674349L, scores!!.generatedTs, "generatedTs is what identifies a weighting")
        assertEquals(1, scores.rooms)
        assertEquals(1, scores.sampled)
        assertEquals(RoomSample(4, 300.0), scores.of("Ice Fill"))
        assertEquals(300.0, scores.medianTicks)
    }

    /**
     * **`clearStay` and nothing else.** The receiver folds four averages per room and three of them
     * answer a different question: `clear` is the same span under the schema-4 anchor, which a
     * walk-through inflates into an upper bound; `afterClear` is the post-checkmark secret hunt,
     * which the user's model explicitly excludes ("KEINE SECRETS"); `secretRun` is a secret time and
     * not a clear time at all.
     *
     * The room below is the exact shape of every room on the box today — a populated `clear` block
     * beside an empty `clearStay` — and it must contribute no measurement whatsoever. Reading the
     * wrong key would not fail anywhere: it would produce plausible numbers off the wrong metric.
     */
    @Test
    fun `only the stay-anchored clear counts, and the other three averages are ignored`(@TempDir dir: Path) {
        val path = write(
            dir,
            document(
                room(
                    "Altar",
                    clear = sampled(1, 730.0),
                    afterClear = sampled(6, 900.0),
                    secretRun = sampled(3, 400.0),
                ),
            ),
        )
        val scores = RoomStats.read(path)!!
        assertEquals(1, scores.rooms, "the room is in the document")
        assertEquals(0, scores.sampled, "and it must contribute no measurement")
        assertNull(scores.of("Altar"))
        assertNull(scores.medianTicks, "with nothing measured there is no distribution to normalise against")
    }

    /**
     * The state of the box right now, as a fixture: 83 rooms, 9 of them with an old-anchor `clear`
     * average, and `clearStay` empty for every single one because no schema 5 build has been
     * released. The correct reading of that file is "nothing measured yet", and this is the case
     * that says so.
     */
    @Test
    fun `a document where nothing is stay-anchored yet reads as nothing measured`(@TempDir dir: Path) {
        val rooms = (1..83).map { index ->
            if (index <= 9) room("Room $index", clear = sampled(1, index * 80.0)) else room("Room $index")
        }
        val scores = RoomStats.read(write(dir, document(*rooms.toTypedArray())))!!
        assertEquals(83, scores.rooms)
        assertEquals(0, scores.sampled)
        assertNull(scores.medianTicks)
        // Which through the model is exactly the seed, for every room in it.
        assertEquals(0.75, ContributionTracker.blend(0.75, scores.of("Room 1"), scores.medianTicks), 0.0)
    }

    @Test
    fun `the median is the middle of the measured rooms`(@TempDir dir: Path) {
        val odd = RoomStats.read(
            write(
                dir,
                document(
                    room("A", clearStay = sampled(2, 10.0)),
                    room("B", clearStay = sampled(2, 40.0)),
                    room("C", clearStay = sampled(2, 4000.0)),
                ),
            ),
        )!!
        assertEquals(40.0, odd.medianTicks, "the outlier must not drag the normaliser — that is why it is a median")

        val even = RoomStats.read(
            write(
                dir.resolve("even").also { Files.createDirectories(it) },
                document(
                    room("A", clearStay = sampled(2, 10.0)),
                    room("B", clearStay = sampled(2, 30.0)),
                    room("C", clearStay = sampled(2, 50.0)),
                    room("D", clearStay = sampled(2, 4000.0)),
                ),
            ),
        )!!
        assertEquals(40.0, even.medianTicks, "an even count averages the two middle rooms")
    }

    /**
     * A broken file must cost the weights and never the run. This one arrives from outside the jar —
     * eventually over a network — so half a download, a truncated write or a hand-edit are all
     * ordinary, and every one of them has to land on the seeds.
     */
    @Test
    fun `a file that cannot be read falls back rather than throwing`(@TempDir dir: Path) {
        for ((label, body) in listOf(
            "empty" to "",
            "truncated" to """{"generatedTs":1,"rooms":[{"name":"Ice Fill","clearStay":{"n":4,""",
            "not json" to "<html>404 not found</html>",
            "an array, not the document" to """[{"name":"Ice Fill"}]""",
            "no rooms array" to """{"generatedTs":1,"runs":9}""",
        )) {
            val path = write(dir, body)
            assertNull(RoomStats.read(path), "$label should have fallen back to the seeds")
        }
    }

    /**
     * One bad entry costs its own room and not the document. A room with no sample is the normal
     * case rather than a defect, so the same tolerance covers both — but a room whose numbers are
     * nonsense must not be able to take the other 82 rooms' weights down with it.
     */
    @Test
    fun `a broken entry costs its own room and not the document`(@TempDir dir: Path) {
        val scores = RoomStats.read(
            write(
                dir,
                document(
                    room("Good", clearStay = sampled(3, 200.0)),
                    """{"name":"No metric block","visits":1}""",
                    """{"name":"Null average","clearStay":{"n":5,"avgTicks":null}}""",
                    """{"name":"Zero ticks","clearStay":{"n":5,"avgTicks":0.0}}""",
                    """{"name":"Negative ticks","clearStay":{"n":5,"avgTicks":-40.0}}""",
                    """{"visits":1,"clearStay":{"n":5,"avgTicks":100.0}}""",
                ),
            ),
        )
        assertNotNull(scores)
        assertEquals(RoomSample(3, 200.0), scores!!.of("Good"), "the good room survived its neighbours")
        assertEquals(1, scores.sampled)
        assertEquals(5, scores.rooms, "the nameless entry is not a room; the other five are")
        assertEquals(200.0, scores.medianTicks)
    }

    /**
     * Layer 2 is a *cache*, so it sits where a cache goes: beside `history.jsonl`, in the mod's own
     * config directory. That is the path the fetch of `scores-fetch-001` will write, which is
     * what makes adding layer 1 wiring rather than surgery.
     */
    @Test
    fun `the cache lives beside the rest of the mod's own files`() {
        val path = RoomStats.cachePath()
        assertEquals(RoomStats.FILE_NAME, path.fileName.toString())
        assertEquals("sighteaddons", path.parent.fileName.toString())
    }

    /** `use(null)` puts the resolution back, so no test can pin scores for the ones after it. */
    @Test
    fun `an installed resolution can be taken back off`() {
        RoomStats.use(RoomScores.parse("""{"generatedTs":42,"rooms":[]}"""))
        assertEquals(42L, RoomStats.scores.generatedTs)
        RoomStats.use(null)
        assertTrue(RoomStats.scores.generatedTs != 42L, "the pinned resolution outlived the test that set it")
    }
}
