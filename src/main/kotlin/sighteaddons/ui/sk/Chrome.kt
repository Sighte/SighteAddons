package sighteaddons.ui.sk

import sighteaddons.ui.theme.Tokens

/**
 * The furniture of the redesigned screen: the panel it all sits in, the rail down its left, and the
 * cards inside it.
 *
 * ### The screen is now an object rather than a surface
 *
 * The old screen filled the window with [Tokens.surfaceBase] and drew hairlines on it, and its own
 * comment argued that a settings screen earns an opaque background. That was right for what it had:
 * with no rounded corners, no real shadow and no blur, a "floating panel" would have been a rectangle
 * of slightly different grey, which is worse than an honest full-bleed layout.
 *
 * With a GPU behind it the trade reverses. A panel with a real cast shadow over a blurred dungeon has
 * an *edge*, and an edge is what lets a measured layout show that it fits — the whole class of bug
 * this screen has had (percentages budgeted at one window size, correct at 480 and wrong at 427) is
 * invisible on a full-bleed layout and obvious the moment there is a border to run past.
 *
 * ### Nothing here is tracked, and that is the monospace paying for itself
 *
 * `Labels` drew uppercase labels one glyph at a time to fake letter-spacing, because the vanilla
 * bitmap font has no tracking control — and its own comment called that cost "the strongest argument
 * for the bundled TTF". The argument landed somewhere unexpected: the bundled face is JetBrains
 * *Mono*, which is already generously spaced, so a 10px uppercase label reads as a label with no
 * tracking at all. The per-glyph loop is gone, and with it one draw call per character per frame.
 */
internal object Chrome {

    // --- Backdrop -------------------------------------------------------------------------------

    /**
     * How much the blurred world is dimmed behind the panel, as an alpha over [Tokens.scrim].
     *
     * Well short of opaque, because the blur is doing most of the work: MC's own blur removes the
     * detail that would compete with text, and the dim only has to flatten what is left into a ground.
     * Deep enough that the panel's shadow has something to fall on — a shadow over a bright blur is
     * invisible, which makes the panel look pasted on rather than lifted.
     *
     * A *dim*, not a scrim in [Tokens]' sense: nothing is read directly off it. The panel is opaque.
     */
    const val DIM_ALPHA = 190

    /**
     * The placing mode's backdrop, and the one rule that survived the redesign verbatim.
     *
     * **Placing must not paint over the game.** The entire question it exists to answer is where the
     * card sits against a dungeon — past the hotbar, the health bar, the boss bar — and a flat surface
     * behind it answers that question about a flat surface. So: no panel, no blur, and a wash light
     * enough to read the world through. Not nothing either, or the hint line has nothing to sit on.
     */
    const val PLACING_DIM_ALPHA = 70

    // --- The panel ------------------------------------------------------------------------------

    /** The panel's corner radius. Cards inside take the smaller one, so the nesting reads. */
    const val RADIUS = Tokens.RADIUS_LG

    const val CARD_RADIUS = Tokens.RADIUS_MD

    /**
     * Draws the panel: shadow, gradient body, border, top lip.
     *
     * The gradient runs [Tokens.surfaceRaised] to [Tokens.surfaceBase] — two values already in the
     * measured ramp, so the redesign adds no colour and `UiThemeTest` has nothing new to check. Top
     * lighter than bottom because that is where light comes from in every UI anyone has used, and
     * because it puts the *lighter* end next to the header, which is the part meant to read first.
     *
     * The lip is the one-pixel highlight along the top edge, kept from `Surface.topHighlight` for the
     * reason stated there: in a design with no hue to spend, it is what separates a raised surface
     * from a slightly different grey. It is not optional and it is not decoration.
     */
    fun panel(x: Float, y: Float, w: Float, h: Float) {
        val e = Tokens.Elevation.E3
        Sk.shadow(
            x, y, w, h,
            Tokens.alpha(Tokens.shadow, e.shadowAlpha),
            RADIUS.toFloat(),
            blur = e.shadowSpread.toFloat(),
            dy = e.shadowOffset.toFloat(),
        )
        Sk.gradient(x, y, w, h, Tokens.surfaceRaised, Tokens.surfaceBase, vertical = true, radius = RADIUS.toFloat())
        Sk.border(x, y, w, h, Tokens.borderDefault, RADIUS.toFloat())
        lip(x, y, w)
    }

