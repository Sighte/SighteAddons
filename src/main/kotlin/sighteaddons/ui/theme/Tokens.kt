package sighteaddons.ui.theme

/**
 * The single source of truth for every colour, radius, spacing step and text size in this UI.
 *
 * Nothing outside this package may hold a colour literal or an off-scale number. That rule is the
 * whole point: before it, the mod carried seventeen hardcoded colours across four files, three of
 * which disagreed with each other under the same name (`SighteAddons.GREY` was `0xFFAAAAAA`,
 * `ClearPopup.GREY` was `0xB9BEC7`), and the one shared value was kept in sync by a hand-copied
 * comment.
 *
 * Colours resolve through [palette], so a theme switch changes values without any component
 * branching on the theme.
 */
internal object Tokens {

    /** The active ramp. Swapping this is the entire theme switch. */
    var palette: Palette = Palette.DARK

    fun theme(dark: Boolean) {
        palette = if (dark) Palette.DARK else Palette.LIGHT
    }

    // Colour — thin accessors so component code reads `Tokens.textPrimary`, never `Palette.DARK.…`.

    val surfaceBase get() = palette.surfaceBase
    val surfaceRaised get() = palette.surfaceRaised
    val surfaceOverlay get() = palette.surfaceOverlay
    val surfaceHover get() = palette.surfaceHover
    val surfaceActive get() = palette.surfaceActive
    val borderSubtle get() = palette.borderSubtle
    val borderDefault get() = palette.borderDefault
    val borderStrong get() = palette.borderStrong
    val textPrimary get() = palette.textPrimary
    val textSecondary get() = palette.textSecondary
    val textTertiary get() = palette.textTertiary
    val textDisabled get() = palette.textDisabled
    val accent get() = palette.accent
    val accentText get() = palette.accentText
    val shadow get() = palette.shadow
    val highlight get() = palette.highlight

    /**
     * Replaces an ARGB colour's alpha with [alpha] (0..255).
     *
     * The one sanctioned way to vary a token, for fades and for the receding opacity of HUD history
     * rows. It varies transparency only — it can never introduce a value that is not in the ramp.
     */
    fun alpha(argb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)

    /** [alpha] as a 0f..1f fraction of the colour's own opacity, for animating a token out. */
    fun fade(argb: Int, fraction: Float): Int =
        alpha(argb, (((argb ushr 24) and 0xFF) * fraction.coerceIn(0f, 1f)).toInt())

    // Geometry. Radii are the only five that exist; `FULL` means "half the shorter side", for chips.

    const val RADIUS_XS = 2
    const val RADIUS_SM = 4
    const val RADIUS_MD = 8
    const val RADIUS_LG = 12
    const val RADIUS_XL = 16
    const val RADIUS_FULL = -1

    /** Cards. */
    const val RADIUS_CARD = RADIUS_LG
    /** Rows. */
    const val RADIUS_ROW = RADIUS_MD
    /** Chips. */
    const val RADIUS_CHIP = RADIUS_FULL

    /**
     * The spacing scale, in GUI pixels at scale 1. Nothing in this UI is spaced off this scale — a
     * layout that wants 7 takes 6 or 8 and the difference is not visible; a layout that takes 7
     * because nobody checked is how a design system dies.
     */
    val SPACING = intArrayOf(2, 4, 6, 8, 12, 16, 20, 24, 32, 48)

    const val SPACE_2 = 2
    const val SPACE_4 = 4
    const val SPACE_6 = 6
    const val SPACE_8 = 8
    const val SPACE_12 = 12
    const val SPACE_16 = 16
    const val SPACE_20 = 20
    const val SPACE_24 = 24
    const val SPACE_32 = 32
    const val SPACE_48 = 48

    /**
     * The type scale, in GUI pixels.
     *
     * The vanilla bitmap font is 9px tall and does not scale to these cleanly, which is the argument
     * for the bundled TTF: every one of these sizes is a real rasterisation, not `pose().scale` on a
     * 9px bitmap. Labels take [TEXT_11] uppercase with tracking; values take [TEXT_14] to [TEXT_20].
     */
    const val TEXT_10 = 10
    const val TEXT_11 = 11
    const val TEXT_12 = 12
    const val TEXT_14 = 14
    const val TEXT_16 = 16
    const val TEXT_20 = 20
    const val TEXT_28 = 28

    /** Tracking for [TEXT_11] uppercase labels, in GUI pixels per character. `0.06em` at 11px. */
    const val TRACKING_LABEL = 0.66f

    /** Line height for prose. Numeric displays sit at 1.0 and are laid out by their own box. */
    const val LINE_HEIGHT_PROSE = 1.4f

    /**
     * Elevation levels. `e0` is flat with a subtle border and no shadow; the rest carry a shadow and
     * the 1px inner top highlight that is what actually sells a raised surface in a UI with no hue to
     * spend. The highlight is not optional — without it a card is a slightly different grey.
     *
     * Only [E2] is used on panels. Shadowing every card costs ~9 draw states each for something that
     * reads as one soft glow; see the primitive layer for the measurement.
     */
    enum class Elevation(val shadowAlpha: Int, val shadowSpread: Int, val shadowOffset: Int, val highlight: Boolean) {
        E0(0, 0, 0, false),
        E1(102, 8, 2, true),
        E2(140, 32, 8, true),
        E3(178, 64, 24, true),
    }
}
