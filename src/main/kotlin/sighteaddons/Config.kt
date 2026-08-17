package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import sighteaddons.ui.theme.Tokens
import java.nio.file.Files

/**
 * The handful of settings the `/sa` screen writes, persisted as one JSON object.
 *
 * Every key is read individually with an explicit fallback rather than through Gson's reflection:
 * Gson instantiates objects through Unsafe without running the constructor, so a key missing from a
 * config file written by an older version would silently become `false`/`0` instead of the default
 * below — a HUD that switches itself off after an update.
 *
 * The file also carries a `version`, and [ConfigMigration] is what reads a file written under an older
 * one. That is the same care applied to the case an explicit fallback cannot cover: a key that has
 * been *renamed* is absent exactly like a key nobody ever set, and only the version can tell them
 * apart.
 */
object Config {
    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/config.json")

    var hud = true

    /**
     * Where the HUD card hangs, and how far in from there — see [HudPlacement] for why it is not two
     * absolute pixels any more, and [ConfigMigration] for what happens to the files that say it was.
     *
     * Read through [hudOrigin] rather than directly: an old config's position is only convertible once
     * a real screen exists, and that call is where it happens.
     */
    var hudAnchor = HudPlacement.DEFAULT_ANCHOR
    var hudOffsetX = HudPlacement.DEFAULT_OFFSET
    var hudOffsetY = HudPlacement.DEFAULT_OFFSET

    /**
     * A parsed config file still at version 0, held until there is a screen to migrate it against.
     *
     * Null for every other case, including a fresh install: a config nobody has written yet is already
     * current. While this is set, [save] writes the version-0 shape back — the file must not claim to
     * have been migrated by a run that could not migrate it, and the old position must survive a
     * player who opens `/sa` and flips an unrelated switch before ever entering a dungeon.
     */
    private var pendingHud: JsonObject? = null

    /**
     * How opaque the backdrop under the HUD card and the two centred overlays is, as a percentage.
     *
     * A setting because how much of the dungeon a player wants to see through it is genuinely
     * personal — the same argument [Slider][sighteaddons.ui.components.Slider] was built on — and
     * clamped because the answer "none of it" is not a preference, it is an unreadable card, and an
     * unreadable card is our fault rather than theirs.
     *
     * The bounds live in [sighteaddons.ui.theme.Tokens] and not here: they are not a taste, they are
     * where the 4.5:1 contrast floor stops holding against a world we do not own, and they have to move
     * with the palette they were measured against.
     */
    var hudScrim = Tokens.SCRIM_PERCENT

    var showRoom = true
    var showStandings = true

    /**
     * The live "your secrets" line. Its own switch rather than part of [showRoom], because it is a
     * standing readout about the whole run and stays true when you are between rooms, which is exactly
     * when the current-room block has nothing to say.
     *
     * The number it shows is attribution and never the party's count — the rule used to live in
     * `SecretHud`, which is gone; it is now
     * [HudSnapshot.roomOwnSecrets][sighteaddons.ui.hud.HudSnapshot.Companion.roomOwnSecrets] and
     * [runOwnSecrets][sighteaddons.ui.hud.HudSnapshot.Companion.runOwnSecrets], where `UiHudSecretsTest`
     * holds it.
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

    /**
     * Storm's cast countdown — see [StormTimer]. On the HUD tab and not the CHAT one for the mirror
     * image of [critLine]'s reason: this is drawn on screen rather than printed into chat.
     *
     * Its own switch rather than part of [hud], on [clearPopup]'s argument: different place on
     * screen, different purpose, and switching off the corner readout must not silently take a
     * combat timer with it.
     */
    var stormTimer = true

    /**
     * How long Storm's countdown runs, and how long `SHOOT NOW` holds after it, both in ticks.
     *
     * **Settings rather than constants because both are inherited and unverified.** 138 and 20 come
     * from the decompiled mod this was ported from, which explains neither, and nothing in this
     * repository can derive them. A wrong tick count is the quiet kind of wrong — the timer counts
     * down and fires and looks entirely correct while being early or late — so the correction has to
     * be reachable without a build. `/sa` → hud steps them a tick at a time; [StormTimer.step] is
     * where the wrap is, and [StormTimer.COUNTDOWN_MIN] the range.
     */
    var stormCountdownTicks = 138
    var stormShootTicks = 20

    var roomMessages = true
    var ownPbsOnly = false
    var runSummary = true

