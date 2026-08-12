package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * The short, large line that flashes over the middle of the screen when you finish a room.
 *
 * Deliberately not part of the HUD block in the corner. That one is a standing readout you look at
 * when you choose to; this is a single event that has to land without you looking away from the
 * fight — so it is centred, drawn at double size, and gone again within three seconds.
 *
 * Only your own rooms trigger it, on the same one-second presence bar the history uses, so the
 * popup can never show a room the records will not have.
 *
 * Timed in wall-clock milliseconds rather than run ticks: the last room of a run finishes right as
 * the tick counter stops, and a popup frozen on screen from then on would be the one you remember.
 */
object ClearPopup {
    private const val HOLD_MS = 2200L
    private const val FADE_MS = 800L

    /** Double size. The font is a 9px bitmap, so anything fractional lands between pixels. */
    private const val SCALE = 2.0f

    /** Above the crosshair: clear of the melee in the centre, still inside where you are looking. */
    private const val OFFSET_Y = -54

    private const val WHITE = 0xFFFFFF
    private const val GREY = 0xB9BEC7
    private const val GOLD = 0xFFC13B // the same gold a PB gets in chat and in /sa

    private var room: String? = null
    private var detail = ""
    private var pb = false
    private var shownAt = 0L

    fun reset() {
        room = null
    }

    /**
     * [ticks] is the local player's own time in the room — the same number that goes into the
     * history, not the party's total, so the popup and the record can never disagree.
     */
    fun show(room: String, secrets: Boolean, ticks: Int, pb: Boolean) {
        if (!Config.clearPopup) return
        this.room = room
        // Locale.ROOT: a German default locale renders "41,2", and every other number this mod
        // prints uses the dot.
        this.detail = "%s in %.1fs".format(Locale.ROOT, if (secrets) "secreted" else "cleared", ticks / 20.0)
        this.pb = pb
        shownAt = System.currentTimeMillis()
    }

    fun render(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int) {
        val name = room ?: return
        val age = System.currentTimeMillis() - shownAt
        if (age > HOLD_MS + FADE_MS) {
            room = null
            return
        }
        // Full opacity for the hold, then a linear fade — a popup that vanishes mid-frame reads as a
        // rendering glitch rather than as something ending.
        val alpha = when {
            age <= HOLD_MS -> 255
            else -> (255 * (HOLD_MS + FADE_MS - age) / FADE_MS).toInt().coerceIn(0, 255)
        }

        val label = "$name "
        val badge = if (pb) "  PB" else ""
        val width = font.width(label) + font.width(detail) + font.width(badge)

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(screenWidth / 2f, (screenHeight / 2 + OFFSET_Y).toFloat())
        pose.scale(SCALE, SCALE)

        // Drawn in parts rather than as one styled component: the alpha has to reach every piece,
        // and a component's own style colour would override it.
        var x = -width / 2
        graphics.text(font, label, x, 0, argb(alpha, WHITE), true)
        x += font.width(label)
        graphics.text(font, detail, x, 0, argb(alpha, GREY), true)
        if (pb) {
            x += font.width(detail)
            graphics.text(font, badge, x, 0, argb(alpha, GOLD), true)
        }
        pose.popMatrix()
    }

    private fun argb(alpha: Int, rgb: Int) = (alpha shl 24) or rgb
}
