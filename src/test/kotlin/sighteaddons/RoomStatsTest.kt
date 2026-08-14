package sighteaddons

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.name

/**
 * Layers 1 and 2 of [RoomStats]: fetching the scores document, and the cached copy on disk.
 *
 * The file this reads is the receiver's `roomstats.json` **verbatim**, with no conversion step, so
 * the fixtures below are shaped exactly like `roomstats.py`'s output — including the null-filled
 * metric blocks it writes for a room nobody has cleared yet, which is every room on the box today.
 *
 * Two failure modes are checked harder than the happy path, because both are silent. Reading the
 * wrong one of the receiver's four averages would weight rooms by a number that means something
 * else and nothing would say so; and a file that is absent or broken must fall back to the seeds
 * rather than throw, because a weighting that takes the mod down on startup costs the run.
 *
 * **The layer 1 cases below run against a real HTTP server on loopback**, which is deliberate and is
 * the only way to measure the thing this feature is: not "does a status code map to an outcome" but
 * "does a refused connection, a body that stops halfway, or an HTML error page with a `200` on it
 * leave the run alone". A truncated read in particular exists only on a socket — no pure function
 * can produce one — and it is exactly the failure a flaky connection hands the mod. The server binds
 * an ephemeral port on the loopback address, answers one request and is closed in the same test; no
 * case here reaches a network.
 */
class RoomStatsTest {
    /**
     * [RoomStats] holds its resolution for the whole process, so a test that installs one would
     * otherwise decide the answer for every test after it — and layer 1 refuses to adopt anything
     * once a resolution is in force, so a leftover would silently turn a fetch case into a no-op.
     */
    @BeforeEach
    @AfterEach
    fun clearTheResolution() = RoomStats.use(null)

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

    // ─── Layer 1: the fetch ───────────────────────────────────────────────────────────────────────

    /** One request the receiver saw, which is how the cases below check what the mod asked for. */
    private class Asked(val method: String, val path: String, val ifNoneMatch: String?, val authorization: String?)