    /**
     * The Explosive Shot crit readout — see [CritMeter]. On the CHAT tab and not the HUD one because
     * it is a line printed into your own chat, like [roomMessages] and [runSummary], rather than
     * something drawn on screen.
     *
     * On by default and inert everywhere except the Maxor phase of M7: the combat window has to be
     * open and Hypixel has to announce a crit before this says anything at all.
     */
    var critLine = true

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
     * first Modrinth upload, which is the same lesson the upload token already taught here.
     *
     * **There is a field for it now**, in `/sa` → debug, and the objection this comment used to raise —
     * *"a text field that echoes a credential on screen is a worse default than one more step"* — is
     * answered rather than overruled, because it was an objection to a field that **echoes** and not to
     * a field. The one on that screen is masked by default; revealing it is a momentary act with a word
     * on it and not a setting anything remembers; and it refuses copy and cut, so it takes a key and
     * never hands one back. Hand-editing `config.json` still works and is still what
     * [HudPlacement.Anchor.of] means by a supported way to set this file.
     *
     * The key is never logged, never written into a run report and never mirrored into a tooltip:
     * [SecretApi] sends it as a request header and nothing else in the mod reads it. What the field
     * makes visible is *whether* one is set, which is the fact a player needs and not the value.
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

    /**
     * Where the HUD card's top-left corner belongs on a [screenW]×[screenH] screen, and the one place
     * an old absolute position becomes an anchored one.
     *
     * Migrating here rather than in [load] is the whole design: at mod init there is no window whose
     * GUI-scaled size means anything, and the conversion needs one. The first frame that has both a
     * screen and the card's real height finishes the job, writes the file once, and from then on this
     * is arithmetic. Until that frame, the card is drawn at exactly the pixels the previous version
     * drew it at — so the migration is not something the player can see happen.
     */
    fun hudOrigin(screenW: Int, screenH: Int, cardW: Int, cardH: Int): HudPlacement.Origin {
        val pending = pendingHud
        if (pending != null) {
            val migrated = ConfigMigration.migrate(pending, screenW, screenH, cardW, cardH)
            if (ConfigMigration.versionOf(migrated) < ConfigMigration.CURRENT) {
                // No usable screen — a minimised window on Windows reports a GUI size of zero. The old
                // absolute position is still the best answer there is, and it is the answer the last
                // version gave, so nothing is lost by waiting for a frame that can do better.
                return HudPlacement.Origin(
                    pending.int("hudX", HudPlacement.DEFAULT_OFFSET),
                    pending.int("hudY", HudPlacement.DEFAULT_OFFSET),
                )
            }
            readHud(migrated)
            pendingHud = null
            save()
        }
        return HudPlacement.origin(hudAnchor, hudOffsetX, hudOffsetY, screenW, screenH, cardW, cardH)
    }

    /**
     * Puts the card's top-left corner at ([x], [y]) by deriving the anchor it reads as.
     *
     * The placement editor drags in absolute pixels because that is what a hand does; the anchor is
     * inferred from where the hand stopped. An explicit placement also settles any pending migration —
     * a position the player just chose outranks one an old file remembers.
     */
    fun placeHud(x: Int, y: Int, screenW: Int, screenH: Int, cardW: Int, cardH: Int) {
        val placement = HudPlacement.nearest(x, y, screenW, screenH, cardW, cardH)
        hudAnchor = placement.anchor
        hudOffsetX = placement.offsetX
        hudOffsetY = placement.offsetY
        pendingHud = null
    }

    /**
     * The three placement keys, from an object already at [ConfigMigration.CURRENT].
     *
     * Its own function because it is read from two places — [read] for a file that was already
     * current, [hudOrigin] for one that has just this second become current — and two copies of it
     * would be two chances for a key to be spelt differently in the one path nobody exercises.
     */
    private fun readHud(obj: JsonObject) {
        hudAnchor = HudPlacement.Anchor.of(obj.str("hudAnchor", hudAnchor.name))
        hudOffsetX = obj.int("hudOffsetX", hudOffsetX)
        hudOffsetY = obj.int("hudOffsetY", hudOffsetY)
    }

