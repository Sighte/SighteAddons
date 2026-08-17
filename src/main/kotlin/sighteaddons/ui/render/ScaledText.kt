package sighteaddons.ui.render

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Text at twice the vanilla bitmap size, and the one place that decision is written down.
 *
 * The two centred overlays and the HUD's room clock are the only things in this UI that want a line
 * bigger than 9 px, and until the bundled TTF lands there is exactly one way to get one: scale the
 * pose by a whole number. `Tokens.TEXT_20` is what the type scale asks for here; 18 is what a 9 px
 * bitmap can actually produce, and the difference is why the token is a stand-in rather than a
 * rasterisation — see the type scale's own note.
 *
 * **Whole numbers only.** At 1.5 or 1.75 the glyph grid lands between pixels and the whole line goes
 * soft, because there is no antialiasing on the GUI path and the font atlas is not filtered for it.
 * Two is therefore not a taste; it is the only step available above one.
 *
 * ### Why the chrome is not drawn in here
 *
 * [DevicePixels.push] refuses any pose that is not a pure translation, because a hairline cannot be
 * snapped to the device grid through a scale it does not know about. So everything inside a `scale(2)`
 * scope silently loses that snapping *and* comes out twice as thick — a one-pixel border becomes a
 * two-pixel one, which is the single most visible way to make a card look home-made.
 *
 * Hence the split every caller here follows: cards, borders, highlights and glyphs are drawn in GUI
 * space, and the pose is entered for the text and left again immediately. That is also why [draw]
 * pushes and pops around one run rather than handing the caller an open scope — an open scope is an
 * invitation to draw a border inside it.
 */
internal object ScaledText {

    /** The only scale factor. See the class note for why it is not configurable. */
    const val SCALE = 2.0f

    /** Vanilla's bitmap line height, before scaling. */
    const val LINE = 9

    /** What one line occupies on screen, in GUI pixels. Every box around this text is measured off it. */
    const val HEIGHT = 18

    /** [value]'s width on screen, in GUI pixels — not in font units. */
    fun width(font: Font, value: String): Int = Math.round(font.width(value) * SCALE)

    /**
     * One run of text with its top-left at ([x], [y]) in GUI pixels.
     *
     * No drop shadow, ever. These lines sit over a bright, moving dungeon and the backdrop is a scrim,
     * which is both cheaper and cleaner than doubling every glyph — the same call the HUD made.
     */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, value: String, x: Int, y: Int, argb: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(SCALE, SCALE)
        graphics.text(font, value, 0, 0, argb, false)
        pose.popMatrix()
    }
}
