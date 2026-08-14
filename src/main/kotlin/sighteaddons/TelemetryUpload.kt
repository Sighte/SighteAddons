package sighteaddons

import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Properties
import kotlin.io.path.name

/**
 * Ships finished telemetry sessions to the analysis server, so a real run can be diagnosed without
 * copying files off the gaming machine by hand.
 *
 * Runs once at startup on a daemon thread and never during a run. `run_end` does not fire when the
 * game crashes or the run is left early, so upload-on-run-end would lose exactly the sessions worth
 * looking at; uploading the *previous* sessions at launch keeps every file and keeps networking out
 * of the tick loop entirely.
 *
 * Two tiers, and the difference between them is what is allowed to leave the machine:
 *
 * - **Public** — every install. Run reports only, keyed by [Config.installId], using [PUBLIC_TOKEN].
 *   Switched off in the `/sa` DEBUG tab.
 * - **Private** — `config/sighteaddons/upload.properties` with `url` and `token`. Adds the debug
 *   sessions. Only the author has that file; the mod never writes it and it is never committed.
 *
 * ```properties
 * url=https://<host>
 * token=<shared secret>
 * ```
 *
 * Sessions are deliberately not in the public tier. They name every party member — pseudonymously
 * since [Pseudonym], but still people who installed nothing and agreed to nothing. A run report is
 * about its own uploader and nobody else, which is what makes it fair to send by default.
 *
 * Uploaded files move to `uploaded/` instead of being deleted, so a server-side mistake cannot
 * destroy the only copy of a run.
 *
 * Both tiers travel over TLS now — Caddy in front, terminating and forwarding to loopback. That
 * closes the readable-on-the-wire hole; it does nothing about [PUBLIC_TOKEN], which is compiled into
 * a jar anybody can decompile and is therefore a spam filter and not a secret. The server treats the
 * public tier as untrusted for exactly that reason, and encryption cannot change who is holding the
 * token at the other end.
 */
object TelemetryUpload {
    private const val CONFIG = "sighteaddons/upload.properties"

    /**
     * Where an ordinary install sends its run reports. Public by construction: it ships inside the
     * jar, so treat it as a filter against drive-by noise rather than as authentication.
     *
     * HTTPS through Caddy, which terminates TLS and forwards to the receiver on loopback. The
     * hostname is the IP with `.sslip.io` after it — that service resolves the address back out of
     * the name, which is what lets Let's Encrypt issue for a box that owns no domain. `8420` is not
     * reachable from outside any more, so an older jar with the plain-HTTP URL compiled in cannot
     * upload at all: it keeps its reports queued and retries at every launch, which is the one shape
     * of this change that loses nothing.
     *
     * Internal rather than private because [RoomStats] fetches the room scores from the same box —
     * one host in one constant, so a move cannot leave half the mod talking to the old address. It
     * is the *public* base on purpose there too: `/roomstats` needs no token and serves the same
     * aggregate document to everybody, so the private tier's URL would only decide which box an
     * install with an `upload.properties` reads its scores from, which is not a question that file
     * is answering.
     */
    internal const val PUBLIC_URL = "https://217.160.51.229.sslip.io"
    private const val PUBLIC_TOKEN = "003c2cc7060f6029a94a4219ac7d7b5a9954eaf04cf89529"

    /** The receiver's own caps, mirrored so a file that cannot land is never put on the wire. */
    private const val MAX_SESSION = 64L * 1024 * 1024
    private const val MAX_RUN = 4L * 1024 * 1024

    /** Matches [DebugLog]'s file name; the group is the session's start time in millis. */
    private val SESSION = Regex("""^session-(\d+)\.jsonl$""")

    /**
     * [RunReport]'s file name. Written in one go, so it is complete as soon as it exists.
     *
     * Internal rather than private because [RunReport.restamp] walks the same queue and has to agree
     * with this on what a report file is — two regexes for one contract is how a file gets edited
     * that the uploader never sends, or missed by the edit and sent stale.
     */
    internal val RUN = Regex("""^run-\d+-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.json$""")

    /**
     * A session is finished exactly when it started before this process did. The current session's
     * file is still open and still being appended to, and [DebugLog] creates it lazily on the first
     * dungeon event — always after [startedAt].
     */
    internal fun finished(name: String, startedAt: Long): Boolean =
        SESSION.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()?.let { it < startedAt } == true

    /**
     * ponytail: everything pending goes up at launch and nothing during a run, so a session played
     * tonight lands tomorrow. Streaming events in-run is the upgrade, and it needs the networking to
     * leave the tick loop alone the way this does.
     */
    fun start() {
        val startedAt = System.currentTimeMillis()
        Thread({
            // Telemetry is a diagnostic, never a reason for the game to misbehave: nothing in here
            // may escape into the client.
            try {
                run(startedAt)
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Telemetry upload failed", e)
            }
        }, "sighteaddons-upload").apply { isDaemon = true }.start()
    }

    internal enum class Outcome { DONE, REJECTED, RETRY, STOP }

    /**
     * What a status code means for the *queue*, which is not the same as what it means for the file.
     *
     * Only `401` is about the run as a whole: a token the server does not accept fails every file
     * identically, so walking the rest is pointless noise. `400` and `413` are about this one file —
     * its name, its size, its contents — and say nothing about the next one. Treating them as fatal
     * was a real bug: reports are sent oldest first, so a single report from an older schema sat at
     * the head of the queue and blocked every newer one, at every launch, permanently.
     */
    internal fun outcome(code: Int): Outcome = when {
        code in 200..299 -> Outcome.DONE
        code == 401 -> Outcome.STOP
        code == 400 || code == 413 -> Outcome.REJECTED
        else -> Outcome.RETRY
    }

