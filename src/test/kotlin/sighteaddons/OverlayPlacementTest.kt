package sighteaddons

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The four things about a placeable overlay that are invisible when they are wrong.
 *
 * Nothing here is about how a chip looks — that is `Minecraft` calls end to end, and the gallery's
 * overlay page is what looks at it. What is decidable without a game is the arithmetic, and this is the
 * arithmetic whose failures all look like something other than a bug:
 *
 * 1. **The defaults.** Both chips were hard-coded at `screenHeight / 2 ± n` and are now an anchor plus
 *    an offset from it. Those are two different expressions of the same pixel and there is no way to
 *    tell by looking that one of them has drifted — a popup eight pixels higher than it used to be is
 *    still a popup.
 * 2. **The file.** Four keys per element are written and four are read, in two different functions. A
 *    single one misspelt on one side loses a position — or a size — on restart and nothing anywhere
 *    says so, which reads as the mod forgetting rather than as a typo.
 * 3. **The drop.** The editor puts the element where the hand stopped and stores the anchor that
 *    position reads as. If the two do not round-trip exactly, everything the player places settles a
 *    few pixels from where they let go of it.
 * 4. **The size.** An offset counts inward from an edge, so an element measured at its designed size
 *    but drawn at 150% belongs somewhere else entirely — and only at the anchors that count from the
 *    far edge, and only for a player who has changed one. It is the drop's failure again, scaled.
 *
 * [Config] itself cannot be loaded here — its path is a `FabricLoader` call in a field initialiser — so
 * these build their own [OverlayPlacement]s from the same defaults [Config] hands it.
 */
class OverlayPlacementTest {

    private fun popup() =
        OverlayPlacement("clearPopup", ClearPopup.DEFAULT_ANCHOR, ClearPopup.DEFAULT_OFFSET_X, ClearPopup.DEFAULT_OFFSET_Y)

    private fun timer() =
        OverlayPlacement("stormTimer", StormHud.DEFAULT_ANCHOR, StormHud.DEFAULT_OFFSET_X, StormHud.DEFAULT_OFFSET_Y)

    /** Every key prefix [Config] hands an [OverlayPlacement], in the order the file is written. */
    private val prefixes = listOf("hud", "clearPopup", "stormTimer", "splits", "splitsCurrent")

    /** Anchor, two offsets, size. */
    private val KEYS_PER_ELEMENT = 4

    /**
     * Five placeable elements share one flat config object, and each one owns four keys in it.
     *
     * **A repeated prefix is silent and total.** Two elements given the same one write over each other
     * on save and read the same position on load, so both settle in the same place and the second is
     * simply not placeable any more — with nothing in the file, the log or the screen to say which pair
     * collided. It cost nothing to check when there were three; the splits port took it to five, two of
     * which differ from each other only by a suffix (`splits`, `splitsCurrent`), which is exactly the
     * pair a copied line would produce.
     *
     * Counted rather than compared name by name: four keys per element and none of them shared is the
     * property, and it stays true of a sixth element added later without this case having to learn its
     * name. The fourth is the size — see [OverlayPlacement.scalePercent] — and it is the newest chance
     * for a copied line to collide, since it is the one key that was added to all five at once.
     */
    @Test
    fun `no two placeable elements share a config key`() {
        val obj = JsonObject()
        prefixes.forEach { OverlayPlacement(it, HudPlacement.DEFAULT_ANCHOR, 1, 2).write(obj) }
        assertEquals(prefixes.size * KEYS_PER_ELEMENT, obj.size(), "one of these prefixes wrote over another")
    }

    /**
     * A fresh install draws both chips on exactly the pixels they were drawn on before either was
     * placeable.
     *
     * The old code was `left = (screenWidth - width) / 2` and `top = screenHeight / 2 + OFFSET_Y - PAD_Y`
     * — 54 px above the crosshair for the popup and 24 below it for the countdown. The sizes below are
     * the GUI-scaled screens Minecraft's own auto scale hands out, including the two the record columns
     * were found to be broken at, plus an odd pair so the integer halving is exercised in both parities.
     */
    @Test
    fun `the defaults are where the two chips have always been drawn`() {
        val screens = listOf(480 to 270, 456 to 256, 427 to 240, 640 to 360, 481 to 271, 342 to 253)
        for ((screenW, screenH) in screens) {
            for (chipW in listOf(60, 180, 342)) {
                val popupAt = popup().origin(screenW, screenH, chipW, ClearPopup.HEIGHT)
                assertEquals(
                    (screenW - chipW).coerceAtLeast(0) / 2, popupAt.x,
                    "the popup is centred on a ${screenW}x$screenH screen at $chipW wide",
                )
                assertEquals(
                    screenH / 2 - 54 - 6, popupAt.y,
                    "the popup sits 54 px above the crosshair on a ${screenW}x$screenH screen",
                )

                val timerAt = timer().origin(screenW, screenH, chipW, StormHud.HEIGHT)
                assertEquals((screenW - chipW).coerceAtLeast(0) / 2, timerAt.x, "the countdown is centred")
                assertEquals(
                    screenH / 2 + 24 - 6, timerAt.y,
                    "the countdown sits 24 px below the crosshair on a ${screenW}x$screenH screen",
                )
            }
        }

        // And a window with no room for the chip at all keeps it on screen rather than off the top left,
        // which the two hard-coded expressions above did not: at 60x60 the popup's old top was −30.
        val squeezed = popup().origin(60, 60, 200, ClearPopup.HEIGHT)
        assertEquals(0, squeezed.x)
        assertEquals(0, squeezed.y)
    }

