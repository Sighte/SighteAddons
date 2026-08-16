package sighteaddons.ui.theme

/**
 * WCAG 2.1 relative luminance and contrast ratio over packed ARGB ints.
 *
 * This exists as production code rather than as a test helper because the acceptance bar for this UI
 * is that contrast is *checked programmatically*, not judged by eye — and because half the palette is
 * translucent white over a surface, so the number that matters is the ratio against the composited
 * backdrop, not against the token's own literal. [over] is what makes that measurable.
 *
 * Alpha is treated as coverage, not as a colour: `over` composites and returns an opaque result, so a
 * token like `surfaceHover` (white at 4%) can be laid on a surface and the pair measured honestly.
 */
internal object Contrast {

    /** The AA floor for body text. Large text may sit at 3.0, which this UI never relies on. */
    const val AA = 4.5

    /**
     * Contrast ratio between two colours, `1.0` (identical) to `21.0` (black on white).
     *
     * Both are composited onto [under] first, so a translucent foreground on a translucent surface
     * still yields the ratio a reader actually sees. Order of the two arguments does not matter.
     */
    fun ratio(foreground: Int, background: Int, under: Int = OPAQUE_BLACK): Double {
        val fg = luminance(over(foreground, over(background, under)))
        val bg = luminance(over(background, under))
        val lighter = maxOf(fg, bg)
        val darker = minOf(fg, bg)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Source-over composite of [top] onto [bottom], returning an opaque ARGB int.
     *
     * [bottom] is assumed opaque; every call site composites down to a real surface, and a chain of
     * translucent layers is folded by calling this repeatedly from the bottom up.
     */
    fun over(top: Int, bottom: Int): Int {
        val alpha = (top ushr 24) and 0xFF
        if (alpha == 255) return top or OPAQUE
        if (alpha == 0) return bottom or OPAQUE
        val a = alpha / 255.0
        val r = ((top ushr 16) and 0xFF) * a + ((bottom ushr 16) and 0xFF) * (1 - a)
        val g = ((top ushr 8) and 0xFF) * a + ((bottom ushr 8) and 0xFF) * (1 - a)
        val b = (top and 0xFF) * a + (bottom and 0xFF) * (1 - a)
        return OPAQUE or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    /** WCAG relative luminance. Alpha is ignored — composite with [over] before calling. */
    fun luminance(argb: Int): Double {
        val r = channel((argb ushr 16) and 0xFF)
        val g = channel((argb ushr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** sRGB companding, the piecewise curve from the spec rather than a plain 2.2 gamma. */
    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private const val OPAQUE = 0xFF shl 24
    private const val OPAQUE_BLACK = OPAQUE or 0x000000
}