    /** The top-edge highlight, inset past the corner radius so it does not paint over the curve. */
    fun lip(x: Float, y: Float, w: Float) {
        val inset = RADIUS.toFloat()
        if (w <= inset * 2f) return
        Sk.line(x + inset, y + 1f, x + w - inset, y + 1f, Tokens.highlight, HAIRLINE)
    }

    /**
     * A card: the container one group of rows sits in.
     *
     * Flat by design — no shadow. Twelve shadowed cards is a hundred blurred draws for something that
     * reads as one soft glow, which is the measurement `Surface.card` recorded and which the GPU makes
     * cheaper without making it a better idea. The panel carries the elevation; the cards carry the
     * grouping.
     */
    fun card(x: Float, y: Float, w: Float, h: Float) {
        Sk.fill(x, y, w, h, Tokens.surfaceOverlay, CARD_RADIUS.toFloat())
        Sk.border(x, y, w, h, Tokens.borderSubtle, CARD_RADIUS.toFloat())
    }

    /** The hairline between two rows inside a card, inset so it does not touch the card's border. */
    fun rowRule(x: Float, y: Float, w: Float) {
        Sk.line(x + Tokens.SPACE_8, y, x + w - Tokens.SPACE_8, y, Tokens.borderSubtle, HAIRLINE)
    }

    /**
     * One physical-ish pixel.
     *
     * A real number rather than an integer, and no device-grid snapping: `DevicePixels` existed
     * because an integer `fill` of height 1 landed between two physical pixels at fractional GUI
     * scales and vanished. An anti-aliased line at 1.0 renders as a line at every scale — dimmer where
     * it straddles the grid, but present, which is the property that was missing.
     */
    const val HAIRLINE = 1f

    /** A vertical hairline, for the rail's divider and anything else that separates two columns. */
    fun ruleV(x: Float, y: Float, h: Float) {
        Sk.fill(x, y, HAIRLINE, h, Tokens.borderSubtle)
    }

    // --- Header, footer, labels -----------------------------------------------------------------

    /** The header strip's height, inside the panel. */
    const val HEADER = 28

    /** The footer strip's height. */
    const val FOOTER = 20

    /**
     * The panel's header: the mod's name, and the version it is.
     *
     * The version is not decoration. Two people run this mod off dev jars, and the one question a
     * screenshot has to be able to answer is which build produced it.
     */
    fun header(x: Float, y: Float, w: Float, title: String, version: String) {
        val size = Tokens.TEXT_11.toFloat()
        val baseline = Sk.centreY(y, HEADER.toFloat(), size, Type.MEDIUM)
        Sk.text(title.uppercase(), x + Tokens.SPACE_16, baseline, size, Tokens.textSecondary, Type.MEDIUM)
        Sk.textRight(version, x + w - Tokens.SPACE_16, baseline, Tokens.TEXT_10.toFloat(), Tokens.textTertiary)
        Sk.fill(x, y + HEADER, w, HAIRLINE, Tokens.borderSubtle)
    }

    /** The panel's footer: one line saying what the screen expects of you. */
    fun footer(x: Float, y: Float, w: Float, text: String) {
        Sk.fill(x, y, w, HAIRLINE, Tokens.borderSubtle)
        val size = Tokens.TEXT_10.toFloat()
        Sk.text(
            Sk.fit(text, w - Tokens.SPACE_16 * 2, size), x + Tokens.SPACE_16,
            Sk.centreY(y, FOOTER.toFloat(), size), size, Tokens.textTertiary,
        )
    }

    /**
     * A group label: small uppercase, with its optional [meta] pushed to the right edge.
     *
     * No tracking loop; see this object's header. Uppercase is applied by the caller, not here, so a
     * label that is deliberately mixed case stays possible and nothing allocates an `uppercase()` per
     * frame for the labels that are already capitals.
     */
    fun groupLabel(x: Float, y: Float, w: Float, label: String, meta: String = "") {
        val size = Tokens.TEXT_10.toFloat()
        Sk.text(label, x, y, size, Tokens.textTertiary)
        if (meta.isNotEmpty()) Sk.textRight(meta, x + w, y, size, Tokens.textDisabled)
    }

}
