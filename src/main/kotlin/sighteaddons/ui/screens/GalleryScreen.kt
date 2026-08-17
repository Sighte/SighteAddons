package sighteaddons.ui.screens

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.motion.Spring
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.theme.Contrast
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Palette
import sighteaddons.ui.theme.Tokens
import java.util.Locale

/**
 * The development gallery: every token, every curve and every measurement on one screen.
 *
 * This is the verification harness for the whole design system, and it is built before the things it
 * verifies on purpose. `runClient` cannot reach Hypixel, so no amount of playing will show what a
 * card looks like at GUI scale 4 in the light theme — but this screen will, in one keypress, without
 * a dungeon.
 *
 * It is also where the claims get checked rather than asserted: the contrast column prints the
 * measured ratio next to each sample rather than a checkmark, the density page prints the real device
 * scale rather than the nominal one, and the motion page draws each curve next to a dot actually
 * travelling along it. Anything that disagrees with the spec is visible here first.
 *
 * Not shipped to players in any menu — reachable only through `/sa gallery`. String formatting per
 * frame is fine here for the same reason; this screen is never on during a run.
 */
class GalleryScreen : Screen(Component.literal("Sighte Addons — UI Gallery")) {

    private enum class Page(val label: String) {
        COLOUR("colour"),
        MOTION("motion"),
        DENSITY("density"),
        HUD("hud"),
    }

    private var page = Page.COLOUR
    private var dark = true
    private val knob = Spring(0f)
    private var knobFlippedAt = 0.0

    /** The preview's own HUD instance, so its animation stamps never touch the live overlay's. */
    private val previewHud = HudRoot()
    private var previewPaused = false
    private var previewHeldAt = 0.0
    private var previewOffset = 0.0

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val window = minecraft.window
        Density.beginFrame(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        Clock.frame(paused = false)
        Tokens.theme(dark)
        graphics.fill(0, 0, width, height, Tokens.surfaceBase)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val left = Tokens.SPACE_24
        graphics.label("UI GALLERY", left, Tokens.SPACE_20, Tokens.textPrimary)
        graphics.flat(Tokens.palette.name, width - left - font.width(Tokens.palette.name), Tokens.SPACE_20, Tokens.textTertiary)
        DevicePixels.hairlineH(graphics, left, Tokens.SPACE_32, width - left * 2, Tokens.borderSubtle)

        var x = left
        for (entry in Page.entries) {
            val active = entry == page
            graphics.flat(entry.label, x, Tokens.SPACE_32 + 8, if (active) Tokens.textPrimary else Tokens.textTertiary)
            if (active) {
                DevicePixels.hairlineH(graphics, x, Tokens.SPACE_32 + 19, font.width(entry.label), Tokens.accent)
            }
            x += font.width(entry.label) + Tokens.SPACE_16
        }
        DevicePixels.hairlineH(graphics, left, Tokens.SPACE_48 + 12, width - left * 2, Tokens.borderSubtle)

        val top = Tokens.SPACE_48 + Tokens.SPACE_24
        when (page) {
            Page.COLOUR -> colourPage(graphics, left, top)
            Page.MOTION -> motionPage(graphics, left, top)
            Page.DENSITY -> densityPage(graphics, left, top)
            Page.HUD -> hudPage(graphics, left, top)
        }

        val footer = if (page == Page.HUD) {
            "1-4 page  ·  T theme  ·  M reduce motion  ·  space ${if (previewPaused) "run" else "hold"}  ·  , . step  ·  esc close"
        } else {
            "1-4 page  ·  T theme  ·  M reduce motion (${if (Motion.reduceMotion) "on" else "off"})  ·  esc close"
        }
        graphics.flat(footer, left, height - Tokens.SPACE_20, Tokens.textTertiary)
    }

    // --- Colour -----------------------------------------------------------------------------

