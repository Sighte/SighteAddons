package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * The handful of settings the `/sa` screen writes, persisted as one JSON object.
 *
 * Every key is read individually with an explicit fallback rather than through Gson's reflection:
 * Gson instantiates objects through Unsafe without running the constructor, so a key missing from a
 * config file written by an older version would silently become `false`/`0` instead of the default
 * below — a HUD that switches itself off after an update.
 */
object Config {
    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/config.json")

    var hud = true
    var hudX = 4
    var hudY = 4
    var showRoom = true
    var showStandings = true

    var roomMessages = true
    var ownPbsOnly = false
    var runSummary = true

    /** `-Dsighteaddons.debug=false` still decides the first launch, before a config file exists. */
    var debugLog = System.getProperty("sighteaddons.debug") != "false"

    fun load() {
        if (!Files.exists(FILE)) return
        try {
            val obj = JsonParser.parseString(Files.readString(FILE)).asJsonObject
            hud = obj.bool("hud", hud)
            hudX = obj.int("hudX", hudX)
            hudY = obj.int("hudY", hudY)
            showRoom = obj.bool("showRoom", showRoom)
            showStandings = obj.bool("showStandings", showStandings)
            roomMessages = obj.bool("roomMessages", roomMessages)
            ownPbsOnly = obj.bool("ownPbsOnly", ownPbsOnly)
            runSummary = obj.bool("runSummary", runSummary)
            debugLog = obj.bool("debugLog", debugLog)
        } catch (e: Exception) {
            // A broken config must never cost the run — the defaults above are already in place.
            SighteAddons.LOGGER.error("Could not read {}, keeping defaults", FILE, e)
        }
    }

    fun save() {
        val obj = JsonObject()
        obj.addProperty("hud", hud)
        obj.addProperty("hudX", hudX)
        obj.addProperty("hudY", hudY)
        obj.addProperty("showRoom", showRoom)
        obj.addProperty("showStandings", showStandings)
        obj.addProperty("roomMessages", roomMessages)
        obj.addProperty("ownPbsOnly", ownPbsOnly)
        obj.addProperty("runSummary", runSummary)
        obj.addProperty("debugLog", debugLog)
        try {
            Files.createDirectories(FILE.parent)
            Files.writeString(FILE, obj.toString())
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Could not write {}", FILE, e)
        }
    }

    private fun JsonObject.bool(key: String, fallback: Boolean) = if (has(key)) get(key).asBoolean else fallback

    private fun JsonObject.int(key: String, fallback: Int) = if (has(key)) get(key).asInt else fallback
}