    /** A receiver on loopback: one ephemeral port, whatever answer the case needs, closed with it. */
    private class Box(private val answer: (HttpExchange) -> Unit) : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val asked = mutableListOf<Asked>()
        val base: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                asked += Asked(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("If-None-Match"),
                    exchange.requestHeaders.getFirst("Authorization"),
                )
                try {
                    answer(exchange)
                } finally {
                    // A case that promises more than it sends throws here rather than in the test;
                    // the client's half of that is the truncated read, which is the point.
                    runCatching { exchange.close() }
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun reply(code: Int, body: String, etag: String? = null): (HttpExchange) -> Unit = { exchange ->
        if (etag != null) exchange.responseHeaders.add("ETag", etag)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    /** Promises more than it sends and then hangs up — a flaky connection, not a malformed document. */
    private fun cutOff(body: String): (HttpExchange) -> Unit = { exchange ->
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size + 4096L)
        exchange.responseBody.write(bytes)
    }

    private fun client() = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun parts(dir: Path) = Files.list(dir).use { paths -> paths.filter { it.name.endsWith(".part") }.toList() }

    private val measured = document(room("Ice Fill", clearStay = sampled(4, 300.0)))

    /**
     * The happy path, and every clause of it is a promise made elsewhere: the cache is the
     * receiver's **bytes**, the `ETag` is kept beside it, the numbers are in force for this session,
     * and `generatedTs` says which fold they are — which is what `award`, `room_scores` and every
     * `history.jsonl` line carry so a past run stays explainable once the scores move on their own.
     */
    @Test
    fun `a fetched document becomes the cache, the ETag beside it, and the scores in force`(@TempDir dir: Path) {
        Box(reply(200, measured, etag = "\"352a8e43\"")).use { box ->
            client().use { client ->
                val outcome = RoomStats.refresh(client, box.base, dir)
                assertTrue(outcome is RoomStats.Fetched.Fresh, "a 200 that parses is the only fresh outcome")
            }
        }
        assertEquals(measured, Files.readString(dir.resolve(RoomStats.FILE_NAME)), "the receiver's bytes, verbatim")
        assertEquals("\"352a8e43\"", Files.readString(dir.resolve(RoomStats.ETAG_FILE_NAME)))
        assertEquals(emptyList<Path>(), parts(dir), "a .part left behind is a cache half a launch away")
        assertEquals(1786709674349L, RoomStats.scores.generatedTs, "which fold this run was scored against")
        assertEquals(RoomSample(4, 300.0), RoomStats.scores.of("Ice Fill"))
    }

    /**
     * **The exact path and nothing else.** The receiver matches it exactly — `/roomstats.json`,
     * `/roomstats/` and `/roomstats?v=1` are all `404` — so a decorated URL here would be a fetch
     * that can only ever fail, at every launch, silently.
     *
     * And no `Authorization` header. The document is aggregate and public by decision on the
     * receiver's side; sending the compiled-in public token to a path that does not want one would
     * put it on an unauthenticated request for nothing.
     */
    @Test
    fun `the request is a plain GET of the one path the receiver matches`(@TempDir dir: Path) {
        Box(reply(200, measured, etag = "\"t\"")).use { box ->
            client().use { client -> RoomStats.refresh(client, box.base, dir) }
            assertEquals(1, box.asked.size)
            assertEquals("GET", box.asked[0].method)
            assertEquals(RoomStats.PATH, box.asked[0].path)
            assertNull(box.asked[0].authorization, "this endpoint takes no token and must not be sent one")
            assertNull(box.asked[0].ifNoneMatch, "nothing was cached, so there was nothing to revalidate")
        }
    }

    /**
     * 107 KB that did not change costs an empty reply instead. The document is rewritten every half
     * hour and the mod asks at every launch, so this is the difference between a polite client and
     * one that re-downloads the same file all evening.
     */
    @Test
    fun `an unchanged document is a 304 and the cache is left exactly as it was`(@TempDir dir: Path) {
        RoomStats.store(dir, measured, "\"352a8e43\"")
        Box({ exchange -> exchange.sendResponseHeaders(304, -1) }).use { box ->
            client().use { client ->
                val outcome = RoomStats.refresh(client, box.base, dir)
                assertTrue(outcome is RoomStats.Fetched.Current, "a 304 is neither fresh nor a failure")
            }
            assertEquals("\"352a8e43\"", box.asked[0].ifNoneMatch, "the tag has to go out or the 304 never comes back")
        }
        assertEquals(measured, Files.readString(dir.resolve(RoomStats.FILE_NAME)), "a 304 rewrites nothing")
        assertEquals("\"352a8e43\"", Files.readString(dir.resolve(RoomStats.ETAG_FILE_NAME)))
    }

    /**
     * **The one shape of this that could wedge an install for good.** An `ETag` file that outlived
     * the document it names would earn a `304` — and a `304` with no cached bytes behind it is a
     * session on the seeds, at every launch, until the receiver's document happened to change.
     */
    @Test
    fun `an ETag with no document behind it is never sent`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(RoomStats.ETAG_FILE_NAME), "\"stale\"")
        assertNull(RoomStats.cachedEtag(dir))

