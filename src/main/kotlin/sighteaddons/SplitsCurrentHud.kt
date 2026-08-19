package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.Format
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.render.ScaledText
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * The running span's server-tick clock, on its own, large and centred — Odin's "Current Split HUD".
 *
 * A second element rather than a row on the panel, because it answers a different question: the panel is
 * a table you read between fights, and this is one number you can see without looking away from a boss.
 * It is the reason it is drawn at [ScaledText]'s double size and near the crosshair, the same argument
 * [ClearPopup] and [StormHud] make for being separate from the card.
 *
 * **The tick clock and not the wall clock**, which is Odin's choice and worth keeping: the number a
 * player is checking mid-fight is the one they will compare afterwards, and a wall-clock reading that
 * included a lag spike would not be it.
 *
 * Off on a fresh install, as Odin's is. Three chips already default to the middle of the screen and a
 * fourth that arrived switched on would land on one of them.
 */
internal object SplitsCurrentHud {

    private const val PAD_X = Tokens.SPACE_12
    private const val PAD_Y = Tokens.SPACE_6

    /** The chip's height, and the rectangle the placement editor grabs it by — as [StormHud.HEIGHT]. */
    internal const val HEIGHT = ScaledText.HEIGHT + PAD_Y * 2

    /**
     * On the crosshair, one chip below the storm countdown.
     *
     * Written as the sum rather than as the number it comes to, for [StormHud.DEFAULT_OFFSET_Y]'s reason.
     * Below the countdown because the countdown has a deadline and this does not: if a player ever has
     * both on screen at once, the one they must not miss keeps the position closest to the eye.
     */
    internal val DEFAULT_ANCHOR = HudPlacement.Anchor.MIDDLE_CENTRE
    internal const val DEFAULT_OFFSET_X = 0
    internal const val DEFAULT_OFFSET_Y = StormHud.DEFAULT_OFFSET_Y + HEIGHT + Tokens.SPACE_8

    fun render(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        serverTicks: Long,
    ) {
        if (!Config.splits || !Config.splitsCurrent) return
        if (HudRoot.editing) return
        draw(graphics, font, screenWidth, screenHeight, text(nowMs, serverTicks) ?: return)
    }

    /**
     * What the chip says, or null when there is nothing running.
     *
     * Null covers a run that has not started and one that has finished — after the last split there is no
     * span in progress, and a chip frozen on the final number would read as one that is still counting.
     */
    internal fun text(nowMs: Long, serverTicks: Long): String? {
        // The same held readout the panel reads, so the two cannot show different numbers for the same
        // span — and so this formats nothing per frame either. See Splits.display.
        val readout = Splits.display(nowMs, serverTicks) ?: return null
        val row = readout.rows.getOrNull(readout.runningRow) ?: return null
        return row.tickText
    }

    /** Split from [render] for the gallery and the placement editor, which supply their own text. */
    internal fun draw(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        text: String,
    ) {
        val width = width(font, text)
        val origin = Config.splitsCurrentPlacement.origin(screenWidth, screenHeight, width, HEIGHT)
        val radius = Surface.resolveRadius(Tokens.RADIUS_CHIP, width, HEIGHT)
        Surface.roundedFill(
            graphics, origin.x, origin.y, width, HEIGHT, radius,
            Tokens.alpha(Tokens.scrim, Tokens.scrimAlpha(Config.hudScrim)),
        )
        Surface.roundedBorder(graphics, origin.x, origin.y, width, HEIGHT, radius, Tokens.borderSubtle)
        Surface.topHighlight(graphics, origin.x, origin.y, width, radius, Tokens.highlight)
        ScaledText.draw(graphics, font, text, origin.x + PAD_X, origin.y + PAD_Y, Tokens.textPrimary)
    }

    /** How wide the chip is for [text] — what [draw] lays out from and what the editor grabs. */
    internal fun width(font: Font, text: String): Int = PAD_X * 2 + ScaledText.width(font, text)

    /** The chip as it reads at the start of a span, for the editor to drag. [StormHud.sample]'s job. */
    internal fun sample(): String = Format.ticks(0)
}
