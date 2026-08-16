package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Storm's countdown, drawn centred and large — the drawing half of [StormTimer], which decides what
 * it says.
 *
 * Modelled on [ClearPopup] and for its reason rather than by imitation: this is a thing that has to
 * land while the player is looking at a boss, not a standing readout they choose to glance at. So it
 * is centred, at double size, under the crosshair, and it is not part of the corner block — it does
 * not move when the HUD is repositioned and it does not disappear when the HUD is switched off.
 *
 * **The source mod's position and scale settings did not come across, deliberately.** They existed
 * because it drew at an absolute pixel offset with no anchor, so the player had to place it by hand
 * on every GUI scale. Centring on `guiScaledWidth`/`guiScaledHeight` is what those settings were
 * being used to approximate, and a coordinate pair that has one right answer is a worse setting than
 * no setting. The two that did come across are the tick counts, because those have no right answer
 * anybody here knows — see [StormTimer].
 *
 * Below the crosshair, where [ClearPopup] is above it. The two cannot really collide (nothing is
 * cleared during a boss phase) but they are drawn from the same call site in the same frame, and
 * stacking two double-size lines on one pixel row would be a bug found by seeing it once.
 *
 * Nothing here is testable in this repository — it is `Minecraft` calls end to end. Everything it
 * could get wrong that is not a draw call lives in [StormTimer.readout], which `StormTimerTest`
 * drives directly.
 */
internal object StormHud {
    /** Double size, matching [ClearPopup]: the font is a 9px bitmap and anything fractional blurs. */
    private const val SCALE = 2.0f

    /** Below the crosshair, clear of the hotbar at every GUI scale. */
    private const val OFFSET_Y = 24

    /**
     * [worldTime] is `client.level?.gameTime`, read by the caller, which already has the client in
     * hand. Null — no level — draws nothing.
     */
    fun render(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int, worldTime: Long?) {
        if (!Config.stormTimer) return
        val readout = StormTimer.readoutAt(worldTime) ?: return

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(screenWidth / 2f, (screenHeight / 2 + OFFSET_Y).toFloat())
        pose.scale(SCALE, SCALE)
        graphics.text(font, readout.text, -font.width(readout.text) / 2, 0, readout.color, true)
        pose.popMatrix()
    }
}
