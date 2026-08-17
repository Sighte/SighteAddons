package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.theme.Tokens

/**
 * The parts a data table is made of: a sortable header cell, a divider, and the detail row an
 * expanded entry drops open.
 *
 * Primitives and not a table widget. The screen that owns the rows is the only thing that knows what
 * its columns are, how they are measured against a variable width, and which of them disappears when a
 * filter is active — and the existing table already re-derives its layout during hit testing so
 * nothing can disagree with what is on screen about where it is. A widget tree would have to be told
 * all of that and would then be a second place it could be wrong.
 *
 * Row backgrounds are deliberately *not* here: `Controls.rowHighlight` already draws them, for lists
 * that are not tables as well, and a second implementation would be a second answer to what a hovered
 * row looks like.
 */
internal object Table {

    /** A data row's height. */
    const val ROW = 20

    /** The header's height, including the rule under it. */
    const val HEADER = 16

    /** The caret's width, so a right-aligned header can leave room for it. */
    const val CARET = 6

    /**
     * A column header, which is also its own sort button.
     *
     * Three states, none of which is a shade: sorted carries a caret, hovered carries the caret it
     * *would* apply in a quieter tone, and neither carries nothing at all. A header that said "this is
     * the sorted one" by being slightly brighter would be saying it in the one register this palette
     * cannot afford — `textPrimary` to `textTertiary` is legible, but which of six columns is the
     * bright one is not a judgement anybody should have to make.
     *
     * [flip] is `0f` ascending and `1f` descending; halfway through the caret is flat, which is what
     * makes a reversal read as one control turning over rather than as two glyphs swapping.
     */
    fun headerCell(
        graphics: GuiGraphicsExtractor, font: Font,
        label: String, x0: Int, x1: Int, y: Int,
        rightAligned: Boolean,
        sorted: Boolean, flip: Float, hover: Float,
    ) {
        val text = label.uppercase()
        val textWidth = Labels.width(font, text)
        val x = if (rightAligned) x1 - textWidth else x0
        Labels.draw(
            graphics, font, text, x, y,
            if (sorted) Tokens.textPrimary else Controls.blend(Tokens.textTertiary, Tokens.textSecondary, hover),
        )

        if (!sorted && hover <= 0f) return
        val caretX = if (rightAligned) x - Tokens.SPACE_8 else x + textWidth + Tokens.SPACE_4
        caret(
            graphics, caretX, y, flip,
            if (sorted) Tokens.accent else Tokens.fade(Tokens.textTertiary, hover),
        )
    }

    /**
     * The sort direction, as a caret that rotates between up and down.
     *
     * Three cells stepping across, so the whole mark is one pixel thick at every angle — a rotated
     * glyph would be resampled and arrive at a different weight than the hairlines beside it.
     */
    fun caret(graphics: GuiGraphicsExtractor, x: Int, y: Int, flip: Float, argb: Int) {
        val lean = (flip.coerceIn(0f, 1f) - 0.5f) * 2f
        for (step in 0..2) {
            val offset = Math.round((step - 1) * lean)
            val px = x + step * 2
            graphics.fill(px, y + 3 + offset, px + 2, y + 4 + offset, argb)
        }
    }

    /** The hairline under a header, or between two groups of rows. */
    fun divider(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int) {
        DevicePixels.hairlineH(graphics, x, y, width, Tokens.borderSubtle)
    }

    /**
     * One line of an expanded row's detail: an indented label, a body, and a left connector.
     *
     * The connector is the whole reason this is a component rather than two `text` calls. An expanded
     * accordion drops rows that look exactly like the rows around them, so without a rule tying them
     * to the entry above, opening a room appears to insert three unrelated lines into the table. The
     * rule grows with [open], so the detail reads as coming *out of* the row that was clicked.
     *
     * [body] is drawn by the caller when it needs more than text — the sparkline lives in the first
     * detail line — hence [contentX], which is where that starts.
     */
    fun detail(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, height: Int,
        label: String, text: String,
        open: Float = 1f,
    ) {
        val grown = Math.round(height * open.coerceIn(0f, 1f))
        if (grown <= 0) return
        DevicePixels.hairlineV(graphics, x, y, grown, Tokens.borderDefault)

        val textY = y + (height - Labels.CAP) / 2
        graphics.text(font, label, x + INDENT, textY, Tokens.fade(Tokens.textTertiary, open), false)
        graphics.text(font, text, contentX(x), textY, Tokens.fade(Tokens.textSecondary, open), false)
    }

    /** Where a detail line's body starts, so a caller drawing its own can line up with the text ones. */
    fun contentX(x: Int): Int = x + INDENT + LABEL

    /** How far a detail line is indented from its parent row. */
    const val INDENT = Tokens.SPACE_16

    /** The width reserved for a detail line's label. */
    const val LABEL = 44
}
