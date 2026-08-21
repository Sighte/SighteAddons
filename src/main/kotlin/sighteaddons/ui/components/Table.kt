package sighteaddons.ui.components

import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * The parts a data table is made of: a sortable header cell, a divider, and the detail row an expanded
 * entry drops open.
 *
 * Primitives and not a table widget. The screen that owns the rows is the only thing that knows what
 * its columns are, how they are measured against a variable width, and which of them disappears when a
 * filter is active — and the existing table already re-derives its layout during hit testing so nothing
 * can disagree with what is on screen about where it is. A widget tree would have to be told all of
 * that and would then be a second place it could be wrong.
 *
 * Row backgrounds are deliberately *not* here: [Controls.rowHighlight] already draws them, for lists
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

    /** A header label's size. */
    val HEADER_SIZE = Tokens.TEXT_10.toFloat()

    /** A data cell's size. */
    val CELL_SIZE = Tokens.TEXT_12.toFloat()

    /**
     * A column header, which is also its own sort button.
     *
     * Three states, none of which is a shade: sorted carries a caret, hovered carries the caret it
     * *would* apply in a quieter tone, and neither carries nothing at all. A header that said "this is
     * the sorted one" by being slightly brighter would be saying it in the one register this palette
     * cannot afford — `textPrimary` to `textTertiary` is legible, but which of six columns is the bright
     * one is not a judgement anybody should have to make.
     *
     * [flip] is `0f` ascending and `1f` descending; halfway through the caret is flat, which is what
     * makes a reversal read as one control turning over rather than as two glyphs swapping.
     */
    fun headerCell(
        label: String, x0: Float, x1: Float, y: Float,
        rightAligned: Boolean,
        sorted: Boolean, flip: Float, hover: Float,
    ) {
        val text = label.uppercase()
        // Medium once sorted, which is the second axis this palette gained. Before, "sorted" had only
        // the caret to say it with, because brightness was already spent separating a header from a
        // value.
        val family = if (sorted) Type.MEDIUM else Type.REGULAR
        val textWidth = Sk.width(text, HEADER_SIZE, family)
        val x = if (rightAligned) x1 - textWidth else x0
        Sk.text(
            text, x, y, HEADER_SIZE,
            if (sorted) Tokens.textPrimary else Controls.blend(Tokens.textTertiary, Tokens.textSecondary, hover),
            family,
        )

        if (!sorted && hover <= 0f) return
        val caretX = if (rightAligned) x - Tokens.SPACE_8 else x + textWidth + Tokens.SPACE_4
        caret(
            caretX, y, flip,
            if (sorted) Tokens.accent else Tokens.fade(Tokens.textTertiary, hover),
        )
    }

    /**
     * The sort direction, as a caret that rotates between up and down.
     *
     * Two anti-aliased strokes from a shared apex, which is what it always wanted to be: the old
     * version stepped three one-pixel cells across because a rotated glyph would have been resampled
     * to a different weight than the hairlines beside it. A real stroke has no such problem, and the
     * mark now actually rotates rather than approximating rotation in three stops.
     */
    fun caret(x: Float, y: Float, flip: Float, argb: Int) {
        val lean = (flip.coerceIn(0f, 1f) - 0.5f) * 2f
        val midY = y + HEADER_SIZE / 2f
        val rise = ARM_RISE * lean
        Sk.line(x, midY - rise, x + CARET / 2f, midY + rise, argb, Chrome.HAIRLINE)
        Sk.line(x + CARET / 2f, midY + rise, x + CARET, midY - rise, argb, Chrome.HAIRLINE)
    }

    /** How far the caret's apex travels either side of centre. */
    private const val ARM_RISE = 1.5f

    /** The hairline under a header, or between two groups of rows. */
    fun divider(x: Float, y: Float, width: Float) {
        Sk.fill(x, y, width, Chrome.HAIRLINE, Tokens.borderSubtle)
    }

    /**
     * One line of an expanded row's detail: an indented label, a body, and a left connector.
     *
     * The connector is the whole reason this is a component rather than two text calls. An expanded
     * accordion drops rows that look exactly like the rows around them, so without a rule tying them to
     * the entry above, opening a room appears to insert three unrelated lines into the table. The rule
     * grows with [open], so the detail reads as coming *out of* the row that was clicked.
     *
     * The body is drawn by the caller when it needs more than text — the sparkline lives in the first
     * detail line — hence [contentX], which is where that starts.
     */
    fun detail(
        x: Float, y: Float, height: Float,
        label: String, text: String,
        open: Float = 1f,
    ) {
        val grown = height * open.coerceIn(0f, 1f)
        if (grown <= 0f) return
        Sk.fill(x, y, Chrome.HAIRLINE, grown, Tokens.borderDefault)

        val size = Tokens.TEXT_11.toFloat()
        val textY = Sk.centreY(y, height, size)
        Sk.text(label, x + INDENT, textY, size, Tokens.fade(Tokens.textTertiary, open))
        Sk.text(text, contentX(x), textY, size, Tokens.fade(Tokens.textSecondary, open))
    }

    /** Where a detail line's body starts, so a caller drawing its own can line up with the text ones. */
    fun contentX(x: Float): Float = x + INDENT + LABEL

    /** The same, for the integer layout arithmetic in `RecordColumns`. */
    fun contentX(x: Int): Int = x + INDENT + LABEL

    /** How far a detail line is indented from its parent row. */
    const val INDENT = Tokens.SPACE_16

    /** The width reserved for a detail line's label. */
    const val LABEL = 44
}
