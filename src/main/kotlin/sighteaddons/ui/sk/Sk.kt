package sighteaddons.ui.sk

import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import java.awt.Color

/**
 * The primitive layer: the only thing components draw shapes and text through.
 *
 * It replaces `ui/render/Surface` and the sprite sheet under it, and the reason is that almost
 * everything that layer did was a workaround for something the GPU does for free. A rounded card was
 * seven draws (four corner blits from a generated atlas plus three fills); here it is one. A hairline
 * needed the pose snapped to the device grid so a one-pixel edge did not land between two physical
 * pixels; here the edge is anti-aliased and lands where it was asked to. A soft shadow was nine blits
 * of a pre-blurred sprite; here it is a real Gaussian. None of that was bad code — it was the best
 * available answer to `fill()` being the only tool. The tool changed.
 *
 * ### Two rules, and both of them bite silently
 *
 * 1. **Everything here is render-thread only, drawing *and* measuring.** BlackSkija enforces it with
 *    a throw at the call site rather than letting a worker thread corrupt a later frame, so a
 *    violation is loud — but it is loud at runtime, not at compile time.
 * 2. **Nothing here may be called outside a draw pass.** Not because of threading, but because the
 *    Skija native is downloaded *asynchronously at startup* and the classes behind these calls are
 *    only safe once it has landed. A draw queued early is harmless — the compositor discards it —
 *    but a *measurement* forces the native path, and a class whose initialisation failed stays
 *    failed for the life of the process. [SkScreen] is what guarantees the timing: it only ever
 *    calls its subclass back from inside the compositor, which does not run until the native is
 *    ready. So layout that needs a width belongs inside the draw pass, never in a constructor, a
 *    click handler or a tick.
 *
 * Rule 2 is why layout maths in this package keeps taking its widths as lambdas, the way
 * [sighteaddons.ui.screens.RecordColumns] already does. That is not indirection for its own sake; it
 * is the only shape that stays testable, because no unit test can call anything in this file.
 *
 * ### Colours stay ARGB ints
 *
 * BlackSkija speaks [java.awt.Color] and the rest of this mod speaks packed ARGB ints, and the ints
 * win: `Contrast`, the theme tokens, the config keys and every existing test are written in them. So
 * the conversion lives here and nowhere else, behind a small cache, because the set of colours a
 * frame uses is a couple of dozen tokens rather than an open set.
 */
internal object Sk {

    /**
     * Packed-ARGB to [Color], memoised.
     *
     * Bounded on purpose. Tokens and their fixed alpha variants are a few dozen values, but an
     * animated fade that bakes its alpha into the colour would mint a new key per frame per element,
     * and a cache that never stops growing is a leak wearing a cache's name. Past the cap it simply
     * allocates — one small object, next to the closure the draw call allocates anyway — so the
     * behaviour degrades in cost and never in correctness.
     *
     * The cheaper way to fade is [alpha], which multiplies at composite time and mints nothing.
     */
    private val colors = HashMap<Int, Color>(64)

    private const val CACHE_CAP = 256

    fun color(argb: Int): Color {
        colors[argb]?.let { return it }
        val made = Color(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, argb ushr 24)
        if (colors.size < CACHE_CAP) colors[argb] = made
        return made
    }

    // --- Shapes ---------------------------------------------------------------------------------

    /** A filled rectangle, square-cornered when [radius] is zero. */
    fun fill(x: Float, y: Float, w: Float, h: Float, argb: Int, radius: Float = 0f) {
        if (w <= 0f || h <= 0f) return
        val c = color(argb)
        // The Float overloads, deliberately: the `Number` ones box every coordinate, and a HUD frame
        // goes through here a few dozen times.
        if (radius <= 0f) Skija.rect(x, y, w, h, c) else Skija.rect(x, y, w, h, c, cap(radius, w, h))
    }

