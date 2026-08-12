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
 */
object TelemetryUpload {
    private const val CONFIG = "sighteaddons/upload.properties"

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
        val (base, token) = config() ?: return
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val config = FabricLoader.getInstance().configDir.resolve("sighteaddons")
        // Both travel the same way but land in different stores: the diagnostic is read once and
        // thrown away, the run report is appended to a permanent per-player profile.
        send(client, config.resolve("debug"), "$base/ingest", token) { finished(it, startedAt) }
        send(client, config.resolve("runs"), "$base/runs", token) { RUN.matches(it) }
    }

    private fun send(client: HttpClient, dir: Path, url: String, token: String, accept: (String) -> Boolean) {
        if (!Files.isDirectory(dir)) return
        val pending = Files.list(dir).use { paths -> paths.filter { accept(it.name) }.toList() }
        if (pending.isEmpty()) return

        val done = dir.resolve("uploaded")
        Files.createDirectories(done)
        for (file in pending) {
            try {
                val code = post(client, file, url, token)
                if (code in 200..299) {
                    Files.move(file, done.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
                    SighteAddons.LOGGER.info("Uploaded telemetry {}", file.name)
                } else {
                    // Left in place on purpose — the next launch retries it.
                    SighteAddons.LOGGER.warn("Telemetry upload of {} rejected: HTTP {}", file.name, code)
                }
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Telemetry upload of {} failed", file.name, e)
            }
        }
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

    private fun config(): Pair<String, String>? {
        val path = FabricLoader.getInstance().configDir.resolve(CONFIG)
        if (!Files.isRegularFile(path)) return null
        val props = Properties()
        Files.newBufferedReader(path).use(props::load)
        // Base URL only: the paths below it are the mod's business, not the config file's.
        val url = props.getProperty("url").orEmpty().trim().trimEnd('/')
        val token = props.getProperty("token").orEmpty().trim()
        if (url.isEmpty() || token.isEmpty()) {
            SighteAddons.LOGGER.warn("{} needs both url and token; telemetry upload stays off", path)
            return null
        }
        return url to token
    }

    internal fun modVersion(): String = FabricLoader.getInstance()
        .getModContainer(SighteAddons.ID)
        .map { it.metadata.version.friendlyString }
        .orElse("unknown")
}
