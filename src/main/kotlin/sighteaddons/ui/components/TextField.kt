package sighteaddons.ui.components

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * A single-line text field, with the masked variant the Hypixel key needs.
 *
 * The mod has exactly one string a player must be able to type — `Config.hypixelKey` — and today it
 * has no UI at all, so `SecretApi` and the whole secret audit have never run once. `Config` states
 * the reason it was left out: *"a text field that echoes a credential on screen is a worse default
 * than one more step"*. That objection is to an **echoing** field, not to a field, so this one does
 * not echo: [Mask.DOTS] is the default for a secret and the reveal is an explicit, momentary act with
 * a word on it, never a persisted "show password" setting.
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

    /** How wide the reveal affordance is, or `0` when the field is not masked. */
    fun revealWidth(font: Font, mask: Mask): Int =
        if (mask == Mask.NONE) 0 else maxOf(font.width(SHOW), font.width(HIDE)) + Tokens.SPACE_12

    /**
     * The reveal affordance's left edge, so a caller can hit-test the same rectangle it is drawn in.
     *
     * Both words are laid out in the width of the longer one — a control that changes width when you
     * press it moves out from under the cursor that pressed it.
     */
    fun revealX(font: Font, mask: Mask, x: Int, width: Int): Int =
        x + width - revealWidth(font, mask)

    /** How much room the text itself has, once padding and any reveal affordance are taken out. */
    fun innerWidth(font: Font, mask: Mask, width: Int): Int =
        width - PADDING * 2 - revealWidth(font, mask)

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

    /** The drawn width of [value] under [mask]. */
    fun contentWidth(font: Font, value: String, mask: Mask, revealed: Boolean): Int =
        if (mask == Mask.NONE || revealed) font.width(value) else maskedWidth(value.length)

    /** How far into the content the caret sits, in pixels from the start of the text. */
    fun caretOffset(font: Font, value: String, caret: Int, mask: Mask, revealed: Boolean): Int {
        val index = caret.coerceIn(0, value.length)
        return if (mask == Mask.NONE || revealed) font.width(value.substring(0, index)) else maskedWidth(index)
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
    fun indexAt(font: Font, value: String, mask: Mask, revealed: Boolean, offsetX: Int): Int {
        if (offsetX <= 0) return 0
        if (mask != Mask.NONE && !revealed) return maskedIndexAt(value.length, offsetX)
        var cursor = 0
        for (i in value.indices) {
            val advance = font.width(value.substring(i, i + 1))
            if (offsetX < cursor + advance / 2) return i
            cursor += advance
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
        graphics: GuiGraphicsExtractor, font: Font,
        x: Int, y: Int, width: Int, height: Int,
        edit: Edit,
        placeholder: String = "",
        mask: Mask = Mask.NONE,
        revealed: Boolean = false,
        focus: Float = 0f, hover: Float = 0f, caret: Float = 0f,
        enabled: Boolean = true,
    ) {
        if (width <= 0 || height <= 0) return
        val radius = Tokens.RADIUS_SM

        Surface.roundedFill(graphics, x, y, width, height, radius, Tokens.surfaceRaised)
        if (enabled && hover > 0f && focus < 1f) {
            Surface.roundedFill(graphics, x, y, width, height, radius, Tokens.fade(Tokens.surfaceHover, hover))
        }
        if (enabled) {
            Surface.roundedBorder(
                graphics, x, y, width, height, radius,
                Controls.blend(Tokens.borderDefault, Tokens.borderStrong, focus.coerceIn(0f, 1f)),
            )
        } else {
            // The same dashed outline every disabled control in this UI wears. A field that said it
            // was uneditable only by dimming its own text would be saying it in the one register this
            // palette cannot afford.
            Controls.dashedBorder(graphics, x, y, width, height, Tokens.borderSubtle)
        }
        // Focus is a rule along the bottom edge, growing from the middle out. A brighter border alone
        // would be the only signal, and this UI does not let a state be a shade.
        if (focus > 0f) {
            val grown = Math.round((width - 2) * focus.coerceIn(0f, 1f))
            DevicePixels.hairlineH(
                graphics, x + 1 + (width - 2 - grown) / 2, y + height - 1, grown,
                Tokens.fade(Tokens.accent, focus),
            )
        }

        val inner = innerWidth(font, mask, width)
        val textY = y + (height - Labels.CAP) / 2
        val left = x + PADDING

        if (mask != Mask.NONE) {
            graphics.text(
                font, if (revealed) HIDE else SHOW,
                revealX(font, mask, x, width) + Tokens.SPACE_6, textY,
                if (enabled) Tokens.textTertiary else Tokens.textDisabled, false,
            )
        }

        if (edit.text.isEmpty()) {
            if (placeholder.isNotEmpty()) {
                graphics.text(
                    font, font.plainSubstrByWidth(placeholder, inner), left, textY,
                    if (enabled) Tokens.textTertiary else Tokens.textDisabled, false,
                )
            }
            if (caret > 0f) caret(graphics, left, y, height, caret)
            return
        }

        val offset = caretOffset(font, edit.text, edit.caret, mask, revealed)
        val scroll = scrollFor(contentWidth(font, edit.text, mask, revealed), offset, inner, edit.scroll)
        edit.scroll = scroll

        // Scissored rather than truncated: a truncated string moves its own characters as the caret
        // travels, and the field would appear to retype itself on every arrow key.
        graphics.enableScissor(left, y, left + inner, y + height)
        val contentX = left - scroll
        if (mask == Mask.NONE || revealed) {
            drawText(graphics, font, edit, contentX, textY, enabled)
        } else {
            drawMask(graphics, edit, contentX, y + (height - MASK_SIZE) / 2, enabled)
        }
        if (caret > 0f) caret(graphics, contentX + offset, y, height, caret)
        graphics.disableScissor()
    }

    /** The value, in three runs, so the selected one can be inverted rather than merely tinted. */
    private fun drawText(
        graphics: GuiGraphicsExtractor, font: Font, edit: Edit,
        x: Int, y: Int, enabled: Boolean,
    ) {
        val colour = if (enabled) Tokens.textPrimary else Tokens.textDisabled
        if (!edit.hasSelection) {
            graphics.text(font, edit.text, x, y, colour, false)
            return
        }
        val head = edit.text.substring(0, edit.selectionStart)
        val body = edit.text.substring(edit.selectionStart, edit.selectionEnd)
        val tail = edit.text.substring(edit.selectionEnd)
        val headWidth = font.width(head)
        val bodyWidth = font.width(body)

        graphics.text(font, head, x, y, colour, false)
        // Solid accent with the opposite extreme written on it — the same pair an active chip uses, so
        // a selection cannot be mistaken for a highlight that means something else.
        graphics.fill(x + headWidth, y - 1, x + headWidth + bodyWidth, y + Labels.CAP + 2, Tokens.accent)
        graphics.text(font, body, x + headWidth, y, Tokens.accentText, false)
        graphics.text(font, tail, x + headWidth + bodyWidth, y, colour, false)
    }

    /**
     * The masked value: one square per character, drawn as rectangles.
     *
     * Not `•`. The vanilla bitmap font has no bullet, so it would fall through to the Unifont fallback
     * and arrive at a different weight and baseline than the placeholder beside it — the same reason
     * `Glyphs` draws its marks rather than typing them.
     */
    private fun drawMask(graphics: GuiGraphicsExtractor, edit: Edit, x: Int, y: Int, enabled: Boolean) {
        val colour = if (enabled) Tokens.textPrimary else Tokens.textDisabled
        for (i in edit.text.indices) {
            val dotX = x + i * MASK_ADVANCE
            val selected = edit.hasSelection && i >= edit.selectionStart && i < edit.selectionEnd
            if (selected) {
                graphics.fill(dotX - 1, y - 3, dotX + MASK_ADVANCE - 1, y + MASK_SIZE + 3, Tokens.accent)
            }
            graphics.fill(
                dotX, y, dotX + MASK_SIZE, y + MASK_SIZE,
                if (selected) Tokens.accentText else colour,
            )
        }
    }

    /** One device pixel, full height of the text, in the accent so it cannot be read as a letter. */
    private fun caret(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int, alpha: Float) {
        val top = y + (height - CARET_HEIGHT) / 2
        DevicePixels.hairlineV(graphics, x, top, CARET_HEIGHT, Tokens.fade(Tokens.accent, alpha))
    }

    private const val CARET_HEIGHT = 11

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
