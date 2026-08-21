package sighteaddons.ui.components

import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * A single-line text field, with a masked variant for a secret.
 *
 * **Nothing in the mod uses this today, and the reason is worth keeping written down.** It was built
 * for `Config.hypixelKey` — the one string a player ever had to type — and that setting is gone: the
 * Hypixel key moved onto the receiver, where one value serves every install and no player is asked for
 * one (see `SecretApi`). Kept rather than deleted because it is a finished, tested part of the design
 * system with a page in the gallery, and because the argument it embodies outlives its first consumer:
 * a field for a secret does not **echo**. [Mask.DOTS] is the default and the reveal is an explicit,
 * momentary act with a word on it, never a persisted "show password" setting.
 *
 * ### Where the state lives
 *
 * [draw] is stateless like everything else here: geometry and an [Edit] go in, pixels come out. [Edit]
 * is the caret and selection arithmetic on its own, with no drawing and no `Font` anywhere near it —
 * which is what makes "backspace with a selection deletes the selection, not one character" a thing a
 * unit test can hold, rather than a thing somebody has to reproduce by hand in a dungeon.
 */
internal object TextField {

    /** How a value is shown. [DOTS] never renders the characters themselves. */
    enum class Mask { NONE, DOTS }

    const val HEIGHT = 20

    /** Inner padding. The caret needs a pixel of air at the left or it merges with the border. */
    const val PADDING = Tokens.SPACE_8

    /** Advance per masked character. */
    const val MASK_ADVANCE = 6

    /** The mark itself: a square, drawn as a rectangle. */
    const val MASK_SIZE = 3

    /**
     * The caret blink period. Half on, half off.
     *
     * 1060 ms rather than the 530 ms most editors use, because this caret is one device pixel wide on
     * a screen that may be rendering at GUI scale 4 — at that size the fast blink reads as flicker.
     */
    const val BLINK_MS = 1060.0

    private const val SHOW = "show"
    private const val HIDE = "hide"

    /**
     * The caret's visibility right now, `0f` or `1f`.
     *
     * A hard square wave and not a fade: a caret that fades is a caret that is half-visible most of
     * the time, and at one device pixel that is a caret that looks broken. Held on under reduce
     * motion — a blink is a loop, and the rule for loops is that they stop at their rest value.
     */
    fun caretOn(focused: Boolean): Float = when {
        !focused -> 0f
        !Motion.ambientEnabled() -> 1f
        (Clock.nowMs % BLINK_MS) < BLINK_MS / 2 -> 1f
        else -> 0f
    }

    /** The value's text size. */
    val SIZE = Tokens.TEXT_12.toFloat()

    /** How wide the reveal affordance is, or `0` when the field is not masked. */
    fun revealWidth(mask: Mask, measure: (String) -> Float): Int =
        if (mask == Mask.NONE) 0
        else Math.ceil(maxOf(measure(SHOW), measure(HIDE)).toDouble()).toInt() + Tokens.SPACE_12

    /**
     * The reveal affordance's left edge, so a caller can hit-test the same rectangle it is drawn in.
     *
     * Both words are laid out in the width of the longer one — a control that changes width when you
     * press it moves out from under the cursor that pressed it.
     */
    fun revealX(mask: Mask, x: Int, width: Int, measure: (String) -> Float): Int =
        x + width - revealWidth(mask, measure)

    /** How much room the text itself has, once padding and any reveal affordance are taken out. */
    fun innerWidth(mask: Mask, width: Int, measure: (String) -> Float): Int =
        width - PADDING * 2 - revealWidth(mask, measure)

    /**
     * The width of [count] mask marks.
     *
     * Split out from [contentWidth] because it is the half that has nothing to do with a font, and
     * therefore the half a unit test can hold: a masked field's entire layout — caret position, scroll,
     * where a click lands — is this one multiplication, and none of it is checkable through `Font`.
     */
    fun maskedWidth(count: Int): Int = count * MASK_ADVANCE

    /** The character index [offsetX] pixels into a masked value of [length] characters. */
    fun maskedIndexAt(length: Int, offsetX: Int): Int =
        if (offsetX <= 0) 0 else ((offsetX + MASK_ADVANCE / 2) / MASK_ADVANCE).coerceIn(0, length)