    private fun colourPage(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        val p = Tokens.palette
        graphics.label("SURFACES", left, top, Tokens.textSecondary)

        var x = left
        val swatchY = top + Tokens.SPACE_12
        for ((name, colour) in listOf(
            "base" to p.surfaceBase,
            "raised" to p.surfaceRaised,
            "overlay" to p.surfaceOverlay,
            "hover" to Contrast.over(p.surfaceHover, p.surfaceRaised),
            "active" to Contrast.over(p.surfaceActive, p.surfaceRaised),
        )) {
            graphics.fill(x, swatchY, x + SWATCH, swatchY + SWATCH_H, colour)
            DevicePixels.hairlineBox(graphics, x, swatchY, SWATCH, SWATCH_H, Tokens.borderDefault)
            graphics.flat(name, x, swatchY + SWATCH_H + 4, Tokens.textSecondary)
            graphics.flat(hex(colour), x, swatchY + SWATCH_H + 14, Tokens.textTertiary)
            x += SWATCH + Tokens.SPACE_8
        }

        // Text ramp, each sample on each surface, with the ratio it actually measures beside it.
        // Printing the number rather than a checkmark is the point: a regression shows as 4.31, not
        // as a silently missing tick.
        val rampTop = swatchY + SWATCH_H + Tokens.SPACE_32
        graphics.label("TEXT ON EACH SURFACE  ·  MEASURED CONTRAST", left, rampTop, Tokens.textSecondary)

        var y = rampTop + Tokens.SPACE_16
        for ((surfaceName, surface) in listOf(
            "base" to p.surfaceBase,
            "raised" to p.surfaceRaised,
            "overlay" to p.surfaceOverlay,
            "raised + hover" to Contrast.over(p.surfaceHover, p.surfaceRaised),
            "raised + active" to Contrast.over(p.surfaceActive, p.surfaceRaised),
        )) {
            graphics.fill(left, y, left + 460, y + 22, surface)
            DevicePixels.hairlineBox(graphics, left, y, 460, 22, Tokens.borderSubtle)
            graphics.flat(surfaceName, left + Tokens.SPACE_8, y + 7, Tokens.textTertiary)

            var tx = left + 100
            for ((label, colour) in listOf(
                "primary" to p.textPrimary,
                "secondary" to p.textSecondary,
                "tertiary" to p.textTertiary,
            )) {
                val ratio = Contrast.ratio(colour, surface)
                graphics.flat("0:41.2", tx, y + 7, colour)
                val note = String.format(Locale.ROOT, "%.2f", ratio)
                graphics.flat(note, tx + 40, y + 7, if (ratio >= Contrast.AA) Tokens.textTertiary else FAIL)
                tx += 120
            }
            y += 26
        }

        // Borders and accent.
        val borderTop = y + Tokens.SPACE_16
        graphics.label("BORDERS  ·  ACCENT", left, borderTop, Tokens.textSecondary)
        var bx = left
        for ((name, colour) in listOf(
            "subtle" to p.borderSubtle,
            "default" to p.borderDefault,
            "strong" to p.borderStrong,
        )) {
            graphics.fill(bx, borderTop + Tokens.SPACE_12, bx + SWATCH, borderTop + Tokens.SPACE_12 + SWATCH_H, p.surfaceRaised)
            DevicePixels.hairlineBox(graphics, bx, borderTop + Tokens.SPACE_12, SWATCH, SWATCH_H, colour)
            graphics.flat(name, bx, borderTop + Tokens.SPACE_12 + SWATCH_H + 4, Tokens.textTertiary)
            bx += SWATCH + Tokens.SPACE_8
        }
        graphics.fill(bx, borderTop + Tokens.SPACE_12, bx + SWATCH, borderTop + Tokens.SPACE_12 + SWATCH_H, Tokens.accent)
        graphics.flat("accent", bx + 6, borderTop + Tokens.SPACE_12 + 10, Tokens.accentText)
    }

    // --- Motion -----------------------------------------------------------------------------

