package sighteaddons.ui.components

import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * The integer control for a value whose exact number matters: `[−] 138 ticks · 6.90s [+]`.
 *
 * This is what `Config.stormCountdownTicks` and `Config.stormShootTicks` are, and the reasoning is
 * already written down in `StormTimer.step`: both numbers are inherited from a decompiled mod that
 * explains neither, a wrong one is invisible while it looks exactly like a working timer, and the
 * plausible correction is *a handful of ticks*. A slider cannot express that — one pixel of a 400-tick
 * range is four ticks, so the correction being looked for is smaller than the control's resolution.
 *
 * What the stepper cannot show is where 138 sits in `1..400`, and a number with no range around it is
 * a number nobody can judge. Hence the track under the value: a hairline with a mark on it, so the
 * position of the mark says "near the middle" without anybody reading either bound.
 */
internal object Stepper {

    const val HEIGHT = 16

    /** Width of one arm. Square, so the two arms and the value read as one control. */
    const val ARM = 14

    /** Gap between an arm and the value. */
    const val GAP = Tokens.SPACE_8

    /** The value's text size. */
    val SIZE = Tokens.TEXT_11.toFloat()

    /** The full width for [text], derived so hit testing and layout use the number this is drawn at. */
    fun width(text: String, measure: (String) -> Float): Int =
        ARM * 2 + GAP * 2 + Math.ceil(measure(text).toDouble()).toInt()

    /** The decrement arm's left edge. */
    fun minusX(x: Int): Int = x

    /** The increment arm's left edge. */
    fun plusX(x: Int, width: Int): Int = x + width - ARM

    /**
     * Which arm a click at [mouseX] hit: `-1` for decrement, `+1` for increment, `0` for neither.
     *
     * Pure, and the caller's only way to find out — the value between the arms is not a target, because
     * a click that lands on the number would otherwise step it in whichever direction the layout
     * happened to put nearest.
     */
    fun armAt(x: Int, width: Int, mouseX: Int): Int = when {
        mouseX >= x && mouseX < x + ARM -> -1
        mouseX >= plusX(x, width) && mouseX < x + width -> 1
        else -> 0
    }

    /**
     * One stepper. [fraction] is where the value sits in its range, `0f..1f`.
     *
     * The two hovers are separate because the two arms are separate targets, and one shared hover would
     * light the arm that is not about to be pressed.
     */
    fun draw(
        x: Float, y: Float, width: Float, height: Float,
        text: String, fraction: Float,
        minusHover: Float = 0f, plusHover: Float = 0f,
        enabled: Boolean = true,
    ) {
        if (width <= ARM * 2 || height <= 0f) return
        val tone = if (enabled) Tokens.textPrimary else Tokens.textDisabled

        arm(x, y, height, minus = true, hover = minusHover, tone = tone, enabled = enabled)
        arm(x + width - ARM, y, height, minus = false, hover = plusHover, tone = tone, enabled = enabled)

        val valueLeft = x + ARM + GAP
        val valueRoom = width - (ARM + GAP) * 2
        // Medium on the value. It is the only number in the control and the whole reason the control
        // exists, and the arms beside it are geometry rather than type — so nothing competes with it.
        val shown = Sk.fit(text, valueRoom, SIZE, Type.MEDIUM)
        Sk.textCenter(
            shown, valueLeft + valueRoom / 2f,
            Sk.centreY(y, height - MARK_LANE, SIZE, Type.MEDIUM), SIZE, tone, Type.MEDIUM,
        )

        // The range track. A mark positioned rather than shaded — this is the only part of the control
        // that says how far there is left to go.
        val trackY = y + height - Chrome.HAIRLINE
        Sk.fill(valueLeft, trackY, valueRoom, Chrome.HAIRLINE, Tokens.borderSubtle)
        val markX = valueLeft + fraction.coerceIn(0f, 1f) * (valueRoom - MARK)
        Sk.fill(
            markX, trackY - 1f, MARK, MARK_LANE,
            if (enabled) Tokens.accent else Tokens.textDisabled, MARK / 2f,
        )
    }

    /**
     * One arm: a bordered square with a bar in it, and a second bar crossing for the increment.
     *
     * Rectangles rather than the font's `-` and `+`, and the reason outlived the bitmap font that
     * prompted it. JetBrains Mono's hyphen and plus are properly aligned, but they are also *glyphs* —
     * they scale with the type and carry its weight, while these two arms want to be the same mark at
     * every size the control is drawn at.
     */
    private fun arm(x: Float, y: Float, height: Float, minus: Boolean, hover: Float, tone: Int, enabled: Boolean) {
        val radius = Tokens.RADIUS_XS.toFloat()
        if (enabled && hover > 0f) {
            Sk.fill(x, y, ARM.toFloat(), height, Tokens.fade(Tokens.surfaceHover, hover), radius)
        }
        if (enabled) {
            Sk.border(
                x, y, ARM.toFloat(), height,
                Controls.blend(Tokens.borderDefault, Tokens.borderStrong, hover.coerceIn(0f, 1f)), radius,
            )
        } else {
            // Dashed, like every other disabled control here — an arm that cannot be clicked has to say
            // so with something other than a slightly quieter grey.
            Controls.dashedBorder(x, y, ARM.toFloat(), height, Tokens.borderSubtle)
        }

        val cx = x + ARM / 2f
        val cy = y + height / 2f
        Sk.fill(cx - BAR, cy - Chrome.HAIRLINE / 2f, BAR * 2f, Chrome.HAIRLINE, tone)
        if (!minus) Sk.fill(cx - Chrome.HAIRLINE / 2f, cy - BAR, Chrome.HAIRLINE, BAR * 2f, tone)
    }

