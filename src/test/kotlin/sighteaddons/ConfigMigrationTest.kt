package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The config migration and the placement arithmetic under it.
 *
 * These earn tests where almost nothing else in this repository does. A migration runs exactly once
 * per installation, on a file written by a version that is no longer here, on a machine nobody
 * testing this will ever touch — and when it is wrong the symptom is a HUD card a bit further left
 * than it used to be, which looks like a HUD card. There is no run to lose and no error to read; it
 * simply happens, once, and cannot be repeated.
 *
 * [Config] itself resolves its path through `FabricLoader` in a field initialiser and cannot be
 * loaded outside a game, which is the reason [ConfigMigration] and [HudPlacement] are separate objects
 * taking plain values rather than methods on it.
 */
class ConfigMigrationTest {

    /** 1920×1080 at GUI scale 4, and the real card width. A plausible screen to migrate against. */
    private val screenW = 480
    private val screenH = 270
    private val cardW = 196
    private val cardH = 90

    // --- Migration --------------------------------------------------------------------------

    /**
     * The whole promise: an old absolute position becomes an anchored one that resolves to the very
     * same pixels on the screen it was migrated against.
     *
     * Not "close to" and not "the nearest corner". The player set that position by dragging a card
     * until it looked right, and a migration that moves it — even by the few pixels that snapping to a
     * corner would cost — is a migration they have to notice and undo.
     */
    @Test
    fun `an old position migrates to the same pixels it was already at`() {
        for (x in intArrayOf(0, 4, 137, 200, 283, 284)) {
            for (y in intArrayOf(0, 4, 90, 179, 180)) {
                val migrated = ConfigMigration.migrate(legacy(x, y), screenW, screenH, cardW, cardH)
                val origin = originOf(migrated, screenW, screenH)
                assertEquals(x, origin.x, "hudX $x moved")
                assertEquals(y, origin.y, "hudY $y moved")
            }
        }
    }

    /** The old keys go, the version arrives, and nothing else in the file is touched. */
    @Test
    fun `migration replaces the position keys and leaves every other setting alone`() {
        val old = legacy(276, 172)
        old.addProperty("hud", false)
        old.addProperty("installId", "abc-123")

        val migrated = ConfigMigration.migrate(old, screenW, screenH, cardW, cardH)

        assertEquals(1, ConfigMigration.versionOf(migrated))
        assertFalse(migrated.has("hudX"), "the old keys are not carried along")
        assertFalse(migrated.has("hudY"))
        assertEquals("BOTTOM_RIGHT", migrated.get("hudAnchor").asString)
        assertFalse(migrated.get("hud").asBoolean, "a switch the player turned off stays off")
        assertEquals("abc-123", migrated.get("installId").asString, "the identity survives")
        assertFalse(old.has("hudAnchor"), "the input is not mutated")
    }

    /**
     * A file already at the current version comes back untouched — the same instance, not an equal
     * copy, because the point is that nothing was rewritten.
     */
    @Test
    fun `an already migrated file is left exactly as it is`() {
        val current = JsonParser.parseString(
            """{"version":1,"hudAnchor":"BOTTOM_RIGHT","hudOffsetX":8,"hudOffsetY":8,"hud":true}""",
        ).asJsonObject

        assertSame(current, ConfigMigration.migrate(current, screenW, screenH, cardW, cardH))
    }

    /**
     * With no screen, nothing happens at all — and that is the design, not a failure.
     *
     * Two absolute pixels do not say what they were measured against, and nothing ever wrote it down.
     * Converting them at mod-init time, before any window has a GUI-scaled size, would mean inventing
     * the screen they meant; the card would land somewhere the player never put it, once, permanently.
     * So the file stays at version 0 until a frame can supply real dimensions.
     */
    @Test
    fun `without a screen the file is left at version 0 to be migrated later`() {
        val old = legacy(276, 172)
        val untouched = ConfigMigration.migrate(old, 0, 0, cardW, cardH)

        assertSame(old, untouched)
        assertEquals(0, ConfigMigration.versionOf(untouched))
        assertTrue(untouched.has("hudX"), "the old position must survive to be converted later")

        // And the deferred attempt, once there is a screen, is the same conversion as any other.
        assertEquals(1, ConfigMigration.versionOf(ConfigMigration.migrate(old, screenW, screenH, cardW, cardH)))
    }

    /**
     * A missing `hudX` falls back to the default position, never to zero.
     *
     * This is the rule the rest of [Config] is written around, inherited: a key that is absent because
     * an older version never wrote it must read as the default, and a rename must not turn one into a
     * different value. Zero here would be a card shoved into the very corner of a screen belonging to
     * somebody who had never moved it.
     */
    @Test
    fun `a file with no position at all migrates to the default one`() {
        val bare = JsonObject()
        bare.addProperty("hud", true)

        val migrated = ConfigMigration.migrate(bare, screenW, screenH, cardW, cardH)

        assertEquals(HudPlacement.DEFAULT_ANCHOR.name, migrated.get("hudAnchor").asString)
        assertEquals(HudPlacement.DEFAULT_OFFSET, migrated.get("hudOffsetX").asInt)
        assertEquals(HudPlacement.DEFAULT_OFFSET, migrated.get("hudOffsetY").asInt)
    }

