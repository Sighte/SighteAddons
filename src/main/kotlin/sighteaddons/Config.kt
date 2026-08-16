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
     * The live "your secrets" line — see [SecretHud]. Its own switch rather than part of [showRoom],
     * because it is a standing readout about the whole run and stays true when you are between
     * rooms, which is exactly when the current-room block has nothing to say.
     */
    var showSecrets = true

    /**
     * The live "Idle · Nav" line — see [IdleTime]. Its own switch for the same reason
     * [showSecrets] has one: it is a standing readout about the whole run, and the navigation half
     * is at its most interesting exactly when the current-room block is empty.
     */
    var showIdle = true

    /**
     * The large centred line when you finish a room yourself. Separate from [hud] on purpose:
     * different place on screen and a different purpose, so switching off the corner readout must
     * not silently take it along.
     */
    var clearPopup = true

    var roomMessages = true
    var ownPbsOnly = false
    var runSummary = true

    /**
     * Off for an ordinary install: the public upload tier never ships sessions, so a session written
     * on a stranger's machine is a few MB per launch that nobody can ever read. On in the development
     * environment, which is where the log is actually used.
     *
     * `-Dsighteaddons.debug=true|false` overrides either way, and still decides the first launch
     * before a config file exists. Only the first launch — [save] persists every key, so an install
     * that has run once keeps whatever its `config.json` says and the `/sa` DEBUG tab owns it.
     */
    var debugLog = System.getProperty("sighteaddons.debug")?.toBooleanStrictOrNull()
        ?: FabricLoader.getInstance().isDevelopmentEnvironment

    /** Sending run reports to the analysis server. On by default; the `/sa` DEBUG tab turns it off. */
    var upload = true

    /**
     * Whether an uploaded run report carries the player's Minecraft name next to [installId].
     *
     * **Off unless the player switches it on**, and the only setting in this file that makes data
     * leave the machine identifiable. A leaderboard needs a name to put on it, but the choice to be
     * on one is not a default anybody can be opted into — so the anonymous id stays the identity and
     * this only adds a label to it.
     *
     * Their own name only. Teammates are never named in a run report at all (see [RunReport]), and
     * this changes nothing about that: the four strangers from party finder cannot consent through
     * somebody else's settings screen.
     */
    var uploadName = false

    /** Whether the one-time disclosure has been said. Persisted, so it is said once per install. */
    var uploadNoticeShown = false

    /**
     * A Hypixel API key, the player's own, for the true per-player secret counts in the run summary.
     *
     * **Blank by default and there is no bundled fallback.** A key in the jar would be public on the
     * first Modrinth upload, which is the same lesson the upload token already taught here. Edit it
     * into `config.json` by hand; it is deliberately not in the `/sa` screen, because a text field
     * that echoes a credential on screen is a worse default than one more step.
     *
     * Blank means [SecretApi] never runs and the summary reads exactly as it did before the feature
     * existed. Nothing about this key or the counts it fetches is ever uploaded — see [SecretApi].
     */
    var hypixelKey = ""

    /**
     * Who the uploaded run reports belong to. Generated once on first launch and then never again.
     *
     * Deliberately **not** the Minecraft UUID: the server files a permanent history under this, and
     * an identity nobody can look up is the difference between a metric and a personal record.
     *
     * Putting a name to it is the player's move, never ours, and there are two ways to make it: read
     * the id in `/sa` and hand it over by hand, or switch on [uploadName] and let the reports carry
     * it. The id is what a leaderboard is keyed by in both cases — [uploadName] only decides whether
     * the rows arrive with a label already on them.
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
            showSecrets = obj.bool("showSecrets", showSecrets)
            showIdle = obj.bool("showIdle", showIdle)
            clearPopup = obj.bool("clearPopup", clearPopup)
            roomMessages = obj.bool("roomMessages", roomMessages)
            ownPbsOnly = obj.bool("ownPbsOnly", ownPbsOnly)
            runSummary = obj.bool("runSummary", runSummary)
            debugLog = obj.bool("debugLog", debugLog)
            upload = obj.bool("upload", upload)
            uploadName = obj.bool("uploadName", uploadName)
            uploadNoticeShown = obj.bool("uploadNoticeShown", uploadNoticeShown)
            hypixelKey = if (obj.has("hypixelKey")) obj.get("hypixelKey").asString else hypixelKey
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
        obj.addProperty("showSecrets", showSecrets)
        obj.addProperty("showIdle", showIdle)
        obj.addProperty("clearPopup", clearPopup)
        obj.addProperty("roomMessages", roomMessages)
        obj.addProperty("ownPbsOnly", ownPbsOnly)
        obj.addProperty("runSummary", runSummary)
        obj.addProperty("debugLog", debugLog)
        obj.addProperty("upload", upload)
        obj.addProperty("uploadName", uploadName)
        obj.addProperty("uploadNoticeShown", uploadNoticeShown)
        obj.addProperty("hypixelKey", hypixelKey)
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
