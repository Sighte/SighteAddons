package sighteaddons.ui.screens

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import sighteaddons.ClearPopup
import sighteaddons.RoomHistory
import sighteaddons.StormHud
import sighteaddons.StormTimer
import sighteaddons.ui.components.Anim
import sighteaddons.ui.components.Badge
import sighteaddons.ui.components.Button
import sighteaddons.ui.components.Controls
import sighteaddons.ui.components.EmptyState
import sighteaddons.ui.components.Labels
import sighteaddons.ui.components.Nav
import sighteaddons.ui.components.Popover
import sighteaddons.ui.components.ProgressBar
import sighteaddons.ui.components.Segmented
import sighteaddons.ui.components.Slider
import sighteaddons.ui.components.Sparkline
import sighteaddons.ui.components.Stepper
import sighteaddons.ui.components.Table
import sighteaddons.ui.components.TextField
import sighteaddons.ui.components.Tooltip
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.motion.Spring
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
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
        CONTROLS("controls"),
        INPUT("input"),
        NAV("nav"),
        DATA("data"),
        OVERLAY("overlay"),
    }

    private var page = Page.COLOUR

    /**
     * The two pieces of process-wide state this screen writes, as they were before it opened.
     *
     * `T` swaps [Tokens.palette] so the two ramps can be compared without restarting, and `M` holds
     * every animation still. Neither is a player setting and neither belongs to anything outside this
     * screen — but both are global, and leaving either changed is not a cosmetic slip. The light ramp's
     * `shadow` is `#3C3F45` and its `textPrimary` `#0A0A0B`: pressing `T`, closing the gallery and
     * walking into a dungeon would draw the HUD card, the clear popup and the storm countdown as
     * near-black text on a mid-grey scrim for the rest of the session — 1.32:1, a card that is present
     * and empty — and nobody would connect that to a keypress in a dev screen half an hour earlier.
     *
     * Captured at construction and put back in [removed], which is the one exit every way of leaving
     * goes through. `UiComponentsTest` already restores the palette in its own teardown for the same
     * reason; the screen that actually ships the keys had no such thing.
     */
    private val entryPalette = Tokens.palette
    private val entryReduceMotion = Motion.reduceMotion

    /** Starts on whichever ramp the session was already using, so `T` is a comparison and not a jump. */
    private var dark = entryPalette === Palette.DARK
    private val knob = Spring(0f)
    private var knobFlippedAt = 0.0

    /** The preview's own HUD instance, so its animation stamps never touch the live overlay's. */
    private val previewHud = HudRoot()
    private var previewPaused = false
    private var previewHeldAt = 0.0
    private var previewOffset = 0.0

    // --- Live specimens ---------------------------------------------------------------------
    //
    // Every component below is also shown in a frozen matrix of its states, which is the part that can
    // actually be reviewed: a hover fade caught mid-flight is a screenshot nobody can reproduce. These
    // few exist alongside that because three things cannot be judged from a still — how *fast* a fade
    // is, whether a press feels attached to the cursor, and whether a caret blinks at a rate that
    // reads as a caret rather than as flicker.

    private val anim = Anim()

    /** Whether the live button is currently held. */
    private var pressed = false

    /** The live segmented control's selection, and the thumb chasing it. */
    private var segment = 0
    private val segmentTravel = Spring(0f)

    /** The live rail's selection. */
    private var railItem = 0

    /** The live sort header's direction, so the caret's rotation can be triggered by hand. */
    private var sortDesc = true

    /** The live text field. Never typed into — see [keyPressed], where the digits belong to the pages. */
    private val field = TextField.Edit("Water Board")
    private val secret = TextField.Edit("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")

    // Where the live specimens were drawn last frame, so a click lands on what is on screen. The same
    // rule the settings screen keeps: hit testing re-derives the drawing layout rather than guessing
    // at it, and here it re-reads it.
    private var liveButtonX = 0
    private var liveButtonY = 0
    private var railTop = 0
    private var segmentLeft = 0
    private var segmentTop = 0
    private var headerTopY = 0

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
            Page.CONTROLS -> controlsPage(graphics, left, top, mouseX, mouseY)
            Page.INPUT -> inputPage(graphics, left, top, mouseX, mouseY)
            Page.NAV -> navPage(graphics, left, top, mouseX, mouseY)
            Page.DATA -> dataPage(graphics, left, top, mouseX, mouseY)
            Page.OVERLAY -> overlayPage(graphics, left, top)
        }

        val footer = if (page == Page.HUD || page == Page.OVERLAY) {
            "1-9 page  ·  T theme  ·  M reduce motion  ·  space ${if (previewPaused) "run" else "hold"}  ·  , . step  ·  esc close"
        } else {
            "1-9 page  ·  T theme  ·  M reduce motion (${if (Motion.reduceMotion) "on" else "off"})  ·  esc close"
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
                val passes = ratio >= Contrast.AA
                graphics.flat("0:41.2", tx, y + 7, colour)
                // A failure is spelled, not coloured. It used to be red — the last hue anywhere in
                // this UI, and it sat on the one screen whose whole job is to prove there is none.
                // Worse, the readers a contrast floor exists for are precisely the ones a red number
                // does not reach; the word does.
                val note = String.format(Locale.ROOT, "%.2f", ratio) + if (passes) "" else " FAIL"
                graphics.flat(note, tx + 40, y + 7, if (passes) Tokens.textTertiary else Tokens.textPrimary)
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
        graphics.fill(left, stageTop, left + stageWidth, stageTop + 190, Tokens.PREVIEW_STAGE)
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
        graphics.fill(0, 0, stageWidth, 190, Tokens.PREVIEW_STAGE)
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

    // --- Controls ---------------------------------------------------------------------------

    /**
     * Buttons, switches, chips and badges, every one of them in every state it has.
     *
     * The matrix is the point. A component reviewed only in the state it happens to be in when the
     * screen is opened is a component whose disabled variant nobody has ever seen — and disabled and
     * pressed are exactly the two that go wrong, because both are reached by a code path that a
     * screenshot of a working screen never takes.
     */
    private fun controlsPage(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val word = "save"
        val buttonWidth = Button.width(font, word)
        val step = buttonWidth + Tokens.SPACE_12
        val right = left + step * 5 + Tokens.SPACE_32

        graphics.label("BUTTON", left, top, Tokens.textSecondary)
        val captions = listOf("idle", "hover", "press", "off", "focus")
        for (i in captions.indices) {
            graphics.flat(captions[i], left + i * step, top + Tokens.SPACE_12, Tokens.textTertiary)
        }

        var y = top + Tokens.SPACE_24
        for ((name, variant) in listOf(
            "primary" to Button.Variant.PRIMARY,
            "secondary" to Button.Variant.SECONDARY,
            "ghost" to Button.Variant.GHOST,
        )) {
            Button.draw(graphics, font, left, y, buttonWidth, Button.HEIGHT, word, variant)
            Button.draw(graphics, font, left + step, y, buttonWidth, Button.HEIGHT, word, variant, hover = 1f)
            Button.draw(graphics, font, left + step * 2, y, buttonWidth, Button.HEIGHT, word, variant, hover = 1f, press = 1f)
            Button.draw(graphics, font, left + step * 3, y, buttonWidth, Button.HEIGHT, word, variant, enabled = false)
            Button.draw(graphics, font, left + step * 4, y, buttonWidth, Button.HEIGHT, word, variant, focus = 1f)
            graphics.flat(name, left + step * 5, y + Tokens.SPACE_6, Tokens.textTertiary)
            y += Button.HEIGHT + Tokens.SPACE_8
        }

        // One that is actually wired to the mouse. The matrix above says what each state looks like;
        // this says how long the fade takes, which no still can.
        liveButtonX = left
        liveButtonY = y + Tokens.SPACE_4
        val overLive = hit(mouseX, mouseY, liveButtonX, liveButtonY, buttonWidth, Button.HEIGHT)
        val livePress = anim.of("live.press")
        livePress.animateTo(if (pressed && overLive) 1f else 0f, Motion.INSTANT, Easing.STANDARD, Motion.Kind.OPACITY)
        Button.draw(
            graphics, font, liveButtonX, liveButtonY, buttonWidth, Button.HEIGHT, word,
            Button.Variant.SECONDARY,
            hover = Controls.hover(anim.of("live.hover"), overLive),
            press = livePress.value,
        )
        graphics.flat("live — hover it, hold it", liveButtonX + step, liveButtonY + Tokens.SPACE_6, Tokens.textTertiary)

        // --- switches, chips, badges, on the right ---
        graphics.label("TOGGLE", right, top, Tokens.textSecondary)
        var ty = top + Tokens.SPACE_16
        for ((name, state) in listOf("off" to 0f, "mid" to 0.5f, "on" to 1f)) {
            Controls.toggle(graphics, right, ty, Controls.TOGGLE_HEIGHT, state, enabled = true)
            graphics.flat(name, right + Controls.TOGGLE_WIDTH + Tokens.SPACE_8, ty + Tokens.SPACE_6, Tokens.textTertiary)
            ty += Controls.TOGGLE_HEIGHT + Tokens.SPACE_6
        }
        Controls.toggle(graphics, right, ty, Controls.TOGGLE_HEIGHT, 1f, enabled = false)
        graphics.flat("disabled", right + Controls.TOGGLE_WIDTH + Tokens.SPACE_8, ty + Tokens.SPACE_6, Tokens.textTertiary)

        val chipTop = ty + Tokens.SPACE_32
        graphics.label("CHIP  ·  BADGE", right, chipTop, Tokens.textSecondary)
        var cx = right
        for ((state, hover) in listOf(0f to 0f, 0f to 1f, 1f to 0f)) {
            Controls.chip(graphics, font, cx, chipTop + Tokens.SPACE_16, CHIP_H, "puzzle", 14, state, hover)
            cx += Controls.chipWidth(font, "puzzle", 14) + Tokens.SPACE_6
        }
        // The count is -1 for "no count": a chip that has nothing to advertise says nothing rather
        // than a zero, which would read as a filter that matches no rooms.
        Controls.chip(graphics, font, cx, chipTop + Tokens.SPACE_16, CHIP_H, "no count", -1, 0f, 0f)

        var bx = right
        val badgeY = chipTop + Tokens.SPACE_32 + Tokens.SPACE_6
        Badge.draw(graphics, font, bx, badgeY, "PB", Badge.Style.SOLID)
        bx += Badge.width(font, "PB") + Tokens.SPACE_6
        Badge.draw(graphics, font, bx, badgeY, "NEW", Badge.Style.OUTLINE)
        bx += Badge.width(font, "NEW") + Tokens.SPACE_6
        Badge.draw(graphics, font, bx, badgeY, "OFF", Badge.Style.SOLID, enabled = false)
        bx += Badge.width(font, "OFF") + Tokens.SPACE_6
        Badge.draw(graphics, font, bx, badgeY, "A VERY LONG ONE", Badge.Style.OUTLINE)

        graphics.flat(
            "solid vs outline, never two greys — 1.27:1 apart is not a state",
            right, badgeY + Tokens.SPACE_20, Tokens.textTertiary,
        )
    }

    // --- Input ------------------------------------------------------------------------------

    /**
     * Text fields, steppers, sliders and bars — everything a value is edited through.
     *
     * The two caret rows are drawn deliberately as `on` and `off` side by side rather than as one
     * blinking specimen. A reviewer cannot hold a blink still, and the half nobody ever looks at is
     * the one where the caret is missing and the field looks like it lost focus.
     */
    private fun inputPage(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val fieldWidth = 150
        val right = left + fieldWidth + 150

        graphics.label("TEXT FIELD", left, top, Tokens.textSecondary)
        var y = top + Tokens.SPACE_16

        fun row(caption: String, build: () -> Unit) {
            build()
            graphics.flat(caption, left + fieldWidth + Tokens.SPACE_12, y + Tokens.SPACE_6, Tokens.textTertiary)
            y += TextField.HEIGHT + Tokens.SPACE_8
        }

        row("empty · placeholder") {
            TextField.draw(
                graphics, font, left, y, fieldWidth, TextField.HEIGHT, TextField.Edit(),
                placeholder = "room name",
            )
        }
        row("hovered") {
            TextField.draw(
                graphics, font, left, y, fieldWidth, TextField.HEIGHT, TextField.Edit("Water Board"),
                hover = 1f,
            )
        }
        row("focused · caret on") {
            val edit = TextField.Edit("Water Board")
            edit.placeCaret(5)
            TextField.draw(graphics, font, left, y, fieldWidth, TextField.HEIGHT, edit, focus = 1f, caret = 1f)
        }
        row("focused · caret off") {
            val edit = TextField.Edit("Water Board")
            edit.placeCaret(5)
            TextField.draw(graphics, font, left, y, fieldWidth, TextField.HEIGHT, edit, focus = 1f, caret = 0f)
        }
        row("selection") {
            val edit = TextField.Edit("Water Board")
            edit.placeCaret(0)
            edit.move(5, extend = true)
            TextField.draw(graphics, font, left, y, fieldWidth, TextField.HEIGHT, edit, focus = 1f)
        }
        row("disabled") {
            TextField.draw(
                graphics, font, left, y, fieldWidth, TextField.HEIGHT, TextField.Edit("Water Board"),
                enabled = false,
            )
        }
        row("overlong · scrolled to the caret") {
            val edit = TextField.Edit("Thunder · Bigfoot · Beehive · Water Board · Boulder")
            TextField.draw(graphics, font, left, y, fieldWidth, TextField.HEIGHT, edit, focus = 1f, caret = 1f)
        }
        row("live · real blink rate") {
            TextField.draw(
                graphics, font, left, y, fieldWidth, TextField.HEIGHT, field,
                focus = 1f, caret = TextField.caretOn(true),
            )
        }

        // The masked variant, which is the only reason this component exists at all: `Config.hypixelKey`
        // has no UI today, so `SecretApi` has never run once in the game.
        graphics.label("MASKED  ·  THE HYPIXEL KEY", right, top, Tokens.textSecondary)
        var my = top + Tokens.SPACE_16
        TextField.draw(
            graphics, font, right, my, fieldWidth + Tokens.SPACE_32, TextField.HEIGHT, secret,
            placeholder = "paste your key", mask = TextField.Mask.DOTS,
        )
        graphics.flat("masked", right + fieldWidth + Tokens.SPACE_48, my + Tokens.SPACE_6, Tokens.textTertiary)
        my += TextField.HEIGHT + Tokens.SPACE_8
        // Its own edit, not the one above: the two rows measure the same string differently — one by
        // the mask's advance, one by the font — and sharing the state would leave both scrolled to
        // whichever of the two drew last.
        TextField.draw(
            graphics, font, right, my, fieldWidth + Tokens.SPACE_32, TextField.HEIGHT,
            TextField.Edit(secret.text),
            mask = TextField.Mask.DOTS, revealed = true, focus = 1f, caret = 1f,
        )
        graphics.flat("revealed", right + fieldWidth + Tokens.SPACE_48, my + Tokens.SPACE_6, Tokens.textTertiary)
        my += TextField.HEIGHT + Tokens.SPACE_8
        TextField.draw(
            graphics, font, right, my, fieldWidth + Tokens.SPACE_32, TextField.HEIGHT, TextField.Edit(),
            placeholder = "paste your key", mask = TextField.Mask.DOTS,
        )
        graphics.flat("empty · masked", right + fieldWidth + Tokens.SPACE_48, my + Tokens.SPACE_6, Tokens.textTertiary)

        // --- stepper and slider ---
        val stepperTop = my + Tokens.SPACE_32
        graphics.label("STEPPER  ·  A FEW TICKS AT A TIME", right, stepperTop, Tokens.textSecondary)
        val ticks = "138 ticks · 6.90s"
        val stepperWidth = Stepper.width(font, ticks)
        var sy = stepperTop + Tokens.SPACE_16
        for ((caption, hovers) in listOf(
            "idle" to (0f to 0f),
            "hover +" to (0f to 1f),
            "hover −" to (1f to 0f),
        )) {
            Stepper.draw(
                graphics, font, right, sy, stepperWidth, Stepper.HEIGHT, ticks,
                fraction = 138f / 400f, minusHover = hovers.first, plusHover = hovers.second,
            )
            graphics.flat(caption, right + stepperWidth + Tokens.SPACE_12, sy + Tokens.SPACE_4, Tokens.textTertiary)
            sy += Stepper.HEIGHT + Tokens.SPACE_8
        }
        Stepper.draw(graphics, font, right, sy, stepperWidth, Stepper.HEIGHT, ticks, 138f / 400f, enabled = false)
        graphics.flat("disabled", right + stepperWidth + Tokens.SPACE_12, sy + Tokens.SPACE_4, Tokens.textTertiary)

        val sliderTop = y + Tokens.SPACE_16
        graphics.label("SLIDER  ·  A SHAPE, NOT A NUMBER", left, sliderTop, Tokens.textSecondary)
        var ly = sliderTop + Tokens.SPACE_16
        val sliderWidth = 120
        for ((caption, value) in listOf("0" to 0f, "45" to 0.45f, "100" to 1f)) {
            Slider.draw(graphics, left, ly, sliderWidth, Slider.HEIGHT, value)
            graphics.flat(caption, left + sliderWidth + Tokens.SPACE_12, ly + Tokens.SPACE_4, Tokens.textTertiary)
            ly += Slider.HEIGHT + Tokens.SPACE_6
        }
        Slider.draw(graphics, left, ly, sliderWidth, Slider.HEIGHT, 0.6f, hover = 1f, active = true)
        graphics.flat("held", left + sliderWidth + Tokens.SPACE_12, ly + Tokens.SPACE_4, Tokens.textTertiary)
        ly += Slider.HEIGHT + Tokens.SPACE_6
        Slider.draw(graphics, left, ly, sliderWidth, Slider.HEIGHT, 0.6f, enabled = false)
        graphics.flat("disabled", left + sliderWidth + Tokens.SPACE_12, ly + Tokens.SPACE_4, Tokens.textTertiary)
        ly += Slider.HEIGHT + Tokens.SPACE_6
        // Live, so the fraction the cursor asks for can be checked against where the knob lands —
        // including at the far right, where a slider that measures the wrong span never reaches 1.0.
        val liveFraction = if (hit(mouseX, mouseY, left, ly, sliderWidth, Slider.HEIGHT)) {
            Slider.fractionAt(left, sliderWidth, mouseX)
        } else {
            0.5f
        }
        Slider.draw(graphics, left, ly, sliderWidth, Slider.HEIGHT, liveFraction, hover = 1f)
        graphics.flat(
            "live  ·  ${Slider.valueAt(0, 100, liveFraction)}",
            left + sliderWidth + Tokens.SPACE_12, ly + Tokens.SPACE_4, Tokens.textTertiary,
        )

        // --- bars, under the stepper so neither column runs off the bottom at gui scale 2 ---
        val barTop = sy + Tokens.SPACE_32
        graphics.label("PROGRESS  ·  BAR ABOVE TWELVE, SEGMENTS BELOW", right, barTop, Tokens.textSecondary)
        var by = barTop + Tokens.SPACE_16
        for ((caption, value) in listOf("0" to 0f, "1%" to 0.01f, "40%" to 0.4f, "99%" to 0.99f, "100%" to 1f)) {
            ProgressBar.draw(graphics, right, by, 120, ProgressBar.HEIGHT, value)
            graphics.flat(caption, right + 120 + Tokens.SPACE_12, by - 2, Tokens.textTertiary)
            by += Tokens.SPACE_12
        }
        Surface.segments(graphics, right, by + 2, 120, ProgressBar.HEIGHT, 6, 4f, Tokens.accent, Tokens.borderDefault)
        graphics.flat("4 of 6, countable", right + 120 + Tokens.SPACE_12, by, Tokens.textTertiary)
    }

    // --- Navigation -------------------------------------------------------------------------

    /** The rail, the segmented switch, the section header, and the overlays that float above them. */
    private fun navPage(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        graphics.label("RAIL  ·  LIVE", left, top, Tokens.textSecondary)
        railTop = top + Tokens.SPACE_16
        val names = listOf("hud", "chat", "rooms", "debug")
        val hovered = Nav.rowAt(railTop, names.size, mouseY).takeIf { mouseX in left until (left + Nav.WIDTH) } ?: -1
        for (i in names.indices) {
            val itemY = Nav.rowY(railTop, i)
            val select = anim.of("nav.sel.$i", if (i == railItem) 1f else 0f)
            select.animateTo(if (i == railItem) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            Nav.item(
                graphics, font, left, itemY, Nav.WIDTH, Nav.ROW, names[i],
                select.value,
                Controls.hover(anim.of("nav.hover.$i"), hovered == i && i != railItem),
            )
        }
        Nav.divider(graphics, left + Nav.WIDTH + Tokens.SPACE_12, railTop, Nav.ROW * names.size)

        val segLeft = left + Nav.WIDTH + Tokens.SPACE_32
        graphics.label("SEGMENTED  ·  LIVE", segLeft, top, Tokens.textSecondary)
        segmentLeft = segLeft
        segmentTop = top + Tokens.SPACE_16
        segmentTravel.springTo(segment.toFloat(), Motion.BASE)
        val segHover = Segmented.indexAt(font, SEGMENTS, segmentLeft, mouseX)
            .takeIf { mouseY in segmentTop until (segmentTop + Segmented.HEIGHT) } ?: -1
        Segmented.draw(
            graphics, font, segmentLeft, segmentTop, Segmented.HEIGHT,
            SEGMENTS, segment, segmentTravel.value, segHover,
        )
        val segWidth = Segmented.width(font, SEGMENTS)
        Segmented.draw(
            graphics, font, segmentLeft, segmentTop + Segmented.HEIGHT + Tokens.SPACE_8, Segmented.HEIGHT,
            SEGMENTS, 0, 0f, enabled = false,
        )
        graphics.flat("disabled", segmentLeft + segWidth + Tokens.SPACE_12, segmentTop + Segmented.HEIGHT + Tokens.SPACE_12, Tokens.textTertiary)

        // The label style, and what it costs. The tracked width is what an underline or a right-hand
        // neighbour has to be laid out from; `font.width` alone is short by the tracking.
        // Below both columns, not below the taller-looking one: the rail is four rows and the switch is
        // two, and hard-coding whichever is longer today is how a section lands on top of a control the
        // next time one of them grows an entry.
        val headerTop = maxOf(
            Nav.rowY(railTop, names.size),
            segmentTop + Segmented.HEIGHT * 2 + Tokens.SPACE_8,
        ) + Tokens.SPACE_32
        val headerWidth = 300
        Labels.sectionHeader(graphics, font, "SECTION HEADER", left, headerTop, headerWidth, "12 rooms")
        Labels.sectionHeader(graphics, font, "NO META", left, headerTop + Tokens.SPACE_16, headerWidth)
        graphics.flat(
            "tracked ${Labels.width(font, "SECTION HEADER")}px  ·  untracked ${font.width("SECTION HEADER")}px",
            left, headerTop + Tokens.SPACE_32, Tokens.textTertiary,
        )
        graphics.flat(
            "truncated to 60px: \"${Labels.fit(font, "SECTION HEADER", 60)}\"",
            left, headerTop + Tokens.SPACE_32 + Tokens.SPACE_12, Tokens.textTertiary,
        )

        // --- overlays ---
        val overlayTop = headerTop + Tokens.SPACE_48 + Tokens.SPACE_16
        graphics.label("POPOVER  ·  TOOLTIP", left, overlayTop, Tokens.textSecondary)
        Popover.frame(graphics, left, overlayTop + Tokens.SPACE_16, 160, 44)
        graphics.flat("a popover", left + Popover.PADDING, overlayTop + Tokens.SPACE_16 + Popover.PADDING, Tokens.textPrimary)
        graphics.flat(
            "surfaceOverlay, E2, borderStrong",
            left + Popover.PADDING, overlayTop + Tokens.SPACE_16 + Popover.PADDING + Tooltip.LINE, Tokens.textTertiary,
        )

        // Anchored hard against the right edge, which is the case the placement arithmetic exists for:
        // a tooltip that would run off the screen flips to the other side of its anchor.
        val edgeX = width - Tokens.SPACE_8
        Tooltip.draw(
            graphics, font, edgeX, overlayTop + Tokens.SPACE_24, width, height,
            listOf("Thunder", "flipped — it would not fit to the right"),
        )
        DevicePixels.hairlineV(graphics, edgeX, overlayTop + Tokens.SPACE_16, Tokens.SPACE_24, Tokens.borderStrong)

        // And one following the cursor, so the same arithmetic can be walked into every corner.
        Tooltip.draw(
            graphics, font, mouseX, mouseY, width, height,
            listOf("live tooltip", "$mouseX, $mouseY"),
        )
    }

    // --- Data -------------------------------------------------------------------------------

    /** A table: its header, its rows, the detail one drops open, and what it says when it is empty. */
    private fun dataPage(graphics: GuiGraphicsExtractor, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val tableWidth = 300
        graphics.label("TABLE  ·  CLICK A HEADER", left, top, Tokens.textSecondary)

        headerTopY = top + Tokens.SPACE_16
        val flip = anim.of("data.sort", if (sortDesc) 1f else 0f)
        flip.animateTo(if (sortDesc) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)

        val columns = listOf(
            Triple("room", left, false),
            Triple("clear", left + 150, true),
            Triple("secrets", left + 220, true),
            Triple("last", left + tableWidth, true),
        )
        for ((index, column) in columns.withIndex()) {
            val (name, edge, rightAligned) = column
            val x0 = if (rightAligned) edge - 44 else edge
            val over = mouseX in x0..edge && mouseY in headerTopY until (headerTopY + Table.HEADER)
            Table.headerCell(
                graphics, font, name, x0, edge, headerTopY, rightAligned,
                sorted = index == 0,
                flip = flip.value,
                hover = Controls.hover(anim.of("data.head.$index"), over && index != 0),
            )
        }
        Table.divider(graphics, left, headerTopY + Tokens.SPACE_12, tableWidth)

        var y = headerTopY + Tokens.SPACE_16
        for ((caption, state) in listOf("idle" to 0, "hovered" to 1, "open" to 2)) {
            Controls.rowHighlight(
                graphics, left - Tokens.SPACE_8, y, tableWidth + Tokens.SPACE_16, Table.ROW,
                if (state == 1) 1f else 0f, state == 2,
            )
            val textY = y + (Table.ROW - Labels.CAP) / 2
            graphics.flat("Water Board", left, textY, if (state == 2) Tokens.textPrimary else Tokens.textSecondary)
            graphics.flat("0:41.2", left + 150 - font.width("0:41.2"), textY, Tokens.textPrimary)
            graphics.flat("0:19.8", left + 220 - font.width("0:19.8"), textY, Tokens.textPrimary)
            graphics.flat("today", left + tableWidth - font.width("today"), textY, Tokens.textTertiary)
            if (state == 0) {
                Badge.draw(graphics, font, left + 88, y + (Table.ROW - Badge.HEIGHT) / 2, "PB", Badge.Style.SOLID)
            }
            graphics.flat(caption, left + tableWidth + Tokens.SPACE_16, textY, Tokens.textTertiary)
            y += Table.ROW
        }

        // The detail the open row drops. Drawn twice — fully open, and halfway through — because the
        // connector's whole job is to make the second of those read as coming out of the row above.
        Table.detail(graphics, font, left, y, Table.ROW, "clear", "best 0:41.2 · 18 attempts")
        Sparkline.draw(graphics, Table.contentX(left) + 140, y + 2, 72, Table.ROW - 4, ATTEMPTS, 120)
        y += Table.ROW
        Table.detail(graphics, font, left, y, Table.ROW, "floors", "M7 0:41.2 · F7 0:44.8", open = 0.5f)
        y += Table.ROW
        graphics.flat("detail at open = 1.0 and 0.5", left + tableWidth + Tokens.SPACE_16, y - Table.ROW, Tokens.textTertiary)

        // --- empty states, side by side under the table ---
        val emptyTop = y + Tokens.SPACE_16
        graphics.label("EMPTY", left, emptyTop, Tokens.textSecondary)
        val emptyY = emptyTop + Tokens.SPACE_16
        EmptyState.draw(
            graphics, font, left, emptyY, 200,
            "no history yet", "finish a dungeon room and it lands here", "config/sighteaddons/history.jsonl",
        )
        EmptyState.draw(
            graphics, font, left + 220, emptyY, 200,
            "nothing matches this filter", "esc clears the search · click \"all\" for every room",
        )
        // Overlong, at a width that cannot hold it: truncated rather than wrapped, so the block stays
        // where it was centred instead of growing down out of the panel it was centred in.
        EmptyState.draw(
            graphics, font, left + 440, emptyY, 120,
            "a headline far longer than the space it was given",
            "and a hint that is longer still, which is the case that has to truncate",
        )
    }

    // --- Overlays ---------------------------------------------------------------------------

    /**
     * The two centred overlays — the clear popup and Storm's countdown — on a scripted boss phase.
     *
     * These are the only two pieces of this UI that are *only* reachable inside a real dungeon: a
     * personal best needs a record and a room beaten, and the countdown needs Storm. Nobody has an
     * unreleased build in an M7 to hand, so without this page the two decisions that replaced their
     * colours — a best carried by chevron, word and frame; four urgency steps carried by a count of
     * marks and an inversion — would first be seen by a player, in a fight, once.
     *
     * Both halves are here because neither answers the other's question. The script says whether the
     * fade lands and whether the steps read as an escalation *in the order they arrive*; the frozen
     * cells put the four steps side by side, which is the only way to check that each is actually
     * distinguishable from the one before it rather than merely different from a memory of it.
     */
    private fun overlayPage(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        val ms = OverlayPreview.phase(previewTime())

        graphics.label(
            "SCRIPTED  ·  %.1fs OF %.0fs".format(Locale.ROOT, ms / 1000.0, OverlayPreview.CYCLE_MS / 1000.0),
            left, top, Tokens.textSecondary,
        )
        stage(graphics, left, top + Tokens.SPACE_12, STAGE_W, STAGE_H) { w, h ->
            OverlayPreview.draw(graphics, font, w, h, ms)
        }

        val stripTop = top + Tokens.SPACE_12 + STAGE_H + Tokens.SPACE_6
        timeline(graphics, left, stripTop, ms)
        graphics.flat(OverlayPreview.caption(ms), left, stripTop + Tokens.SPACE_24, Tokens.textTertiary)

        // --- the four steps, frozen, on the right ---
        val right = left + STAGE_W + Tokens.SPACE_24
        graphics.label("STORM  ·  FOUR STEPS, FROZEN", right, top, Tokens.textSecondary)
        // Sampled out of the same script rather than built by hand, so the text beside each mark count
        // is the one the thresholds really produce at that moment.
        for ((index, at) in STORM_STEPS.withIndex()) {
            val readout = OverlayPreview.stormAt(at) ?: continue
            val cellX = right + (index % 2) * (STORM_CELL_W + Tokens.SPACE_12)
            val cellY = top + Tokens.SPACE_12 + (index / 2) * (STORM_CELL_H + Tokens.SPACE_8)
            stage(graphics, cellX, cellY, STORM_CELL_W, STORM_CELL_H) { w, h ->
                StormHud.draw(graphics, font, w, h, readout)
            }
            graphics.flat(
                "${readout.urgency.name.lowercase()}  ·  ${marks(readout.urgency)}",
                cellX, cellY + STORM_CELL_H + 2, Tokens.textTertiary,
            )
        }

        // --- the two popups, frozen at full presence, underneath ---
        //
        // Under both columns and not under the taller-looking one, the same rule the nav page keeps: the
        // scripted half is one stage and the frozen half is two rows of cells, and hard-coding whichever
        // is longer today is how a section lands on top of a specimen the next time one of them grows.
        val popupTop = maxOf(
            stripTop + Tokens.SPACE_24 + Tokens.SPACE_12,
            top + Tokens.SPACE_12 + STORM_CELL_H * 2 + Tokens.SPACE_8 + Tokens.SPACE_16,
        )
        graphics.label("POPUP  ·  A CLEAR, AND ONE THAT SET A RECORD", left, popupTop, Tokens.textSecondary)
        for ((index, popup) in OverlayPreview.POPUPS.withIndex()) {
            val cellX = left + index * (POPUP_CELL_W + Tokens.SPACE_12)
            val cellY = popupTop + Tokens.SPACE_12
            stage(graphics, cellX, cellY, POPUP_CELL_W, POPUP_CELL_H) { w, h ->
                // Held at the middle of the hold, where the entrance is over and the fade has not
                // started — the one age at which the chip is entirely itself.
                ClearPopup.drawAt(graphics, font, w, h, popup.name, popup.detail, popup.pb, FROZEN_AGE_MS)
            }
            graphics.flat(
                if (popup.pb) "personal best  ·  chevron, the word, a stronger frame" else "ordinary  ·  no mark at all",
                cellX, cellY + POPUP_CELL_H + 2, Tokens.textTertiary,
            )
        }
    }

    /** How the urgency reads without a hue, spelled out beside the specimen that shows it. */
    private fun marks(urgency: StormTimer.Urgency): String = when (urgency) {
        StormTimer.Urgency.CALM -> "1 of 3 filled"
        StormTimer.Urgency.CLOSING -> "2 of 3 filled"
        StormTimer.Urgency.IMMINENT -> "3 of 3 filled"
        StormTimer.Urgency.NOW -> "inverted, no marks"
    }

    /**
     * A stand-in screen: lit-dungeon grey with a crosshair at its centre, [w] by [h] standing in for
     * `guiScaledWidth` and `guiScaledHeight`.
     *
     * A whole small screen rather than a chip drawn at a coordinate, because *where* these two land is
     * half of what there is to review — one above the crosshair, one below, and the claim that they
     * cannot stack is only checkable if both are placed by their own arithmetic. [body] receives the
     * stage's size and draws into a pose translated to its corner, which stays a pure translation so
     * hairlines are still snapped to the device grid.
     */
    private fun stage(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, body: (Int, Int) -> Unit) {
        graphics.fill(x, y, x + w, y + h, Tokens.PREVIEW_STAGE)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        val mark = Tokens.alpha(Tokens.textPrimary, CROSSHAIR_ALPHA)
        DevicePixels.hairlineH(graphics, w / 2 - CROSSHAIR, h / 2, CROSSHAIR * 2 + 1, mark)
        DevicePixels.hairlineV(graphics, w / 2, h / 2 - CROSSHAIR, CROSSHAIR * 2 + 1, mark)
        body(w, h)
        pose.popMatrix()
    }

    /**
     * Two lanes and a playhead: when the countdown is on screen and which step it is in, when each
     * popup is up, and where in all of that the frame currently held actually sits.
     *
     * Without it the hold and the step keys are a search. With it, the whole script is one glance and
     * a single frame can be aimed at.
     */
    private fun timeline(graphics: GuiGraphicsExtractor, left: Int, top: Int, ms: Double) {
        val trackX = left + LANE_LABEL
        val trackW = STAGE_W - LANE_LABEL

        fun span(from: Double, to: Double, y: Int, colour: Int) {
            val x0 = trackX + Math.round(from / OverlayPreview.CYCLE_MS * trackW).toInt()
            val x1 = trackX + Math.round(to / OverlayPreview.CYCLE_MS * trackW).toInt()
            graphics.fill(x0, y, maxOf(x1 - 1, x0 + 1), y + LANE_H, colour)
        }

        graphics.flat("storm", left, top - 2, Tokens.textTertiary)
        DevicePixels.hairlineH(graphics, trackX, top + LANE_H / 2, trackW, Tokens.borderSubtle)
        // The three counting steps in the text ramp and the window in the accent — the same inversion
        // the chip itself performs, so the lane and the specimen say the same thing.
        span(0.0, 3_900.0, top, Tokens.textTertiary)
        span(3_900.0, 5_900.0, top, Tokens.textSecondary)
        span(5_900.0, 6_900.0, top, Tokens.textPrimary)
        span(6_900.0, OverlayPreview.STORM_END_MS, top, Tokens.accent)

        val popupY = top + LANE_H + Tokens.SPACE_6
        graphics.flat("popup", left, popupY - 2, Tokens.textTertiary)
        DevicePixels.hairlineH(graphics, trackX, popupY + LANE_H / 2, trackW, Tokens.borderSubtle)
        for (popup in OverlayPreview.POPUPS) {
            span(popup.at, popup.at + ClearPopup.LIFE_MS, popupY, Tokens.textSecondary)
        }

        val playhead = trackX + Math.round(ms / OverlayPreview.CYCLE_MS * trackW).toInt()
        DevicePixels.hairlineV(graphics, playhead, top - 3, LANE_H * 2 + Tokens.SPACE_6 + 6, Tokens.accent)
    }

    /** Whether ([mouseX], [mouseY]) is inside a rectangle. */
    private fun hit(mouseX: Int, mouseY: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    // --- Input ------------------------------------------------------------------------------

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            GLFW.GLFW_KEY_1 -> page = Page.COLOUR
            GLFW.GLFW_KEY_2 -> page = Page.MOTION
            GLFW.GLFW_KEY_3 -> page = Page.DENSITY
            GLFW.GLFW_KEY_4 -> page = Page.HUD
            GLFW.GLFW_KEY_5 -> page = Page.CONTROLS
            GLFW.GLFW_KEY_6 -> page = Page.INPUT
            GLFW.GLFW_KEY_7 -> page = Page.NAV
            GLFW.GLFW_KEY_8 -> page = Page.DATA
            GLFW.GLFW_KEY_9 -> page = Page.OVERLAY
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

    /**
     * The live specimens' input. Everything else on this screen is frozen and needs none.
     *
     * A press has to be held to be judged — whether it feels attached to the cursor is the whole
     * question — so the flag is cleared on release rather than on the next click.
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        when (page) {
            Page.CONTROLS -> {
                pressed = true
                return true
            }
            Page.NAV -> {
                val row = Nav.rowAt(railTop, 4, mouseY)
                if (row >= 0 && mouseX in Tokens.SPACE_24 until (Tokens.SPACE_24 + Nav.WIDTH)) {
                    railItem = row
                    return true
                }
                if (mouseY in segmentTop until (segmentTop + Segmented.HEIGHT)) {
                    val index = Segmented.indexAt(font, SEGMENTS, segmentLeft, mouseX)
                    if (index >= 0) {
                        segment = index
                        return true
                    }
                }
            }
            Page.DATA -> {
                if (mouseY in headerTopY until (headerTopY + Table.HEADER)) {
                    sortDesc = !sortDesc
                    return true
                }
            }
            else -> Unit
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        pressed = false
        return super.mouseReleased(event)
    }

    /**
     * Leaving, by any route — `esc`, another screen opening over this one, the game closing it.
     *
     * Both settings go back to what they were. See [entryPalette] for what one of them costs if it
     * does not, and note that neither `esc` nor this screen is the only way out: a keybind that opens
     * the settings screen would leave a light-ramp HUD behind with nothing left running to notice.
     */
    override fun removed() {
        Tokens.palette = entryPalette
        Motion.reduceMotion = entryReduceMotion
        super.removed()
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
    private fun GuiGraphicsExtractor.label(str: String, x: Int, y: Int, colour: Int) =
        Labels.draw(this, font, str, x, y, colour)

    private fun hex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    private companion object {
        /** The segmented control's specimen options. Three, which is what the control is for. */
        val SEGMENTS = listOf("all", "F7", "M7")

        /** A room's progression, for the sparkline in the table's detail row. */
        val ATTEMPTS = listOf(
            RoomHistory.Attempt(112, 0L, "F7", false),
            RoomHistory.Attempt(96, 0L, "F7", true),
            RoomHistory.Attempt(104, 0L, "M7", false),
            RoomHistory.Attempt(88, 0L, "M7", true),
            RoomHistory.Attempt(93, 0L, "M7", false),
            RoomHistory.Attempt(82, 0L, "M7", true),
        )

        const val CHIP_H = 18
        const val SWATCH = 72
        const val SWATCH_H = 40
        const val PLOT = 72
        const val DOT = 10
        const val LOOP_MS = 2000.0
        const val SPRING_FLIP_MS = 1200.0
        const val STEP_MS = 250.0

        // --- The overlay page's stand-in screens -------------------------------------------
        //
        // Every height here is decided by where the thing it holds places itself: the popup sits 54px
        // above the crosshair and the countdown 24px below it, so a stage has to be at least twice the
        // larger of those plus the chip, or the specimen is clipped by the box that exists to show it.
        // They are not padding numbers and cannot be tuned by eye.

        /** The scripted stage. Tall enough for both overlays at once, which the script does show. */
        const val STAGE_W = 380
        const val STAGE_H = 130

        /** A countdown on its own: 24px below a crosshair at the middle, plus the 30px chip. */
        const val STORM_CELL_W = 190
        const val STORM_CELL_H = 100

        /** A popup on its own: 54px above the middle, so the box is twice that and change. */
        const val POPUP_CELL_W = 380
        const val POPUP_CELL_H = 124

        /** The four moments the frozen countdown cells are sampled at, one per urgency step. */
        val STORM_STEPS = listOf(1_000.0, 4_500.0, 6_400.0, 7_200.0)

        /**
         * Where a frozen popup is held: past the entrance, before the fade begins.
         *
         * The one age at which the chip is entirely itself. A specimen caught during either curve would
         * be a screenshot of a fade, which is what the scripted stage next to it is for.
         */
        const val FROZEN_AGE_MS = 1_200L

        /** Half the crosshair's arm, and how present the mark is against the stage grey. */
        const val CROSSHAIR = 4
        const val CROSSHAIR_ALPHA = 150

        /** The timeline's gutter for its two lane names, and how thick a lane is. */
        const val LANE_LABEL = 34
        const val LANE_H = 5
    }
}