    /**
     * A position that no longer exists on this screen is pulled onto it, and stays on it afterwards.
     *
     * This is the failure the anchor exists to end, met on the way in: a config written on a large
     * window, opened on a small one. The old pixels are off the edge, and an offset derived from them
     * without clamping would be an offset that puts the card back off the edge on every screen it ever
     * sees again — the bug preserved rather than fixed.
     */
    @Test
    fun `a position off today's screen is brought back onto it`() {
        val migrated = ConfigMigration.migrate(legacy(1000, 800), screenW, screenH, cardW, cardH)
        val origin = originOf(migrated, screenW, screenH)

        assertEquals(screenW - cardW, origin.x)
        assertEquals(screenH - cardH, origin.y)
        assertEquals("BOTTOM_RIGHT", migrated.get("hudAnchor").asString)
        assertEquals(0, migrated.get("hudOffsetX").asInt, "an offset from the edge, not a distance past it")

        // And on a screen twice the size it is still the same eight-pixel-free corner, not off it.
        val wide = originOf(migrated, screenW * 2, screenH * 2)
        assertEquals(screenW * 2 - cardW, wide.x)
        assertEquals(screenH * 2 - cardH, wide.y)
    }

    /**
     * A hand-broken `config.json` costs the key it is in and nothing else — above all not the identity.
     *
     * This is the expensive one, and it is not a hypothetical: the placement keys and `installId` are
     * documented as hand-editable, so somebody typing in this file is a supported path and a misplaced
     * quote is what happens on it. `Config.read` calls
     * `migrate` as its very first statement, and `asInt` throws on a string, on a null and on an
     * object — so one bad `version` used to take the *whole* read down into the catch-all. `installId`
     * would then be blank, `Config.load` would mint a new UUID and save it, and every run this install
     * has ever uploaded would be orphaned on the receiver with no way back: the old id was only in the
     * file that had just been overwritten. There is no error to read and nothing to undo.
     *
     * So none of these throw, and a version nobody can parse reads as 0 — which is a redundant
     * migration pass and idempotent, where the alternative is a lost history.
     */
    @Test
    fun `a version of the wrong shape reads as zero instead of throwing`() {
        for (broken in listOf("\"x\"", "null", "{}", "[]", "true")) {
            val obj = JsonParser.parseString("""{"version":$broken,"hudX":40,"hudY":20}""").asJsonObject
            assertEquals(0, ConfigMigration.versionOf(obj), "version $broken")

            // And it still migrates, rather than being stuck behind a version it cannot read.
            val migrated = ConfigMigration.migrate(obj, screenW, screenH, cardW, cardH)
            assertEquals(1, ConfigMigration.versionOf(migrated), "version $broken did not migrate")
        }

        // A number written as a string is still a number, which is what a hand-edited file often has.
        assertEquals(1, ConfigMigration.versionOf(JsonParser.parseString("""{"version":"1"}""").asJsonObject))
        assertEquals(0, ConfigMigration.versionOf(JsonObject()), "no version at all is version 0")
    }

    /** The same rule on the position keys: an unreadable one is a key nobody set. */
    @Test
    fun `an unreadable position falls back to the default rather than throwing`() {
        val obj = JsonParser.parseString("""{"hudX":"left","hudY":null,"installId":"abc-123"}""").asJsonObject
        val migrated = ConfigMigration.migrate(obj, screenW, screenH, cardW, cardH)

        assertEquals(HudPlacement.DEFAULT_ANCHOR.name, migrated.get("hudAnchor").asString)
        assertEquals(HudPlacement.DEFAULT_OFFSET, migrated.get("hudOffsetX").asInt)
        assertEquals(HudPlacement.DEFAULT_OFFSET, migrated.get("hudOffsetY").asInt)
        assertEquals("abc-123", migrated.get("installId").asString, "the identity survives a broken neighbour")
    }

