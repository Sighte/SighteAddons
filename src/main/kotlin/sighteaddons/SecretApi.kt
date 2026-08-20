package sighteaddons

import com.google.gson.JsonParser
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The **true** per-player secret count for a run, taken from Hypixel's API.
 *
 * ## Why this exists, and what it is not
 *
 * [DungeonTab] reads the floor's party-wide total, and [ContributionTracker] counts the secrets this
 * client could *prove* were the local player's. Neither can say what a **teammate** found: Hypixel's
 * per-room counter lives in the action bar and reports only the room you are standing in, so the run
 * summary gives every other player a dash — see [RoomHistory.breakdown].
 *
 * There is no per-player secret count anywhere the client can read; [DungeonTab] settles that from
 * source. What there *is* is the lifetime `skyblock_treasure_hunter` achievement, and the difference
 * between its value at the start of a run and at the end is that player's count for the run. That is
 * what Skyblocker's `SecretsTracker` and Odin's `SecretsCounter` both do, and this is the same shape.
 *
 * **It cannot feed the live HUD and is not meant to.** The counter is lifetime, so a number for "now"
 * would need polling every few seconds — a rate limit spent to learn something the action bar already
 * says exactly for the room you are in. Two snapshots per run, at the two moments a run has.
 *
 * **It changes no record and no report.** [RoomHistory.ownSecretRun] still gates on the local, per-room
 * `ownSecrets == secretsFound`, because a per-run total cannot say which room a secret was in. Nothing
 * here reaches `history.jsonl`, `RunReport` or the receiver: `RunReport.SCHEMA` is untouched and no
 * teammate's name leaves this machine, which is the rule `RunReport`'s header states and this keeps.
 *
 * ## There is no key in this mod, and no way to put one in it
 *
 * The lookup goes through the receiver, always: `GET /v1/secrets/<uuid>`, opened with the upload token
 * every install already carries, answered with the one Hypixel key that lives in
 * `/etc/sighte-ingest.env`. **No setting, no field, no config value** — a player installs the jar and
 * teammate secrets work.
 *
 * That is what the other dungeon mods do, and it was worth checking rather than assuming: Odin's
 * `SecretsCounter` performs this same baseline-and-delta and fetches it from
 * `api.odtheking.com/hypixel/secrets/<uuid>`, its author's proxy, holding its author's key. It also
 * caches the uuid lookup and the profile for five minutes and the secret count **not at all** — a
 * window in the length of a run would answer the closing read out of the opening one and make every
 * delta zero.
 *
 * **Two versions of this feature asked the player for a key, and both were wrong.** Hypixel's dashboard
 * hands out development keys that expire in days; the refusal is a `403` that from inside a client is
 * indistinguishable from no key, a private profile and a timeout. So the failure was invisible, each
 * player rediscovered it alone, and the second version — which kept the field as an "override" — kept
 * exactly that trap for anyone who still had a value in it. One key, on one box, whose refusal is a
 * named line in that box's journal.
 *
 * **No key compiled into the jar either**, which was never possible and still is not: it would be
 * public on the first Modrinth upload, as the mod already learned with its upload token. What the jar
 * carries is that upload token, which reaches this receiver and nothing else.
 *
 * ## What it costs, measured against the live API on 2026-08-16
 *
 * Two requests per party member per run, on a daemon thread, never on the client thread and never
 * during a room. **Hypixel's budget is 300 requests per five-minute window** (`ratelimit-limit: 300`,
 * with `ratelimit-reset` counting down from ~300 s), so a five-player floor spends ten of three
 * hundred. This is not a feature that has to ration itself.
 *
 * Four things were checked against the real endpoint rather than taken from the two mods this is
 * modelled on, because "cited, not observed" is how a wrong field name survives a green suite:
 *
 * - `GET /v2/player?uuid=<id>` with the key in an `API-Key` header answers `200`.
 * - A UUID Hypixel has never seen answers `200` with `{"success":true,"player":null}` — the case
 *   [parse] returns null for, confirmed rather than assumed.
 * - `player.achievements.skyblock_treasure_hunter` is present and is an integer (two public
 *   accounts, 1928 and 50609).
 * - **No key at all is a `400`, not the `403` one would expect.** [fetch] branches on `!= 200`, so
 *   every refusal shape lands in the same place and nothing depends on the number — it is recorded
 *   because a reader checking this against Hypixel's docs will otherwise think it is wrong.
 *
 * Every failure — no network, a receiver without the route, a key refused *on the box*, a `429` at
 * either end, a player whose profile is private — means [settle] never calls back and the summary reads
 * exactly as it did without this feature. That fallback is the path an install with an old receiver is
 * on, so it is the one that has to be right.
 */
