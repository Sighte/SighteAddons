package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.motion.Animatable
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.motion.Spring
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * Per-element animation state, keyed by whatever the caller uses to identify the element.
 *
 * Components here are stateless — they take a value and draw it — but a hover fade needs somewhere to
 * live between frames, and the screen that owns the elements is the only thing that knows how many
 * there are. Handing that state back through this store keeps components free of identity while
 * letting a row remember it was being hovered.
 *
 * Not for the HUD. A `HashMap` lookup and a lazily created `Animatable` per element per frame is
 * nothing on a screen and is exactly the wrong shape for a render path that must not allocate.
 */
internal class Anim {
    private val tweens = HashMap<String, Animatable>()
    private val springs = HashMap<String, Spring>()

    fun of(key: String, initial: Float = 0f): Animatable =
        tweens.getOrPut(key) { Animatable(initial) }

    fun spring(key: String, initial: Float = 0f): Spring =
        springs.getOrPut(key) { Spring(initial) }
}

/**
 * The controls the settings screen is built from.
 *
 * All of them are `(graphics, geometry, value)` in and pixels out, with any animation passed in as an
 * already-resolved `0f..1f`. That keeps every one of them checkable at a single frozen frame, which is
 * the only way to review an animation without being able to drive the game.
 */
internal object Controls {

    const val TOGGLE_WIDTH = 36
    const val TOGGLE_HEIGHT = 20

    /** The switch at [height], keeping the specified 36:20 proportion. */
    fun toggleWidth(height: Int): Int = Math.round(height * TOGGLE_WIDTH / TOGGLE_HEIGHT.toFloat())

