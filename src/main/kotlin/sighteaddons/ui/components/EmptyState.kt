package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * What a list says when it has nothing in it.
 *
 * Three lines and a drawn mark, in that order: what happened, what to do about it, and — only when
 * there is nothing to do — where the data would come from. `SettingsScreen` established the shape and
 * the rule that goes with it, which is the part worth keeping: the hint must name *which* narrowing
 * escape takes off, because "nothing matches this filter" while a chip is hiding every room and
 * "no history yet" on a fresh install are the same picture and opposite problems.
 *
 * The mark is hairline geometry rather than an illustration or a glyph — three descending rules inside
 * a rounded box, which is the shape of a table with nothing in it. It is drawn rather than typed for
 * the reason `Glyphs` gives for every other mark here, and it is there at all because a centred
 * sentence on an otherwise blank panel reads as a page that failed to load.
 */
internal object EmptyState {

    const val BOX_WIDTH = 64
    const val BOX_HEIGHT = 40

    /** How tall the whole block is, so a caller can centre it in the space it has. */
    fun height(note: String? = null): Int =
        BOX_HEIGHT + Tokens.SPACE_16 + Tokens.SPACE_12 + (if (note == null) Labels.CAP else Tokens.SPACE_12 + Labels.CAP)

    /**
     * Draws the block centred in `[x, x + width]`.
     *
     * Every line is truncated to the available width rather than wrapped. A wrapped empty state grows
     * downward as the window narrows and pushes itself out of the panel it is centred in; a truncated
     * one stays where it was put, and the sentences here are written short enough that the case is the
     * exception rather than the layout.
     */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, width: Int,
        headline: String, hint: String, note: String? = null,
    ) {
        val boxX = x + (width - BOX_WIDTH) / 2
        Surface.roundedBorder(graphics, boxX, y, BOX_WIDTH, BOX_HEIGHT, Tokens.RADIUS_MD, Tokens.borderDefault)
        for (i in 0..2) {
            DevicePixels.hairlineH(
                graphics,
                boxX + Tokens.SPACE_12, y + Tokens.SPACE_12 + i * Tokens.SPACE_8,
                BOX_WIDTH - Tokens.SPACE_24 - i * Tokens.SPACE_8,
                Tokens.borderDefault,
            )
        }

        var cursor = y + BOX_HEIGHT + Tokens.SPACE_16
        centred(graphics, font, headline, x, width, cursor, Tokens.textSecondary)
        cursor += Tokens.SPACE_12
        centred(graphics, font, hint, x, width, cursor, Tokens.textTertiary)
        if (note != null) {
            cursor += Tokens.SPACE_12
            centred(graphics, font, note, x, width, cursor, Tokens.textTertiary)
        }
    }

    private fun centred(
        graphics: GuiGraphicsExtractor, font: Font,
        value: String, x: Int, width: Int, y: Int, argb: Int,
    ) {
        val shown = font.plainSubstrByWidth(value, width)
        graphics.text(font, shown, x + (width - font.width(shown)) / 2, y, argb, false)
    }
}
