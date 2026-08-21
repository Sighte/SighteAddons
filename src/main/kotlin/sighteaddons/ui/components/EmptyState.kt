package sighteaddons.ui.components

import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * What a list says when it has nothing in it.
 *
 * Three lines and a drawn mark, in that order: what happened, what to do about it, and — only when
 * there is nothing to do — where the data would come from. `SettingsScreen` established the shape and
 * the rule that goes with it, which is the part worth keeping: the hint must name *which* narrowing
 * escape takes off, because "nothing matches this filter" while a chip is hiding every room and "no
 * history yet" on a fresh install are the same picture and opposite problems.
 *
 * The mark is hairline geometry rather than an illustration or a glyph — three descending rules inside
 * a rounded box, which is the shape of a table with nothing in it. It is there at all because a centred
 * sentence on an otherwise blank panel reads as a page that failed to load.
 */
internal object EmptyState {

    const val BOX_WIDTH = 64
    const val BOX_HEIGHT = 40

    val HEADLINE_SIZE = Tokens.TEXT_12.toFloat()
    val BODY_SIZE = Tokens.TEXT_11.toFloat()

    /** Pitch between the lines under the box. */
    const val LINE = Tokens.SPACE_12

    /** How tall the whole block is, so a caller can centre it in the space it has. */
    fun height(lineHeight: Float, note: String? = null): Float =
        BOX_HEIGHT + Tokens.SPACE_16 + LINE + (if (note == null) lineHeight else LINE + lineHeight)

    /**
     * Draws the block centred in `[x, x + width]`.
     *
     * Every line is truncated to the available width rather than wrapped. A wrapped empty state grows
     * downward as the window narrows and pushes itself out of the panel it is centred in; a truncated
     * one stays where it was put, and the sentences here are written short enough that the case is the
     * exception rather than the layout.
     */
    fun draw(
        x: Float, y: Float, width: Float,
        headline: String, hint: String, note: String? = null,
    ) {
        val boxX = x + (width - BOX_WIDTH) / 2f
        Sk.border(boxX, y, BOX_WIDTH.toFloat(), BOX_HEIGHT.toFloat(), Tokens.borderDefault, Tokens.RADIUS_MD.toFloat())
        for (i in 0..2) {
            Sk.fill(
                boxX + Tokens.SPACE_12, y + Tokens.SPACE_12 + i * Tokens.SPACE_8,
                (BOX_WIDTH - Tokens.SPACE_24 - i * Tokens.SPACE_8).toFloat(), Chrome.HAIRLINE,
                Tokens.borderDefault,
            )
        }

        var cursor = y + BOX_HEIGHT + Tokens.SPACE_16
        // The headline carries the weight, so the two lines under it can sit at a readable tone instead
        // of having to be dim enough to lose the comparison to it.
        centred(headline, x, width, cursor, HEADLINE_SIZE, Tokens.textSecondary, Type.MEDIUM)
        cursor += LINE
        centred(hint, x, width, cursor, BODY_SIZE, Tokens.textTertiary, Type.REGULAR)
        if (note != null) {
            cursor += LINE
            centred(note, x, width, cursor, BODY_SIZE, Tokens.textTertiary, Type.REGULAR)
        }
    }

    private fun centred(
        value: String, x: Float, width: Float, y: Float,
        size: Float, argb: Int, family: String,
    ) {
        Sk.textCenter(Sk.fit(value, width, size, family), x + width / 2f, y, size, argb, family)
    }
}