    /** Half the length of an arm's bar. */
    private const val BAR = 3f

    /** Width of the range mark. */
    private const val MARK = 3f

    /** The strip at the bottom the track and its mark own, kept clear of the value's descenders. */
    private const val MARK_LANE = 3f
}

/**
 * The other integer control: a track and a knob, for a value whose *shape* matters more than its exact
 * number.
 *
 * The two are not interchangeable and the split is the point. A [Stepper] is for a correction of a few
 * units to a number somebody has a reason to believe is wrong; a slider is for sweeping a value until
 * it looks right, which is exactly what the HUD's scrim opacity is. Ten clicks of a stepper to cross a
 * percentage is not a control anybody uses twice.
 *
 * The slider does **not** print its own value. On a settings row the number already exists in the row's
 * value column, and a slider that prints it again prints it twice.
 */
internal object Slider {

    const val HEIGHT = 16

    /** The knob. Round, and large enough to be a target rather than a marker. */
    const val KNOB = 10

    /** Track thickness. Thin, because the knob is the control and the track is the context. */
    const val TRACK = 2

    /** Where the knob's left edge sits for [travel] along a [width]-wide slider. */
    fun knobX(x: Int, width: Int, travel: Float): Int =
        x + Math.round(travel.coerceIn(0f, 1f) * (width - KNOB))

    /**
     * The fraction a cursor at [mouseX] is asking for.
     *
     * Measured against the *knob's* travel, not the whole width: the knob is grabbed by its middle, so
     * a cursor at the far right edge must resolve to 1.0 rather than to "one knob-width short of the
     * end", which is the difference between a slider that reaches its maximum and one that does not.
     */
    fun fractionAt(x: Int, width: Int, mouseX: Int): Float {
        val travel = width - KNOB
        if (travel <= 0) return 0f
        return ((mouseX - x - KNOB / 2).toFloat() / travel).coerceIn(0f, 1f)
    }

    /** The integer [fraction] selects in `[min, max]`. Both ends are reachable. */
    fun valueAt(min: Int, max: Int, fraction: Float): Int {
        if (max <= min) return min
        return min + Math.round(fraction.coerceIn(0f, 1f) * (max - min))
    }

    /** Where [value] sits in `[min, max]`, for driving [draw]. */
    fun fractionOf(value: Int, min: Int, max: Int): Float {
        if (max <= min) return 0f
        return ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    /**
     * One slider. [travel] is the knob's animated position and may overshoot slightly — it comes from a
     * [sighteaddons.ui.motion.Spring], so the knob is clamped to the track rather than the value, the
     * same way [Controls.toggle] clamps its own.
     */
    fun draw(
        x: Float, y: Float, width: Float, height: Float,
        travel: Float,
        hover: Float = 0f, active: Boolean = false, enabled: Boolean = true,
    ) {
        if (width <= KNOB || height <= 0f) return
        val clamped = travel.coerceIn(0f, 1f)
        val trackY = y + (height - TRACK) / 2f
        val knobSpan = width - KNOB
        val filled = clamped * knobSpan + KNOB / 2f

        Sk.fill(x, trackY, width, TRACK.toFloat(), Tokens.surfaceActive, TRACK / 2f)
        Sk.fill(
            x, trackY, filled, TRACK.toFloat(),
            if (enabled) Tokens.accent else Tokens.textDisabled, TRACK / 2f,
        )

        // Sub-pixel, unlike the integer `knobX` the hit test uses. The knob is grabbed by its middle
        // and released to a value, so where it *rests* has to agree with the maths; where it is during
        // the spring's flight only has to be smooth, and rounding that to whole pixels is what made the
        // overshoot invisible before.
        val knobLeft = x + travel.coerceIn(-0.08f, 1.08f) * knobSpan
        val knobY = y + (height - KNOB) / 2f
        val centreX = knobLeft + KNOB / 2f
        val centreY = knobY + KNOB / 2f

        if (enabled && hover > 0f) {
            // The halo is the hover, and it is a size change rather than a shade: the knob is already
            // the accent and has nowhere brighter to go.
            Sk.circle(centreX, centreY, KNOB / 2f + 2f, Tokens.fade(Tokens.surfaceHover, hover))
        }
        Sk.circle(centreX, centreY, KNOB / 2f, if (enabled) Tokens.accent else Tokens.textDisabled)
        // Held is a ring inside the knob — the knob cannot get brighter and must not get bigger, or a
        // drag would appear to move the thing being dragged.
        if (active) {
            Sk.border(
                knobLeft + 2f, knobY + 2f, KNOB - 4f, KNOB - 4f,
                Tokens.accentText, (KNOB - 4f) / 2f,
            )
        }
        if (!enabled) {
            Sk.border(knobLeft, knobY, KNOB.toFloat(), KNOB.toFloat(), Tokens.borderSubtle, KNOB / 2f)
        }
    }
}
