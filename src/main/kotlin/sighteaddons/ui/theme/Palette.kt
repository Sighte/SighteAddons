package sighteaddons.ui.theme

/**
 * One theme's colour ramp. Strictly monochrome — every value here is a neutral, and there is no hue
 * anywhere in this UI by design.
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

    /** The only accent this design has: the extreme of the ramp. */
    val accent: Int,
    /** What sits legibly on top of [accent] — the opposite extreme. */
    val accentText: Int,

    /** Scrim behind HUD text, and the base of every drop shadow. */
    val shadow: Int,
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
            accent = 0xFFFFFFFF.toInt(),
            accentText = 0xFF0A0A0B.toInt(),
            shadow = 0xFF000000.toInt(),
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
            accent = 0xFF0A0A0B.toInt(),
            accentText = 0xFFFAFAFA.toInt(),
            shadow = 0xFF3C3F45.toInt(),
            highlight = 0x99FFFFFF.toInt(),
        )
    }
}