    /**
     * A pill switch. The specified size is `36x20` with a `16px` knob, and [height] scales that whole.
     *
     * Scalable rather than fixed because GUI scale 4 leaves a 1080p screen 270 pixels tall, and a
     * settings list of ten fixed 20px switches does not fit in it. A control that runs off the bottom
     * of the screen at a scale the game offers is not a control.
     *
     * [travel] is the spring's `0f..1f`, which overshoots slightly past either end — so the *knob* is
     * clamped to the track, not the value, or the overshoot pushes it out through the side.
     *
     * On, the track is solid accent and the knob is the accent's opposite; off, the track is the
     * pressed-surface wash with a hairline. Readable with no colour and no motion: the knob's
     * *position* is the state, and the fill only confirms it.
     */
    fun toggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int, travel: Float, enabled: Boolean) {
        val width = toggleWidth(height)
        val knob = Math.round(height * 0.8f).coerceAtLeast(4)
        val clamped = travel.coerceIn(0f, 1f)

        val base = if (enabled) Tokens.textPrimary else Tokens.textDisabled
        Surface.roundedFill(graphics, x, y, width, height, Tokens.RADIUS_FULL, Tokens.surfaceActive)
        if (clamped > 0f) {
            // Fades in rather than sliding in: the knob is the thing that travels, and a second moving
            // element on a 36px control reads as a glitch.
            Surface.roundedFill(
                graphics, x, y, width, height, Tokens.RADIUS_FULL,
                Tokens.fade(Tokens.accent, clamped * (if (enabled) 1f else 0.4f)),
            )
        }
        Surface.roundedBorder(graphics, x, y, width, height, Tokens.RADIUS_FULL, Tokens.borderDefault)

        val inset = (height - knob) / 2
        val range = width - knob - inset * 2
        val knobX = x + inset + Math.round(travel.coerceIn(-0.08f, 1.08f) * range).coerceIn(0, range)
        val knobColour = if (clamped > 0.5f) Tokens.accentText else base
        Surface.roundedFill(graphics, knobX, y + inset, knob, knob, Tokens.RADIUS_FULL, knobColour)
    }

    /**
     * A filter chip: hairline outline when idle, solid accent with inverted text when active.
     *
     * [count] rides along in tertiary because a chip should advertise the number of rows a click on it
     * produces — that is what makes it possible to choose without trying.
     */
    /**
     * A chip's width, derived rather than returned from [chip].
     *
     * Callers need this *before* drawing — to lay the next chip out, and to hit-test the cursor
     * against the same rectangle the chip occupies. A width that only exists after the draw forces
     * either a guess or a frame of lag, and both show up as a chip highlighting when the cursor is
     * beside it.
     */
    fun chipWidth(font: Font, label: String, count: Int): Int =
        font.width(if (count >= 0) "$label $count" else label) + Tokens.SPACE_16

    fun chip(
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, height: Int,
        label: String, count: Int,
        active: Float, hover: Float,
    ) {
        val width = chipWidth(font, label, count)

        if (hover > 0f && active < 1f) {
            Surface.roundedFill(
                graphics, x, y, width, height, Tokens.RADIUS_FULL,
                Tokens.fade(Tokens.surfaceHover, hover),
            )
        }
        if (active > 0f) {
            Surface.roundedFill(
                graphics, x, y, width, height, Tokens.RADIUS_FULL,
                Tokens.fade(Tokens.accent, active),
            )
        }
        Surface.roundedBorder(
            graphics, x, y, width, height, Tokens.RADIUS_FULL,
            if (active > 0.5f) 0 else Tokens.borderDefault,
        )

        val labelColour = blend(Tokens.textSecondary, Tokens.accentText, active)
        val countColour = blend(Tokens.textTertiary, Tokens.accentText, active)
        val textY = y + (height - 8) / 2
        graphics.text(font, label, x + Tokens.SPACE_8, textY, labelColour, false)
        if (count >= 0) {
            graphics.text(
                font, count.toString(),
                x + Tokens.SPACE_8 + font.width("$label "), textY,
                if (active > 0.5f) countColour else Tokens.textTertiary, false,
            )
        }
    }

    /**
     * A vertical scrollbar: a hairline track with a solid thumb.
     *
     * Drawn only when there is something to scroll. A permanently visible bar that never moves is a
     * control that lies about being one.
     */
    fun scrollbar(
        graphics: GuiGraphicsExtractor,
        x: Int, top: Int, bottom: Int,
        total: Int, visible: Int, offset: Int,
    ) {
        if (total <= visible) return
        val track = bottom - top
        DevicePixels.hairlineV(graphics, x, top, track, Tokens.borderSubtle)
        val thumb = (track * visible / total).coerceAtLeast(Tokens.SPACE_16)
        val travel = ((track - thumb).toLong() * offset / (total - visible)).toInt()
        Surface.roundedFill(graphics, x, top + travel, 3, thumb, Tokens.RADIUS_XS, Tokens.borderStrong)
    }

    /**
     * The 2px indicator that scales in from a row's vertical centre on hover.
     *
     * Growing from the middle rather than sliding in from the top is what makes a list of them read as
     * one element responding rather than as several arriving.
     */
    fun indicator(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int, progress: Float, argb: Int) {
        if (progress <= 0f) return
        val grown = Math.round(height * progress.coerceIn(0f, 1f))
        if (grown <= 0) return
        val top = y + (height - grown) / 2
        Surface.roundedFill(graphics, x, top, 2, grown, Tokens.RADIUS_XS, argb)
    }

    /**
     * A row's hover wash plus its indicator.
     *
     * Returns nothing and draws nothing when [hover] is zero, so an unhovered row in a hundred-row list
     * costs one comparison.
     */
    fun rowHighlight(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        hover: Float, selected: Boolean,
    ) {
        if (selected) {
            Surface.roundedFill(graphics, x, y, width, height, Tokens.RADIUS_ROW, Tokens.surfaceActive)
        } else if (hover > 0f) {
            Surface.roundedFill(
                graphics, x, y, width, height, Tokens.RADIUS_ROW,
                Tokens.fade(Tokens.surfaceHover, hover),
            )
        }
        indicator(graphics, x, y, height, if (selected) 1f else hover, Tokens.accent)
    }

    /** Linear interpolation between two packed ARGB colours, per channel including alpha. */
    fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        if (t <= 0f) return from
        if (t >= 1f) return to
        var out = 0
        var shift = 0
        while (shift < 32) {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            out = out or ((a + ((b - a) * t).toInt()) shl shift)
            shift += 8
        }
        return out
    }

    /** The standard hover fade, so every hoverable thing on a screen agrees on how fast that is. */
    fun hover(anim: Animatable, hovered: Boolean): Float {
        anim.animateTo(if (hovered) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
        return anim.value
    }
}
