package sighteaddons.ui.components

import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
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

    /** The label's size. Small, and the smallest thing on the screen that is still all capitals. */
    val SIZE = Tokens.TEXT_10.toFloat()

    /**
     * The width [draw] will occupy for [text].
     *
     * It used to measure through `Labels`, because a badge's label was tracked like every other small
     * label here and `font.width` alone left the last letter on the pill's right edge. There is no
     * tracking any more — the bundled face is monospaced and does not need it — so this is a plain
     * measurement again, and the failure it used to guard against cannot recur.
     */
    fun width(text: String, measure: (String) -> Float): Float = measure(text) + PADDING * 2

    /** One badge. [text] is drawn as given — callers uppercase, so a mixed-case badge stays possible. */
    fun draw(x: Float, y: Float, text: String, style: Style = Style.SOLID, enabled: Boolean = true) {
        val boxWidth = width(text) { Sk.width(it, SIZE, Type.MEDIUM) }
        val radius = HEIGHT / 2f
        when {
            !enabled -> Sk.border(x, y, boxWidth, HEIGHT.toFloat(), Tokens.borderSubtle, radius)
            style == Style.SOLID -> Sk.fill(x, y, boxWidth, HEIGHT.toFloat(), Tokens.accent, radius)
            else -> Sk.border(x, y, boxWidth, HEIGHT.toFloat(), Tokens.borderStrong, radius)
        }
        // Medium, always. A badge is three or four capitals at ten pixels sitting on a fill that may be
        // solid accent — the one place on this screen where the weight is not a nicety but what keeps
        // the letters from dissolving into their own background.
        Sk.text(
            text, x + PADDING, Sk.centreY(y, HEIGHT.toFloat(), SIZE, Type.MEDIUM), SIZE,
            labelColour(style, enabled), Type.MEDIUM,
        )
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
