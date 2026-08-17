package sighteaddons.ui.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens

/**
 * The primitive layer: the only thing components draw shapes through.
 *
 * It owns the split between what is a rectangle (a `fill`, cheap and exact) and what is not (a blit
 * from [Sheet]), so no component has to know that the split exists — and so the decision can change
 * later without touching a call site. A rounded card is seven draws here: four corner blits and three
 * fills.
 *
 * Text is not this class's business. Callers draw their own, after any [push]-scoped work has ended.
 */
internal object Surface {

    /**
     * Resolve a requested radius to one the sheet actually carries, capped so a corner can never eat
     * more than half the shorter side.
     *
     * [Tokens.RADIUS_FULL] means "as round as this box allows", which is how a chip becomes a
     * lozenge. The snap is downward: a box that asks for 12 and can only afford 8 gets 8, never a
     * corner that overlaps its opposite.
     */
    fun resolveRadius(radius: Int, width: Int, height: Int): Int {
        val room = minOf(width, height) / 2
        val wanted = if (radius == Tokens.RADIUS_FULL) room else minOf(radius, room)
        if (wanted < Sheet.RADII[0]) return 0
        return Sheet.RADII[Sheet.radiusIndex(wanted)]
    }

    /**
     * A filled rounded rectangle: four corner blits plus three fills.
     *
     * Falls through to a single `fill` when the resolved radius is zero, which is both correct and
     * the cheap path a small element wants.
     */
    fun roundedFill(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, radius: Int, argb: Int) {
        if (width <= 0 || height <= 0) return
        val r = resolveRadius(radius, width, height)
        if (r == 0) {
            graphics.fill(x, y, x + width, y + height, argb)
            return
        }

        val index = Sheet.radiusIndex(r)
        val right = x + width - r
        val bottom = y + height - r

        Sheet.corner(graphics, x, y, index, Sheet.TOP_LEFT, r, argb)
        Sheet.corner(graphics, right, y, index, Sheet.TOP_RIGHT, r, argb)
        Sheet.corner(graphics, x, bottom, index, Sheet.BOTTOM_LEFT, r, argb)
        Sheet.corner(graphics, right, bottom, index, Sheet.BOTTOM_RIGHT, r, argb)

        graphics.fill(x + r, y, right, y + r, argb)
        graphics.fill(x, y + r, x + width, bottom, argb)
        graphics.fill(x + r, bottom, right, y + height, argb)
    }

    /**
     * A hairline outline: four straight edges at one physical pixel, and four corner arcs.
     *
     * The straight edges stop at the corners' tangent points so the arcs are not double-drawn — a
     * translucent border overlapping itself reads as a brighter corner, which is exactly the artefact
     * that makes a hairline look hand-made.
     *
     * The arcs are one *GUI* pixel rather than one physical pixel; see the note in the generator.
     */
    fun roundedBorder(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, radius: Int, argb: Int) {
        if (width <= 0 || height <= 0) return
        val r = resolveRadius(radius, width, height)

        if (r > 0) {
            val index = Sheet.radiusIndex(r)
            Sheet.ring(graphics, x, y, index, Sheet.TOP_LEFT, r, argb)
            Sheet.ring(graphics, x + width - r, y, index, Sheet.TOP_RIGHT, r, argb)
            Sheet.ring(graphics, x, y + height - r, index, Sheet.BOTTOM_LEFT, r, argb)
            Sheet.ring(graphics, x + width - r, y + height - r, index, Sheet.BOTTOM_RIGHT, r, argb)
        }

        // One push for all four edges rather than four: the pose snap is per-scope, and doing it once
        // also guarantees the four edges agree about where the device grid is.
        if (!DevicePixels.push(graphics)) {
            graphics.fill(x + r, y, x + width - r, y + 1, argb)
            graphics.fill(x + r, y + height - 1, x + width - r, y + height, argb)
            graphics.fill(x, y + r, x + 1, y + height - r, argb)
            graphics.fill(x + width - 1, y + r, x + width, y + height - r, argb)
            return
        }
        val left = Density.deviceX(x.toFloat())
        val top = Density.deviceY(y.toFloat())
        val right = Density.deviceX((x + width).toFloat())
        val bottom = Density.deviceY((y + height).toFloat())
        val innerLeft = Density.deviceX((x + r).toFloat())
        val innerTop = Density.deviceY((y + r).toFloat())
        val innerRight = Density.deviceX((x + width - r).toFloat())
        val innerBottom = Density.deviceY((y + height - r).toFloat())
        val t = Density.hairline

        graphics.fill(innerLeft, top, innerRight, top + t, argb)
        graphics.fill(innerLeft, bottom - t, innerRight, bottom, argb)
        graphics.fill(left, innerTop, left + t, innerBottom, argb)
        graphics.fill(right - t, innerTop, right, innerBottom, argb)
        DevicePixels.pop(graphics)
    }

