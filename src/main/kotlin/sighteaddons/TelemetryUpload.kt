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
 * Off unless `config/sighteaddons/upload.properties` exists with both keys:
 *
 * ```properties
 * url=http://<vps-ip>:8420
 * token=<shared secret>
 * ```
 *
 * The file is never written by the mod and never committed — the token stays on the machine that
 * plays. Uploaded files move to `uploaded/` instead of being deleted, so a server-side mistake
 * cannot destroy the only copy of a run.
 *
 * ponytail: plain HTTP, and the sessions carry party member names — the token keeps strangers out of
 * the inbox but everything travels readable. Caddy in front with a real hostname is the fix.
 */
object TelemetryUpload {
    private const val CONFIG = "sighteaddons/upload.properties"

    /** The receiver's own caps, mirrored so a file that cannot land is never put on the wire. */
    private const val MAX_SESSION = 64L * 1024 * 1024
    private const val MAX_RUN = 4L * 1024 * 1024

    /** Matches [DebugLog]'s file name; the group is the session's start time in millis. */
    private val SESSION = Regex("""^session-(\d+)\.jsonl$""")

    /** [RunReport]'s file name. Written in one go, so it is complete as soon as it exists. */
    private val RUN = Regex("""^run-\d+-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.json$""")

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

    private fun run(startedAt: Long) {
        val path = FabricLoader.getInstance().configDir.resolve(CONFIG)
        val credentials = credentials(path)
        if (credentials == null) {
            // Absent is the normal state for anyone who is not the author, so it stays an INFO line;
            // a file that exists but is half filled in is a mistake worth pointing at.
            if (Files.isRegularFile(path)) {
                SighteAddons.LOGGER.warn("{} needs both url and token; telemetry upload stays off", path)
            } else {
                SighteAddons.LOGGER.info("No {}; telemetry upload stays off", path)
            }
            return
        }

        val (base, token) = credentials
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val config = FabricLoader.getInstance().configDir.resolve("sighteaddons")
        // Both travel the same way but land in different stores: the diagnostic is read once and
        // thrown away, the run report is appended to a permanent per-player profile.
        if (!send(client, config.resolve("debug"), "$base/ingest", token, MAX_SESSION) { finished(it, startedAt) }) return
        send(client, config.resolve("runs"), "$base/runs", token, MAX_RUN) { RUN.matches(it) }
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
                val code = post(client, file, url, token)
                when {
                    code in 200..299 -> {
                        Files.move(file, done.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
                        SighteAddons.LOGGER.info("Uploaded telemetry {}", file.name)
                    }
                    // A wrong token fails every file identically, and 400/413 after the checks above
                    // mean the mod and the receiver disagree about the contract — so does the rest.
                    code == 400 || code == 401 || code == 413 -> {
                        SighteAddons.LOGGER.warn(
                            "Telemetry upload of {} rejected: HTTP {}; giving up for this launch", file.name, code,
                        )
                        return false
                    }
                    // Left in place on purpose — the next launch retries it.
                    // ponytail: the retry schedule is "next game start". No backoff, no queue; a real
                    // one belongs here if the server ever stays down long enough for that to matter.
                    else -> SighteAddons.LOGGER.warn("Telemetry upload of {} rejected: HTTP {}", file.name, code)
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