        Box(reply(200, measured, etag = "\"fresh\"")).use { box ->
            client().use { client -> RoomStats.refresh(client, box.base, dir) }
            assertNull(box.asked[0].ifNoneMatch, "revalidating bytes we do not have asks for a 304 we cannot use")
        }
        assertEquals(measured, Files.readString(dir.resolve(RoomStats.FILE_NAME)))
        assertEquals("\"fresh\"", Files.readString(dir.resolve(RoomStats.ETAG_FILE_NAME)))
    }

    /**
     * **Failure is the ordinary case, not an error**, and the list is what actually happens rather
     * than what is easy to simulate: a box that has not folded a document yet answers `503`, a
     * receiver too old to know this path answers `404`, a proxy in front of a dead upstream answers
     * an HTML page with a `200` on it, and a connection that drops mid-body delivers half a
     * document. Every one of them keeps the cache that was already there — and none of them throws,
     * which is the property `clearpoints-002`'s evaluator drove ten broken shapes through the reader
     * to establish and this feature must not regress.
     */
    @Test
    fun `every way the fetch can fail keeps the last cache and throws nothing`(@TempDir dir: Path) {
        val cached = document(room("Water Board", clearStay = sampled(9, 400.0)))

        fun failing(label: String, answer: ((HttpExchange) -> Unit)? = null, base: String = "") {
            RoomStats.store(dir, cached, "\"cached\"")
            val outcome = client().use { client ->
                if (answer == null) {
                    RoomStats.refresh(client, base, dir)
                } else {
                    Box(answer).use { box -> RoomStats.refresh(client, box.base, dir) }
                }
            }
            assertTrue(outcome is RoomStats.Fetched.Failed, "$label should have failed rather than being adopted")
            assertEquals(cached, Files.readString(dir.resolve(RoomStats.FILE_NAME)), "$label overwrote the cache")
            assertEquals("\"cached\"", Files.readString(dir.resolve(RoomStats.ETAG_FILE_NAME)), "$label lost the tag")
            assertEquals(emptyList<Path>(), parts(dir), "$label left a .part behind")
            assertEquals(RoomSample(9, 400.0), RoomStats.read(dir.resolve(RoomStats.FILE_NAME))?.of("Water Board"))
        }

        failing("no document on the box yet", reply(503, "no scores document"))
        failing("a receiver too old to serve it", reply(404, "not found"))
        failing("an error from the box", reply(500, "boom"))
        failing("a proxy's error page with a 200 on it", reply(200, "<html>502 Bad Gateway</html>"))
        failing("half a document", reply(200, """{"generatedTs":1,"rooms":[{"name":"Ice Fill","clearStay":{"n":4,"""))
        failing("an empty body", reply(200, ""))
        failing("an array instead of the document", reply(200, """[{"name":"Ice Fill"}]"""))
        failing("a body that stops halfway", cutOff(measured.take(200)))
        // Nothing is listening there: the port was bound to find a free one and released again. This
        // is a player with no connection, or the box being down, and it is the most common failure
        // of the lot.
        val dead = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        failing("the box is down", base = "http://127.0.0.1:$dead")
    }

    /**
     * The only body of unknown length this mod reads off a network, so it reads a bounded one. The
     * cap is a parameter purely so this case does not have to serve eight megabytes to prove it.
     */
    @Test
    fun `a body past the cap is refused rather than read`(@TempDir dir: Path) {
        Box(reply(200, "x".repeat(4096))).use { box ->
            client().use { client ->
                val outcome = RoomStats.fetch(client, box.base, etag = null, maxBytes = 1024)
                assertTrue(outcome is RoomStats.Fetched.Failed)
                assertEquals("over 1024 bytes", (outcome as RoomStats.Fetched.Failed).reason)
            }
        }
        assertFalse(Files.exists(dir.resolve(RoomStats.FILE_NAME)), "nothing that was refused may reach the cache")
    }

    /**
     * A half-written cache is worse than none: it survives the launch that produced it and is read
     * as scores by the next one. The write goes through `.part` and a rename for exactly that
     * reason — the same shape `RunReport` publishes a report with, and the same one the receiver's
     * own fold writes this document with.
     */
    @Test
    fun `a torn write never becomes the cache`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        // What an interrupted attempt leaves behind. It is not a name anything reads, and the next
        // successful write consumes it.
        Files.writeString(dir.resolve("${RoomStats.FILE_NAME}.part"), """{"generatedTs":1,"rooms":[{"na""")
        assertNull(RoomStats.read(dir.resolve(RoomStats.FILE_NAME)), "the torn file is not the cache")

        assertTrue(RoomStats.store(dir, measured, "\"t\""))
        assertEquals(measured, Files.readString(dir.resolve(RoomStats.FILE_NAME)))
        assertEquals(emptyList<Path>(), parts(dir), "the stale .part is consumed rather than accumulating")
    }

    /**
     * **A weight may not change in the middle of a run.** Points are compared between party members
     * and a room's weight is read the moment it is cleared, so a document that lands after the
     * session resolved would make the second half of a run incomparable with its first — and with
     * the other four players' halves. It goes to the cache and takes effect at the next launch,
     * which is the whole reason the cache exists as a layer of its own.
     */
    @Test
    fun `a fetch that arrives after the session resolved is cached and not adopted`(@TempDir dir: Path) {
        RoomStats.use(RoomScores.NONE)
        Box(reply(200, measured, etag = "\"late\"")).use { box ->
            client().use { client ->
                assertTrue(RoomStats.refresh(client, box.base, dir) is RoomStats.Fetched.Fresh)
            }
        }
        assertSame(RoomScores.NONE, RoomStats.scores, "the run keeps the numbers it started with")
        assertEquals(
            1786709674349L, RoomStats.read(dir.resolve(RoomStats.FILE_NAME))?.generatedTs,
            "and the next launch resolves the one that arrived late",
        )

        RoomStats.use(null)
        assertTrue(RoomStats.adopt(RoomScores.parse(measured)!!), "with nothing resolved it is taken into use")
        assertFalse(RoomStats.adopt(RoomScores.NONE), "and then it is fixed for the session")
        assertEquals(1786709674349L, RoomStats.scores.generatedTs)
    }
}
