package sighteaddons

import com.google.gson.JsonObject

/**
 * Where one overlay sits, as the player left it and as `config.json` remembers it.
 *
 * [HudPlacement] is the arithmetic — nine anchors, an offset inward from each, ints in and ints out,
 * no state. This is the other half: the three values for *one* element, the two keys' worth of file
 * it is written under, and the default it goes back to. The card had them as three loose fields on
 * [Config]; three elements would have been nine, and the drag, the migration and the save would each
 * have had to name all nine correctly.
 *
 * ### Why every overlay gets one
 *
 * The card was placeable and the two centred chips were not. [StormHud] used to argue that a position
 * setting for a centred chip was a setting with one right answer — and that argument was about
 * absolute pixels, which is what the source mod had and what the card had before [HudPlacement]. An
 * anchor plus an offset is not that: "forty pixels above the middle" and "eight in from the right
 * edge" survive a GUI-scale change and a resize, which is exactly what a hand-placed pixel pair does
 * not. So the objection is answered rather than overruled, and where a chip belongs — over the boss
 * bar, clear of a mod somebody else is running, out of the way of an eye that keeps landing on it —
 * is a question only the player at that screen can answer.
 *
 * ### One key prefix, three keys
 *
 * `<key>Anchor`, `<key>OffsetX`, `<key>OffsetY`, which for the card is exactly the three keys it has
 * written since [ConfigMigration]'s 0 → 1 step: this class inherited them rather than replacing them,
 * so no file is migrated and no card moves. The two new elements are additions, and an addition is the
 * case an explicit fallback per key already covers — see [Config].
 *
 * Every value is read defensively, for [ConfigMigration.intOr]'s reason: a hand-edited file is a
 * supported way to set this one, and a typo must cost the key it is in and nothing else. A misspelt
 * anchor falls back to *this* element's default rather than to the top left corner — a popup that
 * jumped to a corner the card lives in would look like the mod having lost the plot, not like a typo.
 */
