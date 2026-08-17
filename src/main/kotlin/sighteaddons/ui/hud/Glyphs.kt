package sighteaddons.ui.hud

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The geometric marks this UI encodes meaning with, drawn from rectangles.
 *
 * Not characters. The vanilla bitmap font has no `▲`, `◆` or `●` — those fall through to the Unifont
 * fallback and arrive at a different weight and baseline than everything around them, which is why
 * `SettingsScreen` already refused them and drew its carets as rectangles. Composing them here keeps
 * the stroke width consistent with the hairlines beside them, and keeps the marks available before
 * the bundled font lands.
 *
 * They exist because a monochrome UI cannot say "faster" in green. A chevron and a weight change can
 * say it, and unlike luminance alone they survive both a greyscale screenshot and a reader who cannot
 * distinguish the two greys.
 */
internal object Glyphs {

    /** Nominal box for every mark, so a column of them lines up whatever the mark is. */
    const val SIZE = 7

    /** A one-pixel chevron. Up means an improvement; down means a regression, and never colour. */
    fun chevron(graphics: GuiGraphicsExtractor, x: Int, y: Int, up: Boolean, argb: Int) {
        // Five cells stepping to a point. Drawn cell by cell rather than as two diagonals so the apex
        // is exactly one pixel and both arms are the same length.
        for (step in 0..2) {
            val dy = if (up) 2 - step else step
            if (step == 2) {
                graphics.fill(x + 2, y + dy, x + 3, y + dy + 1, argb)
            } else {
                graphics.fill(x + step, y + dy, x + step + 1, y + dy + 1, argb)
                graphics.fill(x + 4 - step, y + dy, x + 5 - step, y + dy + 1, argb)
            }
        }
    }

    /** A filled dot — a state that is on, reached, or current. */
    fun dotFilled(graphics: GuiGraphicsExtractor, x: Int, y: Int, argb: Int) {
        graphics.fill(x + 1, y + 2, x + 4, y + 5, argb)
        graphics.fill(x + 2, y + 1, x + 3, y + 6, argb)
    }

    /** A hollow dot — the same state, not reached. Paired with [dotFilled], never with a shade of it. */
    fun dotHollow(graphics: GuiGraphicsExtractor, x: Int, y: Int, argb: Int) {
        graphics.fill(x + 1, y + 2, x + 4, y + 3, argb)
        graphics.fill(x + 1, y + 4, x + 4, y + 5, argb)
        graphics.fill(x + 1, y + 3, x + 2, y + 4, argb)
        graphics.fill(x + 3, y + 3, x + 4, y + 4, argb)
    }

    /**
     * The mark for a room's kind.
     *
     * Shape carries the category and fill carries its weight: a puzzle and a champion are both
     * diamonds because both are a gate the party has to open, and the champion is filled because it is
     * the heavier of the two. That mirrors what ClearPoints already pays for, so the mark on screen and
     * the score in the summary cannot disagree.
     *
     * The room database and the map colours use different vocabularies for the same rooms — `CHAMPION`
     * against `MINIBOSS`, `NORMAL` against `ROOM` — so both spellings are accepted rather than one of
     * them silently falling through to the default.
     */
    fun roomType(graphics: GuiGraphicsExtractor, x: Int, y: Int, type: String, argb: Int) {
        when (type.uppercase()) {
            "PUZZLE" -> diamond(graphics, x, y, filled = false, argb = argb)
            "CHAMPION", "MINIBOSS" -> diamond(graphics, x, y, filled = true, argb = argb)
            "TRAP" -> triangle(graphics, x, y, filled = false, argb = argb)
            "BLOOD" -> triangle(graphics, x, y, filled = true, argb = argb)
            "RARE" -> square(graphics, x, y, filled = true, argb = argb)
            "FAIRY" -> dotFilled(graphics, x, y, argb)
            "ENTRANCE" -> square(graphics, x, y, filled = false, argb = argb)
            else -> dotHollow(graphics, x, y, argb)
        }
    }

    private fun diamond(graphics: GuiGraphicsExtractor, x: Int, y: Int, filled: Boolean, argb: Int) {
        for (row in 0..4) {
            val half = if (row <= 2) row else 4 - row
            val left = x + 2 - half
            val right = x + 3 + half
            if (filled || half == 0) {
                graphics.fill(left, y + row + 1, right, y + row + 2, argb)
            } else {
                graphics.fill(left, y + row + 1, left + 1, y + row + 2, argb)
                graphics.fill(right - 1, y + row + 1, right, y + row + 2, argb)
            }
        }
    }

    private fun triangle(graphics: GuiGraphicsExtractor, x: Int, y: Int, filled: Boolean, argb: Int) {
        for (row in 0..3) {
            val left = x + 3 - row
            val right = x + 4 + row
            if (filled || row == 3) {
                graphics.fill(left, y + row + 2, right, y + row + 3, argb)
            } else {
                graphics.fill(left, y + row + 2, left + 1, y + row + 3, argb)
                graphics.fill(right - 1, y + row + 2, right, y + row + 3, argb)
            }
        }
    }

    private fun square(graphics: GuiGraphicsExtractor, x: Int, y: Int, filled: Boolean, argb: Int) {
        if (filled) {
            graphics.fill(x + 1, y + 2, x + 6, y + 7, argb)
            return
        }
        graphics.fill(x + 1, y + 2, x + 6, y + 3, argb)
        graphics.fill(x + 1, y + 6, x + 6, y + 7, argb)
        graphics.fill(x + 1, y + 3, x + 2, y + 6, argb)
        graphics.fill(x + 5, y + 3, x + 6, y + 6, argb)
    }
}
