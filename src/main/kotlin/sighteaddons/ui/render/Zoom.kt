package sighteaddons.ui.render

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Draws a whole overlay at the size the player scrolled it to, and the one place that costs anything.
 *
 * An element's layout is forty hand-set constants — paddings, row heights, glyph sizes, the width a
 * time column needs. Making a size adjustable by multiplying every one of them is forty chances to
 * multiply thirty-nine, and the fortieth is the one that shows. So the element keeps drawing itself at
 * the size it was designed at, from its own top-left corner, and the pose puts that drawing on screen
 * bigger or smaller. One transform, and no constant anywhere learns about scale.
 *
 * ### What a scale costs, stated plainly
 *
 * [DevicePixels.push] refuses a pose that is not a pure translation, because a hairline cannot be
 * snapped onto the device grid through a scale it does not know about — so inside this scope
 * [Surface]'s borders take the one-GUI-pixel fallback and come out `scale` pixels thick, and text
 * drawn at a non-integer multiple of [ScaledText.SCALE] lands between glyph pixels and softens. That
 * is the honest price of a continuously adjustable size on a bitmap font, and it is why the range in
 * [sighteaddons.OverlayPlacement] stops where it does rather than going down to a quarter.
 *
 * **At 100% none of it applies.** The pose is then a pure translation, [DevicePixels] snaps exactly as
 * it did before any of this existed, and the pixels are the pixels that shipped — which is the state
 * every element is in until somebody deliberately changes one.
 *
 * ### Why the scope is a lambda and pops in a `finally`
 *
 * [ScaledText] hands out no open scope on purpose: an open scope is an invitation to draw a border
 * inside it. The same argument applies here in reverse — everything an element draws belongs inside
 * this one — so the scope is closed by construction instead. The `finally` is not decoration:
 * [sighteaddons.ui.hud.HudRoot.draw] returns early when there is no dungeon, that `return` is a
 * non-local one out of an inlined lambda, and a matrix pushed and not popped leaks into every element
 * drawn after it for the rest of the frame.
 */
internal object Zoom {

    /**
     * Runs [body] with the pose translated to ([x], [y]) and scaled by [scale], so [body] can lay
     * itself out from `0, 0` and be drawn where it belongs at the size it was asked for.
     */
    inline fun at(graphics: GuiGraphicsExtractor, x: Int, y: Int, scale: Float, body: () -> Unit) {
        val pose = graphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(x.toFloat(), y.toFloat())
            if (scale != 1f) pose.scale(scale, scale)
            body()
        } finally {
            pose.popMatrix()
        }
    }
}