    /** [sessions] is what separates the tiers: only the private one may ship other people's names. */
    internal class Tier(val base: String, val token: String, val sessions: Boolean)

    /**
     * Which tier this install uploads on, or null for nothing at all. Free of logging and of
     * [FabricLoader] so the decision is testable — it is the one that decides whether other people's
     * names may leave the machine.
     */
    internal fun tier(enabled: Boolean, path: Path): Tier? {
        if (!enabled) return null
        credentials(path)?.let { (base, token) -> return Tier(base, token, sessions = true) }
        // A file that exists but is half filled in is a typo. Dropping to the public tier would hide
        // it and the author would keep wondering where their sessions went, so it switches off.
        if (Files.isRegularFile(path)) return null
        if (PUBLIC_TOKEN.isBlank()) return null
        return Tier(PUBLIC_URL, PUBLIC_TOKEN, sessions = false)
    }

    private fun run(startedAt: Long) {
        val path = FabricLoader.getInstance().configDir.resolve(CONFIG)
        val tier = tier(Config.upload, path)
        if (tier == null) {
            if (Files.isRegularFile(path)) {
                SighteAddons.LOGGER.warn("{} needs both url and token; telemetry upload stays off", path)
            } else {
                SighteAddons.LOGGER.info("Telemetry upload is off in /sa")
            }
            return
        }
        SighteAddons.LOGGER.info("Telemetry upload on the {} tier", if (tier.sessions) "private" else "public")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val config = FabricLoader.getInstance().configDir.resolve("sighteaddons")
        // Both travel the same way but land in different stores: the diagnostic is read once and
        // thrown away, the run report is appended to a permanent per-install profile.
        if (tier.sessions) {
            val debug = config.resolve("debug")
            if (!send(client, debug, "${tier.base}/ingest", tier.token, MAX_SESSION) { finished(it, startedAt) }) return
        }
        send(client, config.resolve("runs"), "${tier.base}/runs", tier.token, MAX_RUN) { RUN.matches(it) }
    }

    /** False when the rejection will repeat for every remaining file, so the whole run gives up. */
    private fun send(
        client: HttpClient,
        dir: Path,
        url: String,
        token: String,
        maxBytes: Long,
        accept: (String) -> Boolean,
    ): Boolean {
        if (!Files.isDirectory(dir)) return true
        // Oldest first: the millis in the name are fixed width, so the name already orders them.
        val pending = Files.list(dir).use { paths -> paths.filter { accept(it.name) }.toList() }
            .sortedBy { it.name }
        if (pending.isEmpty()) return true

        val done = dir.resolve("uploaded")
        Files.createDirectories(done)
        for (file in pending) {
            try {
                val size = Files.size(file)
                if (size > maxBytes) {
                    // The receiver would only answer 413. A finished session is a few MB, so this
                    // fires when something else went wrong and the file is worth looking at by hand.
                    SighteAddons.LOGGER.warn(
                        "Telemetry {} is {} bytes, over the {} byte limit; left in place", file.name, size, maxBytes,
                    )
                    continue
                }
                when (outcome(post(client, file, url, token))) {
                    Outcome.DONE -> {
                        Files.move(file, done.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
                        SighteAddons.LOGGER.info("Uploaded telemetry {}", file.name)
                    }
                    Outcome.REJECTED -> {
                        // Moved out of the queue, not deleted. It will never be accepted as it
                        // stands, and leaving it where it is means retrying it at every launch
                        // forever — with oldest-first ordering, one such file in front of the queue
                        // is enough to keep everything behind it from ever being sent.
                        val out = dir.resolve("rejected")
                        Files.createDirectories(out)
                        Files.move(file, out.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
                        SighteAddons.LOGGER.warn("Telemetry {} was rejected; moved to rejected/", file.name)
                    }
                    // Left in place on purpose — the next launch retries it.
                    // ponytail: the retry schedule is "next game start". No backoff, no queue; a real
                    // one belongs here if the server ever stays down long enough for that to matter.
                    Outcome.RETRY -> SighteAddons.LOGGER.warn("Telemetry upload of {} deferred", file.name)
                    Outcome.STOP -> {
                        SighteAddons.LOGGER.warn("Telemetry upload rejected the token; giving up for this launch")
                        return false
                    }
                }
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Telemetry upload of {} failed", file.name, e)
            }
        }
        return true
    }

    private fun post(client: HttpClient, file: Path, url: String, token: String): Int {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", if (file.name.endsWith(".json")) "application/json" else "application/x-ndjson")
            // The server names the stored file from this, and the agent needs to know which build
            // produced a log before it reads anything into it.
            .header("X-Session-File", file.name)
            .header("X-Mod-Version", modVersion())
            .POST(HttpRequest.BodyPublishers.ofFile(file))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /**
     * Null when the file is absent or either key is missing — the caller decides which of those is
     * worth saying out loud. Kept free of logging and of [FabricLoader] so it is testable on its own.
     */
    internal fun credentials(path: Path): Pair<String, String>? {
        if (!Files.isRegularFile(path)) return null
        val props = Properties()
        Files.newBufferedReader(path).use(props::load)
        // Base URL only: the paths below it are the mod's business, not the config file's.
        val url = props.getProperty("url").orEmpty().trim().trimEnd('/')
        val token = props.getProperty("token").orEmpty().trim()
        if (url.isEmpty() || token.isEmpty()) return null
        return url to token
    }

    internal fun modVersion(): String = FabricLoader.getInstance()
        .getModContainer(SighteAddons.ID)
        .map { it.metadata.version.friendlyString }
        .orElse("unknown")
}
