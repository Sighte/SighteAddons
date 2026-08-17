package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
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

    /** The full width for [text], derived so hit testing and layout use the number this is drawn at. */
    fun width(font: Font, text: String): Int = ARM * 2 + GAP * 2 + font.width(text)

    /** The decrement arm's left edge. */
    fun minusX(x: Int): Int = x

    /** The increment arm's left edge. */
    fun plusX(x: Int, width: Int): Int = x + width - ARM

    /**
     * Which arm a click at [mouseX] hit: `-1` for decrement, `+1` for increment, `0` for neither.
     *
     * Pure, and the caller's only way to find out — the value between the arms is not a target,
     * because a click that lands on the number would otherwise step it in whichever direction the
     * layout happened to put nearest.
     */
    fun armAt(x: Int, width: Int, mouseX: Int): Int = when {
        mouseX >= x && mouseX < x + ARM -> -1
        mouseX >= plusX(x, width) && mouseX < x + width -> 1
        else -> 0
    }

    /**
     * One stepper. [fraction] is where the value sits in its range, `0f..1f`.
     *
     * The two hovers are separate because the two arms are separate targets, and one shared hover
     * would light the arm that is not about to be pressed.
     */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, width: Int, height: Int,
        text: String, fraction: Float,
        minusHover: Float = 0f, plusHover: Float = 0f,
        enabled: Boolean = true,
    ) {
        if (width <= ARM * 2 || height <= 0) return
        val tone = if (enabled) Tokens.textPrimary else Tokens.textDisabled

        arm(graphics, x, y, height, minus = true, hover = minusHover, tone = tone, enabled = enabled)
        arm(graphics, plusX(x, width), y, height, minus = false, hover = plusHover, tone = tone, enabled = enabled)

        val textY = y + (height - Labels.CAP) / 2
        val valueLeft = x + ARM + GAP
        val valueRoom = width - (ARM + GAP) * 2
        val shown = font.plainSubstrByWidth(text, valueRoom)
        graphics.text(font, shown, valueLeft + (valueRoom - font.width(shown)) / 2, textY, tone, false)

        // The range track. Two device pixels of mark on a hairline, positioned rather than shaded —
        // this is the only part of the control that says how far there is left to go.
        val trackY = y + height - 1
        DevicePixels.hairlineH(graphics, valueLeft, trackY, valueRoom, Tokens.borderSubtle)
        val markX = valueLeft + Math.round(fraction.coerceIn(0f, 1f) * (valueRoom - MARK))
        graphics.fill(
            markX, trackY - 1, markX + MARK, trackY + 1,
            if (enabled) Tokens.accent else Tokens.textDisabled,
        )
    }

    /**
     * One arm: a bordered square with a bar in it, and a second bar crossing for the increment.
     *
     * Rectangles rather than the font's `-` and `+`. The bitmap font's hyphen is two pixels tall and
     * sits at x-height, so a `[-]` and a `[+]` drawn as text are different weights at different
     * heights — the same argument `Glyphs` makes for every other mark in this UI.
     */
    private fun arm(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, height: Int,
        minus: Boolean, hover: Float, tone: Int, enabled: Boolean,
    ) {
        if (enabled && hover > 0f) {
            Surface.roundedFill(
                graphics, x, y, ARM, height, Tokens.RADIUS_XS,
                Tokens.fade(Tokens.surfaceHover, hover),
            )
        }
        if (enabled) {
            Surface.roundedBorder(
                graphics, x, y, ARM, height, Tokens.RADIUS_XS,
                Controls.blend(Tokens.borderDefault, Tokens.borderStrong, hover.coerceIn(0f, 1f)),
            )
        } else {
            // Dashed, like every other disabled control here — an arm that cannot be clicked has to
            // say so with something other than a slightly quieter grey.
            Controls.dashedBorder(graphics, x, y, ARM, height, Tokens.borderSubtle)
        }

        val cx = x + ARM / 2
        val cy = y + height / 2
        graphics.fill(cx - BAR, cy, cx + BAR, cy + 1, tone)
        if (!minus) graphics.fill(cx, cy - BAR, cx + 1, cy + BAR + 1, tone)
    }

    /** Half the length of an arm's bar. */
    private const val BAR = 3

    /** Width of the range mark. */
    private const val MARK = 3
}

/**
 * The other integer control: a track and a knob, for a value whose *shape* matters more than its exact
 * number.
 *
 * The two are not interchangeable and the split is the point. A [Stepper] is for a correction of a few
 * units to a number somebody has a reason to believe is wrong; a slider is for sweeping a value until
 * it looks right, which is exactly what the HUD's scrim opacity is — `HudRoot` already says out loud
 * that *"how much of the dungeon a player wants to see through it is genuinely personal"* while
 * hard-coding 160, because there was no control to put it behind. Ten clicks of a stepper to cross a
 * percentage is not a control anybody uses twice.
 *
 * The slider does **not** print its own value. On a settings row the number already exists in the
 * row's value column, and a slider that prints it again prints it twice.
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
     * One slider. [travel] is the knob's animated position and may overshoot slightly — it comes from
     * a [sighteaddons.ui.motion.Spring], so the knob is clamped to the track rather than the value,
     * the same way `Controls.toggle` clamps its own.
     */
    fun draw(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        travel: Float,
        hover: Float = 0f, active: Boolean = false, enabled: Boolean = true,
    ) {
        if (width <= KNOB || height <= 0) return
        val clamped = travel.coerceIn(0f, 1f)
        val trackY = y + (height - TRACK) / 2
        val filled = Math.round(clamped * (width - KNOB)) + KNOB / 2

        Surface.roundedFill(graphics, x, trackY, width, TRACK, Tokens.RADIUS_FULL, Tokens.surfaceActive)
        Surface.roundedFill(
            graphics, x, trackY, filled, TRACK, Tokens.RADIUS_FULL,
            if (enabled) Tokens.accent else Tokens.textDisabled,
        )

        val knobLeft = knobX(x, width, travel)
        val knobY = y + (height - KNOB) / 2
        if (enabled && hover > 0f) {
            // The halo is the hover, and it is a size change rather than a shade: the knob is already
            // the accent and has nowhere brighter to go.
            Surface.roundedFill(
                graphics, knobLeft - 2, knobY - 2, KNOB + 4, KNOB + 4, Tokens.RADIUS_FULL,
                Tokens.fade(Tokens.surfaceHover, hover),
            )
        }
        Surface.roundedFill(
            graphics, knobLeft, knobY, KNOB, KNOB, Tokens.RADIUS_FULL,
            if (enabled) Tokens.accent else Tokens.textDisabled,
        )
        // Held is a ring inside the knob — the knob cannot get brighter and must not get bigger, or a
        // drag would appear to move the thing being dragged.
        if (active) {
            Surface.roundedBorder(
                graphics, knobLeft + 2, knobY + 2, KNOB - 4, KNOB - 4, Tokens.RADIUS_FULL,
                Tokens.accentText,
            )
        }
        if (!enabled) {
            Surface.roundedBorder(graphics, knobLeft, knobY, KNOB, KNOB, Tokens.RADIUS_FULL, Tokens.borderSubtle)
        }
    }
}
