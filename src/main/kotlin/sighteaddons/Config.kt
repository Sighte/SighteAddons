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
    val hudPlacement = OverlayPlacement(
        "hud", HudPlacement.DEFAULT_ANCHOR, HudPlacement.DEFAULT_OFFSET, HudPlacement.DEFAULT_OFFSET,
    )

    /**
     * Where the two centred overlays sit.
     *
     * Three placements and not one, because they are three elements a player looks at for three
     * different reasons and the whole point of moving one is that the other two stay put. Each carries
     * its own default — the card in the corner, both chips on the crosshair — and [OverlayPlacement] is
     * where the shared half lives: the keys, the drag, and the defence against a hand-edited file.
     *
     * There is no migration to do for either. A file written before this existed is missing three keys
     * per chip, and a missing key is exactly the case an explicit fallback per key covers; the version
     * only has to move when a key is *renamed*. See [ConfigMigration].
     */
    val clearPopupPlacement = OverlayPlacement(
        "clearPopup", ClearPopup.DEFAULT_ANCHOR, ClearPopup.DEFAULT_OFFSET_X, ClearPopup.DEFAULT_OFFSET_Y,
    )
    val stormPlacement = OverlayPlacement(
        "stormTimer", StormHud.DEFAULT_ANCHOR, StormHud.DEFAULT_OFFSET_X, StormHud.DEFAULT_OFFSET_Y,
    )

    /**
     * Where the two splits elements sit — the table, and the single running clock.
     *
     * Two of them, for the reason the three above are three: the table is something a player reads
     * between fights and the clock is something they glance at during one, so they belong in different
     * places and moving one must not move the other. The table starts down the left edge rather than on
     * the crosshair because it is ten rows tall; the clock starts with the other chips and is off until
     * it is asked for ([splitsCurrent]).
     */
    val splitsPlacement = OverlayPlacement(
        "splits", SplitsHud.DEFAULT_ANCHOR, SplitsHud.DEFAULT_OFFSET_X, SplitsHud.DEFAULT_OFFSET_Y,
    )
    val splitsCurrentPlacement = OverlayPlacement(
        "splitsCurrent",
        SplitsCurrentHud.DEFAULT_ANCHOR,
        SplitsCurrentHud.DEFAULT_OFFSET_X,
        SplitsCurrentHud.DEFAULT_OFFSET_Y,
    )

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
     * How opaque the backdrop under the HUD card **and both centred chips** is, as a percentage.
     *
     * One number for all three, and that is the design rather than an economy: the card, the clear popup
     * and Storm's countdown can be on screen within a second of each other, and three separately-set
     * opacities on three surfaces that read as one material would look like three different materials.
     * [ClearPopup] and [StormHud] both say so where they read it.
     *
     * A setting because how much of the dungeon a player wants to see through it is genuinely personal —
     * the same argument [Slider][sighteaddons.ui.components.Slider] was built on.
     *
     * The bounds live in [sighteaddons.ui.theme.Tokens] and not here, and they are now two different
     * facts: [sighteaddons.ui.theme.Tokens.SCRIM_MIN_PERCENT] is how far the slider goes, and
     * [sighteaddons.ui.theme.Tokens.SCRIM_CONTRAST_PERCENT] is where the measured 4.5:1 floor stops
     * holding against a world we do not own. They used to be the same number; the user asked for the
     * range, and the `/sa` row is what says what the low end costs. The name stays `hudScrim` because it
     * is the config key — renaming it would be a migration for a word.
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
     *
     * Drawn inside the run-totals panel, so it needs [totalsOpen] as well as this.
     */
    var showIdle = true

    /**
     * Whether the run-totals panel is open — the standings and the idle/navigation split.
     *
     * **A setting, since a player switched on [showIdle] and correctly reported that nothing happened.**
     * It was a field on `HudRoot`, flipped only by `HudKeys.expandTotals`, and that keybind ships
     * unbound: two switches controlling lines inside a panel with no reachable handle, and the panel
     * forgot itself on every game start even for somebody who had bound one.
     *
     * The keybind flips this same value rather than a second copy of it — one state with two ways in, so
     * the `/sa` row can never disagree with what is on screen. Which also means the panel now persists: a
     * player who wants it open all the time switches it on once.
     *
     * Closed by default, and that is deliberate rather than inherited. Both switches inside it default to
     * on, so an open default would put the old permanent five-line corner readout back — exactly what the
     * HUD rebuild took out. The choice is the player's; the default stays the redesigned one.
     */
    var totalsOpen = false

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

    /**
     * The run splits — see [Splits]. Its own switch for [clearPopup]'s reason, and it is the only gate
     * the feature has: off, nothing is timed, nothing is drawn, nothing is filed.
     *
     * **That last part is a deliberate departure from the mod this came from.** Odin keeps writing
     * personal bests while its Splits module is switched off, so a player who turned the feature away
     * still accumulates records they never asked for and cannot see. `Splits.arm` refuses instead, which
     * is `CLAUDE.md`'s rule about gating *whether* something is written rather than what is in it.
     */
    var splits = true

    /**
     * The second time column: the same span counted in Hypixel's server ticks ([ServerTicks]) rather
     * than on a wall clock. On, because it is the column two players can actually compare — the wall
     * clock carries whatever the server happened to be doing at the time.
     */
    var splitsTickTime = true

    /** The aggregate row: Mort's line to the boss's first. Odin's `Boss Entry Split`, and its default. */
    var splitsBossEntry = true

    /** The per-split lines at the end of a run. Odin's `Send Splits`, and its default. */
    var splitsSendToChat = true

    /**
     * The lag row: how much of the run was the server being behind — [Splits.lostToLag].
     *
     * On, because it is the one number the two time columns already imply and nobody can do in their
     * head: the panel has shown both readings since the port, and the difference between them is the
     * part a player actually wants when a run felt slower than it read. Nothing Odin has.
     *
     * The row needs the tick column to make sense and [SplitsHud] gates it on that as well; the line at
     * the end of a run is printed whatever the panel is showing, because a summary is read after the
     * fact and has no column to sit next to.
     */
    var splitsLag = true

    /**
     * The single large clock for the running split — see [SplitsCurrentHud]. Off, as Odin's is: three
     * elements already default to the middle of the screen and a fourth arriving switched on would land
     * on one of them.
     */
    var splitsCurrent = false

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
     * Whether a solo clear is announced in Discord — [SoloClear].
     *
     * **Off unless the player switches it on, and a separate decision from [upload].** A run report is
     * about its own uploader, anonymous by default and read by nobody but the analysis agent; this
     * puts a Minecraft name and a time into a chat channel other people read. The two switches are
     * therefore independent in both directions: telemetry off with announcements on is a coherent
     * choice, and so is the reverse, and neither may be inferred from the other.
     *
     * Off is the default for the reason [uploadName] is: this mod has a second user, and their runs
     * are not something a default may start posting into somebody else's channel.
     */
    var soloClears = false

    /**
     * Whether a new run personal best is sent to the leaderboard — [RunPbs].
     *
     * **Off unless the player switches it on**, and this is [uploadName]'s argument arriving at the
     * thing it was about: a leaderboard needs a name to put on it, and the choice to be on one is not
     * a default anybody can be opted into. It is a third independent consent, not a sub-setting of
     * [upload] or [soloClears] — a run report is anonymous and read by an analysis agent, an
     * announcement is one line in a channel, and this is a standing, public row with a name on it.
     *
     * **The record is kept either way.** A personal best is a number in a file on the player's own
     * disk, exactly like a split record, and this switch is about the wire and nothing else. What it
     * gates is complete: while it is off nothing is queued, so flipping it on later cannot post runs
     * played while it was off.
     */
    var runPbs = false

    /**
     * The lowest score a solo run must reach to be announced. `0` announces every solo clear.
     *
     * **Hypixel's own number, off the end-of-run chat block, and never [DungeonScore]'s.** That one is a
     * live estimate for a screen; a channel must not be gated on an estimate, and the official score is
     * stated outright a few lines after the run-end headline — see [SoloClear.SCORE].
     *
     * 300 is S+ and 270 is S. The consequence of the number being unreadable is spelled out in
     * [SoloClear.passes]: above 0, an unknown score **fails** the gate. A threshold that cannot be
     * evaluated has not been met, and the other direction would turn one wrong pattern into a channel
     * that announces everything.
     */
    var soloClearMinScore = 300

    // No Hypixel key setting, and that is deliberate rather than pending. Two versions of the secret
    // lookup asked the player for one and both were traps: the dashboard hands out keys that expire in
    // days, and the refusal is a 403 that from inside a client is indistinguishable from no key at all.
    // The key lives on the receiver now (`SIGHTE_HYPIXEL_KEY`), where it is one value to rotate and its
    // refusal is a line in a journal somebody reads. See SecretApi.
    //
    // A file written by an older version still has its `hypixelKey`; nothing reads it and the next save
    // drops it, which is the one direction worth having — a credential this mod no longer uses should
    // not keep sitting in a config file.

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
            hudPlacement.read(migrated)
            pendingHud = null
            save()
        }
        return hudPlacement.origin(screenW, screenH, cardW, cardH)
    }

    /**
     * Puts [slot]'s top-left corner at ([x], [y]) by deriving the anchor it reads as.
     *
     * The placement editor drags in absolute pixels because that is what a hand does; the anchor is
     * inferred from where the hand stopped. One entry point for all three elements, and it is here
     * rather than on [OverlayPlacement] because of the line below.
     *
     * An explicit placement of the *card* also settles any pending migration — a position the player
     * just chose outranks one an old file remembers. Placing a chip must not: while [pendingHud] is set,
     * [hudPlacement] still holds its defaults and the card's real position is inside that object.
     * Clearing it from under a popup drag would move the card to the top left corner, which is the one
     * thing the whole deferred migration exists to avoid.
     */
    fun place(slot: OverlayPlacement, x: Int, y: Int, screenW: Int, screenH: Int, w: Int, h: Int) {
        slot.place(x, y, screenW, screenH, w, h)
        if (slot === hudPlacement) pendingHud = null
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
            if (ConfigMigration.versionOf(obj) >= ConfigMigration.CURRENT) {
                hudPlacement.read(obj)
            } else {
                pendingHud = obj
            }
            // The two chips have no old shape and are read whatever version the file is: their keys
            // either exist or they are the defaults, which is true of a version-0 file as much as of a
            // current one. Deferring them with the card would leave both chips at their defaults for the
            // one launch that finishes the card's migration, and a popup that moves back on its own for
            // exactly one session is worse than one that never moved.
            clearPopupPlacement.read(obj)
            stormPlacement.read(obj)
            splitsPlacement.read(obj)
            splitsCurrentPlacement.read(obj)

            hud = obj.bool("hud", hud)
            // Clamped on the way in for the reason the storm ticks are: `config.json` is hand-edited,
            // and a 0 here would be a HUD card with nothing readable on it that still looked switched on.
            hudScrim = obj.int("hudScrim", hudScrim)
                .coerceIn(Tokens.SCRIM_MIN_PERCENT, Tokens.SCRIM_MAX_PERCENT)
            showRoom = obj.bool("showRoom", showRoom)
            showStandings = obj.bool("showStandings", showStandings)
            showSecrets = obj.bool("showSecrets", showSecrets)
            showIdle = obj.bool("showIdle", showIdle)
            totalsOpen = obj.bool("totalsOpen", totalsOpen)
            clearPopup = obj.bool("clearPopup", clearPopup)
            stormTimer = obj.bool("stormTimer", stormTimer)
            // Clamped on the way in as well as on the way round in `/sa`. A hand-edited config is the
            // other way these are set — see StormTimer — and a zero countdown would put SHOOT NOW on
            // screen the instant Storm speaks, which looks exactly like the timer working.
            stormCountdownTicks = obj.int("stormCountdownTicks", stormCountdownTicks)
                .coerceIn(StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX)
            stormShootTicks = obj.int("stormShootTicks", stormShootTicks)
                .coerceIn(StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX)
            splits = obj.bool("splits", splits)
            splitsTickTime = obj.bool("splitsTickTime", splitsTickTime)
            splitsBossEntry = obj.bool("splitsBossEntry", splitsBossEntry)
            splitsSendToChat = obj.bool("splitsSendToChat", splitsSendToChat)
            splitsLag = obj.bool("splitsLag", splitsLag)
            splitsCurrent = obj.bool("splitsCurrent", splitsCurrent)
            // Records rather than a setting, and read as defensively as one: a malformed floor costs
            // that floor and nothing else. See SplitPbs.read.
            SplitPbs.read(obj)
            roomMessages = obj.bool("roomMessages", roomMessages)
            ownPbsOnly = obj.bool("ownPbsOnly", ownPbsOnly)
            runSummary = obj.bool("runSummary", runSummary)
            critLine = obj.bool("critLine", critLine)
            debugLog = obj.bool("debugLog", debugLog)
            upload = obj.bool("upload", upload)
            uploadName = obj.bool("uploadName", uploadName)
            uploadNoticeShown = obj.bool("uploadNoticeShown", uploadNoticeShown)
            soloClears = obj.bool("soloClears", soloClears)
            runPbs = obj.bool("runPbs", runPbs)
            soloClearMinScore = obj.int("soloClearMinScore", soloClearMinScore)
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
            hudPlacement.write(obj)
        }
        // Written whatever version the file is, for the reason [read] gives about reading them: they are
        // not part of the shape the version describes, and a chip the player has moved must survive the
        // launch that has not been able to migrate the card yet.
        clearPopupPlacement.write(obj)
        stormPlacement.write(obj)
        splitsPlacement.write(obj)
        splitsCurrentPlacement.write(obj)
        obj.addProperty("hud", hud)
        obj.addProperty("hudScrim", hudScrim)
        obj.addProperty("showRoom", showRoom)
        obj.addProperty("showStandings", showStandings)
        obj.addProperty("showSecrets", showSecrets)
        obj.addProperty("showIdle", showIdle)
        obj.addProperty("totalsOpen", totalsOpen)
        obj.addProperty("clearPopup", clearPopup)
        obj.addProperty("stormTimer", stormTimer)
        obj.addProperty("stormCountdownTicks", stormCountdownTicks)
        obj.addProperty("stormShootTicks", stormShootTicks)
        obj.addProperty("splits", splits)
        obj.addProperty("splitsTickTime", splitsTickTime)
        obj.addProperty("splitsBossEntry", splitsBossEntry)
        obj.addProperty("splitsSendToChat", splitsSendToChat)
        obj.addProperty("splitsLag", splitsLag)
        obj.addProperty("splitsCurrent", splitsCurrent)
        SplitPbs.write(obj)
        obj.addProperty("roomMessages", roomMessages)
        obj.addProperty("ownPbsOnly", ownPbsOnly)
        obj.addProperty("runSummary", runSummary)
        obj.addProperty("critLine", critLine)
        obj.addProperty("debugLog", debugLog)
        obj.addProperty("upload", upload)
        obj.addProperty("uploadName", uploadName)
        obj.addProperty("uploadNoticeShown", uploadNoticeShown)
        obj.addProperty("soloClears", soloClears)
        obj.addProperty("runPbs", runPbs)
        obj.addProperty("soloClearMinScore", soloClearMinScore)
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
