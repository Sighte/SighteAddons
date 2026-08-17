package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * The floating surface: a shadowed card on [Tokens.surfaceOverlay], for anything that sits above the
 * page rather than in it.
 *
 * One primitive under both a tooltip and a menu, because they are the same object with different
 * contents — and because [Tokens.Elevation.E2] costs nine draws for the shadow, which is a decision
 * worth making once rather than per popover.
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
    fun frame(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        Surface.card(
            graphics, x, y, width, height,
            radius = Tokens.RADIUS_MD,
            fill = Tokens.surfaceOverlay,
            border = Tokens.borderStrong,
            elevation = Tokens.Elevation.E2,
        )
    }
}

/**
 * A tooltip, in this design system rather than vanilla's.
 *
 * The reason this exists is on screen today: `SettingsScreen` hands a truncated room name to
 * `setTooltipForNextFrame`, and vanilla answers with its own box — a purple-gradient border on a
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

    fun width(font: Font, lines: List<String>): Int {
        var widest = 0
        for (line in lines) widest = maxOf(widest, font.width(line))
        return widest + Popover.PADDING * 2
    }

    fun height(lines: List<String>): Int =
        Popover.PADDING * 2 + lines.size * LINE - (LINE - Labels.CAP)

    /**
     * Where the box's left edge goes.
     *
     * Prefers the right of the cursor, flips to the left when it would not fit, and clamps when
     * neither side fits — which happens on a narrow window at GUI scale 4, where the box can be wider
     * than the gap on either side. Clamping puts it under the cursor rather than off the screen; a
     * tooltip half outside the window is worse than one the cursor is sitting on.
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
     * needs; a tooltip with two equal lines is two tooltips.
     */
    fun draw(
        graphics: GuiGraphicsExtractor, font: Font,
        anchorX: Int, anchorY: Int,
        screenWidth: Int, screenHeight: Int,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        val boxWidth = width(font, lines)
        val boxHeight = height(lines)
        val x = placeX(anchorX, boxWidth, screenWidth)
        val y = placeY(anchorY, boxHeight, screenHeight)

        Popover.frame(graphics, x, y, boxWidth, boxHeight)
        for (i in lines.indices) {
            graphics.text(
                font, lines[i], x + Popover.PADDING, y + Popover.PADDING + i * LINE,
                if (i == 0) Tokens.textPrimary else Tokens.textTertiary, false,
            )
        }
    }
}