object SecretApi {

    // Neither the achievement's name nor Hypixel's host appears in this mod any more: the receiver reads
    // `player.achievements.skyblock_treasure_hunter` and hands back a number, so the only shape this side
    // has to know is the receiver's own — see [parseProxy]. `ingest.py`'s `secret_from_body` is where the
    // Hypixel document is parsed, and `SecretBody` in `test_ingest.py` is where that is held.

    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

    /**
     * How long a whole snapshot may take, across all players. Two seconds past [REQUEST_TIMEOUT], so
     * a request that runs its full course still counts and one that hangs past it does not hold the
     * run summary any longer than the request itself was ever allowed to.
     */
    private const val DEADLINE_MS = 12_000L

    /**
     * A Hypixel player object carries every game mode's stats and is genuinely large. The cap is a
     * refusal to buffer something pathological, not a size anybody is expected to approach.
     */
    internal const val MAX_BYTES = 8L * 1024 * 1024

    /** Name to lifetime count when the run started. Empty until a baseline was taken. */
    @Volatile
    private var baseline: Map<String, Int> = emptyMap()

    /** Guards against a second baseline overwriting the first mid-run, and against two settles. */
    @Volatile
    private var state: State = State.IDLE

    /** Last successful per-player delta from this run. When the closing snapshot fails, keep the last
     * valid answer instead of erasing teammates back to the empty/default path. */
    @Volatile
    private var lastCounts: Map<String, Int> = emptyMap()

    private enum class State { IDLE, BASELINE, SETTLED }

    /**
     * The receiver's route and the token that opens it.
     *
     * [base] is a field rather than a constant because it already was one: the private tier reads its
     * own URL out of `upload.properties` ([TelemetryUpload.endpoint] is the same `(base, token)` pair
     * `SoloClear` announces through), and it is what lets `SecretApiTest` point every failure case at a
     * loopback stub. [token] is the upload token — this mod holds no Hypixel key of any kind.
     */
    internal data class Box(val base: String, val token: String)

    /**
     * Set once the box has answered `404` for this route.
     *
     * A receiver that predates `secrets-001` answers 404 for every uuid, and retrying four more times
     * per snapshot to be told the same thing is four wasted requests a run. Latched for the session
     * rather than remembered anywhere: a receiver gains the route by being deployed, and a deploy is a
     * restart of the game away from being noticed. Never set by a `502`/`503` — those mean the route is
     * there and today's answer is no, which must not stop the mod asking until relaunch.
     */
    @Volatile
    private var boxRouteMissing = false

    /**
     * Where lookups go, or null when there is nowhere to send them.
     *
     * Null covers an install whose receiver does not serve the route and one with no endpoint at all —
     * both of which read exactly as this feature did before it existed: the local player's own count,
     * and nothing claimed about anybody else.
     */
    internal fun box(
        endpoint: Pair<String, String>? = TelemetryUpload.endpoint(),
        routeMissing: Boolean = boxRouteMissing,
    ): Box? {
        if (routeMissing) return null
        val (base, token) = endpoint ?: return null
        return Box(base, token)
    }

    /** True when a lookup has somewhere to go. Everything here is a no-op otherwise. */
    val enabled: Boolean get() = box() != null

    fun reset() {
        baseline = emptyMap()
        lastCounts = emptyMap()
        state = State.IDLE
    }

