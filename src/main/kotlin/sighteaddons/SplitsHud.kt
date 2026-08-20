package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.components.Labels
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.render.Surface
import sighteaddons.ui.render.Zoom
import sighteaddons.ui.theme.Tokens

/**
 * The splits panel: one row per span of the run, drawn where the player put it — the drawing half of
 * [Splits], which decides what the numbers are.
 *
 * ### Two columns, not a parenthetical
 *
 * Odin writes a row as `§5Maxor: 26.39s §8(§726.4§8)` — the wall-clock time and, in brackets, the same
 * span in server ticks. Here they are two right-aligned columns instead, because a bracket is a
 * parenthetical and this is a table: with ten rows on screen the thing a reader actually does is run an
 * eye down one column, which a bracketed second number makes impossible. The wall clock stays the
 * primary tone and the tick column is [Tokens.textTertiary] — the same pairing
 * `HudRoot.drawSecrets` uses for a count and its qualifier, and the reason the two are still
 * distinguishable in a greyscale screenshot is position, not tone.
 *
 * Both are printed through [Format], so a split reads in the same `m:ss.t` as a room clear and a record
 * delta. Odin's `59m 59s` and `(59.9)` were two more dialects and [Format] is the file that exists to
 * have none.
 *
 * ### Above the calibration gate, like [StormHud]
 *
 * Drawn from [SighteAddons.renderHud] *before* it returns on an uncalibrated session, and that is the
 * whole reason this is not part of [HudRoot]: the card is the clear phase and deliberately fades out at
 * the boss, which is exactly where half of these rows are still being written. A splits panel that
 * disappeared when Maxor spoke would be missing for the five spans it exists to time.
 *
 * ### Fixed width, no reflow
 *
 * [HudRoot.WIDTH] rather than a measured one, and the same number so the two panels line up when a
 * player stacks them. A width that tracked the widest label would change between floors, and one that
 * tracked the widest *time* would change the moment a span crossed a minute — a table that moves while
 * it is being read.
 *
 * Nothing here is testable in this repository: it is `Minecraft` calls end to end. Everything that could
 * be wrong and is not a draw call lives in [Splits.readout], which `SplitsTest` drives directly.
 */
internal object SplitsHud {

    private const val PADDING = Tokens.SPACE_8

    /** One row's height. [HudRoot]'s, so a stacked pair of panels shares a baseline grid. */
    private const val ROW = 12

    /** The panel's width. See the class comment for why it is not measured. */
    internal const val WIDTH = HudRoot.WIDTH

    /** Where a fresh install puts it: down the left edge, clear of the card's corner. */
    internal val DEFAULT_ANCHOR = HudPlacement.Anchor.MIDDLE_LEFT
    internal const val DEFAULT_OFFSET_X = 4
    internal const val DEFAULT_OFFSET_Y = 0

    /**
     * The widest a time column ever gets, measured off a string rather than guessed at.
     *
     * `10:00.0` and not `0:00.0`: an M7 that goes wrong passes ten minutes, and a column sized for one
     * digit of minutes would push its own contents left on exactly the run somebody is staring at.
     */
    private const val WIDEST_TIME = "10:00.0"

    fun render(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        serverTicks: Long,
    ) {
        if (!Config.splits) return
        // The placement editor draws its own copy from the same config, and two panels at once is two
        // tables with no way to tell which one is being moved. HudRoot.editing states it for the card.
        if (HudRoot.editing) return
        // Through `display` and not `readout`: it is held at the tenth of a second these rows print, so
        // a frame that would draw the identical characters does not rebuild them. See Splits.display.
        val readout = Splits.display(nowMs, serverTicks) ?: return
        val height = measure(readout)
        val origin = Config.splitsPlacement.origin(screenWidth, screenHeight, WIDTH, height)
        // The panel draws at whatever origin it is handed, so the size is the pose's job and the
        // gallery keeps calling `draw` unchanged. See Zoom.
        Zoom.at(graphics, origin.x, origin.y, Config.splitsPlacement.scale) {
            draw(graphics, font, readout, 0, 0)
        }
    }

    /**
     * The panel's height, which has to be the exact sum of what [draw] advances by.
     *
     * [HudRoot.measure]'s reason, in full: the two drifting apart is invisible in code and obvious on
     * screen, as a border that stops above the last row it is supposed to contain.
     */
    internal fun measure(readout: Splits.Readout): Int =
        PADDING * 2 + (readout.rows.size + extraRows(readout)) * ROW

