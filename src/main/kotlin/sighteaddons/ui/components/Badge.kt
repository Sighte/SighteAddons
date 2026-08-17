package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * A pill with a word in it: `PB` on a record, `NEW` on a room the database has never seen.
 *
 * The one thing a badge must not be is a coloured dot. This UI has no hue to spend, so a mark that
 * means "personal best" has to say so — and `Glyphs.chevron` already covers "an improvement", which is
 * a different claim: a chevron says this run beat the last one, a `PB` says it beat every one.
 *
 * The two styles differ by *fill*, not by shade. A solid badge is the strong claim and an outlined one
 * is the weak one, and both survive a reader who cannot separate two greys — which a "brighter grey
 * badge" would not, with `textSecondary` and `textTertiary` 1.27:1 apart.
 */
internal object Badge {

    enum class Style {
        /** Solid accent. The strong claim: earned, current, best. */
        SOLID,

        /** Hairline outline. The weak one: new, pending, informational. */
        OUTLINE,
    }

    const val HEIGHT = 12

    /** Padding either side of the label, inside the pill. */
    const val PADDING = Tokens.SPACE_6

    /**
     * The width [draw] will occupy for [text].
     *
     * Measured through [Labels], because a badge's label is tracked like every other 11px label in
     * this UI — measuring it with `font.width` alone leaves the last letter sitting on the pill's
     * right edge.
     */
    fun width(font: Font, text: String): Int = Labels.width(font, text) + PADDING * 2

    /**
     * One badge. [text] is drawn as given — callers uppercase, for the reason [Labels.draw] states.
     */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, text: String,
        style: Style = Style.SOLID,
        enabled: Boolean = true,
    ) {
        val boxWidth = width(font, text)
        when {
            !enabled -> Surface.roundedBorder(graphics, x, y, boxWidth, HEIGHT, Tokens.RADIUS_FULL, Tokens.borderSubtle)
            style == Style.SOLID -> Surface.roundedFill(graphics, x, y, boxWidth, HEIGHT, Tokens.RADIUS_FULL, Tokens.accent)
            else -> Surface.roundedBorder(graphics, x, y, boxWidth, HEIGHT, Tokens.RADIUS_FULL, Tokens.borderStrong)
        }
        Labels.draw(graphics, font, text, x + PADDING, y + (HEIGHT - Labels.CAP) / 2, labelColour(style, enabled))
    }

    /** The label colour for a style, public so a contrast test can measure it against [fill]. */
    fun labelColour(style: Style, enabled: Boolean): Int = when {
        !enabled -> Tokens.textDisabled
        style == Style.SOLID -> Tokens.accentText
        else -> Tokens.textSecondary
    }

    /** What the label lands on, or `0` when the badge has no fill of its own. */
    fun fill(style: Style, enabled: Boolean): Int =
        if (enabled && style == Style.SOLID) Tokens.accent else 0
}
