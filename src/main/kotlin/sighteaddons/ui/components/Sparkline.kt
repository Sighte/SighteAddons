package sighteaddons.ui.components

import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.RoomHistory
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Sheet
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens

/**
 * A room's progression as a one-pixel stroke over a fading area fill.
 *
 * Replaces the bar-per-attempt the old table drew. Bars answered "how did each attempt go"; a line
 * answers "is this getting better", which is the question somebody opens their own history to ask.
 *
 * **Down is better.** Time is on the vertical axis and less of it is the good outcome, so a line that
 * falls to the right is improvement. That is the opposite of the usual chart convention and it is the
 * right way round here — inverting it would put "faster" at the top and make every personal best a
 * downward spike, which reads as a failure at a glance.
 *
 * Personal bests are marked with a filled dot; everything else gets a hollow one only if it is the
 * newest attempt. A dot on every point turns a twenty-attempt line into beads.
 */
internal object Sparkline {

    /** Beyond this the line is denser than the pixels available and each attempt stops being visible. */
    const val MAX_POINTS = 32

    /**
     * Draws [attempts] into ([x], [y], [width], [height]).
     *
     * [cap] is the slowest time the chart will show; anything above is clamped to the top. The caller
     * passes twice the median, so one catastrophic wipe cannot flatten every other attempt into a
     * single line at the bottom — the same reasoning the old bar chart used.
     */
    fun draw(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        attempts: List<RoomHistory.Attempt>,
        cap: Int,
    ) {
        if (attempts.isEmpty() || width <= 2 || height <= 2 || cap <= 0) return

        val count = minOf(attempts.size, MAX_POINTS)
        val first = attempts.size - count
        if (count == 1) {
            // One attempt is a point, not a line. Drawing a flat stroke across the whole width would
            // claim a trend that a single measurement cannot support.
            val only = attempts[first]
            val py = valueY(only.ticks, cap, y, height)
            dot(graphics, x + width / 2, py, filled = only.pb)
            return
        }

        val step = (width - 1).toFloat() / (count - 1)

        // The area fill first, as a column per point under the stroke. The ramp is vertical, so each
        // column is one blit of the sheet's gradient rather than a stack of fills.
        for (i in 0 until count) {
            val px = x + Math.round(i * step)
            val py = valueY(attempts[first + i].ticks, cap, y, height)
            val columnWidth = if (i == count - 1) 1 else Math.round(step).coerceAtLeast(1)
            val depth = y + height - py
            if (depth > 0) {
                Sheet.rampH(
                    graphics, px, py, columnWidth, depth,
                    Tokens.alpha(Tokens.accent, AREA_ALPHA),
                )
            }
        }

        // Then the stroke, in device pixels so it stays one physical pixel at every GUI scale.
        val scoped = DevicePixels.push(graphics)
        for (i in 0 until count - 1) {
            val ax = x + Math.round(i * step)
            val bx = x + Math.round((i + 1) * step)
            val ay = valueY(attempts[first + i].ticks, cap, y, height)
            val by = valueY(attempts[first + i + 1].ticks, cap, y, height)
            segment(graphics, ax, ay, bx, by, scoped)
        }
        if (scoped) DevicePixels.pop(graphics)

        // Marks last, over the stroke.
        for (i in 0 until count) {
            val attempt = attempts[first + i]
            if (!attempt.pb && i != count - 1) continue
            val px = x + Math.round(i * step)
            dot(graphics, px, valueY(attempt.ticks, cap, y, height), filled = attempt.pb)
        }
    }

    /** Faster is lower on screen; see the class comment. */
    private fun valueY(ticks: Int, cap: Int, y: Int, height: Int): Int {
        val clamped = ticks.coerceIn(0, cap)
        return y + height - 1 - Math.round((1f - clamped.toFloat() / cap) * (height - 1))
    }

    /**
     * One segment, drawn as a staircase of vertical runs.
     *
     * A real line-drawing algorithm would be better and is not worth it here: the horizontal step is
     * a handful of pixels, so the difference between a Bresenham line and a vertical run per column is
     * invisible, and this way the whole line is a handful of fills in one device-pixel scope.
     */
    private fun segment(graphics: GuiGraphicsExtractor, ax: Int, ay: Int, bx: Int, by: Int, scoped: Boolean) {
        val span = (bx - ax).coerceAtLeast(1)
        for (i in 0 until span) {
            val t = i.toFloat() / span
            val from = ay + Math.round((by - ay) * t)
            val to = ay + Math.round((by - ay) * ((i + 1).toFloat() / span))
            val top = minOf(from, to)
            val bottom = maxOf(from, to) + 1
            val px = ax + i
            if (!scoped) {
                graphics.fill(px, top, px + 1, bottom, Tokens.accent)
                continue
            }
            val dx = Density.deviceX(px.toFloat())
            graphics.fill(
                dx, Density.deviceY(top.toFloat()),
                dx + Density.hairline, Density.deviceY(bottom.toFloat()),
                Tokens.accent,
            )
        }
    }

    private fun dot(graphics: GuiGraphicsExtractor, cx: Int, cy: Int, filled: Boolean) {
        if (filled) {
            graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, Tokens.accent)
        } else {
            graphics.fill(cx - 1, cy - 1, cx + 2, cy, Tokens.textTertiary)
            graphics.fill(cx - 1, cy + 1, cx + 2, cy + 2, Tokens.textTertiary)
            graphics.fill(cx - 1, cy, cx, cy + 1, Tokens.textTertiary)
            graphics.fill(cx + 1, cy, cx + 2, cy + 1, Tokens.textTertiary)
        }
    }

    private const val AREA_ALPHA = 20
}
