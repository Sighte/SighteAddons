package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * The three buttons this UI has, and the only ones it is allowed to grow.
 *
 * [Variant.PRIMARY] is the one thing a panel wants you to do, [Variant.SECONDARY] is everything else
 * that changes something, [Variant.GHOST] is a control that must not compete with the content it sits
 * in. Three is a decision, not a shortage: a fourth variant is a fourth thing a reader has to learn to
 * rank, and a monochrome UI ranks by weight alone.
 *
 * ### Why the state colours are public functions
 *
 * [fill] and [labelColour] are the whole of what each state looks like, and they are pure functions of
 * `(variant, hover, press, enabled)`. That is deliberate: it lets `UiComponentsTest` measure the
 * label against its own background across the entire state space, including the halfway point of a
 * hover fade, rather than trusting that a wash which passed at rest still passes at 0.6. A colour
 * decision that only exists inside a draw call is a colour decision nobody can check.
 *
 * ### No state is carried by luminance alone
 *
 * `textTertiary` sits 1.27:1 from `textSecondary`, so "slightly dimmer" is not a state anybody can be
 * asked to read. Hover adds a ring, press moves the label down a pixel, focus adds an outer ring, and
 * disabled dashes the border. Each of those survives a greyscale screenshot and a reader who cannot
 * separate two greys; the luminance change only ever confirms what a shape already said.
 */
internal object Button {

    enum class Variant { PRIMARY, SECONDARY, GHOST }

    /** The comfortable height. Rows compress it the same way `SettingsScreen` compresses its own. */
    const val HEIGHT = 20

    /** Horizontal padding either side of the label. */
    const val PADDING = Tokens.SPACE_12

    /**
     * Nothing narrower than this, however short the label.
     *
     * "ok" is 11 pixels wide and a button that size is a target you have to aim at. The minimum is
     * what makes a row of buttons read as a row of buttons rather than as text with boxes round it.
     */
    const val MIN_WIDTH = 48

    /** How far the label moves down while held. One pixel, which is all a press needs to be. */
    const val PRESS_TRAVEL = 1

    /** How far outside the button the focus ring sits. */
    const val FOCUS_OFFSET = 2

    /**
     * The width a button needs for [label].
     *
     * Derived rather than returned from [draw], for the reason `Controls.chipWidth` states: a caller
     * has to lay out the next button and hit-test the cursor against the same rectangle this one
     * occupies, and a width that only exists after the draw forces either a guess or a frame of lag.
     */
    fun width(font: Font, label: String): Int =
        maxOf(MIN_WIDTH, font.width(label) + PADDING * 2)

    /**
     * The surface under the label, as it will actually be drawn.
     *
     * Translucent for the two quiet variants — they wash whatever they land on, which is what lets one
     * button work on a card and in a popover. Callers measuring contrast must composite this onto the
     * surface it lands on; [Tokens.surfaceHover] over a card and over a popover are different greys.
     */
    fun fill(variant: Variant, hover: Float, press: Float, enabled: Boolean): Int {
        if (!enabled) return 0
        val h = hover.coerceIn(0f, 1f)
        val p = press.coerceIn(0f, 1f)
        return when (variant) {
            // The accent is already the extreme of the ramp, so a hover cannot brighten it — it has
            // nowhere to go. Pressing therefore moves it the only direction available, back toward its
            // own text colour, and hover is carried by the ring in `draw` instead.
            Variant.PRIMARY -> Controls.blend(Tokens.accent, Tokens.accentText, p * PRESS_MIX)
            // `0` and not a fully transparent token: a caller measuring contrast has to be able to
            // tell "no wash" from "a wash at zero", and `roundedFill` would otherwise spend seven
            // draws painting nothing at all under every idle button on the screen.
            Variant.SECONDARY, Variant.GHOST -> when {
                p > 0f -> Tokens.fade(Tokens.surfaceActive, p)
                h > 0f -> Tokens.fade(Tokens.surfaceHover, h)
                else -> 0
            }
        }
    }

