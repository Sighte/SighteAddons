package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.theme.Tokens

/**
 * The 11px uppercase label with tracking, and the section header built out of it.
 *
 * This existed three times before it existed once: `HudRoot`, `SettingsScreen` and `GalleryScreen`
 * each carried a private `label` extension with the same glyph-by-glyph loop, and two of them also
 * needed to know how wide the result was — which none of them could answer, because the width was a
 * side effect of the loop. That is how a tab underline ends up shorter than the tab it underlines.
 *
 * The loop is not an implementation detail waiting to be optimised away. The vanilla bitmap font has
 * no letter-spacing control, so tracking costs one draw call per character, and that cost is the
 * strongest argument for the bundled TTF. It is deliberately visible here rather than hidden behind a
 * helper that quietly drops the tracking to save draws.
 */
internal object Labels {

    /** Cap height of the bitmap font, which is what a label is vertically centred on. */
    const val CAP = 8

    /**
     * The single-character strings the loops below hand to the font, one per ASCII code point.
     *
     * Both `draw` and `fit` need each glyph as a `String`, because that is what `Font` measures and
     * draws — and `value.substring(i, i + 1)` builds a new one every time. On the HUD that was two
     * strings per character per frame, thirty-six of them for an eighteen-character room name at up to
     * two hundred and forty frames a second, for a set of objects that is finite and never changes.
     * So it is built once. Anything outside ASCII — a room name never is, but a section header could
     * one day be — falls back to allocating, which is the rare path and stays correct.
     */
    private val ASCII = Array(128) { it.toChar().toString() }

    private fun glyph(value: String, index: Int): String {
        val c = value[index]
        return if (c.code < ASCII.size) ASCII[c.code] else c.toString()
    }

    /**
     * Draws [value] at ([x], [y]) with [Tokens.TRACKING_LABEL] between glyphs.
     *
     * Callers uppercase their own strings. Doing it here would make a label that is deliberately
     * mixed case impossible to write, and `uppercase()` allocates on every frame it is called from.
     */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, value: String, x: Int, y: Int, argb: Int) {
        var cursor = x.toFloat()
        for (i in value.indices) {
            val glyph = glyph(value, i)
            graphics.text(font, glyph, Math.round(cursor), y, argb, false)
            cursor += font.width(glyph) + Tokens.TRACKING_LABEL
        }
    }

    /**
     * How wide [draw] will actually be.
     *
     * Measured glyph by glyph, the same way it is drawn, because the two must agree — an underline or
     * a right-aligned neighbour laid out from `font.width(whole)` is short by the tracking and lands
     * visibly inside the last letter.
     */
    fun width(font: Font, value: String): Int {
        if (value.isEmpty()) return 0
        var glyphs = 0
        for (i in value.indices) glyphs += font.width(glyph(value, i))
        return trackedWidth(glyphs, value.length)
    }

    /**
     * The tracking arithmetic on its own: [glyphs] pixels of glyph, [count] of them.
     *
     * Tracking sits *between* glyphs, so there are `count - 1` gaps and not `count`. One gap too many
     * is 0.66px, which is invisible on a two-word header and is a whole pixel of drift by the time a
     * room name is truncated to fit beside a clock.
     */
    fun trackedWidth(glyphs: Int, count: Int): Int =
        if (count <= 0) 0 else glyphs + Math.round(Tokens.TRACKING_LABEL * (count - 1))

    /**
     * The longest prefix of [value] that fits [maxWidth] when drawn by [draw].
     *
     * `Font.plainSubstrByWidth` cannot answer this: it measures the glyphs alone while [draw] adds
     * tracking between every pair of them. On a long room name that is several pixels, which is the
     * difference between a name that stops short of the clock and one that runs underneath it.
     */
    fun fit(font: Font, value: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        var glyphs = 0
        for (i in value.indices) {
            val next = glyphs + font.width(glyph(value, i))
            if (trackedWidth(next, i + 1) > maxWidth) return value.substring(0, i)
            glyphs = next
        }
        return value
    }

    /**
     * [fit]'s answer, held while the string and the width are what they were.
     *
     * A truncated name is one `substring` per frame, and the frame is where this UI is not allowed to
     * allocate — but a room name changes once a room and the width it is measured against changes when
     * the window does. Holding both is cheaper than either comparison it replaces. An instance per
     * call site, not a shared one: two cards fitting two different names would otherwise evict each
     * other every frame and be slower than no cache at all.
     */
    class Fitter {
        private var source = ""
        private var width = Int.MIN_VALUE
        private var text = ""

        fun of(font: Font, value: String, maxWidth: Int): String {
            if (maxWidth != width || value != source) {
                source = value
                width = maxWidth
                text = fit(font, value, maxWidth)
            }
            return text
        }
    }

    /**
     * A section header: the label, a hairline rule running to the right edge, and optional [meta].
     *
     * The rule is not decoration. `textSecondary` and `textTertiary` sit 1.27:1 apart, so a section
     * boundary drawn only as "this line is slightly brighter than the rows under it" is a boundary
     * some readers will not see at all. A rule is a shape, and shapes survive both a greyscale
     * screenshot and a reader who cannot separate two greys.
     */
    fun sectionHeader(
        graphics: GuiGraphicsExtractor, font: Font,
        title: String, x: Int, y: Int, available: Int,
        meta: String? = null,
    ) {
        draw(graphics, font, title, x, y, Tokens.textSecondary)

        val metaWidth = if (meta == null) 0 else font.width(meta)
        if (meta != null) {
            graphics.text(font, meta, x + available - metaWidth, y, Tokens.textTertiary, false)
        }

        val ruleLeft = x + width(font, title) + Tokens.SPACE_8
        val ruleRight = x + available - metaWidth - (if (meta == null) 0 else Tokens.SPACE_8)
        if (ruleRight > ruleLeft) {
            // Aligned to the middle of the cap height rather than to the baseline, so the rule reads
            // as continuing through the words rather than as underlining them.
            DevicePixels.hairlineH(graphics, ruleLeft, y + CAP / 2, ruleRight - ruleLeft, Tokens.borderSubtle)
        }
    }
}