    private fun motionPage(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        graphics.label("CURVES  ·  PLOTTED, AND TRAVELLED", left, top, Tokens.textSecondary)

        val curves = listOf(
            "standard" to Easing.STANDARD,
            "entrance" to Easing.ENTRANCE,
            "exit" to Easing.EXIT,
            "linear" to Easing.LINEAR,
        )

        var x = left
        val plotY = top + Tokens.SPACE_16
        for ((name, easing) in curves) {
            graphics.fill(x, plotY, x + PLOT, plotY + PLOT, Tokens.surfaceRaised)
            DevicePixels.hairlineBox(graphics, x, plotY, PLOT, PLOT, Tokens.borderDefault)

            // The curve, one dot per horizontal pixel. `t` is time, the vertical axis is the eased
            // value, so a decelerating curve leans up and to the left exactly as its name says.
            for (step in 0 until PLOT) {
                val t = step / (PLOT - 1f)
                val value = easing.ease(t).coerceIn(0f, 1f)
                val py = plotY + PLOT - 1 - Math.round(value * (PLOT - 1))
                graphics.fill(x + step, py, x + step + 1, py + 1, Tokens.textPrimary)
            }
            graphics.flat(name, x, plotY + PLOT + 4, Tokens.textTertiary)
            x += PLOT + Tokens.SPACE_16
        }

        // The same four curves, actually running. Everything here is a function of Clock.nowMs, so
        // this loop is identical at 60 and 240 fps and stops dead under reduce-motion.
        val trackTop = plotY + PLOT + Tokens.SPACE_32
        graphics.label("LIVE  ·  ${if (Motion.ambientEnabled()) "2000ms LOOP" else "HELD — REDUCE MOTION"}", left, trackTop, Tokens.textSecondary)

        var y = trackTop + Tokens.SPACE_16
        val trackWidth = 320
        val phase = if (Motion.ambientEnabled()) ((Clock.nowMs % LOOP_MS) / LOOP_MS).toFloat() else 0f
        for ((name, easing) in curves) {
            DevicePixels.hairlineH(graphics, left, y + 5, trackWidth, Tokens.borderSubtle)
            // Out and back, so the curve is read in both directions without a snap at the seam.
            val t = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            val dot = left + Math.round(easing.ease(t) * (trackWidth - DOT))
            graphics.fill(dot, y, dot + DOT, y + DOT, Tokens.textPrimary)
            graphics.flat(name, left + trackWidth + Tokens.SPACE_12, y, Tokens.textTertiary)
            y += Tokens.SPACE_16
        }

        // The spring, flipped on a timer so the overshoot is visible without an input.
        val springTop = y + Tokens.SPACE_16
        graphics.label("SPRING  ·  ~1.06 OVERSHOOT", left, springTop, Tokens.textSecondary)
        if (Clock.nowMs - knobFlippedAt > SPRING_FLIP_MS) {
            knobFlippedAt = Clock.nowMs
            knob.springTo(if (knob.target > 0.5f) 0f else 1f, Motion.BASE)
        }
        val springY = springTop + Tokens.SPACE_16
        DevicePixels.hairlineH(graphics, left, springY + 5, trackWidth, Tokens.borderSubtle)
        // Not clamped: the overshoot past the end of the track is the thing being demonstrated.
        val knobX = left + Math.round(knob.value * (trackWidth - DOT))
        graphics.fill(knobX, springY, knobX + DOT, springY + DOT, Tokens.accent)
        graphics.flat(
            String.format(Locale.ROOT, "%.3f", knob.value),
            left + trackWidth + Tokens.SPACE_12, springY, Tokens.textTertiary,
        )

        val ladder = "instant 80  ·  fast 140  ·  base 220  ·  slow 360  ·  ambient 1200"
        graphics.flat(ladder, left, springY + Tokens.SPACE_24, Tokens.textTertiary)
    }

    // --- Density ----------------------------------------------------------------------------

