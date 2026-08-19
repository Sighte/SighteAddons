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
 * ## Where the key lives, and why not here
 *
 * **Since `secrets-001` the lookup goes through the receiver and no player needs a key at all.** See
 * [Source]: the box holds one, `GET /v1/secrets/<uuid>` answers with it, and [Config.hypixelKey] is
 * left as an override for somebody who would rather their party's uuids did not pass through it.
 *
 * That is what the other dungeon mods do, and it was worth checking rather than assuming: Odin's
 * `SecretsCounter` performs this same baseline-and-delta and fetches it from
 * `api.odtheking.com/hypixel/secrets/<uuid>`, its author's proxy, holding its author's key. It also
 * caches the uuid lookup and the profile for five minutes and the secret count **not at all** — a
 * window in the length of a run would answer the closing read out of the opening one and make every
 * delta zero.
 *
 * The version before this asked each player for their own key, and the reason it had to change is not
 * convenience. Hypixel's dashboard hands out development keys that expire in days; the refusal is a
 * `403` that from inside a client is indistinguishable from no key, a private profile and a timeout,
 * so the failure was invisible and each player rediscovered it alone. One key on one box is one key to
 * rotate, and its refusal is a named line in that box's journal.
 *
 * **Still no key compiled into the jar**, which is the part that has not changed and cannot: it would
 * be public on the first Modrinth upload, as the mod already learned with its upload token. What the
 * jar carries is the upload token, which reaches this receiver and nothing else.
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
 * Every failure — no key, no network, a revoked key, a `429`, a player whose profile is private —
 * means [settle] never calls back and the summary reads exactly as it did without this feature. That
 * fallback is the path most installs will be on, so it is the one that has to be right.
 */
object SecretApi {
    /** Hypixel's lifetime dungeon-secret achievement. The only per-player secret figure that exists. */
    internal const val ACHIEVEMENT = "skyblock_treasure_hunter"

    internal const val HOST = "https://api.hypixel.net"

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
     * Where a lookup goes, and the whole of what changed when this stopped needing a key per player.
     *
     * **[Box] is the default and [Own] is the escape hatch**, which is the opposite of how this feature
     * shipped. The receiver holds one key for every client ([TelemetryUpload.endpoint] is the same
     * `(base, token)` pair `SoloClear` announces through), so nobody has to obtain one, nobody has one
     * expire in their config, and there is one place to rotate. That is how the other dungeon mods
     * manage not to ask: Odin's `SecretsCounter` performs the identical baseline-and-delta against
     * `api.odtheking.com/hypixel/secrets/<uuid>` — its author's proxy, holding its author's key.
     *
     * [Own] stays for the player who would rather their party's uuids did not pass through the box, and
     * as the fallback for a receiver too old to have the route.
     */
    internal sealed interface Source {
        /** The receiver's `GET /v1/secrets/<uuid>`. [token] is the upload token, not a Hypixel key. */
        data class Box(val base: String, val token: String) : Source

        /**
         * Hypixel directly, with the player's own key.
         *
         * [base] is a parameter with the real host as its default, and that is not ceremony: it is the
         * only way `SecretApiTest` can point a refused key, a rate limit and a truncated body at a
         * loopback stub. Baking [HOST] into the request builder took that away once and the suite said so.
         */
        data class Own(val key: String, val base: String = HOST) : Source

        /** What the debug log calls this, so a session says which half answered. */
        val via: String get() = if (this is Box) "box" else "key"
    }

    /**
     * Set once the box has answered `404` for this route.
     *
     * A receiver that predates the route answers 404 for every uuid, and retrying four more times per
     * snapshot to be told the same thing is four wasted requests a run. Latched for the session rather
     * than remembered anywhere: a receiver gains the route by being deployed, and a deploy is a restart
     * of the game away from being noticed. Never set by a `502`/`503` — those mean the route is there
     * and today's answer is no, which is a different thing and must not disable the box until relaunch.
     */
    @Volatile
    private var boxRouteMissing = false

