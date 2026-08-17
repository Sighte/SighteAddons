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
 * ## The key is the player's own and is never shipped
 *
 * [Config.hypixelKey] is blank by default and this whole object is inert until somebody fills it in.
 * A key compiled into the jar would be public on the first Modrinth upload — the mod already learned
 * that with its upload token — so there is no default, no fallback and no bundled value.
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

    private enum class State { IDLE, BASELINE, SETTLED }

    /** True when a key is configured. Everything here is a no-op otherwise. */
    val enabled: Boolean get() = Config.hypixelKey.isNotBlank()

    fun reset() {
        baseline = emptyMap()
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
        if (!enabled || state != State.IDLE || players.isEmpty()) return
        state = State.BASELINE
        val key = Config.hypixelKey
        thread("sighteaddons-secrets-start") {
            val taken = snapshot(newClient(), HOST, key, players)
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
        if (!enabled || state != State.BASELINE || players.isEmpty()) return false
        state = State.SETTLED
        val key = Config.hypixelKey
        val before = baseline
        thread("sighteaddons-secrets-end") {
            // Every failure lands on an empty map rather than on no call at all. The summary now
            // waits for this answer, so a callback that silently never comes is a summary the player
            // never sees — the one outcome worse than a summary with dashes in it.
            val counts = try {
                val after = snapshot(newClient(), HOST, key, players)
                DebugLog.event(
                    "secret_api_settle",
                    "before" to before.size, "after" to after.size, "got" to delta(before, after).size,
                )
                delta(before, after)
            } catch (e: Exception) {
                SighteAddons.LOGGER.error("Closing secret snapshot failed", e)
                emptyMap()
            }
            then(counts)
        }
        return true
    }

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
        base: String,
        key: String,
        players: Map<String, UUID>,
    ): Map<String, Int> {
        if (players.isEmpty()) return emptyMap()
        val found = ConcurrentHashMap<String, Int>()
        val threads = players.map { (name, id) ->
            Thread({ fetch(client, base, key, id)?.let { found[name] = it } }, "sighteaddons-secrets-fetch")
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
     * Null covers a refused key, a rate limit, a private profile, a body that is not the JSON this
     * expects and a connection that never answers. The caller treats all of them the same way, which
     * is the same way it treats not having a key at all.
     */
    internal fun fetch(client: HttpClient, base: String, key: String, uuid: UUID): Int? = try {
        val request = HttpRequest.newBuilder(URI.create("$base/v2/player?uuid=$uuid"))
            .timeout(REQUEST_TIMEOUT)
            // Hypixel v2 takes the key as a header. Never a query parameter: that lands in proxy
            // logs and in any error page that echoes the URL back.
            .header("API-Key", key)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            null
        } else {
            body(response.body(), MAX_BYTES)?.let(::parse)
        }
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
        achievements?.get(ACHIEVEMENT)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (e: Exception) {
        null
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