    /** The label colour for a state. See [fill] for why this is a function and not a branch in [draw]. */
    fun labelColour(variant: Variant, hover: Float, press: Float, enabled: Boolean): Int {
        if (!enabled) return Tokens.textDisabled
        val lift = maxOf(hover.coerceIn(0f, 1f), press.coerceIn(0f, 1f))
        return when (variant) {
            Variant.PRIMARY -> Tokens.accentText
            Variant.SECONDARY -> Tokens.textPrimary
            // The only variant whose label moves, because it is the only one with no border and no
            // fill at rest: without the lift there would be nothing at all to say it is a control.
            Variant.GHOST -> Controls.blend(Tokens.textSecondary, Tokens.textPrimary, lift)
        }
    }

    /**
     * One button. [hover], [press] and [focus] are already-resolved `0f..1f`, so every state of this
     * is reachable at a frozen frame.
     */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, width: Int, height: Int,
        label: String,
        variant: Variant = Variant.SECONDARY,
        hover: Float = 0f, press: Float = 0f, focus: Float = 0f,
        enabled: Boolean = true,
    ) {
        if (width <= 0 || height <= 0) return
        val radius = Tokens.RADIUS_SM

        val background = fill(variant, hover, press, enabled)
        if (background != 0) Surface.roundedFill(graphics, x, y, width, height, radius, background)

        val border = border(variant, hover, enabled)
        if (enabled) {
            if (border != 0) Surface.roundedBorder(graphics, x, y, width, height, radius, border)
        } else {
            // Dashed, so "you cannot press this" is a pattern rather than a shade — and drawn on every
            // variant including the ghost, which has no border at rest and would otherwise say it was
            // disabled only by being a slightly dimmer word.
            Controls.dashedBorder(graphics, x, y, width, height, border)
        }

        // Hover on a primary has nowhere to go in luminance, so it arrives as a ring just outside the
        // fill. On the quiet variants the ring doubles the border it already has, which reads as the
        // outline thickening — the same gesture at both weights.
        if (enabled && hover > 0f) {
            Surface.roundedBorder(
                graphics, x - 1, y - 1, width + 2, height + 2, radius + 1,
                Tokens.fade(Tokens.borderStrong, hover),
            )
        }

        if (focus > 0f) {
            // Outside the hover ring and in the accent: a focus ring that shares a colour with hover
            // cannot say which of the two a keyboard user is looking at.
            Surface.roundedBorder(
                graphics,
                x - FOCUS_OFFSET, y - FOCUS_OFFSET,
                width + FOCUS_OFFSET * 2, height + FOCUS_OFFSET * 2,
                radius + FOCUS_OFFSET,
                Tokens.fade(Tokens.accent, focus),
            )
        }

        val text = font.plainSubstrByWidth(label, width - PADDING)
        val textX = x + (width - font.width(text)) / 2
        val textY = y + (height - Labels.CAP) / 2 + Math.round(press.coerceIn(0f, 1f) * PRESS_TRAVEL)
        graphics.text(font, text, textX, textY, labelColour(variant, hover, press, enabled), false)
    }

    /** The outline for a state. Disabled is dashed at the draw site; see [draw]. */
    fun border(variant: Variant, hover: Float, enabled: Boolean): Int = when {
        !enabled -> Tokens.borderSubtle
        variant == Variant.PRIMARY || variant == Variant.GHOST -> 0
        else -> Controls.blend(Tokens.borderDefault, Tokens.borderStrong, hover.coerceIn(0f, 1f))
    }

    /**
     * How far a pressed primary travels toward its own text colour.
     *
     * Small on purpose: the press is already said by the label moving, and a fill that darkens far
     * enough to be obvious costs the label its contrast against it.
     */
    private const val PRESS_MIX = 0.14f
}