    private fun densityPage(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        val window = minecraft.window
        graphics.label("DEVICE SCALE", left, top, Tokens.textSecondary)

        var y = top + Tokens.SPACE_16
        // The nominal scale and the derived one, side by side, because the whole point is that they
        // differ. If the two derived rows ever read exactly the nominal value at a window size that
        // does not divide evenly, the per-axis derivation has been lost somewhere.
        val rows = listOf(
            "framebuffer" to "${window.width} x ${window.height}",
            "gui scaled" to "${window.guiScaledWidth} x ${window.guiScaledHeight}",
            "nominal gui scale" to "${window.guiScale}",
            "derived scale x" to String.format(Locale.ROOT, "%.4f", Density.scaleX),
            "derived scale y" to String.format(Locale.ROOT, "%.4f", Density.scaleY),
            "hairline" to "${Density.hairline} device px",
        )
        for ((name, value) in rows) {
            graphics.flat(name, left, y, Tokens.textTertiary)
            graphics.flat(value, left + 160, y, Tokens.textPrimary)
            y += Tokens.SPACE_12
        }

        // The comparison that justifies the whole device-pixel path. At GUI scale 1 these two look
        // identical; at scale 3 or 4 the lower one is a slab and the upper one is still a hairline.
        val lineTop = y + Tokens.SPACE_16
        graphics.label("HAIRLINE  ·  DEVICE PIXEL vs GUI PIXEL", left, lineTop, Tokens.textSecondary)
        DevicePixels.hairlineH(graphics, left, lineTop + Tokens.SPACE_16, 260, Tokens.textPrimary)
        graphics.flat("1 device px", left + 272, lineTop + Tokens.SPACE_12, Tokens.textTertiary)
        graphics.fill(left, lineTop + Tokens.SPACE_24 + 4, left + 260, lineTop + Tokens.SPACE_24 + 5, Tokens.textPrimary)
        graphics.flat("1 gui px", left + 272, lineTop + Tokens.SPACE_24, Tokens.textTertiary)

        val boxTop = lineTop + Tokens.SPACE_48
        DevicePixels.hairlineBox(graphics, left, boxTop, 120, 40, Tokens.borderStrong)
        graphics.flat("device-pixel box", left + Tokens.SPACE_8, boxTop + 16, Tokens.textSecondary)

        // Spacing scale.
        val spacingTop = boxTop + Tokens.SPACE_48 + Tokens.SPACE_16
        graphics.label("SPACING", left, spacingTop, Tokens.textSecondary)
        var sx = left
        for (step in Tokens.SPACING) {
            graphics.fill(sx, spacingTop + Tokens.SPACE_16, sx + step, spacingTop + Tokens.SPACE_16 + 8, Tokens.textSecondary)
            graphics.flat(step.toString(), sx, spacingTop + Tokens.SPACE_16 + 12, Tokens.textTertiary)
            sx += step + Tokens.SPACE_16
        }

        // Radii are squares until the sprite sheet lands in phase 2 — labelled as such rather than
        // quietly drawn wrong, so this screen never flatters the implementation.
        val radiusTop = spacingTop + Tokens.SPACE_48
        graphics.label("RADII  ·  SQUARE UNTIL THE SHEET LANDS", left, radiusTop, Tokens.textSecondary)
        var rx = left
        for (radius in intArrayOf(Tokens.RADIUS_XS, Tokens.RADIUS_SM, Tokens.RADIUS_MD, Tokens.RADIUS_LG, Tokens.RADIUS_XL)) {
            graphics.fill(rx, radiusTop + Tokens.SPACE_16, rx + 40, radiusTop + Tokens.SPACE_16 + 32, Tokens.surfaceRaised)
            DevicePixels.hairlineBox(graphics, rx, radiusTop + Tokens.SPACE_16, 40, 32, Tokens.borderDefault)
            graphics.flat("r$radius", rx + 4, radiusTop + Tokens.SPACE_16 + 12, Tokens.textTertiary)
            rx += 40 + Tokens.SPACE_12
        }
    }

    // --- HUD --------------------------------------------------------------------------------

    /**
     * The HUD, fed a scripted run.
     *
     * Drawn twice at two GUI scales side by side, because the two things most likely to be wrong are
     * both scale-dependent: the hairline border and the fixed card width against variable text.
     */
    private fun hudPage(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        val elapsed = previewTime()
        val snapshot = HudPreview.at(elapsed)

        graphics.label("SCRIPTED RUN  ·  %.1fs OF %.0fs".format(Locale.ROOT, (elapsed % HudPreview.CYCLE_MS) / 1000.0, HudPreview.CYCLE_MS / 1000.0), left, top, Tokens.textSecondary)

        // The card over a mid grey rather than over the screen background: the HUD's whole backdrop
        // problem is that it sits on something bright and busy, and previewing it on a near-black
        // panel would flatter the scrim into looking like it works.
        val stageTop = top + Tokens.SPACE_16
        val stageWidth = HudRoot.WIDTH + Tokens.SPACE_32
        graphics.fill(left, stageTop, left + stageWidth, stageTop + 190, MID)
        previewHud.draw(graphics, font, snapshot, left + Tokens.SPACE_16, stageTop + Tokens.SPACE_16)

        // The same card again at double size, for reading the layout and checking that text still
        // fits its box. Note what it does *not* show: under a scaled pose DevicePixels bails to its
        // one-GUI-pixel fallback, so the borders here are the fallback path, not the device-pixel one.
        // Judging hairline thickness needs the game's own GUI scale changed, not this.
        val zoomX = left + stageWidth + Tokens.SPACE_24
        graphics.label("AT 2x", zoomX, top, Tokens.textSecondary)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(zoomX.toFloat(), stageTop.toFloat())
        pose.scale(2f, 2f)
        graphics.fill(0, 0, stageWidth, 190, MID)
        // A second HUD instance would be needed for correct animation state here; reusing the same one
        // is deliberate, so both cards show exactly the same frame of the same script.
        previewHud.draw(graphics, font, snapshot, Tokens.SPACE_16, Tokens.SPACE_16)
        pose.popMatrix()

        val notes = "scrim is the only backdrop — blurBeforeThisStratum would blur the hotbar too"
        graphics.flat(notes, left, stageTop + 200, Tokens.textTertiary)
    }

