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

    var anchor = defaultAnchor
        private set
    var offsetX = defaultOffsetX
        private set
    var offsetY = defaultOffsetY
        private set

    /** This element's top-left corner on a [screenW]×[screenH] screen, if it is [w]×[h] in size. */
    fun origin(screenW: Int, screenH: Int, w: Int, h: Int): HudPlacement.Origin =
        HudPlacement.origin(anchor, offsetX, offsetY, screenW, screenH, w, h)

    /**
     * Puts the top-left corner at ([x], [y]) and derives the anchor that position reads as.
     *
     * The editor drags in absolute pixels because that is what a hand does; which of the nine edges
     * the result hangs off is [HudPlacement.nearest]'s answer, not a question anybody is asked.
     */
    fun place(x: Int, y: Int, screenW: Int, screenH: Int, w: Int, h: Int) =
        set(HudPlacement.nearest(x, y, screenW, screenH, w, h))

    /** Back to where a fresh install has it. */
    fun reset() {
        anchor = defaultAnchor
        offsetX = defaultOffsetX
        offsetY = defaultOffsetY
    }

    /**
     * The three values as one, so the editor can put them back.
     *
     * An allocation, and it happens once when placement mode opens — not per frame. The editor's
     * escape hatch has to remember a position across a drag that changes it a hundred times, and three
     * fields per element on the screen that does the dragging is how the card's version of this ended
     * up with a comment explaining which of two flags belonged to which mode.
     */
    fun snapshot(): HudPlacement.Placement = HudPlacement.Placement(anchor, offsetX, offsetY)

    fun set(placement: HudPlacement.Placement) {
        anchor = placement.anchor
        offsetX = placement.offsetX
        offsetY = placement.offsetY
    }

    /**
     * `centre · 0, -45` — the anchor first, because it is the half that decides what the offset means.
     *
     * One function for the settings row and for the label under the element being dragged: they say the
     * same thing and there is no reading in which one of them should be able to say it differently.
     */
    fun label(): String = "${anchor.label} · $offsetX, $offsetY"

    fun read(obj: JsonObject) {
        anchor = HudPlacement.Anchor.of(ConfigMigration.stringOr(obj, anchorKey, anchor.name), defaultAnchor)
        offsetX = ConfigMigration.intOr(obj, offsetXKey, offsetX)
        offsetY = ConfigMigration.intOr(obj, offsetYKey, offsetY)
    }

    fun write(obj: JsonObject) {
        obj.addProperty(anchorKey, anchor.name)
        obj.addProperty(offsetXKey, offsetX)
        obj.addProperty(offsetYKey, offsetY)
    }
}