    /**
     * The one-pixel inner highlight along the top edge.
     *
     * Small, and not optional. In a design with no hue to spend, this is what separates a raised
     * surface from "a slightly different grey" — it reads as the top face catching the light, and
     * removing it makes every card in the system look flat at once.
     */
    fun topHighlight(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, radius: Int, argb: Int) {
        val r = resolveRadius(radius, width, width)
        if (!DevicePixels.push(graphics)) {
            graphics.fill(x + r, y + 1, x + width - r, y + 2, argb)
            return
        }
        val top = Density.deviceY((y + 1).toFloat())
        graphics.fill(
            Density.deviceX((x + r).toFloat()), top,
            Density.deviceX((x + width - r).toFloat()), top + Density.hairline,
            argb,
        )
        DevicePixels.pop(graphics)
    }

    /**
     * A complete card: shadow, fill, border, highlight — in that order.
     *
     * [Tokens.Elevation.E0] draws no shadow and no highlight, which is the flat variant most rows
     * want. Anything above that costs nine extra draws for the shadow, so elevation is a decision
     * made per panel and not per row: twelve shadowed cards is a hundred draws for something that
     * reads as one soft glow.
     */
    fun card(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        radius: Int = Tokens.RADIUS_CARD,
        fill: Int = Tokens.surfaceRaised,
        border: Int = Tokens.borderDefault,
        elevation: Tokens.Elevation = Tokens.Elevation.E0,
    ) {
        if (elevation.shadowAlpha > 0) {
            Sheet.shadow(
                graphics, x, y, width, height,
                elevation.shadowSpread, elevation.shadowOffset,
                Tokens.alpha(Tokens.shadow, elevation.shadowAlpha),
            )
        }
        roundedFill(graphics, x, y, width, height, radius, fill)
        if (border != 0) roundedBorder(graphics, x, y, width, height, radius, border)
        if (elevation.highlight) topHighlight(graphics, x, y, width, radius, Tokens.highlight)
    }

    /**
     * A segmented progress indicator: one filled block per unit, with a one-pixel gap between them.
     *
     * Segments rather than a continuous bar for totals of twelve or fewer, because a room with six
     * secrets and four found should be countable at a glance without reading a number — and because a
     * bar at 66 % and a bar at 70 % look the same, while four blocks of six never do.
     */
    fun segments(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, width: Int, height: Int,
        total: Int, filled: Float,
        on: Int, off: Int,
    ) {
        if (total <= 0) return
        val gap = Tokens.SPACE_2
        val each = (width - gap * (total - 1)).toFloat() / total
        if (each <= 0f) return
        for (i in 0 until total) {
            val left = x + Math.round(i * (each + gap))
            val right = left + Math.round(each)
            // Partial fill on the leading segment, so the indicator moves between whole secrets
            // rather than snapping — the progress animation has somewhere to go.
            val coverage = (filled - i).coerceIn(0f, 1f)
            graphics.fill(left, y, right, y + height, off)
            if (coverage > 0f) {
                graphics.fill(left, y, left + Math.round(each * coverage), y + height, on)
            }
        }
    }
}