    /** Split out for the gallery, which has a readout and no screen to place it against. */
    internal fun draw(
        graphics: GuiGraphicsExtractor,
        font: Font,
        readout: Splits.Readout,
        originX: Int,
        originY: Int,
    ) {
        val height = measure(readout)
        Surface.roundedFill(
            graphics, originX, originY, WIDTH, height, Tokens.RADIUS_CARD,
            Tokens.alpha(Tokens.scrim, Tokens.scrimAlpha(Config.hudScrim)),
        )
        Surface.roundedBorder(graphics, originX, originY, WIDTH, height, Tokens.RADIUS_CARD, Tokens.borderSubtle)
        Surface.topHighlight(graphics, originX, originY, WIDTH, Tokens.RADIUS_CARD, Tokens.highlight)

        val left = originX + PADDING
        val right = originX + WIDTH - PADDING
        val column = font.width(WIDEST_TIME)
        val tickRight = right
        val timeRight = if (Config.splitsTickTime) right - column - Tokens.SPACE_8 else right

        var y = originY + PADDING
        readout.rows.forEach { row ->
            // The running row is the only one on the panel whose number is still moving, and it is named
            // in the primary tone while a row that has not started yet drops to tertiary. A span that is
            // finished sits between them, which is the ordering a reader wants: what is happening now,
            // what happened, what has not happened.
            val tone = when {
                row.running -> Tokens.textPrimary
                row.known -> Tokens.textSecondary
                else -> Tokens.textTertiary
            }
            Labels.draw(graphics, font, row.label, left, y, tone)
            time(graphics, font, row.timeText, timeRight, y, if (row.known) Tokens.textPrimary else Tokens.textTertiary)
            if (Config.splitsTickTime) time(graphics, font, row.tickText, tickRight, y, Tokens.textTertiary)
            y += ROW
        }

        if (bossEntryShown(readout)) {
            // An aggregate of the three spans above it, so it is written in the qualifying tone
            // throughout rather than in a row's: it is not a split and nothing files a record for it.
            Labels.draw(graphics, font, DungeonSplits.BOSS_ENTRY_LABEL, left, y, Tokens.textTertiary)
            time(graphics, font, readout.bossEntryText, timeRight, y, Tokens.textSecondary)
            if (Config.splitsTickTime) {
                time(graphics, font, readout.bossEntryTickText, tickRight, y, Tokens.textTertiary)
            }
            y += ROW
        }

        if (lagShown(readout)) {
            // **In the left column, where wall-clock times live.** The number is milliseconds of wall
            // clock and belongs under the totals it was subtracted from; the tick column stays empty
            // because there is no tick figure for it — a lag span counted in ticks is zero by
            // definition, and printing that would read as "no lag" next to a number saying otherwise.
            Labels.draw(graphics, font, DungeonSplits.LAG_LABEL, left, y, Tokens.textTertiary)
            time(graphics, font, readout.lagText, timeRight, y, Tokens.textTertiary)
        }
    }

    private fun bossEntryShown(readout: Splits.Readout): Boolean =
        Config.splitsBossEntry && readout.hasBossEntry && readout.bossEntryMs >= 0

    /**
     * The rows below the splits themselves, which the height has to include.
     *
     * Spelled out rather than inlined into [measure]: `if (a) 1 else 0 + if (b) 1 else 0` parses as
     * `if (a) 1 else (0 + ...)`, which counts the second row only when the first is absent — a border
     * drawn one row short of what is inside it, which is the exact failure [measure] exists to prevent.
     */
    private fun extraRows(readout: Splits.Readout): Int =
        (if (bossEntryShown(readout)) 1 else 0) + (if (lagShown(readout)) 1 else 0)

    /**
     * Whether the lag row is drawn.
     *
     * Gated on the tick column as well as on its own switch: the number is the difference between the
     * two columns, and on a panel showing only one of them it would be a figure with nothing on screen
     * to check it against. Switching the tick column back on is also how a reader finds out where it
     * came from.
     */
    private fun lagShown(readout: Splits.Readout): Boolean =
        Config.splitsLag && Config.splitsTickTime && readout.hasLag

    /** One right-aligned cell, so a column of them stays a column whatever the digits are. */
    private fun time(graphics: GuiGraphicsExtractor, font: Font, text: String, right: Int, y: Int, argb: Int) {
        graphics.text(font, text, right - font.width(text), y, argb, false)
    }
}
