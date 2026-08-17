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

    /** A file with no `version` is version 0: everything written before this existed. */
    fun versionOf(obj: JsonObject): Int = if (obj.has("version")) obj.get("version").asInt else 0

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
     * bug the whole of [Config]'s explicit-fallback style exists to prevent.
     *
     * The old keys are then removed. They are not carried along: a config file with both shapes in it
     * has two answers to where the HUD is, and the next person to read this file would have to know
     * which one wins.
     */
    private fun toAnchoredHud(obj: JsonObject, screenW: Int, screenH: Int, cardW: Int, cardH: Int): JsonObject? {
        if (screenW <= 0 || screenH <= 0 || cardW <= 0 || cardH <= 0) return null

        val out = obj.deepCopy()
        val x = if (out.has("hudX")) out.get("hudX").asInt else HudPlacement.DEFAULT_OFFSET
        val y = if (out.has("hudY")) out.get("hudY").asInt else HudPlacement.DEFAULT_OFFSET
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
