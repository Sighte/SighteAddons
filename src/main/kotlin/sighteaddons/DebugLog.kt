package sighteaddons

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Append-only JSONL telemetry, one object per line, for diagnosing a real run afterwards.
 *
 * Writes into the game directory, so with Prism the log stays inside the instance:
 * `<instance>/.minecraft/config/sighteaddons/debug/`. Under `gradlew runClient` that is
 * `<project>/run/config/sighteaddons/debug/`.
 *
 * Every line is flushed, so a crash keeps everything up to the last event.
 */
object DebugLog {
    /** Runaway guard. Truncation is logged rather than silent. */
    private const val MAX_EVENTS = 20_000

    /**
     * ponytail: on by default in 0.1.x. The only purpose of this version is producing a diagnostic
     * log, and nobody but the author runs the mod. Gate it behind the development environment again
     * once that changes.
     *
     * The `/sa` DEBUG tab is the single source of truth; `-Dsighteaddons.debug=false` only seeds
     * [Config.debugLog] for the first launch, before a config file exists. Read per event rather than
     * cached, so toggling it takes effect without a restart.
     */
    val enabled get() = Config.debugLog

    private var writer: BufferedWriter? = null
    private var events = 0
    private var truncated = false

    fun event(type: String, vararg fields: Pair<String, Any?>) {
        if (!enabled || truncated) return
        if (events >= MAX_EVENTS) {
            truncated = true
            write("""{"e":"truncated","after":$MAX_EVENTS}""")
            return
        }
        events++
        write(line(type, DungeonSession.runTicks, fields))
    }

    /**
     * One JSONL record. Kept pure and separate because this format is the contract the log is read
     * back with: numbers must stay numbers, absent values must be null, everything else stringifies.
     */
    internal fun line(type: String, ticks: Int, fields: Array<out Pair<String, Any?>>): String {
        val obj = JsonObject()
        obj.addProperty("t", ticks)
        obj.addProperty("e", type)
        for ((key, value) in fields) {
            when (value) {
                null -> obj.add(key, JsonNull.INSTANCE)
                is Number -> obj.addProperty(key, value)
                is Boolean -> obj.addProperty(key, value)
                else -> obj.addProperty(key, value.toString())
            }
        }
        return obj.toString()
    }

    private fun write(line: String) {
        try {
            val out = writer ?: open() ?: return
            out.write(line)
            out.newLine()
            out.flush()
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Debug log write failed, disabling", e)
            truncated = true
        }
    }

    private fun open(): BufferedWriter? {
        val dir = FabricLoader.getInstance().configDir.resolve("sighteaddons/debug")
        return try {
            Files.createDirectories(dir)
            val file = dir.resolve("session-${System.currentTimeMillis()}.jsonl")
            SighteAddons.LOGGER.info("Debug telemetry -> {}", file)
            Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).also { writer = it }
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Could not open debug log in {}", dir, e)
            truncated = true
            null
        }
    }
}