    /**
     * A position survives being written to a file and read back out of it, and a hand-edited nonsense
     * anchor costs the anchor and nothing else.
     *
     * The fallback is the *element's own* default and not the card's corner, which is the whole reason
     * [HudPlacement.Anchor.of] takes one: a popup that reappeared in the top left after a typo would
     * read as the mod having lost the plot rather than as a misspelt word.
     */
    @Test
    fun `a placement survives the file, and a misspelt anchor costs only the anchor`() {
        val saved = popup()
        saved.place(300, 12, 480, 270, 180, ClearPopup.HEIGHT)
        val obj = JsonObject()
        saved.write(obj)

        val loaded = popup()
        loaded.read(obj)
        assertEquals(saved.anchor, loaded.anchor, "the anchor came back")
        assertEquals(saved.offsetX, loaded.offsetX)
        assertEquals(saved.offsetY, loaded.offsetY)
        assertEquals(saved.label(), loaded.label())

        // The countdown's keys are its own: reading a popup's file must not move the countdown.
        val other = timer()
        other.read(obj)
        assertEquals(StormHud.DEFAULT_ANCHOR, other.anchor, "one chip's keys are not the other's")
        assertEquals(StormHud.DEFAULT_OFFSET_Y, other.offsetY)

        obj.addProperty("clearPopupAnchor", "TOP_LEFTISH")
        val typo = popup()
        typo.read(obj)
        assertEquals(ClearPopup.DEFAULT_ANCHOR, typo.anchor, "back to the crosshair, not to the card's corner")
        assertEquals(saved.offsetX, typo.offsetX, "the offsets in the same file are untouched")
        assertEquals(saved.offsetY, typo.offsetY)
    }

    /**
     * A size survives the file, a nonsense one in it costs nothing, and the wheel lands on round numbers.
     *
     * All three are invisible when they are wrong in the way this suite exists for. A size that does not
     * round-trip is a HUD that quietly returns to its designed size on restart — which reads as the mod
     * forgetting rather than as a misspelt key. A hand-edited `4` clamps to something that can still be
     * grabbed and scrolled back, because an element two pixels across cannot be picked up to fix itself.
     * And the wheel is stepped in whole points so that a size found by flicking is a number a player can
     * recognise, rather than 1.1 to the seventh power.
     */
    @Test
    fun `a size survives the file, and the wheel stays on the grid`() {
        val slot = popup()
        assertEquals(OverlayPlacement.DEFAULT_PERCENT, slot.scalePercent, "a fresh element is at its designed size")

        slot.zoom(3)
        assertEquals(130, slot.scalePercent, "three notches up from 100")
        slot.zoom(-1)
        assertEquals(120, slot.scalePercent, "and one back down")

        val obj = JsonObject()
        slot.write(obj)
        val loaded = popup()
        loaded.read(obj)
        assertEquals(120, loaded.scalePercent, "the size came back out of the file")
        assertEquals(slot.label(), loaded.label(), "and so did the label that states it")

        // The label says the size only when there is something to say about it.
        assertEquals(true, loaded.label().endsWith("· 120%"), "the label carries a changed size: ${loaded.label()}")
        loaded.reset()
        assertEquals(OverlayPlacement.DEFAULT_PERCENT, loaded.scalePercent, "r puts the size back too")
        assertEquals(false, loaded.label().contains('%'), "and says nothing about it again: ${loaded.label()}")

        // A hand-edited file, at both ends of unreasonable.
        obj.addProperty("clearPopupScale", 4)
        val tiny = popup()
        tiny.read(obj)
        assertEquals(OverlayPlacement.MIN_PERCENT, tiny.scalePercent, "a size too small to grab is clamped")
        obj.addProperty("clearPopupScale", 100000)
        val huge = popup()
        huge.read(obj)
        assertEquals(OverlayPlacement.MAX_PERCENT, huge.scalePercent, "and one too large to see whole")

        // An odd number, from a hand or from an older step, joins the grid in the direction of travel
        // rather than carrying its remainder forever.
        obj.addProperty("clearPopupScale", 137)
        val odd = popup()
        odd.read(obj)
        odd.zoom(1)
        assertEquals(140, odd.scalePercent, "up from 137 lands on the grid")
        obj.addProperty("clearPopupScale", 137)
        val oddDown = popup()
        oddDown.read(obj)
        oddDown.zoom(-1)
        assertEquals(130, oddDown.scalePercent, "and down from 137 does too")

        // The wheel cannot leave the range whatever it is handed.
        val edge = popup()
        edge.zoom(-100)
        assertEquals(OverlayPlacement.MIN_PERCENT, edge.scalePercent)
        edge.zoom(100)
        assertEquals(OverlayPlacement.MAX_PERCENT, edge.scalePercent)
    }

