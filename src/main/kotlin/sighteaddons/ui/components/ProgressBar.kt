package sighteaddons.ui.components

import sighteaddons.ui.sk.Sk
import sighteaddons.ui.theme.Tokens

/**
 * A continuous progress bar, for the totals a segmented indicator cannot count.
 *
 * The split between the two is a number: segments up to twelve, a bar above it. That is not a
 * preference — segments exist so four of six is readable *without reading the number*, and past a
 * dozen blocks they are too narrow to count, at which point the countable version is the worse one. A
 * run's secrets across forty rooms is the case that needs this one.
 *
 * The bar's own weakness is real: 66 % and 70 % look identical. That is why a bar in this UI is never
 * the only place its number appears, and why this component draws no text of its own — the number
 * belongs to the row, which already has somewhere to put it.
 */
internal object ProgressBar {

    const val HEIGHT = 4

    /**
     * How much of [width] is filled at [fraction].
     *
     * Never zero for a non-zero fraction, and never the full width for anything short of one. Both
     * ends are the same bug in opposite directions: a bar that reads empty when something has already
     * started, and one that reads finished when it has not.
     *
     * Still integers, and still rounding, even though the renderer would now accept a float. The two
     * clamps at the ends are the entire point of this function, and they are statements about whole
     * pixels: "at least one pixel lit" and "at least one pixel dark" have no float equivalent that a
     * test can pin down.
     */
    fun fillWidth(width: Int, fraction: Float): Int {
        if (width <= 0) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0f) return 0
        if (clamped >= 1f) return width
        return Math.round(clamped * width).coerceIn(1, width - 1)
    }

    fun draw(
        x: Float, y: Float, width: Float, height: Float,
        fraction: Float,
        on: Int = Tokens.accent,
        off: Int = Tokens.borderDefault,
    ) {
        if (width <= 0f || height <= 0f) return
        val radius = height / 2f
        Sk.fill(x, y, width, height, off, radius)
        val filled = fillWidth(Math.round(width), fraction)
        if (filled > 0) Sk.fill(x, y, filled.toFloat(), height, on, radius)
    }
}
