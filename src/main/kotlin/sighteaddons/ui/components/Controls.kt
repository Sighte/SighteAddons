package sighteaddons.ui.components

import sighteaddons.ui.motion.Animatable
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.motion.Spring
import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
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
 * All of them are geometry-and-value in, pixels out, with any animation passed in as an
 * already-resolved `0f..1f`. That keeps every one of them checkable at a single frozen frame, which is
 * the only way to review an animation without being able to drive the game.
 *
 * Coordinates are floats now. Not cosmetic: a switch whose knob travels across 22 pixels in integer
 * steps has 22 positions, and the spring driving it has hundreds — so the old version quantised its
 * own animation and the overshoot at the end of the travel was often invisible. Anti-aliased
 * sub-pixel placement is what makes the motion that was always being computed actually visible.
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
    fun toggle(x: Float, y: Float, height: Float, travel: Float, enabled: Boolean) {
        val width = height * TOGGLE_WIDTH / TOGGLE_HEIGHT
        val knob = (height * 0.8f).coerceAtLeast(4f)
        val clamped = travel.coerceIn(0f, 1f)
        val radius = height / 2f

        val base = if (enabled) Tokens.textPrimary else Tokens.textDisabled
        Sk.fill(x, y, width, height, Tokens.surfaceActive, radius)
        if (clamped > 0f) {
            // Fades in rather than sliding in: the knob is the thing that travels, and a second moving
            // element on a 36px control reads as a glitch.
            Sk.fill(x, y, width, height, Tokens.fade(Tokens.accent, clamped * (if (enabled) 1f else 0.4f)), radius)
        }
        Sk.border(x, y, width, height, Tokens.borderDefault, radius)

        val inset = (height - knob) / 2f
        val range = width - knob - inset * 2f
        val knobX = x + inset + (travel.coerceIn(-0.08f, 1.08f) * range).coerceIn(0f, range)
        val knobColour = if (clamped > 0.5f) Tokens.accentText else base
        Sk.fill(knobX, y + inset, knob, knob, knobColour, knob / 2f)
    }

    /**
     * A chip's width, derived rather than returned from [chip].
     *
     * Callers need this *before* drawing — to lay the next chip out, and to hit-test the cursor against
     * the same rectangle the chip occupies. A width that only exists after the draw forces either a
     * guess or a frame of lag, and both show up as a chip highlighting when the cursor is beside it.
     *
     * Takes a measurement rather than a font, so the layout that depends on it is not tied to a client.
     */
    fun chipWidth(label: String, count: Int, measure: (String) -> Float): Float =
        measure(if (count >= 0) "$label $count" else label) + Tokens.SPACE_16

    /**
     * A filter chip: hairline outline when idle, solid accent with inverted text when active.
     *
     * [count] rides along in tertiary because a chip should advertise the number of rows a click on it
     * produces — that is what makes it possible to choose without trying.
     */
    fun chip(
        x: Float, y: Float, height: Float,
        label: String, count: Int,
        active: Float, hover: Float,
    ) {
        val size = Tokens.TEXT_11.toFloat()
        val width = chipWidth(label, count) { Sk.width(it, size) }
        val radius = height / 2f

        if (hover > 0f && active < 1f) {
            Sk.fill(x, y, width, height, Tokens.fade(Tokens.surfaceHover, hover), radius)
        }
        if (active > 0f) {
            Sk.fill(x, y, width, height, Tokens.fade(Tokens.accent, active), radius)
        }
        if (active <= 0.5f) Sk.border(x, y, width, height, Tokens.borderDefault, radius)

        val family = if (active > 0.5f) Type.MEDIUM else Type.REGULAR
        val labelColour = blend(Tokens.textSecondary, Tokens.accentText, active)
        val textY = Sk.centreY(y, height, size, family)
        Sk.text(label, x + Tokens.SPACE_8, textY, size, labelColour, family)
        if (count >= 0) {
            val countColour =
                if (active > 0.5f) blend(Tokens.textTertiary, Tokens.accentText, active) else Tokens.textTertiary
            Sk.text(
                count.toString(),
                x + Tokens.SPACE_8 + Sk.width("$label ", size, family), textY, size, countColour, family,
            )
        }
    }

    /**
     * A vertical scrollbar: a hairline track with a solid thumb.
     *
     * Drawn only when there is something to scroll. A permanently visible bar that never moves is a
     * control that lies about being one.
     */
    fun scrollbar(x: Float, top: Float, bottom: Float, total: Int, visible: Int, offset: Int) {
        if (total <= visible) return
        val track = bottom - top
        if (track <= 0f) return
        Sk.fill(x, top, Chrome.HAIRLINE, track, Tokens.borderSubtle)
        val thumb = (track * visible / total).coerceAtLeast(Tokens.SPACE_16.toFloat())
        val travel = (track - thumb) * offset / (total - visible)
        Sk.fill(x, top + travel, THUMB, thumb, Tokens.borderStrong, THUMB / 2f)
    }

    private const val THUMB = 3f

    /**
     * The 2px indicator that scales in from a row's vertical centre on hover.
     *
     * Growing from the middle rather than sliding in from the top is what makes a list of them read as
     * one element responding rather than as several arriving.
     */
    fun indicator(x: Float, y: Float, height: Float, progress: Float, argb: Int) {
        if (progress <= 0f) return
        val grown = height * progress.coerceIn(0f, 1f)
        if (grown <= 0f) return
        Sk.fill(x, y + (height - grown) / 2f, INDICATOR, grown, argb, INDICATOR / 2f)
    }

    private const val INDICATOR = 2f

    /**
     * A row's hover wash plus its indicator.
     *
     * Draws nothing when [hover] is zero and the row is not selected, so an unhovered row in a
     * hundred-row list costs one comparison.
     */
    fun rowHighlight(x: Float, y: Float, width: Float, height: Float, hover: Float, selected: Boolean) {
        if (selected) {
            Sk.fill(x, y, width, height, Tokens.surfaceActive, Tokens.RADIUS_ROW.toFloat())
        } else if (hover > 0f) {
            Sk.fill(x, y, width, height, Tokens.fade(Tokens.surfaceHover, hover), Tokens.RADIUS_ROW.toFloat())
        }
        indicator(x, y, height, if (selected) 1f else hover, Tokens.accent)
    }

    /**
     * A dashed hairline rectangle — what a disabled control wears instead of a solid outline.
     *
     * Shared by every control that can be switched off, because "unavailable" has to be one mark
     * everywhere or it is not a mark at all. It is a *pattern* rather than a fainter grey for the
     * reason the palette states: `textSecondary` and `textTertiary` sit 1.27:1 apart, so a control that
     * says it is disabled by being slightly dimmer is not saying it to everyone.
     *
     * Still square-cornered, but the reason changed. It used to be that the sprite sheet's corners were
     * solid arcs that could not be dashed; now it is simply that Skija exposes no dash effect here, so
     * the dashes are still walked by hand — and a hand-walked dash around a curve is arc-length
     * arithmetic for a border nobody will measure.
     */
    fun dashedBorder(x: Float, y: Float, width: Float, height: Float, argb: Int) {
        var cursor = 0f
        while (cursor < width) {
            val run = minOf(DASH, width - cursor)
            Sk.fill(x + cursor, y, run, Chrome.HAIRLINE, argb)
            Sk.fill(x + cursor, y + height - Chrome.HAIRLINE, run, Chrome.HAIRLINE, argb)
            cursor += DASH + DASH_GAP
        }
        cursor = 0f
        while (cursor < height) {
            val run = minOf(DASH, height - cursor)
            Sk.fill(x, y + cursor, Chrome.HAIRLINE, run, argb)
            Sk.fill(x + width - Chrome.HAIRLINE, y + cursor, Chrome.HAIRLINE, run, argb)
            cursor += DASH + DASH_GAP
        }
    }

    private const val DASH = 3f
    private const val DASH_GAP = 2f

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