    private fun read() {
        try {
            val raw = JsonParser.parseString(Files.readString(FILE)).asJsonObject

            // The identity first, before anything else in this function can go wrong.
            //
            // Every read below is written not to throw, so today this is belt and braces — but it is
            // the one line whose loss is not recoverable. A blank installId sends [load] off to mint a
            // new UUID and save it, which retires the id every run this install has ever uploaded is
            // filed under: the history does not move, it is orphaned, and there is no way back because
            // the old id is only in the file that was just overwritten. Every other setting in here
            // costs a switch the player can flip again. So it is read where nothing precedes it, and
            // the next person to add a line to this function does not have to notice why.
            installId = raw.str("installId", installId)

            // As far forward as this file can go without a screen — which for version 0 is nowhere.
            // See ConfigMigration: the HUD position is the one key whose old form cannot be read
            // without knowing what it was once measured against.
            val obj = ConfigMigration.migrate(raw, 0, 0, 0, 0)
            if (ConfigMigration.versionOf(obj) >= ConfigMigration.CURRENT) readHud(obj) else pendingHud = obj

            hud = obj.bool("hud", hud)
            // Clamped on the way in for the reason the storm ticks are: `config.json` is hand-edited,
            // and a 0 here would be a HUD card with nothing readable on it that still looked switched on.
            hudScrim = obj.int("hudScrim", hudScrim)
                .coerceIn(Tokens.SCRIM_MIN_PERCENT, Tokens.SCRIM_MAX_PERCENT)
            showRoom = obj.bool("showRoom", showRoom)
            showStandings = obj.bool("showStandings", showStandings)
            showSecrets = obj.bool("showSecrets", showSecrets)
            showIdle = obj.bool("showIdle", showIdle)
            clearPopup = obj.bool("clearPopup", clearPopup)
            stormTimer = obj.bool("stormTimer", stormTimer)
            // Clamped on the way in as well as on the way round in `/sa`. A hand-edited config is the
            // other way these are set — see StormTimer — and a zero countdown would put SHOOT NOW on
            // screen the instant Storm speaks, which looks exactly like the timer working.
            stormCountdownTicks = obj.int("stormCountdownTicks", stormCountdownTicks)
                .coerceIn(StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX)
            stormShootTicks = obj.int("stormShootTicks", stormShootTicks)
                .coerceIn(StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX)
            roomMessages = obj.bool("roomMessages", roomMessages)
            ownPbsOnly = obj.bool("ownPbsOnly", ownPbsOnly)
            runSummary = obj.bool("runSummary", runSummary)
            critLine = obj.bool("critLine", critLine)
            debugLog = obj.bool("debugLog", debugLog)
            upload = obj.bool("upload", upload)
            uploadName = obj.bool("uploadName", uploadName)
            uploadNoticeShown = obj.bool("uploadNoticeShown", uploadNoticeShown)
            hypixelKey = obj.str("hypixelKey", hypixelKey)
        } catch (e: Exception) {
            // A broken config must never cost the run — the defaults above are already in place.
            SighteAddons.LOGGER.error("Could not read {}, keeping defaults", FILE, e)
        }
    }

    fun save() {
        val obj = JsonObject()
        val pending = pendingHud
        if (pending != null) {
            // Still unmigrated, so still version 0 on disk, with the position untouched. A file that
            // carried a version it has not been brought up to would be read next launch as one whose
            // missing `hudAnchor` means "never set" — and the player's position would be gone.
            obj.addProperty("hudX", pending.int("hudX", HudPlacement.DEFAULT_OFFSET))
            obj.addProperty("hudY", pending.int("hudY", HudPlacement.DEFAULT_OFFSET))
        } else {
            obj.addProperty("version", ConfigMigration.CURRENT)
            obj.addProperty("hudAnchor", hudAnchor.name)
            obj.addProperty("hudOffsetX", hudOffsetX)
            obj.addProperty("hudOffsetY", hudOffsetY)
        }
        obj.addProperty("hud", hud)
        obj.addProperty("hudScrim", hudScrim)
        obj.addProperty("showRoom", showRoom)
        obj.addProperty("showStandings", showStandings)
        obj.addProperty("showSecrets", showSecrets)
        obj.addProperty("showIdle", showIdle)
        obj.addProperty("clearPopup", clearPopup)
        obj.addProperty("stormTimer", stormTimer)
        obj.addProperty("stormCountdownTicks", stormCountdownTicks)
        obj.addProperty("stormShootTicks", stormShootTicks)
        obj.addProperty("roomMessages", roomMessages)
        obj.addProperty("ownPbsOnly", ownPbsOnly)
        obj.addProperty("runSummary", runSummary)
        obj.addProperty("critLine", critLine)
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

    // All three read through ConfigMigration, which is where the argument for them is written down:
    // a hand-edited file is a supported way to set this one, and a value of the wrong shape must cost
    // the key it is in rather than every key below it — including installId. Kept as extensions here
    // so the call sites above read as a list of settings rather than as a list of parses.
    private fun JsonObject.bool(key: String, fallback: Boolean) = ConfigMigration.boolOr(this, key, fallback)

    private fun JsonObject.int(key: String, fallback: Int) = ConfigMigration.intOr(this, key, fallback)

    private fun JsonObject.str(key: String, fallback: String) = ConfigMigration.stringOr(this, key, fallback)
}