    /**
     * The typed readers `Config` goes through, on every shape a hand-edited file can hold.
     *
     * A boolean is the one worth spelling out: Gson reads `"hud": "yes"` as `false` rather than
     * throwing, which is worse than a throw — the HUD switches itself off and looks like a setting
     * somebody made. So only a real boolean counts and everything else is "nobody set this".
     */
    @Test
    fun `a value of the wrong type reads as unset`() {
        val obj = JsonParser.parseString(
            """{"hud":"yes","stormCountdownTicks":{},"hudAnchor":[],"installId":null}""",
        ).asJsonObject

        assertTrue(ConfigMigration.boolOr(obj, "hud", true), "a string is not a switch anybody set")
        assertFalse(ConfigMigration.boolOr(obj, "hud", false), "and the fallback is what wins, either way")
        assertEquals(138, ConfigMigration.intOr(obj, "stormCountdownTicks", 138))
        assertEquals("", ConfigMigration.stringOr(obj, "hudAnchor", ""))
        assertEquals("keep-me", ConfigMigration.stringOr(obj, "installId", "keep-me"))

        val good = JsonParser.parseString("""{"hud":false,"stormCountdownTicks":90,"installId":"id"}""").asJsonObject
        assertFalse(ConfigMigration.boolOr(good, "hud", true), "a real boolean is still read")
        assertEquals(90, ConfigMigration.intOr(good, "stormCountdownTicks", 138))
        assertEquals("id", ConfigMigration.stringOr(good, "installId", ""))
    }

    // --- Placement --------------------------------------------------------------------------

    /**
     * Every anchor survives the trip out to a position and back, because that trip is what a drag is:
     * the editor moves the card in pixels and the anchor is read off where it stopped. A pair that did
     * not round-trip would move the card by the rounding error the moment it was let go.
     */
    @Test
    fun `every anchor round-trips through a position`() {
        for (anchor in HudPlacement.Anchor.entries) {
            val origin = HudPlacement.origin(anchor, 12, 9, screenW, screenH, cardW, cardH)
            val back = HudPlacement.nearest(origin.x, origin.y, screenW, screenH, cardW, cardH)

            assertEquals(anchor, back.anchor, "${anchor.name} read back as ${back.anchor.name}")
            assertEquals(12, back.offsetX, "${anchor.name} horizontal offset")
            assertEquals(9, back.offsetY, "${anchor.name} vertical offset")
        }
    }

    /**
     * The point of the whole change, stated once: an anchored card keeps its distance from its own
     * edge when the screen changes size, where an absolute one keeps its distance from the top left
     * and ends up somewhere else — or nowhere.
     */
    @Test
    fun `an anchored card holds its edge across a resolution change`() {
        val corner = HudPlacement.Anchor.BOTTOM_RIGHT
        for (screen in arrayOf(intArrayOf(480, 270), intArrayOf(320, 180), intArrayOf(960, 540))) {
            val origin = HudPlacement.origin(corner, 8, 8, screen[0], screen[1], cardW, cardH)
            assertEquals(screen[0] - cardW - 8, origin.x, "${screen[0]}px wide")
            assertEquals(screen[1] - cardH - 8, origin.y, "${screen[1]}px tall")
        }
    }

    /**
     * A window with no room for the card at all still puts it somewhere readable.
     *
     * There is no correct answer — the card does not fit — so the one that is chosen has to be the one
     * that recovers: flush to the top left, which is the corner the room name and the clock hang off,
     * and an offset of zero, which means the same thing on the normal-sized window this player will be
     * back on in a moment.
     */
    @Test
    fun `a window smaller than the card anchors flush to the corner`() {
        val tiny = HudPlacement.nearest(0, 0, 150, 60, cardW, cardH)

        assertEquals(HudPlacement.Anchor.TOP_LEFT, tiny.anchor)
        assertEquals(0, tiny.offsetX)
        assertEquals(0, tiny.offsetY)

        val origin = HudPlacement.origin(tiny.anchor, tiny.offsetX, tiny.offsetY, 150, 60, cardW, cardH)
        assertEquals(0, origin.x, "never negative — the card's own corner stays on screen")
        assertEquals(0, origin.y)
    }

    /** A hand-edited config is a supported way to set this file, so a typo costs the anchor only. */
    @Test
    fun `an unreadable anchor name falls back to the default`() {
        assertEquals(HudPlacement.Anchor.BOTTOM_LEFT, HudPlacement.Anchor.of("BOTTOM_LEFT"))
        assertEquals(HudPlacement.DEFAULT_ANCHOR, HudPlacement.Anchor.of("bottom-left"))
        assertEquals(HudPlacement.DEFAULT_ANCHOR, HudPlacement.Anchor.of(""))
    }

    // --- Helpers ----------------------------------------------------------------------------

    /** A config file as versions before this one wrote it: absolute pixels, no version. */
    private fun legacy(x: Int, y: Int): JsonObject {
        val obj = JsonObject()
        obj.addProperty("hudX", x)
        obj.addProperty("hudY", y)
        return obj
    }

    /** Where a migrated object puts the card, which is the only thing the player can see. */
    private fun originOf(obj: JsonObject, screenW: Int, screenH: Int) = HudPlacement.origin(
        HudPlacement.Anchor.of(obj.get("hudAnchor").asString),
        obj.get("hudOffsetX").asInt,
        obj.get("hudOffsetY").asInt,
        screenW, screenH, cardW, cardH,
    )
}