    /**
     * Takes the opening snapshot, once, the first time a run has a roster to take it of.
     *
     * Called every second from [PartyTracker.update] and does nothing after the first accepted call —
     * the baseline has to be the value *before* the party started finding things, and a later one
     * would quietly shrink everybody's total. A run that was already underway when the mod saw its
     * first roster therefore reports low rather than wrong, and reports nothing at all if the roster
     * was empty, because [delta] only answers for names present in both snapshots.
     */
    fun observe(players: Map<String, UUID>) {
        if (state != State.IDLE || players.isEmpty()) return
        val from = box() ?: return
        state = State.BASELINE
        thread("sighteaddons-secrets-start") {
            val taken = snapshot(newClient(), from, players)
            baseline = taken
            DebugLog.event("secret_api_baseline", "asked" to players.size, "got" to taken.size)
        }
    }

    /**
     * Takes the closing snapshot and hands the per-player counts to [then], on the daemon thread.
     *
     * **The summary does not wait for this.** [RoomHistory.printSummary] runs off the run-end chat
     * line and prints its estimates immediately; this answer arrives a moment later as its own line.
     * Blocking the client thread on a network call at the exact moment a floor ends is the one thing
     * this must never do, and a summary that appears late is worse than a follow-up that appears.
     */
    fun settle(players: Map<String, UUID>, then: (Map<String, Int>) -> Unit): Boolean {
        if (state != State.BASELINE || players.isEmpty()) return false
        val from = box() ?: return false
        state = State.SETTLED
        val before = baseline
        thread("sighteaddons-secrets-end") {
            // A failed closing snapshot is not a reason to erase the last real count this run had.
            // The summary waits for this callback, and a silent empty map is exactly how a run's
            // teammate totals disappear back to the default/zero path. Keep the last successful answer
            // unless the current snapshot produced a real replacement.
            val counts = try {
                val after = snapshot(newClient(), from, players)
                val next = delta(before, after)
                if (next.isNotEmpty() || after.isNotEmpty()) lastCounts = next
                DebugLog.event(
                    "secret_api_settle",
                    "before" to before.size, "after" to after.size, "got" to next.size,
                )
                fallbackResult(next, lastCounts)
            } catch (e: Exception) {
                SighteAddons.LOGGER.error("Closing secret snapshot failed", e)
                fallbackResult(emptyMap(), lastCounts)
            }
            then(counts)
        }
        return true
    }

    internal fun fallbackResult(next: Map<String, Int>, previous: Map<String, Int>): Map<String, Int> =
        if (next.isEmpty()) previous else next

    /**
     * Per-player secrets for the run: the rise in each lifetime count.
     *
     * Only names in **both** snapshots get an answer — a player who joined mid-run, or whose opening
     * request failed, has no baseline to subtract and is absent rather than credited with their whole
     * lifetime total. A fall is impossible and is dropped rather than shown as a negative: Hypixel's
     * counter does not go down, so a negative means one of the two readings was wrong.
     */
    internal fun delta(before: Map<String, Int>, after: Map<String, Int>): Map<String, Int> =
        after.mapNotNull { (name, now) ->
            val then = before[name] ?: return@mapNotNull null
            val rise = now - then
            if (rise >= 0) name to rise else null
        }.toMap()