    /**
     * How far into the content a press at [mouseX] lands, for a field whose left edge is [x] and which
     * is currently scrolled by [scroll].
     *
     * The screen's half of the hit test, here rather than there so it is the same arithmetic [draw]
     * lays the content out with — `draw` puts the first character at `x + PADDING - scroll`, and a
     * click handler that forgot either term would put the caret a padding or a scroll away from the
     * character that was pressed. On a masked field that is invisible: every mark looks like every
     * other one, so the caret landing in the wrong place cannot be seen, only felt three keystrokes
     * later.
     */
    fun offsetAt(x: Int, mouseX: Int, scroll: Int): Int = mouseX - x - PADDING + scroll

    /** The drawn width of [value] under [mask]. */
    fun contentWidth(value: String, mask: Mask, revealed: Boolean, measure: (String) -> Float): Int =
        if (mask == Mask.NONE || revealed) Math.ceil(measure(value).toDouble()).toInt()
        else maskedWidth(value.length)

    /** How far into the content the caret sits, in pixels from the start of the text. */
    fun caretOffset(value: String, caret: Int, mask: Mask, revealed: Boolean, measure: (String) -> Float): Int {
        val index = caret.coerceIn(0, value.length)
        return if (mask == Mask.NONE || revealed) Math.ceil(measure(value.substring(0, index)).toDouble()).toInt()
        else maskedWidth(index)
    }

    /**
     * How far the content must be scrolled left so the caret is inside the visible window.
     *
     * Pure arithmetic and separately testable, because getting it wrong is the classic text-field bug:
     * type past the right edge and the caret disappears while the text keeps growing, so you are
     * typing blind into a field that looks full. [scroll] is the current offset — the answer is
     * "leave it where it is" whenever the caret is already visible, or the field jitters on every
     * keystroke.
     */
    fun scrollFor(contentWidth: Int, caretOffset: Int, innerWidth: Int, scroll: Int): Int {
        if (innerWidth <= 0) return 0
        // A field that has room for everything is never scrolled, whatever it was scrolled to before.
        if (contentWidth <= innerWidth) return 0
        var next = scroll.coerceIn(0, contentWidth - innerWidth)
        if (caretOffset - next > innerWidth) next = caretOffset - innerWidth
        if (caretOffset < next) next = caretOffset
        return next.coerceIn(0, contentWidth - innerWidth)
    }

    /**
     * The character index a click at [offsetX] pixels into the content lands on.
     *
     * Rounds to the nearer gap between two characters rather than truncating: clicking the right half
     * of a letter must put the caret after it, which is what everything else that takes text input
     * does and what makes a click feel like it landed where it was aimed.
     */
    fun indexAt(value: String, mask: Mask, revealed: Boolean, offsetX: Int, measure: (String) -> Float): Int {
        if (offsetX <= 0) return 0
        if (mask != Mask.NONE && !revealed) return maskedIndexAt(value.length, offsetX)
        // Walks the prefixes rather than summing per-character advances. With a proportional face those
        // are not the same number — kerning and shaping mean the width of a string is not the sum of
        // its glyphs — and the prefix is what `caretOffset` measures, so this is the arithmetic that has
        // to agree with where the caret is actually drawn.
        var previous = 0f
        for (i in value.indices) {
            val upto = measure(value.substring(0, i + 1))
            if (offsetX < (previous + upto) / 2f) return i
            previous = upto
        }
        return value.length
    }

