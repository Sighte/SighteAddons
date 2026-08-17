package sighteaddons.ui.components

import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * A continuous progress bar, for the totals `Surface.segments` cannot count.
 *
 * The split between the two is a number: segments up to twelve, a bar above it. That is not a
 * preference — segments exist so four of six is readable *without reading the number*, and past a
 * dozen blocks they are too narrow to count, at which point the countable version is the worse one. A
 * run's secrets across forty rooms is the case that needs this one.
 *
 * The bar's own weakness is stated in `Surface.segments` and is real: 66 % and 70 % look identical.
 * That is why a bar in this UI is never the only place its number appears, and why this component
 * draws no text of its own — the number belongs to the row, which already has somewhere to put it.
 */
internal object ProgressBar {

    const val HEIGHT = 4

    /**
     * How much of [width] is filled at [fraction].
     *
     * Never zero for a non-zero fraction, and never the full width for anything short of one. Both
     * ends are the same bug in opposite directions: a bar that reads empty when something has already
     * started, and one that reads finished when it has not.
     */
    fun fillWidth(width: Int, fraction: Float): Int {
        if (width <= 0) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0f) return 0
        if (clamped >= 1f) return width
        return Math.round(clamped * width).coerceIn(1, width - 1)
    }

    fun draw(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        fraction: Float,
        on: Int = Tokens.accent,
        off: Int = Tokens.borderDefault,
    ) {
        if (width <= 0 || height <= 0) return
        Surface.roundedFill(graphics, x, y, width, height, Tokens.RADIUS_FULL, off)
        val filled = fillWidth(width, fraction)
        if (filled > 0) Surface.roundedFill(graphics, x, y, filled, height, Tokens.RADIUS_FULL, on)
    }
}
