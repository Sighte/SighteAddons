package sighteaddons.ui.components

import sighteaddons.RoomHistory
import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.theme.Tokens

/**
 * A room's progression as a stroke over a fading area fill.
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
 *
 * ### This is the component the new renderer changed most
 *
 * The old one drew the stroke as a staircase of vertical runs, one fill per horizontal pixel, inside a
 * device-pixel scope — with a comment conceding that a real line algorithm "would be better and is not
 * worth it here". It is now simply a line. The area fill was a blit per column from a generated
 * gradient sprite; it is now a gradient per column with no sprite behind it. Both were faithful
 * workarounds for having only `fill()`, and both are gone rather than ported.
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
        x: Float, y: Float, width: Float, height: Float,
        attempts: List<RoomHistory.Attempt>,
        cap: Int,
    ) {
        if (attempts.isEmpty() || width <= 2f || height <= 2f || cap <= 0) return

        val count = minOf(attempts.size, MAX_POINTS)
        val first = attempts.size - count
        if (count == 1) {
            // One attempt is a point, not a line. Drawing a flat stroke across the whole width would
            // claim a trend that a single measurement cannot support.
            val only = attempts[first]
            dot(x + width / 2f, valueY(only.ticks, cap, y, height), only.pb)
            return
        }

        val step = (width - 1f) / (count - 1)
        val bottom = y + height
        val area = Tokens.alpha(Tokens.accentSoft, AREA_ALPHA)
        val clear = Tokens.alpha(Tokens.accentSoft, 0)

        // The area fill first, a column per point. Vertical gradient from the stroke down to nothing,
        // so the fill reads as belonging to the line rather than as a bar chart behind it.
        for (i in 0 until count) {
            val px = x + i * step
            val py = valueY(attempts[first + i].ticks, cap, y, height)
            val columnWidth = if (i == count - 1) 1f else step.coerceAtLeast(1f)
            val depth = bottom - py
            if (depth > 0f) Sk.gradient(px, py, columnWidth, depth, area, clear, vertical = true)
        }

        // Then the stroke. One anti-aliased, round-capped line per segment — which is also what makes
        // the joins between segments read as a continuous line rather than as a chain of dashes.
        for (i in 0 until count - 1) {
            Sk.line(
                x + i * step, valueY(attempts[first + i].ticks, cap, y, height),
                x + (i + 1) * step, valueY(attempts[first + i + 1].ticks, cap, y, height),
                Tokens.accentSoft, Chrome.HAIRLINE,
            )
        }

        // Marks last, over the stroke.
        for (i in 0 until count) {
            val attempt = attempts[first + i]
            if (!attempt.pb && i != count - 1) continue
            dot(x + i * step, valueY(attempt.ticks, cap, y, height), attempt.pb)
        }
    }

    /** Faster is lower on screen; see the class comment. */
    private fun valueY(ticks: Int, cap: Int, y: Float, height: Float): Float {
        val clamped = ticks.coerceIn(0, cap)
        return y + height - 1f - (1f - clamped.toFloat() / cap) * (height - 1f)
    }

    /**
     * A point mark: filled for a personal best, hollow for the newest attempt.
     *
     * Hollow is now a real ring rather than four fills arranged around a gap, which is the difference
     * between a circle and a square with its middle missing — and at three pixels across, that
     * difference is most of what says "this is a point on a line".
     */
    private fun dot(cx: Float, cy: Float, filled: Boolean) {
        if (filled) {
            Sk.circle(cx, cy, DOT, Tokens.accentSoft)
        } else {
            Sk.circle(cx, cy, DOT, Tokens.surfaceBase)
            Sk.border(cx - DOT, cy - DOT, DOT * 2f, DOT * 2f, Tokens.textTertiary, DOT)
        }
    }

    private const val DOT = 1.75f

    private const val AREA_ALPHA = 40
}