    /**
     * One field.
     *
     * [focus] and [hover] are resolved `0f..1f`; [caret] is what [caretOn] returned, passed in rather
     * than read here so a gallery can show the caret's two halves side by side instead of asking a
     * reviewer to catch one of them going past.
     */
    fun draw(
        x: Float, y: Float, width: Float, height: Float,
        edit: Edit,
        placeholder: String = "",
        mask: Mask = Mask.NONE,
        revealed: Boolean = false,
        focus: Float = 0f, hover: Float = 0f, caret: Float = 0f,
        enabled: Boolean = true,
    ) {
        if (width <= 0f || height <= 0f) return
        val radius = Tokens.RADIUS_SM.toFloat()
        val measure: (String) -> Float = { Sk.width(it, SIZE) }

        Sk.fill(x, y, width, height, Tokens.surfaceRaised, radius)
        if (enabled && hover > 0f && focus < 1f) {
            Sk.fill(x, y, width, height, Tokens.fade(Tokens.surfaceHover, hover), radius)
        }
        if (enabled) {
            Sk.border(
                x, y, width, height,
                Controls.blend(Tokens.borderDefault, Tokens.borderStrong, focus.coerceIn(0f, 1f)), radius,
            )
        } else {
            // The same dashed outline every disabled control in this UI wears. A field that said it was
            // uneditable only by dimming its own text would be saying it in the one register this
            // palette cannot afford.
            Controls.dashedBorder(x, y, width, height, Tokens.borderSubtle)
        }
        // Focus is a rule along the bottom edge, growing from the middle out. A brighter border alone
        // would be the only signal, and this UI does not let a state be a shade.
        if (focus > 0f) {
            val grown = (width - 2f) * focus.coerceIn(0f, 1f)
            Sk.fill(
                x + 1f + (width - 2f - grown) / 2f, y + height - Chrome.HAIRLINE, grown, Chrome.HAIRLINE,
                Tokens.fade(Tokens.accent, focus),
            )
        }

        val inner = innerWidth(mask, Math.round(width), measure)
        val textY = Sk.centreY(y, height, SIZE)
        val left = x + PADDING

        if (mask != Mask.NONE) {
            Sk.text(
                if (revealed) HIDE else SHOW,
                revealX(mask, Math.round(x), Math.round(width), measure) + Tokens.SPACE_6.toFloat(), textY, SIZE,
                if (enabled) Tokens.textTertiary else Tokens.textDisabled,
            )
        }

        if (edit.text.isEmpty()) {
            if (placeholder.isNotEmpty()) {
                Sk.text(
                    Sk.fit(placeholder, inner.toFloat(), SIZE), left, textY, SIZE,
                    if (enabled) Tokens.textTertiary else Tokens.textDisabled,
                )
            }
            if (caret > 0f) caret(left, y, height, caret)
            return
        }

        val offset = caretOffset(edit.text, edit.caret, mask, revealed, measure)
        val scroll = scrollFor(contentWidth(edit.text, mask, revealed, measure), offset, inner, edit.scroll)
        edit.scroll = scroll

        // Clipped rather than truncated: a truncated string moves its own characters as the caret
        // travels, and the field would appear to retype itself on every arrow key.
        Sk.clip(left, y, inner.toFloat(), height)
        val contentX = left - scroll
        if (mask == Mask.NONE || revealed) {
            drawText(edit, contentX, textY, enabled, measure)
        } else {
            drawMask(edit, contentX, y + (height - MASK_SIZE) / 2f, enabled)
        }
        if (caret > 0f) caret(contentX + offset, y, height, caret)
        Sk.unclip()
    }

    /** The value, in three runs, so the selected one can be inverted rather than merely tinted. */
    private fun drawText(edit: Edit, x: Float, y: Float, enabled: Boolean, measure: (String) -> Float) {
        val colour = if (enabled) Tokens.textPrimary else Tokens.textDisabled
        if (!edit.hasSelection) {
            Sk.text(edit.text, x, y, SIZE, colour)
            return
        }
        val head = edit.text.substring(0, edit.selectionStart)
        val body = edit.text.substring(edit.selectionStart, edit.selectionEnd)
        val tail = edit.text.substring(edit.selectionEnd)
        val headWidth = measure(head)
        val bodyWidth = measure(body)

        Sk.text(head, x, y, SIZE, colour)
        // Solid accent with the opposite extreme written on it, the same pair an active chip uses, so a
        // selection cannot be mistaken for a highlight that means something else. Sized to the real
        // line rather than to a cap height plus guesswork, which is what `lineHeight` is for.
        Sk.fill(x + headWidth, y, bodyWidth, Sk.lineHeight(SIZE), Tokens.accent)
        Sk.text(body, x + headWidth, y, SIZE, Tokens.accentText)
        Sk.text(tail, x + headWidth + bodyWidth, y, SIZE, colour)
    }

    /**
     * The masked value: one square per character.
     *
     * Not the bullet character. The reason has shifted but not gone: the bitmap font had no bullet and
     * would have fallen through to Unifont at a different weight and baseline, whereas JetBrains Mono
     * does have one. It is simply that a drawn square is the same mark at every size the field is drawn
     * at, while a glyph carries the type's own weight into a place that is not type.
     */
    private fun drawMask(edit: Edit, x: Float, y: Float, enabled: Boolean) {
        val colour = if (enabled) Tokens.textPrimary else Tokens.textDisabled
        for (i in edit.text.indices) {
            val dotX = x + i * MASK_ADVANCE
            val selected = edit.hasSelection && i >= edit.selectionStart && i < edit.selectionEnd
            if (selected) {
                Sk.fill(dotX - 1f, y - 3f, MASK_ADVANCE.toFloat(), MASK_SIZE + 6f, Tokens.accent)
            }
            Sk.fill(
                dotX, y, MASK_SIZE.toFloat(), MASK_SIZE.toFloat(),
                if (selected) Tokens.accentText else colour,
            )
        }
    }

