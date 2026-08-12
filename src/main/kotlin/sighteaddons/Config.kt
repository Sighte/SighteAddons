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

    /**
     * The large centred line when you finish a room yourself. Separate from [hud] on purpose:
     * different place on screen and a different purpose, so switching off the corner readout must
     * not silently take it along.
     */
    var clearPopup = true

    var roomMessages = true
    var ownPbsOnly = false
    var runSummary = true

    /** `-Dsighteaddons.debug=false` still decides the first launch, before a config file exists. */
    var debugLog = System.getProperty("sighteaddons.debug") != "false"

    /** Sending run reports to the analysis server. On by default; the `/sa` DEBUG tab turns it off. */
    var upload = true

    /** Whether the one-time disclosure has been said. Persisted, so it is said once per install. */
    var uploadNoticeShown = false

    /**
     * Who the uploaded run reports belong to. Generated once on first launch and then never again.
     *
     * Deliberately **not** the Minecraft UUID: the server files a permanent history under this, and
     * an identity nobody can look up is the difference between a metric and a personal record. The
     * player can read it in `/sa` and hand it over voluntarily — that is what a later leaderboard
     * would be built on, and it stays the player's decision rather than ours.
     *
     * Deleting `config.json` starts a new identity and orphans the old history. Acceptable: the
     * alternative is deriving it from something we are trying not to store.
     */
    var installId = ""

    fun load() {
        if (Files.exists(FILE)) read()
        // First launch, or a config written before this field existed.
        if (installId.isBlank()) {
            installId = java.util.UUID.randomUUID().toString()
            save()
        }
    }

    private fun read() {
        try {
            val obj = JsonParser.parseString(Files.readString(FILE)).asJsonObject
            hud = obj.bool("hud", hud)
            hudX = obj.int("hudX", hudX)
            hudY = obj.int("hudY", hudY)
            showRoom = obj.bool("showRoom", showRoom)
            showStandings = obj.bool("showStandings", showStandings)
            clearPopup = obj.bool("clearPopup", clearPopup)
            roomMessages = obj.bool("roomMessages", roomMessages)
            ownPbsOnly = obj.bool("ownPbsOnly", ownPbsOnly)
            runSummary = obj.bool("runSummary", runSummary)
            debugLog = obj.bool("debugLog", debugLog)
            upload = obj.bool("upload", upload)
            uploadNoticeShown = obj.bool("uploadNoticeShown", uploadNoticeShown)
            installId = if (obj.has("installId")) obj.get("installId").asString else installId
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
        obj.addProperty("clearPopup", clearPopup)
        obj.addProperty("roomMessages", roomMessages)
        obj.addProperty("ownPbsOnly", ownPbsOnly)
        obj.addProperty("runSummary", runSummary)
        obj.addProperty("debugLog", debugLog)
        obj.addProperty("upload", upload)
        obj.addProperty("uploadNoticeShown", uploadNoticeShown)
        obj.addProperty("installId", installId)
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