    /**
     * A scaled element is placed by the rectangle it is *drawn* at, not the one it measures.
     *
     * This is the case that is invisible in code and obvious on screen. An offset counts inward from an
     * edge, so at every anchor except the top left the corner depends on the element's size: a chip
     * measured at 60 px, drawn at 150% and placed 8 px off the right edge belongs 98 px from the right,
     * not 68. Get it wrong and the element hangs off the screen by exactly the difference — and only at
     * the four anchors nobody tests by hand, and only once somebody has changed a size.
     *
     * The drop is the same property the unscaled case has: released where the hand let go, at every
     * size. If [OverlayPlacement.place] and [OverlayPlacement.origin] did not apply the same factor,
     * everything placed at 150% would settle a proportion of its own width away from the cursor.
     */
    @Test
    fun `a scaled element is placed by the size it is drawn at`() {
        val screenW = 480
        val screenH = 270
        val chipW = 60
        val chipH = ClearPopup.HEIGHT

        val right = popup()
        right.place(screenW - chipW, 20, screenW, screenH, chipW, chipH)
        val before = right.origin(screenW, screenH, chipW, chipH)
        assertEquals(screenW - chipW, before.x, "flush right at its designed size")

        right.zoom(5)
        assertEquals(150, right.scalePercent)
        val after = right.origin(screenW, screenH, chipW, chipH)
        assertEquals(
            screenW - right.scaled(chipW), after.x,
            "still flush right once it is half again as wide",
        )
        assertEquals(90, right.scaled(chipW), "and that width is the drawn one")

        for (percent in listOf(50, 100, 170, 300)) {
            for ((x, y) in listOf(0 to 0, 57 to 200, 260 to 40)) {
                val slot = popup()
                slot.zoom((percent - OverlayPlacement.DEFAULT_PERCENT) / OverlayPlacement.STEP_PERCENT)
                assertEquals(percent, slot.scalePercent, "the fixture is at the size it says")
                val w = slot.scaled(chipW)
                val h = slot.scaled(chipH)
                val onX = x.coerceAtMost(screenW - w)
                val onY = y.coerceAtMost(screenH - h)
                slot.place(onX, onY, screenW, screenH, chipW, chipH)
                val back = slot.origin(screenW, screenH, chipW, chipH)
                assertEquals(onX, back.x, "dropped at $onX,$onY at $percent% and came back at ${back.x}")
                assertEquals(onY, back.y, "dropped at $onX,$onY at $percent% and came back at ${back.y}")
            }
        }
    }

    /**
     * Dropping an element leaves it exactly where the hand let go, at every anchor a drag can produce.
     *
     * [OverlayPlacement.place] stores what [HudPlacement.nearest] reads off the position, and
     * [OverlayPlacement.origin] resolves that back — the two are the drag and the draw, and this is the
     * property that makes a release not move the thing being released. Two chip sizes, because a chip's
     * width is its content and the same drop has to hold for a narrow one.
     */
    @Test
    fun `dropping an element leaves it where the hand let go`() {
        val screenW = 480
        val screenH = 270
        for (chipW in listOf(60, 180)) {
            val chipH = ClearPopup.HEIGHT
            val corners = listOf(
                0 to 0,
                screenW - chipW to 0,
                0 to screenH - chipH,
                screenW - chipW to screenH - chipH,
                (screenW - chipW) / 2 to (screenH - chipH) / 2,
                57 to 200,
            )
            for ((x, y) in corners) {
                val slot = popup()
                slot.place(x, y, screenW, screenH, chipW, chipH)
                val back = slot.origin(screenW, screenH, chipW, chipH)
                assertEquals(x, back.x, "dropped at $x,$y with a $chipW px chip and came back at ${back.x}")
                assertEquals(y, back.y, "dropped at $x,$y with a $chipW px chip and came back at ${back.y}")
            }
        }
    }
}
