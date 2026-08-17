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
    val scrim get() = palette.scrim
    val highlight get() = palette.highlight

    /**
     * The scrim's opacity range, as a percentage, and the reason it is not `0..100`.
     *
     * A scrim is the only backdrop this UI has over the world — there is no blur available (see
     * `HudRoot`) — so it is the entire reason the text on the HUD card and the two centred overlays is
     * readable at all. How much of the dungeon shows through is a matter of taste, which is why it is a
     * setting; a backdrop with *no* opacity at all is not a preference but an invisible card, which is
     * why the range still has ends.
     *
     * **[SCRIM_MIN_PERCENT] used to be [SCRIM_CONTRAST_PERCENT] and is not any more, at the user's
     * explicit request.** The measurement did not change and has not moved out of this file — it is the
     * constant below, and `UiThemeTest` still computes both of its ends. What changed is that it is no
     * longer the control's bound: two people use this mod, both of them know what a washed-out card
     * looks like, and a floor that holds a contrast ratio nobody asked to be held is a setting that
     * refuses to do the thing it is for. Below [SCRIM_CONTRAST_PERCENT] the `/sa` row says so.
     *
     * 30 rather than 0: at zero there is no chip at all, only text over a moving dungeon, and the whole
     * argument for a scrim over a shadow is that a chip has to have a back to it.
     */
    const val SCRIM_MIN_PERCENT = 30
    const val SCRIM_MAX_PERCENT = 100

    /**
     * The lowest opacity at which every text tone still clears 4.5:1 over any world.
     *
     * Measured rather than chosen. At this opacity the worst pairing in either ramp — light
     * `textTertiary` on a white scrim over a black dungeon — sits at **4.57:1**, and one percent lower
     * it is **4.48:1**, under the floor. `UiThemeTest` computes both ends rather than trusting this
     * paragraph.
     *
     * The number is high, and that is the honest cost of holding the floor against a backdrop we do not
     * own: the world behind the card can be anything from a black corridor to a snow floor, and a wash
     * that has to work over both has very little translucency to spend. The spec asked for 55–70 %,
     * which measures 1.6:1 to 2.8:1 in the worst case — a card with nothing legible on it.
     *
     * So it is no longer a limit but a *statement*, and the one place that reads it is the note under
     * the `/sa` scrim row: a player below this number has been told what it costs, which is the
     * difference between a choice and a defect.
     */
    const val SCRIM_CONTRAST_PERCENT = 88

    /**
     * The default: still a tenth of the dungeon showing, and above [SCRIM_CONTRAST_PERCENT].
     *
     * Unchanged by the range opening downwards, deliberately. Every install that has never touched the
     * slider keeps the backdrop it has always had, and a default nobody chose is the one place the
     * measured floor should still win.
     */
    const val SCRIM_PERCENT = 90

    /**
     * [percent] as an alpha byte, clamped into the range the slider offers.
     *
     * Returned as an alpha rather than as a finished colour because all three callers multiply it by
     * their own fade before it reaches [alpha], and a token faded twice is a token at the wrong opacity.
     */
    fun scrimAlpha(percent: Int): Int =
        Math.round(percent.coerceIn(SCRIM_MIN_PERCENT, SCRIM_MAX_PERCENT) * 255f / 100f)

    /**
     * The grey the gallery stands a HUD element on, so a scrim can be judged over something lit.
     *
     * Not a design token and never drawn in the game: every overlay in this UI is transparent to some
     * degree, and previewing one over `surfaceBase` would flatter its scrim into looking like it works
     * against a dungeon. It is a mid neutral because that is roughly what a torch-lit corridor
     * measures, and it is deliberately outside the ramp — nothing in the ramp is allowed to be a
     * backdrop the mod does not own.
     *
     * It lives here anyway, and not next to the screen that uses it, because the rule is that no
     * colour is written outside this package. An exception carved out for development code would be an
     * exception no test can see, and `UiThemeTest` reads the source to hold exactly that line.
     */
    const val PREVIEW_STAGE = 0xFF6E7378.toInt()

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
