package sighteaddons

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.UUID

/**
 * [SecretApi], driven at its two testable seams: the parse of a Hypixel player object, and the
 * arithmetic that turns two lifetime snapshots into a per-run count.
 *
 * The fetch is exercised against a real HTTP server on an ephemeral loopback port, the same way
 * [RoomStatsTest] does it and for the same reason: a refused connection, a `403` and a body that is
 * not what it claims cannot be produced by a pure function. **This is not a network test** — it
 * binds loopback, it is closed with the case, and it never leaves the machine.
 *
 * **The response shapes below are not invented.** The endpoint, the `API-Key` header, the
 * `{"success":true,"player":null}` body for an unknown UUID and the integer at
 * `player.achievements.skyblock_treasure_hunter` were all checked against the live API on
 * 2026-08-16 — see [SecretApi]'s header for what was measured. A fixture that agrees only with
 * itself is the failure mode this whole object exists to avoid.
 *
 * What no test here can reach: the two wiring lines. That [PartyTracker.update] calls
 * [SecretApi.observe] inside a dungeon, and that [RoomHistory.printSummary] calls
 * [SecretApi.settle], both need a live `Minecraft`. Same ceiling as every other wiring line in this
 * repository.
 */
class SecretApiTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-00000000beef")

    // ─── parse ────────────────────────────────────────────────────────────────────────────────────

    private fun player(vararg achievements: Pair<String, Int>): String {
        val body = achievements.joinToString(",") { """"${it.first}":${it.second}""" }
        return """{"success":true,"player":{"achievements":{$body}}}"""
    }

    @Test
    fun `the achievement is read out of a player object`() {
        assertEquals(1234, SecretApi.parse(player(SecretApi.ACHIEVEMENT to 1234)))
    }

    @Test
    fun `other achievements alongside it do not confuse the read`() {
        val body = player("skyblock_harp_hero" to 7, SecretApi.ACHIEVEMENT to 42, "skyblock_angler" to 3)
        assertEquals(42, SecretApi.parse(body))
    }

    @Test
    fun `zero is a real answer and is not absence`() {
        assertEquals(0, SecretApi.parse(player(SecretApi.ACHIEVEMENT to 0)))
    }

    @Test
    fun `a quoted count is a body that is not what it claims, not a count`() {
        // Gson coerces the string "812" to 812, which both parsers used to accept. Hypixel writes the
        // field as a number and so does the receiver, so a quoted one means the document is wrong —
        // and the receiver's `secret_from_body` refuses it, so accepting it here had the two halves of
        // one feature disagreeing about one field.
        assertNull(SecretApi.parse("""{"success":true,"player":{"achievements":{"${SecretApi.ACHIEVEMENT}":"812"}}}"""))
        assertNull(SecretApi.parse("""{"success":true,"player":{"achievements":{"${SecretApi.ACHIEVEMENT}":true}}}"""))
    }

    @Test
    fun `an account that never found a secret has no such field and reads as absent`() {
        assertNull(SecretApi.parse(player("skyblock_angler" to 3)))
    }

    @Test
    fun `an account that never played skyblock has no achievements object`() {
        assertNull(SecretApi.parse("""{"success":true,"player":{"displayname":"Somebody"}}"""))
    }

    @Test
    fun `a name Hypixel has never seen returns a null player`() {
        assertNull(SecretApi.parse("""{"success":true,"player":null}"""))
    }

    @Test
    fun `a refusal is not a player object`() {
        assertNull(SecretApi.parse("""{"success":false,"cause":"Invalid API key"}"""))
    }

    @Test
    fun `a body that is not JSON at all does not throw`() {
        assertNull(SecretApi.parse("<html><body>502 Bad Gateway</body></html>"))
    }

    @Test
    fun `an empty body does not throw`() {
        assertNull(SecretApi.parse(""))
    }

    // ─── delta ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the count for a run is the rise in the lifetime total`() {
        val counts = SecretApi.delta(mapOf("Ann" to 1000, "Bo" to 20), mapOf("Ann" to 1012, "Bo" to 23))
        assertEquals(mapOf("Ann" to 12, "Bo" to 3), counts)
    }

    @Test
    fun `a player with no baseline is absent rather than credited with a lifetime`() {
        // Joined mid-run, or their opening request failed. Either way there is nothing to subtract,
        // and 4310 secrets "this run" is the wrong answer in the most obvious possible way.
        val counts = SecretApi.delta(mapOf("Ann" to 1000), mapOf("Ann" to 1005, "Late" to 4310))
        assertEquals(mapOf("Ann" to 5), counts)
    }

    @Test
    fun `a player whose closing request failed is absent`() {
        val counts = SecretApi.delta(mapOf("Ann" to 1000, "Gone" to 50), mapOf("Ann" to 1005))
        assertEquals(mapOf("Ann" to 5), counts)
    }

    @Test
    fun `a player who found nothing reports zero rather than dropping out`() {
        assertEquals(mapOf("Idle" to 0), SecretApi.delta(mapOf("Idle" to 77), mapOf("Idle" to 77)))
    }

    @Test
    fun `a fall is impossible and is dropped rather than shown as negative`() {
        // Hypixel's counter does not go down, so this means one of the two readings was wrong.
        assertTrue(SecretApi.delta(mapOf("Ann" to 1000), mapOf("Ann" to 999)).isEmpty())
    }

    @Test
    fun `two empty snapshots are an empty answer and not an exception`() {
        assertTrue(SecretApi.delta(emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `a failed closing snapshot keeps the last valid answer instead of erasing it`() {
        assertEquals(mapOf("Ann" to 5), SecretApi.fallbackResult(emptyMap(), mapOf("Ann" to 5)))
        assertEquals(mapOf("Ann" to 7), SecretApi.fallbackResult(mapOf("Ann" to 7), mapOf("Ann" to 5)))
    }

    // ─── fetch, over a real socket ────────────────────────────────────────────────────────────────

    /**
     * What the server actually received, so a case can check what the mod asked for.
     *
     * Both credential headers are recorded, because since `secrets-001` the interesting assertion is
     * often a *negative* one: the box request must carry no `API-Key`, and the Hypixel request must
     * carry no upload token.
     */
    private class Asked(
        val path: String,
        val query: String?,
        val key: String?,
        val authorization: String?,
    )

    private class Api(private val answer: (HttpExchange) -> Unit) : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val asked = java.util.Collections.synchronizedList(mutableListOf<Asked>())
        val base: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                asked += Asked(
                    exchange.requestURI.path,
                    exchange.requestURI.query,
                    exchange.requestHeaders.getFirst("API-Key"),
                    exchange.requestHeaders.getFirst("Authorization"),
                )
                try {
                    answer(exchange)
                } finally {
                    runCatching { exchange.close() }
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun reply(code: Int, body: String): (HttpExchange) -> Unit = { exchange ->
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun client() = HttpClient.newHttpClient()

    @Test
    fun `a good answer yields the count, and the key travels as a header`() {
        Api(reply(200, player(SecretApi.ACHIEVEMENT to 812))).use { api ->
            assertEquals(812, SecretApi.fetch(client(), SecretApi.Source.Own("the-key", api.base), id))
            val asked = api.asked.single()
            assertEquals("/v2/player", asked.path)
            assertEquals("uuid=$id", asked.query)
            // Never a query parameter: that lands in proxy logs and in echoed error pages.
            assertEquals("the-key", asked.key)
            assertTrue(asked.query!!.none { it == '&' }, "the key must not be in the query string")
        }
    }

    @Test
    fun `a refused key is null rather than an exception`() {
        Api(reply(403, """{"success":false,"cause":"Invalid API key"}""")).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Own("wrong", api.base), id))
        }
    }

    @Test
    fun `a missing key is a 400 rather than a 403, and is still null`() {
        // Measured against the live API: Hypixel answers 400 for no key at all, not the 403 the
        // shape of the problem suggests. fetch branches on != 200 so the number does not matter —
        // this pins that it does not matter.
        Api(reply(400, """{"success":false,"cause":"Missing API key"}""")).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Own("", api.base), id))
        }
    }

    @Test
    fun `a rate limit is null`() {
        Api(reply(429, """{"success":false,"cause":"Key throttle"}""")).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Own("the-key", api.base), id))
        }
    }

    @Test
    fun `a proxy's HTML error page under a 200 is null`() {
        Api(reply(200, "<html>502</html>")).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Own("the-key", api.base), id))
        }
    }

    @Test
    fun `a dead port is null`() {
        // Bound and immediately closed, so the port is real and refuses.
        val base = Api(reply(200, "")).use { it.base }
        assertNull(SecretApi.fetch(client(), SecretApi.Source.Own("the-key", base), id))
    }

    @Test
    fun `a body that stops halfway is null`() {
        Api({ exchange ->
            val whole = player(SecretApi.ACHIEVEMENT to 5).toByteArray()
            exchange.sendResponseHeaders(200, whole.size.toLong())
            exchange.responseBody.write(whole, 0, whole.size / 2)
        }).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Own("the-key", api.base), id))
        }
    }

    // ─── the box, and which half gets asked ───────────────────────────────────────────────────────

    private fun proxy(secrets: String) = """{"uuid":"$id","secrets":$secrets}"""

    @Test
    fun `the box is asked on its own route, with the upload token and no hypixel key`() {
        Api(reply(200, proxy("812"))).use { api ->
            assertEquals(812, SecretApi.fetch(client(), SecretApi.Source.Box(api.base, "upload-token"), id))
            val asked = api.asked.single()
            assertEquals("/v1/secrets/$id", asked.path)
            assertNull(asked.query, "the uuid is the path, not a parameter")
            // The one property worth pinning about this transport: **no Hypixel key is anywhere near
            // it.** The client holds an upload token that reaches this box and nothing else.
            assertNull(asked.key, "the mod must not send an API-Key to its own receiver")
            assertEquals("Bearer upload-token", asked.authorization)
        }
    }

    @Test
    fun `a null secrets field is absence and not zero`() {
        // The box says Hypixel has no figure for that player — the same fact `parse` reports by finding
        // no achievement. It has to stay apart from zero, which would claim they found nothing.
        assertNull(SecretApi.parseProxy(proxy("null")))
        assertEquals(0, SecretApi.parseProxy(proxy("0")))
    }

    @Test
    fun `a body the box did not write is null`() {
        for (body in listOf("", "not json", "[]", """{"uuid":"x"}""", """{"secrets":"812"}""",
                            """{"secrets":{}}""")) {
            assertNull(SecretApi.parseProxy(body), body)
        }
    }

    @Test
    fun `every refusal from the box is null`() {
        // 503 unconfigured, 502 a refused lookup, 429 the address bucket, 401 a token this box does not
        // take. All of them mean "no figure for this player", which is the dash the summary had before.
        for (code in listOf(401, 429, 502, 503)) {
            Api(reply(code, "no\n")).use { api ->
                assertNull(SecretApi.fetch(client(), SecretApi.Source.Box(api.base, "t"), id), "status $code")
            }
        }
    }

    @Test
    fun `the box is preferred, and a key is what is left when it cannot serve the route`() {
        val endpoint = "https://box.invalid" to "upload-token"
        assertEquals(
            SecretApi.Source.Box("https://box.invalid", "upload-token"),
            SecretApi.source(key = "own-key", endpoint = endpoint, boxMissing = false),
        )
        // The order matters for exactly one install: the author's, which still carries a key from before
        // the box could do this. Backwards, that install would stay on the expiring-key path.
        assertEquals(
            SecretApi.Source.Own("own-key"),
            SecretApi.source(key = "own-key", endpoint = endpoint, boxMissing = true),
        )
        assertEquals(
            SecretApi.Source.Own("own-key"),
            SecretApi.source(key = "own-key", endpoint = null, boxMissing = false),
        )
        assertNull(SecretApi.source(key = "", endpoint = null, boxMissing = false))
        assertNull(SecretApi.source(key = "  ", endpoint = null, boxMissing = true))
    }

    @Test
    fun `one 404 from the box is enough to stop asking it`() {
        // A receiver older than `secrets-001` answers 404 for every uuid, so without the latch a
        // snapshot spends one request per player learning that five times a run.
        //
        // This case reads the latched field through `source`'s default, which is the only test here
        // that does — the ones above pass `boxMissing` explicitly so they cannot be reordered into
        // depending on it.
        Api(reply(404, "no such route\n")).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Box(api.base, "t"), id))
        }
        assertEquals(
            SecretApi.Source.Own("own-key"),
            SecretApi.source(key = "own-key", endpoint = "https://box.invalid" to "t"),
            "a 404 latches, so the next snapshot falls to the key",
        )
    }

    @Test
    fun `a 503 does not take the box out of service`() {
        // The route is there and today's answer is no — an unconfigured key on the box, or its outbound
        // budget spent. Latching on that would move every client onto its own key until relaunch for
        // something that fixes itself in five minutes.
        Api(reply(503, "no hypixel key configured\n")).use { api ->
            assertNull(SecretApi.fetch(client(), SecretApi.Source.Box(api.base, "t"), id))
        }
        assertEquals(
            SecretApi.Source.Box("https://box.invalid", "t"),
            SecretApi.source(key = "own-key", endpoint = "https://box.invalid" to "t", boxMissing = false),
        )
    }

    // ─── snapshot ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a snapshot asks once per player and leaves out the ones that fail`() {
        val known = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val broken = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        Api({ exchange ->
            if (exchange.requestURI.query.orEmpty().endsWith(known.toString())) {
                reply(200, player(SecretApi.ACHIEVEMENT to 300))(exchange)
            } else {
                reply(500, "nope")(exchange)
            }
        }).use { api ->
            val taken = SecretApi.snapshot(client(), SecretApi.Source.Own("the-key", api.base), mapOf("Ann" to known, "Bo" to broken))
            assertEquals(mapOf("Ann" to 300), taken)
            assertEquals(2, api.asked.size, "every player is asked, even the one that fails")
        }
    }

    @Test
    fun `an empty roster asks nothing`() {
        Api(reply(200, player(SecretApi.ACHIEVEMENT to 1))).use { api ->
            assertTrue(SecretApi.snapshot(client(), SecretApi.Source.Own("the-key", api.base), emptyMap()).isEmpty())
            assertTrue(api.asked.isEmpty())
        }
    }
}