    /**
     * A stroked outline, inset so the whole stroke lands inside ([x], [y], [w], [h]).
     *
     * Skija centres a stroke on its path, so asking for a one-pixel border at the exact bounds paints
     * half a pixel outside them. A border that overhangs its own box is the artefact that makes two
     * adjacent cards look like they have a two-pixel gutter on one side and none on the other, so the
     * inset is not cosmetic.
     */
    fun border(x: Float, y: Float, w: Float, h: Float, argb: Int, radius: Float = 0f, thickness: Float = 1f) {
        if (w <= thickness || h <= thickness) return
        val half = thickness / 2f
        Skija.hollowRect(
            x + half, y + half, w - thickness, h - thickness,
            thickness, color(argb), (cap(radius, w, h) - half).coerceAtLeast(0f),
        )
    }

    /**
     * A soft drop shadow under a box of the same geometry. Draw it before what stands on top.
     *
     * [spread] grows the shadow box before blurring, which is what makes a shadow read as cast by
     * something with thickness rather than as a blurred copy of the element.
     */
    fun shadow(
        x: Float, y: Float, w: Float, h: Float,
        argb: Int, radius: Float, blur: Float, spread: Float = 0f, dy: Float = 0f,
    ) {
        if (w <= 0f || h <= 0f) return
        Skija.dropShadow(x, y + dy, w, h, blur, spread, cap(radius, w, h), color(argb))
    }

    /** A two-stop linear gradient fill. [Gradient] offers only these two axes; there is no diagonal. */
    fun gradient(
        x: Float, y: Float, w: Float, h: Float,
        from: Int, to: Int, vertical: Boolean = true, radius: Float = 0f,
    ) {
        if (w <= 0f || h <= 0f) return
        Skija.gradientRect(
            x, y, w, h, color(from), color(to),
            if (vertical) Gradient.TOP_BOTTOM else Gradient.LEFT_RIGHT,
            cap(radius, w, h),
        )
    }

    /** A round-capped line. Used for rules and for the sparkline. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, argb: Int, thickness: Float = 1f) =
        Skija.line(x1, y1, x2, y2, thickness, color(argb))

    fun circle(cx: Float, cy: Float, radius: Float, argb: Int) {
        if (radius <= 0f) return
        Skija.circle(cx, cy, radius, color(argb))
    }

    /** "As round as this box allows" — a pill, whatever its height. */
    fun pill(x: Float, y: Float, w: Float, h: Float, argb: Int) = fill(x, y, w, h, argb, h / 2f)

    /**
     * Clamps a corner radius so it can never exceed half the shorter side.
     *
     * Skija will happily draw a radius larger than the box, and the result is not a lozenge but a
     * shape whose opposite corners have eaten each other. Carried over from `Surface.resolveRadius`,
     * minus the snap to the sprite sheet's available radii, which no longer exist.
     */
    private fun cap(radius: Float, w: Float, h: Float): Float = radius.coerceAtMost(minOf(w, h) / 2f)

    // --- State ----------------------------------------------------------------------------------

    /**
     * Multiplies everything drawn until the enclosing [pop] by [amount].
     *
     * This is how a fade should be expressed. Baking the alpha into the colour instead mints a cache
     * key per frame (see [colors]) and cannot fade a gradient at all.
     */
    fun alpha(amount: Float) = Skija.globalAlpha(amount)

    fun push() = Skija.push()

    fun pop() = Skija.pop()

    /** Clips to a rectangle until [unclip]. Pairs must balance; BlackSkija warns and unwinds if not. */
    fun clip(x: Float, y: Float, w: Float, h: Float) = Skija.pushScissor(x, y, w, h)

    fun unclip() = Skija.popScissor()

    fun translate(dx: Float, dy: Float) = Skija.translate(dx, dy)

    fun scale(factor: Float) = Skija.scale(factor, factor)

    // --- Text -----------------------------------------------------------------------------------

