package sighteaddons.ui.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens

/**
 * The two one-shot effects that mark an event on the HUD.
 *
 * Both are stateless: the caller keeps a start stamp and passes it in, and both draw nothing once
 * their window has passed. That keeps the "positive events earn animation, negative events are stated
 * quietly" rule cheap to honour — a regression simply never sets a stamp.
 *
 * Neither loops. A celebration that keeps moving after it has said its piece is the thing that makes
 * a tracker overlay exhausting to play with.
 */
internal object Effects {

    /** One sweep across a row. Marks a new personal best, once. */
    const val SHIMMER_MS = 700.0

    /** One ring expanding outward. Marks a secret picked up. */
    const val PULSE_MS = 400.0

    /** How far the pulse ring travels beyond its origin rectangle, in GUI pixels. */
    const val PULSE_SPREAD = 6

    /**
     * A soft highlight travelling left to right across ([x], [y], [width], [height]).
     *
     * Clipped with a scissor rather than by shortening the blit, so the leading and trailing edges of
     * the sweep are cut off cleanly at the row's bounds instead of being squashed into it. The travel
     * is linear on purpose — this is one of the two cases where linear is right, because the sweep is
     * passing through rather than arriving.
     */
    fun shimmer(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        startedAtMs: Double,
        tint: Int = Tokens.accent,
    ) {
        if (!Motion.ambientEnabled()) return
        val elapsed = Clock.nowMs - startedAtMs
        if (elapsed < 0.0 || elapsed > SHIMMER_MS) return

        val progress = (elapsed / SHIMMER_MS).toFloat()
        val sweep = width / 2
        // Enters fully off the left edge and leaves fully off the right, so no partial band is ever
        // parked at a boundary.
        val left = x - sweep + Math.round(Easing.LINEAR.ease(progress) * (width + sweep * 2))

        graphics.enableScissor(x, y, x + width, y + height)
        Sheet.rampH(graphics, left, y, sweep, height, Tokens.alpha(tint, SHIMMER_ALPHA))
        graphics.disableScissor()
    }

    /**
     * A hairline ring expanding outward from ([x], [y], [width], [height]) and fading as it goes.
     *
     * Drawn as an expanding rounded rectangle rather than a circle: the shape it emanates from is a
     * rectangle, and a circle pulsing out of a rectangular counter reads as a stray element rather
     * than as that element reacting. It is also four fills and four small blits instead of a texture.
     */
    fun pulse(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        startedAtMs: Double,
        tint: Int = Tokens.accent,
    ) {
        if (!Motion.ambientEnabled()) return
        val elapsed = Clock.nowMs - startedAtMs
        if (elapsed < 0.0 || elapsed > PULSE_MS) return

        val progress = (elapsed / PULSE_MS).toFloat()
        val spread = Math.round(Easing.STANDARD.ease(progress) * PULSE_SPREAD)
        // Fades out over the whole travel, so the ring is at its faintest when it is at its largest —
        // the opposite reads as an expanding box rather than as a pulse dissipating.
        val alpha = Math.round((1f - progress) * PULSE_ALPHA)
        if (alpha <= 0) return

        Surface.roundedBorder(
            graphics,
            x - spread, y - spread,
            width + spread * 2, height + spread * 2,
            Tokens.RADIUS_SM,
            Tokens.alpha(tint, alpha),
        )
    }

    /**
     * The ambient breathing opacity for the active-room indicator: `0.65 -> 1.0` on a 2400 ms sine.
     *
     * One of only two ambient loops this UI is allowed to have on screen at once. Returns the rest
     * value under reduce-motion, so callers need no branch.
     */
    fun breathe(): Float {
        if (!Motion.ambientEnabled()) return BREATHE_MAX
        val phase = ((Clock.nowMs % BREATHE_MS) / BREATHE_MS).toFloat()
        return BREATHE_MIN + (BREATHE_MAX - BREATHE_MIN) * Easing.SINE.ease(phase)
    }

    /**
     * A one-physical-pixel wipe travelling left to right across a card — the "committed" signal when
     * the tracked room changes.
     *
     * Deliberately a hard line rather than a soft sweep: it is stating that something has been
     * recorded, and a gradient would read as decoration.
     */
    fun wipe(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        startedAtMs: Double,
        durationMs: Double,
        tint: Int = Tokens.accent,
    ) {
        if (!Motion.ambientEnabled()) return
        val elapsed = Clock.nowMs - startedAtMs
        if (elapsed < 0.0 || elapsed > durationMs) return

        val progress = Easing.STANDARD.ease((elapsed / durationMs).toFloat())
        val edge = x + Math.round(progress * width)
        val alpha = Math.round((1f - progress) * WIPE_ALPHA)
        if (alpha <= 0) return

        if (!DevicePixels.push(graphics)) {
            graphics.fill(edge, y, edge + 1, y + height, Tokens.alpha(tint, alpha))
            return
        }
        val left = Density.deviceX(edge.toFloat())
        graphics.fill(
            left, Density.deviceY(y.toFloat()),
            left + Density.hairline, Density.deviceY((y + height).toFloat()),
            Tokens.alpha(tint, alpha),
        )
        DevicePixels.pop(graphics)
    }

    private const val SHIMMER_ALPHA = 56
    private const val PULSE_ALPHA = 150f
    private const val WIPE_ALPHA = 190f
    private const val BREATHE_MS = 2400.0
    private const val BREATHE_MIN = 0.65f
    private const val BREATHE_MAX = 1.0f
}
