package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.components.Labels
import sighteaddons.ui.hud.Glyphs
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.render.DevicePixels
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
 * ### A header, a dot per split, and lower case (04.09.2026)
 *
 * The rows used to be uppercase [Labels] — a panel of ten headings and no body. Now the names are the
 * lower-case spellings [DungeonSplits] already defines, drawn plain the way [HudRoot] draws every
 * value, and the one tracked label left is the floor tag in the header, where a heading actually sits.
 * The header also carries the running total over the wall-clock column, because it is that column's
 * sum and the context every row is read against.
 *
 * Each split wears [Glyphs]' own pair: filled means the span ran, hollow means it has not started, and
 * the running one takes [Tokens.accent] on its dot and its clock — the state that hue is reserved for
 * ([sighteaddons.ui.theme.Palette]'s rule), while the shape and the primary-tone name keep saying it
 * in a greyscale screenshot. The aggregate rows below carry no dot: the dots are the chain, and an
 * aggregate is not a link of it.
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

    /**
     * The air the est. run row's divider costs: the hairline and a breath either side of it.
     *
     * A named constant used by [measure] and [draw] both, because the two agreeing to the pixel is
     * [measure]'s whole contract and a literal written twice is how they stop.
     */
    private const val RULE_GAP = 4

    /**
     * The header line — floor tag, rule, running total — and the air under it. A [measure]/[draw]
     * constant for [RULE_GAP]'s reason.
     */
    private const val HEADER = ROW + Tokens.SPACE_6

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
        PADDING * 2 + HEADER + (readout.rows.size + extraRows(readout)) * ROW +
            (if (estimateShown(readout)) RULE_GAP else 0)

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
        // The rows' name column starts past the dot, HudRoot's glyph-then-name pattern, so ten dots
        // line up as a column whatever the mark in each is.
        val nameLeft = left + Glyphs.SIZE + Tokens.SPACE_6

        var y = originY + PADDING
        // The header: which floor, and where the run stands. The tag is the panel's one tracked label
        // (Labels.sectionHeader's shape, hand-composed because its meta slot is tertiary and the running
        // total has earned textSecondary), the rule is what makes the boundary a shape rather than a
        // shade, and the total sits over the wall-clock column because it is that column's sum.
        Labels.draw(graphics, font, readout.floorTag, left, y, Tokens.textSecondary)
        val ruleLeft = left + Labels.width(font, readout.floorTag) + Tokens.SPACE_8
        val ruleRight = timeRight - font.width(readout.totalText) - Tokens.SPACE_8
        if (ruleRight > ruleLeft) {
            DevicePixels.hairlineH(graphics, ruleLeft, y + Labels.CAP / 2, ruleRight - ruleLeft, Tokens.borderSubtle)
        }
        time(graphics, font, readout.totalText, timeRight, y, Tokens.textSecondary)
        y += HEADER

        readout.rows.forEach { row ->
            // The running row is the only one on the panel whose number is still moving: its dot and
            // its clock take the accent — the one state the palette reserves that hue for — while the
            // shape still says it without the colour (a filled dot under hollow ones, the primary-tone
            // name). A finished span keeps its filled dot in its own tone; one that has not started is
            // hollow, Glyphs' own pair for reached and not.
            val tone = when {
                row.running -> Tokens.textPrimary
                row.known -> Tokens.textSecondary
                else -> Tokens.textTertiary
            }
            val dotY = y + (Labels.CAP - Glyphs.SIZE) / 2
            when {
                row.running -> Glyphs.dotFilled(graphics, left, dotY, Tokens.accent)
                row.known -> Glyphs.dotFilled(graphics, left, dotY, tone)
                else -> Glyphs.dotHollow(graphics, left, dotY, tone)
            }
            // Mixed case, plain text: the names read as words, not as headings — HudRoot draws every
            // value this way, and the uppercase rows this replaces were the one place the panel shouted.
            graphics.text(font, row.name, nameLeft, y, tone, false)
            val timeTone = when {
                row.running -> Tokens.accent
                row.known -> Tokens.textPrimary
                else -> Tokens.textTertiary
            }
            time(graphics, font, row.timeText, timeRight, y, timeTone)
            if (Config.splitsTickTime) time(graphics, font, row.tickText, tickRight, y, Tokens.textTertiary)
            y += ROW
        }

        if (bossEntryShown(readout)) {
            // An aggregate of the three spans above it, so it is written in the qualifying tone
            // throughout rather than in a row's, and it carries no dot: the dots are the chain, and it
            // is not a link of it.
            graphics.text(font, DungeonSplits.BOSS_ENTRY, nameLeft, y, Tokens.textTertiary, false)
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
            graphics.text(font, DungeonSplits.LAG, nameLeft, y, Tokens.textTertiary, false)
            time(graphics, font, readout.lagText, timeRight, y, Tokens.textTertiary)
            y += ROW
        }

        if (estimateShown(readout)) {
            // Last, under a rule, because every row above it is a measurement and this one is a
            // projection — the divider is what keeps a reader from summing it in with the facts. The
            // tick cell stays empty for the lag row's reason turned around: expected times are typed in
            // wall-clock seconds (SplitExpected says why), so there is no tick figure to print.
            DevicePixels.hairlineH(graphics, left, y + 1, WIDTH - PADDING * 2, Tokens.borderSubtle)
            y += RULE_GAP
            graphics.text(font, DungeonSplits.ESTIMATE, nameLeft, y, Tokens.textTertiary, false)
            time(graphics, font, readout.estimateText, timeRight, y, Tokens.textSecondary)
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
        (if (bossEntryShown(readout)) 1 else 0) +
            (if (lagShown(readout)) 1 else 0) +
            (if (estimateShown(readout)) 1 else 0)

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

    /**
     * Whether the EST. RUN row is drawn.
     *
     * **Not gated on the tick column, unlike [lagShown]** — the lag row is the difference between the
     * two columns and needs both on screen to be checkable, while the estimate is a wall-clock figure
     * whose inputs are the wall-clock column above it and the player's own typed times. The
     * finished-run and missing-expectation cases are already inside `estimateMs` ([RunEstimate] returns
     * its NONE for both), so [measure], [draw] and this cannot disagree about them.
     */
    private fun estimateShown(readout: Splits.Readout): Boolean =
        Config.splitsEstimate && readout.estimateMs >= 0

    /** One right-aligned cell, so a column of them stays a column whatever the digits are. */
    private fun time(graphics: GuiGraphicsExtractor, font: Font, text: String, right: Int, y: Int, argb: Int) {
        graphics.text(font, text, right - font.width(text), y, argb, false)
    }
}