    /**
     * Draws [text] with its left edge at [x] and the **top of its line box** at [y].
     *
     * Top rather than baseline, and this is BlackSkija's convention rather than a choice made here: a
     * line drawn at [y] occupies `y` through `y + `[lineHeight]. It happens to be the same convention
     * the vanilla font helper used, which is what makes the call sites migrate almost unchanged — but
     * only *almost*, because the height of a line is now `ascent + descent` at the real font rather
     * than a fixed nine pixels. Anything that centred text by hand-subtracting 4 has to ask
     * [centreY] instead.
     *
     * [family] defaults to regular; [Type] says when a heavier one is warranted.
     */
    fun text(text: String, x: Float, y: Float, size: Float, argb: Int, family: String = Type.REGULAR) {
        if (text.isEmpty()) return
        Type.ensure()
        Skija.text(text, x, y, size, color(argb), family)
    }

    /** [text] with its right edge at [x]. */
    fun textRight(text: String, x: Float, y: Float, size: Float, argb: Int, family: String = Type.REGULAR) =
        text(text, x - width(text, size, family), y, size, argb, family)

    /** [text] centred horizontally on [cx]. */
    fun textCenter(text: String, cx: Float, y: Float, size: Float, argb: Int, family: String = Type.REGULAR) =
        text(text, cx - width(text, size, family) / 2f, y, size, argb, family)

    /**
     * How wide [text] will be drawn at [size].
     *
     * Real measurement, cached natively by BlackSkija per (string, size, family) — which is why
     * measuring the same label every frame is cheap, and why measuring inside the draw pass is the
     * intended use rather than a compromise. Subject to rule 2 at the top of this file.
     */
    fun width(text: String, size: Float, family: String = Type.REGULAR): Float {
        if (text.isEmpty()) return 0f
        Type.ensure()
        return Skija.textWidth(text, size, family)
    }

    /**
     * The height one line actually occupies at [size]: ascent plus descent.
     *
     * **Not [size].** A font size is an em, and the glyphs it rasterises to run to roughly 1.3× it, so
     * every box sized to `size` clips its own descenders. This is the number every vertical layout in
     * the UI is built on, and it is read from the font rather than approximated as a fraction, because
     * the ratio belongs to the typeface and is quietly wrong for any other one.
     */
    fun lineHeight(size: Float, family: String = Type.REGULAR): Float {
        Type.ensure()
        val m = Skija.textMetrics(size, family)
        return m[0] + m[1]
    }

    /** Distance from the top of the line box down to the baseline, for aligning against other art. */
    fun ascent(size: Float, family: String = Type.REGULAR): Float {
        Type.ensure()
        return Skija.textMetrics(size, family)[0]
    }

    /**
     * The [text] y that centres one line vertically in a box of [height] starting at [top].
     *
     * The single most common vertical calculation on this screen — a row's label against its control,
     * a value against its cell — and the one most likely to be got wrong by hand, because the naive
     * `top + (height - size) / 2` is off by the difference between an em and a line.
     */
    fun centreY(top: Float, height: Float, size: Float, family: String = Type.REGULAR): Float =
        top + (height - lineHeight(size, family)) / 2f

    /**
     * Truncates [text] to fit [room] pixels at [size], ending in an ellipsis when it had to cut.
     *
     * Replaces `Font.plainSubstrByWidth`, which could binary-search a bitmap font's integer widths. A
     * proportional font needs the same search done against real measurements, and it is done here
     * once rather than at each of the call sites that used to ask the font directly.
     */
    fun fit(text: String, room: Float, size: Float, family: String = Type.REGULAR): String {
        if (room <= 0f) return ""
        if (width(text, size, family) <= room) return text
        val budget = room - width(ELLIPSIS, size, family)
        if (budget <= 0f) return ELLIPSIS
        // Linear from the end rather than a binary search: the strings that reach here are room names
        // and labels, and the cut is usually within a few characters of the full length.
        var end = text.length
        while (end > 0 && width(text.substring(0, end), size, family) > budget) end--
        return text.substring(0, end).trimEnd() + ELLIPSIS
    }

    private const val ELLIPSIS = "…"
}