    /** One hairline, full height of the text, in the accent so it cannot be read as a letter. */
    private fun caret(x: Float, y: Float, height: Float, alpha: Float) {
        Sk.fill(
            x, y + (height - CARET_HEIGHT) / 2f, Chrome.HAIRLINE, CARET_HEIGHT,
            Tokens.fade(Tokens.accent, alpha),
        )
    }

    private const val CARET_HEIGHT = 11f

    /**
     * The caret and selection arithmetic. No drawing, no `Font`, no Minecraft.
     *
     * Held by the screen that owns the field, exactly like `Anim` holds a hover fade: a stateless
     * component cannot remember where a caret is, and the screen is the only thing that knows how many
     * fields there are.
     *
     * [anchor] is where a selection started and [caret] is where it now ends, in that order and never
     * sorted — the direction is what makes shift+left extend from the right end rather than collapsing
     * to it.
     */
    class Edit(text: String = "", val maxLength: Int = Int.MAX_VALUE) {

        var text: String = text.take(maxLength)
            private set

        var caret: Int = this.text.length
            private set

        var anchor: Int = this.text.length
            private set

        /**
         * The horizontal scroll, in pixels, which [draw] both reads and writes.
         *
         * It lives here rather than in the component because it belongs to this field, and it is
         * computed during the draw because only the draw knows the font and the width. A screen never
         * has to set it; it exists so the value survives to the next frame.
         */
        var scroll: Int = 0

        val hasSelection: Boolean get() = anchor != caret
        val selectionStart: Int get() = minOf(anchor, caret)
        val selectionEnd: Int get() = maxOf(anchor, caret)

        /** The selected run, or the empty string. */
        fun selected(): String = text.substring(selectionStart, selectionEnd)

        /** Replaces everything, caret at the end. What a screen calls when it loads a config value. */
        fun set(value: String) {
            text = value.take(maxLength)
            caret = text.length
            anchor = caret
            scroll = 0
        }

        /**
         * Types [value] at the caret, replacing any selection.
         *
         * Truncates against [maxLength] rather than refusing the whole insert: pasting a key with a
         * trailing newline should give you the key, not nothing.
         */
        fun insert(value: String) {
            if (value.isEmpty()) return
            val head = text.substring(0, selectionStart)
            val tail = text.substring(selectionEnd)
            val room = maxLength - head.length - tail.length
            if (room <= 0) {
                collapse(head.length)
                return
            }
            val added = value.take(room)
            text = head + added + tail
            collapse(head.length + added.length)
        }

        /**
         * Backspace. Deletes the selection when there is one, otherwise the character before the
         * caret. Returns whether anything changed, so a screen can decide whether to save.
         */
        fun backspace(): Boolean {
            if (hasSelection) return deleteSelection()
            if (caret <= 0) return false
            text = text.removeRange(caret - 1, caret)
            collapse(caret - 1)
            return true
        }

        /** Forward delete. Same rule about the selection taking precedence. */
        fun delete(): Boolean {
            if (hasSelection) return deleteSelection()
            if (caret >= text.length) return false
            text = text.removeRange(caret, caret + 1)
            collapse(caret)
            return true
        }

        /**
         * Moves the caret by [delta].
         *
         * With [extend] the anchor stays put and the selection grows. Without it, a caret that had a
         * selection collapses to the end it is moving toward rather than stepping one further — which
         * is what every text field does and what makes left-then-right return you to where you were.
         */
        fun move(delta: Int, extend: Boolean) {
            if (!extend && hasSelection) {
                collapse(if (delta < 0) selectionStart else selectionEnd)
                return
            }
            caret = (caret + delta).coerceIn(0, text.length)
            if (!extend) anchor = caret
        }

        fun home(extend: Boolean) {
            caret = 0
            if (!extend) anchor = 0
        }

        fun end(extend: Boolean) {
            caret = text.length
            if (!extend) anchor = caret
        }

        fun selectAll() {
            anchor = 0
            caret = text.length
        }

        fun clearSelection() {
            anchor = caret
        }

        /** Places the caret at [index], dropping any selection. What a click does. */
        fun placeCaret(index: Int) {
            collapse(index)
        }

        private fun deleteSelection(): Boolean {
            val start = selectionStart
            if (start == selectionEnd) return false
            text = text.removeRange(start, selectionEnd)
            collapse(start)
            return true
        }

        private fun collapse(at: Int) {
            caret = at.coerceIn(0, text.length)
            anchor = caret
        }
    }
}