    /**
     * One snapshot of every player that answers. A player that does not is simply absent.
     *
     * **Concurrent, one thread per player, because the summary now waits for the closing snapshot.**
     * Sequentially this was `players * REQUEST_TIMEOUT` in the worst case — a five-player party where
     * every request hangs is fifty seconds, and that used to cost nothing because nobody was waiting.
     * In parallel the worst case is one timeout, and the ordinary case is one request instead of five.
     * A party is at most five, so this is five threads twice a run and needs no pool.
     *
     * [DEADLINE_MS] is the ceiling on the whole snapshot, not per player: joining each thread for the
     * full timeout would add them back up again. A thread still running when it expires is abandoned
     * rather than killed — it is a daemon holding a socket with its own timeout, and its answer is
     * simply not in the map.
     */
    internal fun snapshot(
        client: HttpClient,
        from: Box,
        players: Map<String, UUID>,
    ): Map<String, Int> {
        if (players.isEmpty()) return emptyMap()
        val found = ConcurrentHashMap<String, Int>()
        val threads = players.map { (name, id) ->
            Thread({ fetch(client, from, id)?.let { found[name] = it } }, "sighteaddons-secrets-fetch")
                .apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L
        for (worker in threads) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) break
            worker.join(left)
        }
        return found.toMap()
    }

    /**
     * One player's lifetime count, or null for every way this can fail.
     *
     * Null covers a receiver that does not serve the route, one whose own key is refused or unconfigured,
     * a rate limit at either end, a player Hypixel has no figure for, a body that is not the JSON this
     * expects and a connection that never answers. The caller treats all of them the same way, which is
     * the same way it treats having nowhere to ask at all.
     *
     * **One `404` latches [boxRouteMissing]** and nothing else does. A receiver older than `secrets-001`
     * answers 404 for every uuid, and a snapshot would otherwise spend one request per player learning
     * that once per player; a `502` or `503`, by contrast, means the route is there and today's answer
     * is no, which must not stop the mod asking until the next launch.
     *
     * The header is this mod's upload token, which every install already holds and which reaches nothing
     * but this box. There is no Hypixel key on this side of the wire.
     */
    internal fun fetch(client: HttpClient, from: Box, uuid: UUID): Int? = try {
        val request = HttpRequest.newBuilder(URI.create("${from.base}/v1/secrets/$uuid"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer ${from.token}")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            if (response.statusCode() == 404) {
                boxRouteMissing = true
                DebugLog.event("secret_api_route_absent", "status" to 404)
            }
            null
        } else {
            body(response.body(), MAX_BYTES)?.let(::parseProxy)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The count out of the receiver's `{"uuid": …, "secrets": n}`, or null.
     *
     * A `secrets` of `null` is the box saying Hypixel has no figure for that player — the same fact
     * [parse] reports by finding no achievement field, and it has to stay distinguishable from zero for
     * [delta]'s reason: a player this cannot read is left out of the summary, where a zero would say
     * they found nothing.
     */
    internal fun parseProxy(body: String): Int? = try {
        val root = JsonParser.parseString(body).asJsonObject
        wholeNumber(root.get("secrets"))
    } catch (e: Exception) {
        null
    }

    /**
     * [element] as an Int, and only when it really is a number.
     *
     * `takeIf { isJsonPrimitive }?.asInt` was both parsers' test and it is one character too generous:
     * Gson coerces the *string* `"812"` to `812`, so a body with the field quoted read as a real count.
     * Neither Hypixel nor the receiver writes it quoted, which is exactly why a quoted one means the
     * document is not what it claims to be — and the receiver's own `secret_from_body` refuses it, so
     * the loose version had the two halves of one feature disagreeing about the same field.
     */
    private fun wholeNumber(element: com.google.gson.JsonElement?): Int? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return if (primitive.isNumber) primitive.asInt else null
    }

    /** Null when the body runs past [maxBytes] rather than reading it anyway. Mirrors [RoomStats]. */
    private fun body(stream: InputStream, maxBytes: Long): String? = stream.use {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        val reader = it.reader()
        var total = 0L
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.appendRange(buffer, 0, read)
        }
        out.toString()
    }

    private fun newClient(): HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    /**
     * A named daemon thread, everything inside it wrapped. The same ceiling [RoomStats.start] and
     * [TelemetryUpload.start] hold: networking is never why the game ticks late, and the worst case —
     * a host that accepts the connection and then says nothing — costs a parked daemon thread.
     */
    private fun thread(name: String, body: () -> Unit) {
        Thread({
            try {
                body()
            } catch (e: Exception) {
                SighteAddons.LOGGER.error("Secret lookup failed", e)
            }
        }, name).apply { isDaemon = true }.start()
    }
}
