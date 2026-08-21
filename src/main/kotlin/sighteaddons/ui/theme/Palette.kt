package sighteaddons.ui.theme

/**
 * One theme's colour ramp: a neutral scale, plus exactly two hues that are only ever spent on state.
 *
 * **This file used to say "strictly monochrome ... there is no hue anywhere in this UI by design", and
 * that is no longer true.** The user reversed it on 21.08.2026 after seeing the two side by side. Worth
 * recording rather than quietly editing, because the monochrome rule was load-bearing for a while and
 * its consequences are still all over this UI: every state is *also* carried by a shape, a label or a
 * weight, never by colour alone. That property is kept. The hues make the states easier to find; they
 * are never the only thing that says what a state is, so the design still survives a greyscale
 * screenshot and a reader who cannot separate two hues.
 *
 * The two are [accent] and [positive], and the rules for them are narrow:
 *
 * - [accent] marks **what is currently selected or in progress** — the open page, a switch that is on,
 *   a slider's travelled span, the sorted column's direction, a focus ring.
 * - [positive] marks **one fact and no others**: that a record is the player's own best. Nothing
 *   decorative is allowed to borrow it, or it stops meaning anything.
 * - [invert] is the achromatic pair that survives from the monochrome design, and it keeps its job:
 *   a filter chip and a badge invert rather than colour, because a chip set is a *filter* and a filter
 *   is not a state of progress.
 *
 * Two instances exist, [DARK] and [LIGHT]. Component code never names either: it reads [Tokens],
 * which points at whichever is active, so a theme switch changes values and never branches.
 *
 * Values are packed ARGB ints. The translucent ones (`hover`, `active`, the three borders) are
 * deliberately *not* pre-composited — they are laid over whatever surface they land on, which is what
 * makes one hover token work on a card, a popover and a row alike.
 */
