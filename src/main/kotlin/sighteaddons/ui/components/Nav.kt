package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * The vertical nav rail: one entry per page, down the left edge.
 *
 * A rail and not a row of tabs because the thing being switched is a whole page — `/sa` has four of
 * them and the stats screen will have more — and vertical entries can carry a longer word without the
 * header running out of room. It is also what the screen already does, drawn by hand; this is that,
 * with the hit zones and the drawing agreeing by construction instead of by two people editing the
 * same numbers.
 */
internal object Nav {

    /** One entry's height. */
    const val ROW = 24

    /** The rail's width. Wide enough for the longest page name plus its indicator. */
    const val WIDTH = 88

    /** Where entry [index] starts, measured from the rail's [top]. */
    fun rowY(top: Int, index: Int): Int = top + index * ROW

    /**
     * Which entry a cursor at [mouseY] is over, or `-1`.
     *
     * Pure, so a screen's hit testing and its drawing cannot disagree about where an entry is — the
     * bug that puts a row's highlight one place and its click somewhere else.
     */
    fun rowAt(top: Int, count: Int, mouseY: Int): Int {
        if (mouseY < top) return -1
        val index = (mouseY - top) / ROW
        return if (index in 0 until count) index else -1
    }

    /**
     * One entry.
     *
     * [select] is the selected indicator's `0f..1f`, which is separate from [hover] on purpose: the
     * indicator is the state and the wash is the feedback, and a selected entry that is also hovered
     * must not double up on either.
     */
    fun item(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, width: Int, height: Int,
        label: String,
        select: Float, hover: Float,
    ) {
        Controls.rowHighlight(graphics, x, y, width, height, hover, selected = false)
        // The indicator is drawn after the highlight's own so its animated length wins: `rowHighlight`
        // grows one from the hover, and this one is what says the page is current even with the cursor
        // somewhere else entirely.
        Controls.indicator(graphics, x, y, height, select, Tokens.accent)
        graphics.text(
            font, label, x + Tokens.SPACE_12, y + (height - Labels.CAP) / 2,
            Controls.blend(Tokens.textTertiary, Tokens.textPrimary, maxOf(select, hover)),
            false,
        )
    }

    /** The hairline that separates the rail from the content beside it. */
    fun divider(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int) {
        DevicePixels.hairlineV(graphics, x, y, height, Tokens.borderSubtle)
    }
}

/**
 * A segmented control: two to four exclusive options with a thumb that slides between them.
 *
 * Not a duplicate of `Controls.chip`, and the line between them is worth stating because getting it
 * wrong is how a UI ends up with two controls that look like each other and mean different things. A
 * chip is a *filter*: there can be eight of them, they carry counts, and they wrap onto a second line.
 * A segmented control is a *switch*: a fixed handful of options, no counts, and the thumb's travel
 * between them is what tells you which way you just moved — a chip set has no travel because nothing
 * moves, it merely lights up somewhere else.
 *
 * Segments are laid out at a uniform width for the same reason. Proportional segments would make the
 * thumb change size as it travels, which reads as the control resizing rather than as a selection
 * moving.
 */
internal object Segmented {

    const val HEIGHT = 20

    /** Padding inside one segment, either side of its label. */
    const val PADDING = Tokens.SPACE_12

    /** The uniform width of one segment, which is the widest label plus padding. */
    fun segmentWidth(font: Font, labels: List<String>): Int {
        var widest = 0
        for (label in labels) widest = maxOf(widest, font.width(label))
        return widest + PADDING * 2
    }

    /** The whole control's width. */
    fun width(font: Font, labels: List<String>): Int = segmentWidth(font, labels) * labels.size

    /** Which segment a cursor at [mouseX] is over, or `-1`. */
    fun indexAt(font: Font, labels: List<String>, x: Int, mouseX: Int): Int {
        if (labels.isEmpty()) return -1
        val each = segmentWidth(font, labels)
        if (mouseX < x || mouseX >= x + each * labels.size) return -1
        return (mouseX - x) / each
    }

    /**
     * One control.
     *
     * [travel] is the thumb's animated position in segment units — `1.5f` is halfway between the
     * second and third — and [active] is the segment that is actually selected, which is what decides
     * the label colours. The two are separate so the thumb can still be in flight while the labels
     * have already swapped: they swap at the crossing, not at the arrival, or a label spends the whole
     * animation unreadable on top of an arriving thumb.
     */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, height: Int,
        labels: List<String>,
        active: Int, travel: Float,
        hover: Int = -1,
        enabled: Boolean = true,
    ) {
        if (labels.isEmpty() || height <= 0) return
        val each = segmentWidth(font, labels)
        val total = each * labels.size

        Surface.roundedFill(graphics, x, y, total, height, Tokens.RADIUS_FULL, Tokens.surfaceActive)
        Surface.roundedBorder(graphics, x, y, total, height, Tokens.RADIUS_FULL, Tokens.borderSubtle)

        if (enabled) {
            val thumbX = x + Math.round(travel.coerceIn(0f, (labels.size - 1).toFloat()) * each)
            Surface.roundedFill(graphics, thumbX + 1, y + 1, each - 2, height - 2, Tokens.RADIUS_FULL, Tokens.accent)
        }

        val textY = y + (height - Labels.CAP) / 2
        for (i in labels.indices) {
            val label = labels[i]
            val selected = enabled && i == active
            val colour = when {
                !enabled -> Tokens.textDisabled
                selected -> Tokens.accentText
                i == hover -> Tokens.textPrimary
                else -> Tokens.textSecondary
            }
            graphics.text(font, label, x + i * each + (each - font.width(label)) / 2, textY, colour, false)
        }
    }
}
