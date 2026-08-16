package sighteaddons.ui.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.theme.Density

/**
 * Draws in device pixels instead of GUI pixels, so a hairline is one physical pixel at every GUI
 * scale rather than a four-pixel slab at scale 4.
 *
 * The trick is to scale the pose down by the device scale, which makes one unit of the coordinate
 * system equal one physical pixel, and then to draw using integers in that space. Two details make
 * the difference between crisp and broken:
 *
 * - **The scale is per axis and is not `Window.getGuiScale()`** — see [Density] for why.
 * - **The origin must be snapped onto a device pixel first.** There is no antialiasing on the GUI
 *   path, so a line at a fractional device position covers one pixel or none depending on where the
 *   pixel centre falls. Skip the snap and borders blink in and out as the window is resized.
 *
 * Text is never drawn inside this scope: the font would render at 1/scale of its size and turn to
 * mush. [pop] first.
 */
internal object DevicePixels {

    /**
     * Enter device-pixel space.
     *
     * Returns `false` without touching the pose if the current transform is anything other than a
     * pure translation — a caller inside a scaled or rotated pose cannot have its hairlines snapped
     * meaningfully, and should fall back to a one-GUI-pixel line rather than draw something wrong.
     * Callers must not call [pop] when this returns `false`.
     */
    fun push(graphics: GuiGraphicsExtractor): Boolean {
        val pose = graphics.pose()
        if (pose.m00 != 1f || pose.m01 != 0f || pose.m10 != 0f || pose.m11 != 1f) return false

        val translateX = pose.m20
        val translateY = pose.m21
        pose.pushMatrix()
        pose.translate(
            Density.snapOffset(translateX, Density.scaleX),
            Density.snapOffset(translateY, Density.scaleY),
        )
        pose.scale(1f / Density.scaleX, 1f / Density.scaleY)
        return true
    }

    fun pop(graphics: GuiGraphicsExtractor) {
        graphics.pose().popMatrix()
    }

    /**
     * A horizontal hairline from GUI coordinate ([x], [y]) running [width] GUI pixels, exactly
     * [Density.hairline] device pixels thick.
     *
     * Falls back to a one-GUI-pixel `fill` when the pose is not a pure translation, so a call site
     * never has to know which case it is in.
     */
    fun hairlineH(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, argb: Int) {
        if (!push(graphics)) {
            graphics.fill(x, y, x + width, y + 1, argb)
            return
        }
        val left = Density.deviceX(x.toFloat())
        val top = Density.deviceY(y.toFloat())
        graphics.fill(left, top, Density.deviceX((x + width).toFloat()), top + Density.hairline, argb)
        pop(graphics)
    }

    /** A vertical hairline. See [hairlineH]. */
    fun hairlineV(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int, argb: Int) {
        if (!push(graphics)) {
            graphics.fill(x, y, x + 1, y + height, argb)
            return
        }
        val left = Density.deviceX(x.toFloat())
        val top = Density.deviceY(y.toFloat())
        graphics.fill(left, top, left + Density.hairline, Density.deviceY((y + height).toFloat()), argb)
        pop(graphics)
    }

    /** A hairline rectangle outline — four calls, so the corners meet exactly. */
    fun hairlineBox(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, argb: Int) {
        if (!push(graphics)) {
            graphics.fill(x, y, x + width, y + 1, argb)
            graphics.fill(x, y + height - 1, x + width, y + height, argb)
            graphics.fill(x, y + 1, x + 1, y + height - 1, argb)
            graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, argb)
            return
        }
        val left = Density.deviceX(x.toFloat())
        val top = Density.deviceY(y.toFloat())
        val right = Density.deviceX((x + width).toFloat())
        val bottom = Density.deviceY((y + height).toFloat())
        val t = Density.hairline
        graphics.fill(left, top, right, top + t, argb)
        graphics.fill(left, bottom - t, right, bottom, argb)
        graphics.fill(left, top + t, left + t, bottom - t, argb)
        graphics.fill(right - t, top + t, right, bottom - t, argb)
        pop(graphics)
    }
}