internal class Palette(
    val name: String,

    /** Screen background. */
    val surfaceBase: Int,
    /** Cards and panels. */
    val surfaceRaised: Int,
    /** Popovers, dropdowns, tooltips. */
    val surfaceOverlay: Int,
    /** Row hover. Translucent. */
    val surfaceHover: Int,
    /** Pressed and selected. Translucent. */
    val surfaceActive: Int,

    /** Dividers. Translucent. */
    val borderSubtle: Int,
    /** Card outlines. Translucent. */
    val borderDefault: Int,
    /** Focused and active outlines, and the focus ring. Translucent. */
    val borderStrong: Int,

    /** Values and headings. */
    val textPrimary: Int,
    /** Labels. */
    val textSecondary: Int,
    /** Metadata and units. */
    val textTertiary: Int,
    /** Inactive. Exempt from the contrast floor, per WCAG's disabled-control exemption. */
    val textDisabled: Int,

    /** Selected, on, in progress. See the class notes for what may and may not use it. */
    val accent: Int,
    /** What sits legibly on top of [accent]. Measured, not assumed — see the ramps below. */
    val accentText: Int,
    /**
     * The accent at text weight.
     *
     * [accent] itself is a *fill* colour: at the saturation the design wants, white on it measures
     * 3.48:1 and fails the floor, which is why [accentText] is the dark end. Set as text on a dark
     * surface the same hue has to travel the other way instead, and this is where it lands.
     */
    val accentSoft: Int,

    /** The achromatic inverted fill an active chip and a solid badge wear. */
    val invert: Int,
    /** What sits on [invert]. */
    val invertText: Int,

    /** The player's own best. One fact, one colour, nothing else may use it. */
    val positive: Int,

    /** The base of every drop shadow. Always the dark end, because a shadow is an absence of light. */
    val shadow: Int,

    /**
     * The backdrop laid under HUD and overlay text, over whatever the world happens to be.
     *
     * **Its own token rather than [shadow] reused, because the two want opposite things in a light
     * ramp.** A shadow is dark in both themes — that is what a shadow is. A scrim is a *surface* the
     * theme's own text has to be readable on, so in the light ramp it has to be light: with `shadow`
     * standing in for it, `LIGHT`'s `#0A0A0B` text sat on a `#3C3F45` wash and measured **1.32:1**
     * against a dark dungeon. The card was there and empty.
     *
     * Opaque values, laid down at the opacity `Tokens.scrimAlpha` resolves — the alpha is the setting
     * and belongs to the caller, not to the ramp.
     */
    val scrim: Int,
    /** The 1px inner top highlight that sells a raised surface without a hue. Translucent. */
    val highlight: Int,
) {

    /** The three surfaces text can land on, darkest-contrast first — what [Tokens] checks against. */
    val surfaces: IntArray get() = intArrayOf(surfaceBase, surfaceRaised, surfaceOverlay)

    companion object {

        /**
         * The default. Values follow the specified ramp, with one measured deviation.
         *
         * `textTertiary` was specified as `#6C7078`. That fails the 4.5:1 floor this UI is also
         * required to hold, and not marginally: **3.57:1** on `surfaceOverlay`, and **2.88:1** on a
         * pressed overlay row once `surfaceActive` is composited in. The two requirements contradict
         * each other and the floor is the one a reader can feel, so the floor wins.
         *
         * `#91959D` is the darkest neutral that clears 4.5:1 against every surface *and* every
         * surface under the hover and pressed washes — the binding case being an overlay row under
         * `surfaceActive`, where it measures 4.77:1. On a plain card it is 6.23:1.
         *
         * The cost is real and worth stating: tertiary now sits 1.27:1 from secondary rather than
         * 2.0:1, so the step between "label" and "metadata" is a smaller one than the spec drew. In a
         * design with no hue to spend, luminance separation *is* the hierarchy — which is exactly why
         * the spec also requires every state to be carried by a glyph or a label as well, never by
         * luminance alone. UiThemeTest pins all of this rather than trusting it.
         */
        val DARK = Palette(
            name = "dark",
            surfaceBase = 0xFF0A0A0B.toInt(),
            surfaceRaised = 0xFF121214.toInt(),
            surfaceOverlay = 0xFF18181B.toInt(),
            surfaceHover = 0x0AFFFFFF,
            surfaceActive = 0x14FFFFFF,
            borderSubtle = 0x0FFFFFFF,
            borderDefault = 0x1AFFFFFF,
            borderStrong = 0x2EFFFFFF,
            textPrimary = 0xFFF6F7F8.toInt(),
            textSecondary = 0xFFA5A9B0.toInt(),
            textTertiary = 0xFF91959D.toInt(),
            textDisabled = 0xFF474B52.toInt(),
            // BlackSkija's own pair, taken from its README example, which is what "orient on this"
            // meant literally. `accentText` stays the dark end and is a measurement, not a taste: white
            // on this blue is 3.48:1 and under the floor, while `#0A0A0B` on it is 5.55:1.
            accent = 0xFF5A82FF.toInt(),
            accentText = 0xFF0A0A0B.toInt(),
            accentSoft = 0xFF96CDFF.toInt(),
            invert = 0xFFFFFFFF.toInt(),
            invertText = 0xFF0A0A0B.toInt(),
            positive = 0xFF6BE0A6.toInt(),
            shadow = 0xFF000000.toInt(),
            scrim = 0xFF000000.toInt(),
            highlight = 0x0FFFFFFF,
        )

        /**
         * The inverted ramp behind the theme toggle. Not a mirror image of [DARK]: white surfaces
         * reflect more than black ones absorb, so the borders and the hover wash have to be carried a
         * few percent further before they read at all, and the greys move independently of their dark
         * counterparts to hold the same contrast against a much brighter backdrop.
         */
        val LIGHT = Palette(
            name = "light",
            surfaceBase = 0xFFFAFAFA.toInt(),
            surfaceRaised = 0xFFFFFFFF.toInt(),
            surfaceOverlay = 0xFFFFFFFF.toInt(),
            surfaceHover = 0x0D000000,
            surfaceActive = 0x17000000,
            borderSubtle = 0x14000000,
            borderDefault = 0x24000000,
            borderStrong = 0x3D000000,
            textPrimary = 0xFF0A0A0B.toInt(),
            textSecondary = 0xFF4C5058.toInt(),
            textTertiary = 0xFF5F636B.toInt(),
            textDisabled = 0xFFAEB2B9.toInt(),
            // Both hues move a long way down for the light ramp, and for the usual reason: a colour
            // that reads as an accent on near-black is a pastel on white. The dark ramp's `#96CDFF`
            // measures 1.4:1 on a white card; this blue is 6.6:1, and it doubles as `accentSoft`
            // because a light theme has nowhere brighter for the same hue to go.
            accent = 0xFF2F52C8.toInt(),
            accentText = 0xFFFAFAFA.toInt(),
            accentSoft = 0xFF2F52C8.toInt(),
            invert = 0xFF0A0A0B.toInt(),
            invertText = 0xFFFAFAFA.toInt(),
            // `#0F7A52` was the first attempt and measured 4.17:1 on a pressed white row, which the
            // new hue test caught. The binding case is not a plain card but a card under
            // `surfaceActive`, exactly as it was for `textTertiary` in the dark ramp.
            positive = 0xFF0E7049.toInt(),
            shadow = 0xFF3C3F45.toInt(),
            // Pure white rather than `surfaceBase`: this is the only surface in the ramp that has to
            // carry text over an unknown backdrop, and every step it is darkened is a step the world
            // underneath shows through. The floor in `Tokens.SCRIM_MIN_PERCENT` is computed against
            // this value, and a darker one would push that floor past fully opaque.
            scrim = 0xFFFFFFFF.toInt(),
            highlight = 0x99FFFFFF.toInt(),
        )
    }
}