    /** Preview time, honouring the hold and the manual step. */
    private fun previewTime(): Double =
        (if (previewPaused) previewHeldAt else Clock.nowMs) + previewOffset

    // --- Input ------------------------------------------------------------------------------

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            GLFW.GLFW_KEY_1 -> page = Page.COLOUR
            GLFW.GLFW_KEY_2 -> page = Page.MOTION
            GLFW.GLFW_KEY_3 -> page = Page.DENSITY
            GLFW.GLFW_KEY_4 -> page = Page.HUD
            GLFW.GLFW_KEY_LEFT -> page = Page.entries[(page.ordinal + Page.entries.size - 1) % Page.entries.size]
            GLFW.GLFW_KEY_RIGHT -> page = Page.entries[(page.ordinal + 1) % Page.entries.size]
            GLFW.GLFW_KEY_T -> dark = !dark
            GLFW.GLFW_KEY_M -> Motion.reduceMotion = !Motion.reduceMotion
            // Hold the script so a single frame of an animation can be studied, and step it by a
            // quarter second either way. A 400ms pulse is otherwise over before it can be judged.
            GLFW.GLFW_KEY_SPACE -> {
                previewPaused = !previewPaused
                if (previewPaused) previewHeldAt = Clock.nowMs
            }
            GLFW.GLFW_KEY_COMMA -> previewOffset -= STEP_MS
            GLFW.GLFW_KEY_PERIOD -> previewOffset += STEP_MS
            else -> return super.keyPressed(event)
        }
        return true
    }

    /** The gallery is a tool, not a menu — it should not stop the world behind it. */
    override fun isPauseScreen(): Boolean = false

    // --- Drawing helpers --------------------------------------------------------------------

    private fun GuiGraphicsExtractor.flat(str: String, x: Int, y: Int, colour: Int) =
        text(font, str, x, y, colour, false)

    /**
     * An 11px uppercase label with tracking, drawn one glyph at a time.
     *
     * The vanilla bitmap font has no letter-spacing control, so tracking has to be applied by hand —
     * which is exactly the kind of thing the bundled TTF removes. Until then this is what the label
     * style costs, and seeing it here rather than reading about it is the point of the gallery.
     */
    private fun GuiGraphicsExtractor.label(str: String, x: Int, y: Int, colour: Int) {
        var cursor = x.toFloat()
        for (i in str.indices) {
            val glyph = str.substring(i, i + 1)
            text(font, glyph, Math.round(cursor), y, colour, false)
            cursor += font.width(glyph) + Tokens.TRACKING_LABEL
        }
    }

    private fun hex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    private companion object {
        const val SWATCH = 72
        const val SWATCH_H = 40
        const val PLOT = 72
        const val DOT = 10
        const val LOOP_MS = 2000.0
        const val SPRING_FLIP_MS = 1200.0
        const val STEP_MS = 250.0

        /** The stage the HUD preview sits on — a mid grey standing in for a lit dungeon. */
        val MID = 0xFF6E7378.toInt()

        /**
         * The one colour in this UI that is not in the ramp, and it is not part of the design: it
         * marks a failing contrast measurement on this dev screen only. A number that has to shout
         * cannot do it in greyscale, and a gallery that reported a failure in tertiary grey would
         * hide exactly the thing it exists to surface.
         */
        val FAIL = 0xFFFF4444.toInt()
    }
}
