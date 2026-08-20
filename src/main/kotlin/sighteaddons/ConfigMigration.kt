package sighteaddons

import com.google.gson.JsonObject

/**
 * Bringing a `config.json` written by an older version up to the shape this one reads.
 *
 * ### Why there is a version number at all
 *
 * Until now there was no way to tell a key that had been *renamed* from a key the player had never
 * set. Both look identical from inside [Config.read]: absent. That is survivable while every change is
 * an addition — the explicit fallback on each key is exactly the guard against it, and it is why this
 * file has never read through Gson's reflection — but it is not survivable for a rename, because the
 * fallback for the new key is a default, and a default silently replacing something the player chose
 * is the same failure as a HUD that switches itself off after an update. `version` is what separates
 * the two cases: below [CURRENT], a missing key may be an old key under another name; at [CURRENT], a
 * missing key really is a setting nobody ever touched.
 *
 * ### Deliberately a pure function
 *
 * [migrate] takes a [JsonObject] and returns one. No [net.fabricmc.loader.api.FabricLoader], no file
 * system, no `Minecraft` — [Config] itself resolves its path in a field initialiser and cannot even be
 * loaded outside a game. A migration runs exactly once per installation, on a file nobody here will
 * ever see, and its failure mode is a HUD quietly somewhere else. It has to be checkable in a unit
 * test, which means it has to be reachable from one.
 *
 * ### Ordered and idempotent
 *
 * Steps are applied one version at a time, in order, and an object already at [CURRENT] is returned
 * untouched — so a file that has been migrated is never rewritten, and a file three versions behind
 * walks the same path the intervening releases did rather than a special case somebody wrote once.
 */
object ConfigMigration {

    /**
     * The shape [Config] writes today.
     *
     * 1 — `hudX`/`hudY` (absolute GUI pixels from the top left) became `hudAnchor` plus
     * `hudOffsetX`/`hudOffsetY`. See [HudPlacement] for why.
     */
    const val CURRENT = 1

    /**
     * A file with no `version` is version 0: everything written before this existed.
     *
     * And so is a file whose `version` is not a number. That is not defensiveness for its own sake:
     * [Config.read] calls [migrate] as its very first statement, so anything thrown here takes the
     * *whole* read with it — including `installId`, which [Config.load] then finds blank, regenerates,
     * and saves over. A misplaced comma in a hand-edited `config.json` would silently retire the
     * player's upload identity and orphan every run already filed under it on the receiver. Reading a
     * broken version as 0 costs at worst one redundant migration pass, which is idempotent.
     *
     * Hand-editing is a supported way to set this file — the placement anchors and offsets are plain
     * enough to set by hand, and `installId` is read by anybody comparing an upload against the
     * receiver — so a typo has to cost the key it was in and nothing else. Same call
     * [HudPlacement.Anchor.of] already makes.
     */
    fun versionOf(obj: JsonObject): Int = intOr(obj, "version", 0)

    /**
     * [key] as an Int, or [fallback] for absent, null, structured, or unparseable values.
     *
     * Gson's `asInt` throws four different ways — `NumberFormatException` on `"x"`, `ClassCastException`
     * on `true`, `UnsupportedOperationException` on `{}` and on `null` — and every one of them means
     * the same thing here: this key does not carry a number, so use the value that says nobody set it.
     * Catching the supertype rather than listing three is deliberate; a fifth kind of malformed JSON
     * must not be the one that gets through.
     */
    internal fun intOr(obj: JsonObject, key: String, fallback: Int): Int = try {
        val value = obj.get(key)
        if (value == null || !value.isJsonPrimitive) fallback else value.asInt
    } catch (e: RuntimeException) {
        fallback
    }

    /** [key] as a String, on [intOr]'s reasoning: a structured or absent value is nobody's setting. */
    internal fun stringOr(obj: JsonObject, key: String, fallback: String): String = try {
        val value = obj.get(key)
        if (value == null || !value.isJsonPrimitive) fallback else value.asString
    } catch (e: RuntimeException) {
        fallback
    }

    /**
     * [key] as a Boolean, on [intOr]'s reasoning.
     *
     * Gson reads any other primitive as `false` rather than throwing, which is worse than a throw: a
     * `"hud": "yes"` would switch the HUD off and look like a setting. So only a real boolean counts.
     */
    internal fun boolOr(obj: JsonObject, key: String, fallback: Boolean): Boolean {
        val value = obj.get(key) ?: return fallback
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return fallback
        return value.asBoolean
    }

    /**
     * [obj] brought forward as far as it can go, against a [screenW]×[screenH] GUI-scaled screen
     * showing a [cardW]×[cardH] HUD card.
     *
     * **A zero screen means "no screen yet", and is not an error.** The 0 → 1 step cannot be done
     * without one: two absolute pixels do not say which corner they were meant to be near, and the
     * screen they were measured against was never written down. Guessing would move the card. So a
     * screen this migration has not been given yet leaves the object exactly as it was, at version 0,
     * to be migrated by the first frame that has real dimensions to offer — see [Config.hudOrigin].
     * That frame draws the card at the same absolute pixels the old version drew it at, and every
     * frame after that draws it at an anchor, so nothing about the migration is visible.
     */
    fun migrate(obj: JsonObject, screenW: Int, screenH: Int, cardW: Int, cardH: Int): JsonObject {
        var out = obj
        while (true) {
            val version = versionOf(out)
            if (version >= CURRENT) return out
            out = step(out, version, screenW, screenH, cardW, cardH) ?: return out
        }
    }

    /** One version forward, or null when this step cannot be taken yet. */
    private fun step(obj: JsonObject, from: Int, screenW: Int, screenH: Int, cardW: Int, cardH: Int): JsonObject? =
        when (from) {
            0 -> toAnchoredHud(obj, screenW, screenH, cardW, cardH)
            else -> null
        }

    /**
     * 0 → 1: the absolute HUD position becomes an anchor and an offset from it.
     *
     * The old keys are read with [HudPlacement.DEFAULT_OFFSET] as the fallback rather than zero. A
     * file missing `hudX` is a file that never had a position, and the position it never had was four
     * pixels in — reading it as zero would move a card nobody moved, which is precisely the class of
     * bug the whole of [Config]'s explicit-fallback style exists to prevent. A `hudX` that is *not a
     * number* reads the same way, through [intOr]: see [versionOf] for what a throw on this path costs.
     *
     * The old keys are then removed. They are not carried along: a config file with both shapes in it
     * has two answers to where the HUD is, and the next person to read this file would have to know
     * which one wins.
     */
    private fun toAnchoredHud(obj: JsonObject, screenW: Int, screenH: Int, cardW: Int, cardH: Int): JsonObject? {
        if (screenW <= 0 || screenH <= 0 || cardW <= 0 || cardH <= 0) return null

        val out = obj.deepCopy()
        val x = intOr(out, "hudX", HudPlacement.DEFAULT_OFFSET)
        val y = intOr(out, "hudY", HudPlacement.DEFAULT_OFFSET)
        val placement = HudPlacement.nearest(x, y, screenW, screenH, cardW, cardH)

        out.remove("hudX")
        out.remove("hudY")
        out.addProperty("hudAnchor", placement.anchor.name)
        out.addProperty("hudOffsetX", placement.offsetX)
        out.addProperty("hudOffsetY", placement.offsetY)
        out.addProperty("version", 1)
        return out
    }
}
