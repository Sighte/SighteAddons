package sighteaddons.ui.components

import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * The floating surface: a shadowed card on [Tokens.surfaceOverlay], for anything that sits above the
 * page rather than in it.
 *
 * One primitive under both a tooltip and a menu, because they are the same object with different
 * contents. The old note here said [Tokens.Elevation.E2] "costs nine draws for the shadow, which is a
 * decision worth making once rather than per popover" — the nine draws are now one real Gaussian, so
 * the cost argument has gone, but the reason to have one primitive has not.
 */
internal object Popover {

    /** Inner padding. */
    const val PADDING = Tokens.SPACE_8

    /**
     * The frame only. Callers draw their own contents inside it, after this returns.
     *
     * Elevated because a floating surface with no shadow is indistinguishable from a card on a screen
     * with no hue: the shadow is the only thing saying it is *above* rather than *on*.
     */
    fun frame(x: Float, y: Float, width: Float, height: Float) {
        val e = Tokens.Elevation.E2
        val radius = Tokens.RADIUS_MD.toFloat()
        Sk.shadow(
            x, y, width, height,
            Tokens.alpha(Tokens.shadow, e.shadowAlpha), radius,
            blur = e.shadowSpread.toFloat(), dy = e.shadowOffset.toFloat(),
        )
        Sk.fill(x, y, width, height, Tokens.surfaceOverlay, radius)
        Sk.border(x, y, width, height, Tokens.borderStrong, radius)
    }
}

/**
 * A tooltip, in this design system rather than vanilla's.
 *
 * The reason this exists was on screen: `SettingsScreen` handed a truncated room name to
 * `setTooltipForNextFrame`, and vanilla answered with its own box — a purple-gradient border on a
 * dark-blue fill, the one thing on the entire screen carrying a hue in a UI that has spent four files
 * establishing it has none. A tooltip is not a big enough element to be worth an exception.
 *
 * Placement is arithmetic and lives in [placeX] and [placeY], separately from the drawing, because a
 * tooltip that runs off the screen edge is a tooltip that cannot be read at all — and the case only
 * happens at the one screen position nobody tests at by hand.
 */
internal object Tooltip {

    /** Line pitch. Slightly more than the font's own, so two lines do not read as one paragraph. */
    const val LINE = 11

    /** How far from the cursor the box sits. */
    const val OFFSET = Tokens.SPACE_12

    /** How close to the screen edge it may come. */
    const val MARGIN = Tokens.SPACE_4

    /** The body size. */
    val SIZE = Tokens.TEXT_11.toFloat()

    fun width(lines: List<String>, measure: (String) -> Float): Int {
        var widest = 0f
        for (line in lines) widest = maxOf(widest, measure(line))
        return Math.ceil(widest.toDouble()).toInt() + PADDING_2
    }

    /**
     * How tall the box is for [lines].
     *
     * The trailing term used to subtract the difference between the line pitch and the bitmap font's
     * cap height, so the box did not carry a line's worth of leading below the last line. The same
     * correction, now against the real line height: the last line contributes its own height rather
     * than a full pitch.
     */
    fun height(lines: List<String>, lineHeight: Float): Int {
        if (lines.isEmpty()) return 0
        val body = (lines.size - 1) * LINE + Math.ceil(lineHeight.toDouble()).toInt()
        return PADDING_2 + body
    }

    private const val PADDING_2 = Popover.PADDING * 2

    /**
     * Where the box's left edge goes.
     *
     * Prefers the right of the cursor, flips to the left when it would not fit, and clamps when neither
     * side fits — which happens on a narrow window at GUI scale 4, where the box can be wider than the
     * gap on either side. Clamping puts it under the cursor rather than off the screen; a tooltip half
     * outside the window is worse than one the cursor is sitting on.
     */
    fun placeX(anchorX: Int, width: Int, screenWidth: Int): Int {
        val right = anchorX + OFFSET
        if (right + width + MARGIN <= screenWidth) return right
        val left = anchorX - OFFSET - width
        if (left >= MARGIN) return left
        return (screenWidth - width - MARGIN).coerceAtLeast(MARGIN)
    }

    /** The same, vertically: below the cursor, flipped above when it would not fit, then clamped. */
    fun placeY(anchorY: Int, height: Int, screenHeight: Int): Int {
        val below = anchorY + OFFSET
        if (below + height + MARGIN <= screenHeight) return below
        val above = anchorY - OFFSET - height
        if (above >= MARGIN) return above
        return (screenHeight - height - MARGIN).coerceAtLeast(MARGIN)
    }

    /**
     * The whole thing, placed against the cursor.
     *
     * The first line is the subject and the rest are detail, which is the only hierarchy a tooltip
     * needs; a tooltip with two equal lines is two tooltips. The subject now also carries the weight,
     * which is what lets the detail lines sit at a readable tone instead of having to be dim enough to
     * lose the comparison.
     */
    fun draw(anchorX: Int, anchorY: Int, screenWidth: Int, screenHeight: Int, lines: List<String>) {
        if (lines.isEmpty()) return
        val boxWidth = width(lines) { Sk.width(it, SIZE) }
        val boxHeight = height(lines, Sk.lineHeight(SIZE))
        val x = placeX(anchorX, boxWidth, screenWidth).toFloat()
        val y = placeY(anchorY, boxHeight, screenHeight).toFloat()

        Popover.frame(x, y, boxWidth.toFloat(), boxHeight.toFloat())
        for (i in lines.indices) {
            val family = if (i == 0) Type.MEDIUM else Type.REGULAR
            Sk.text(
                lines[i], x + Popover.PADDING, y + Popover.PADDING + i * LINE, SIZE,
                if (i == 0) Tokens.textPrimary else Tokens.textSecondary, family,
            )
        }
    }
}