    /**
     * Where lookups should go right now, or null when neither half is available.
     *
     * An explicit key wins only after the box has proved it cannot serve the route. The order matters
     * for exactly one install — the author's, which still has a key in its config from before this
     * existed — and getting it backwards would leave that install on the expiring-key path it is the
     * point of this change to leave.
     */
    internal fun source(
        key: String = Config.hypixelKey,
        endpoint: Pair<String, String>? = TelemetryUpload.endpoint(),
        boxMissing: Boolean = boxRouteMissing,
    ): Source? {
        if (!boxMissing && endpoint != null) return Source.Box(endpoint.first, endpoint.second)
        if (key.isNotBlank()) return Source.Own(key)
        // Neither half. Nothing is asked and the summary reads exactly as it did before this feature,
        // which is the path an install with no key and an old receiver has always been on.
        return null
    }

    /** True when a lookup has somewhere to go. Everything here is a no-op otherwise. */
    val enabled: Boolean get() = source() != null

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
        val from = source() ?: return
        state = State.BASELINE
        thread("sighteaddons-secrets-start") {
            val taken = snapshot(newClient(), from, players)
            baseline = taken
            // `via` since `secrets-001`: a session with `got: 0` used to be four different faults
            // wearing one face. Now it at least says which half was asked.
            DebugLog.event(
                "secret_api_baseline",
                "asked" to players.size, "got" to taken.size, "via" to from.via,
            )
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
        val from = source() ?: return false
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
                    "via" to from.via,
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
        from: Source,
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
     * Null covers a refused key, a rate limit, a private profile, a receiver that does not serve the
     * route, a body that is not the JSON this expects and a connection that never answers. The caller
     * treats all of them the same way, which is the same way it treats having nowhere to ask at all.
     *
     * **One `404` from the box latches [boxRouteMissing]** and nothing else does. A receiver older than
     * `secrets-001` answers 404 for every uuid, and a snapshot would otherwise spend five requests
     * learning that five times per run; a `502` or `503`, by contrast, means the route is there and
     * today's answer is no, which must not take the box out until the next launch. The latch is checked
     * by [source], so the *next* snapshot falls to a configured key if there is one.
     */
    internal fun fetch(client: HttpClient, from: Source, uuid: UUID): Int? = try {
        val response = client.send(request(from, uuid), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            if (from is Source.Box && response.statusCode() == 404) {
                boxRouteMissing = true
                DebugLog.event("secret_api_route_absent", "status" to 404)
            }
            null
        } else {
            body(response.body(), MAX_BYTES)?.let { if (from is Source.Box) parseProxy(it) else parse(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** The one request, either shape. Split out so [fetch] reads as the error handling it mostly is. */
    private fun request(from: Source, uuid: UUID): HttpRequest = when (from) {
        // The receiver's route. The header is this mod's upload token, which every install already
        // holds and which reaches nothing but this box — not a Hypixel key, and nothing here has one.
        is Source.Box -> HttpRequest.newBuilder(URI.create("${from.base}/v1/secrets/$uuid"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer ${from.token}")
            .GET()
            .build()

        // Hypixel v2 takes the key as a header. Never a query parameter: that lands in proxy logs and
        // in any error page that echoes the URL back.
        is Source.Own -> HttpRequest.newBuilder(URI.create("${from.base}/v2/player?uuid=$uuid"))
            .timeout(REQUEST_TIMEOUT)
            .header("API-Key", from.key)
            .GET()
            .build()
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
     * The achievement out of a Hypixel player object, or null if it is not there.
     *
     * Every level of this is optional in practice: `success` can be false, `player` is null for a
     * name Hypixel has never seen, `achievements` is absent for an account that never played
     * SkyBlock, and the field itself is absent until the first secret. Absent is not zero — a player
     * this cannot read is left out of the summary rather than shown as having found nothing.
     */
    internal fun parse(body: String): Int? = try {
        val root = JsonParser.parseString(body).asJsonObject
        val player = root.getAsJsonObject("player")
        val achievements = player?.getAsJsonObject("achievements")
        wholeNumber(achievements?.get(ACHIEVEMENT))
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