class OverlayPlacement(
    key: String,
    private val defaultAnchor: HudPlacement.Anchor,
    private val defaultOffsetX: Int,
    private val defaultOffsetY: Int,
) {

    private val anchorKey = "${key}Anchor"
    private val offsetXKey = "${key}OffsetX"
    private val offsetYKey = "${key}OffsetY"
    private val scaleKey = "${key}Scale"

    var anchor = defaultAnchor
        private set
    var offsetX = defaultOffsetX
        private set
    var offsetY = defaultOffsetY
        private set

    /**
     * How big this element is drawn, as a percentage of the size it was designed at.
     *
     * ### Why a size per element and not one HUD scale
     *
     * Because the reason to change one is never the reason to change another. The card is a block of
     * text somebody reads between fights and wants small enough to see the room behind it; the split
     * clock is a number glanced at mid-swing and wants to be big. A single slider moves both and
     * settles at whichever of the two the player cared about least. The elements are placed one at a
     * time for that same reason, and a size belongs to the same act as a position: this is where the
     * element is, and how big it is there.
     *
     * ### Why percent and not a float
     *
     * `config.json` is a supported thing to edit by hand - see [ConfigMigration] - and `120` is a
     * number a person can type and mean. A float invites `1.2000000476837158` on the way back out, and
     * [ConfigMigration.intOr] is the defensive read this file already has for ints. Integers also make
     * the steps exact: ten points per notch of the wheel lands on round numbers forever, where
     * repeated multiplication by 1.1 drifts and never comes home to 100.
     *
     * Clamped on every path in, the file included, because the clamp is what stops a hand-edited `4`
     * or `100000` from producing an element too small or too large to grab and fix.
     */
    var scalePercent = DEFAULT_PERCENT
        private set

    /** [scalePercent] as the factor a pose is scaled by. */
    val scale: Float get() = scalePercent / 100f

    /** [size] in GUI pixels once drawn at [scale] - the size the screen, and a hand, actually see. */
    fun scaled(size: Int): Int = Math.round(size * scale)

    /**
     * How much of [available] GUI pixels the element has to lay out in, inside its own scaled space.
     *
     * Text that is cut to fit the screen - a room name in the popup - is cut while the element is
     * measured, which happens before the pose is scaled and therefore in a space where the screen is a
     * different size. Floored, because a prefix half a pixel too wide is one that does not fit; the
     * same reasoning as [sighteaddons.ui.render.ScaledText.fit].
     */
    fun room(available: Int): Int = (available / scale).toInt()

    /**
     * This element's top-left corner on a [screenW]×[screenH] screen, if it measures [w]×[h]
     * **before scaling**.
     *
     * The scale is applied here rather than at the five call sites: an offset counts inward from an
     * edge, so every anchor except the top left depends on how much room the element takes up, and one
     * measured unscaled but drawn half again as big hangs off the bottom right by exactly the
     * difference. Callers pass the size the element measures itself at and get back the corner the
     * scaled thing belongs in.
     */
    fun origin(screenW: Int, screenH: Int, w: Int, h: Int): HudPlacement.Origin =
        HudPlacement.origin(anchor, offsetX, offsetY, screenW, screenH, scaled(w), scaled(h))

    /**
     * Puts the top-left corner at ([x], [y]) and derives the anchor that position reads as.
     *
     * The editor drags in absolute pixels because that is what a hand does; which of the nine edges
     * the result hangs off is [HudPlacement.nearest]'s answer, not a question anybody is asked.
     */
    fun place(x: Int, y: Int, screenW: Int, screenH: Int, w: Int, h: Int) =
        set(HudPlacement.nearest(x, y, screenW, screenH, scaled(w), scaled(h)))

    /**
     * Bigger or smaller by [steps] notches of the wheel, clamped.
     *
     * Snapped onto the step grid in the direction of travel, so a hand-edited `137` becomes 140 going
     * up and 130 going down rather than carrying an odd number forever. One notch is [STEP_PERCENT]
     * points and not a factor - see [scalePercent] for why the arithmetic is integer.
     */
    fun zoom(steps: Int) {
        if (steps == 0) return
        val grid = if (steps > 0) {
            Math.floorDiv(scalePercent, STEP_PERCENT) * STEP_PERCENT
        } else {
            -Math.floorDiv(-scalePercent, STEP_PERCENT) * STEP_PERCENT
        }
        scalePercent = clamp(grid + steps * STEP_PERCENT)
    }

    /** Back to where a fresh install has it - position **and** size. */
    fun reset() {
        anchor = defaultAnchor
        offsetX = defaultOffsetX
        offsetY = defaultOffsetY
        scalePercent = DEFAULT_PERCENT
    }

    /**
     * The three values as one, so the editor can put them back.
     *
     * An allocation, and it happens once when placement mode opens — not per frame. The editor's
     * escape hatch has to remember a position across a drag that changes it a hundred times, and three
     * fields per element on the screen that does the dragging is how the card's version of this ended
     * up with a comment explaining which of two flags belonged to which mode.
     */
    fun snapshot(): Saved = Saved(anchor, offsetX, offsetY, scalePercent)

    fun set(placement: HudPlacement.Placement) {
        anchor = placement.anchor
        offsetX = placement.offsetX
        offsetY = placement.offsetY
    }

    /** Puts back everything [snapshot] took, which is the position and the size. */
    fun restore(saved: Saved) {
        anchor = saved.anchor
        offsetX = saved.offsetX
        offsetY = saved.offsetY
        scalePercent = clamp(saved.scalePercent)
    }

    /**
     * Everything the editor has to be able to undo, in one object.
     *
     * [HudPlacement.Placement] is what the drag arithmetic answers with and knows nothing about size:
     * [HudPlacement.nearest] derives one from a position alone and has to keep being able to. So the
     * escape hatch gets its own type rather than a fourth field on that one - two jobs that happen to
     * overlap in three numbers.
     */
    class Saved(
        val anchor: HudPlacement.Anchor,
        val offsetX: Int,
        val offsetY: Int,
        val scalePercent: Int,
    )

    /**
     * `centre · 0, -45` — the anchor first, because it is the half that decides what the offset means.
     *
     * One function for the settings row and for the label under the element being dragged: they say the
     * same thing and there is no reading in which one of them should be able to say it differently.
     */
    fun label(): String {
        val where = "${anchor.label} · $offsetX, $offsetY"
        // Silent at 100%, because a size nobody has changed is not news, and this same string is the
        // settings row for five elements that mostly sit at the size they were designed at.
        return if (scalePercent == DEFAULT_PERCENT) where else "$where · $scalePercent%"
    }

    fun read(obj: JsonObject) {
        anchor = HudPlacement.Anchor.of(ConfigMigration.stringOr(obj, anchorKey, anchor.name), defaultAnchor)
        offsetX = ConfigMigration.intOr(obj, offsetXKey, offsetX)
        offsetY = ConfigMigration.intOr(obj, offsetYKey, offsetY)
        scalePercent = clamp(ConfigMigration.intOr(obj, scaleKey, scalePercent))
    }

    fun write(obj: JsonObject) {
        obj.addProperty(anchorKey, anchor.name)
        obj.addProperty(offsetXKey, offsetX)
        obj.addProperty(offsetYKey, offsetY)
        obj.addProperty(scaleKey, scalePercent)
    }

    private fun clamp(percent: Int) = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    companion object {

        /** The size every element was designed at, and what a file that never mentions size means. */
        const val DEFAULT_PERCENT = 100

        /**
         * Half size, and triple.
         *
         * The floor is where the 9 px bitmap font stops being a font: below half a scaled glyph drops
         * whole rows of pixels, so the line does not get small, it gets wrong. The ceiling is where the
         * card - the widest element there is - still fits across a GUI-scale-4 screen. Past that the
         * thing being resized cannot be seen whole, which is also the state it could not be dragged
         * out of.
         */
        const val MIN_PERCENT = 50
        const val MAX_PERCENT = 300

        /**
         * One notch of the wheel.
         *
         * Ten points is about the smallest step that reads as a change at a glance - the point of the
         * wheel is that a size is found by looking, not by counting - and it cuts the range into 26
         * stops, which is a couple of flicks end to end.
         */
        const val STEP_PERCENT = 10
    }
}
