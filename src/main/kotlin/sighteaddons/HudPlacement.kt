package sighteaddons

/**
 * Where the HUD card sits, expressed so that it stays there.
 *
 * ### Why an anchor at all
 *
 * The position used to be two absolute GUI pixels from the top left. That is only a position for as
 * long as the screen keeps the size it was measured against: change the GUI scale or resize the
 * window and every one of those pixels means something else. A card parked in the bottom-right corner
 * at scale 2 is somewhere in the middle at scale 4, and a card parked near the right edge of a
 * maximised window is off the edge of a small one. The player never moved it; the frame moved under
 * it.
 *
 * So the position is an [Anchor] — one of nine points on the screen — plus an offset measured
 * **inward from that anchor**. "Eight pixels off the right edge" survives every resolution, because
 * the right edge is still the right edge.
 *
 * ### Why nine and not four corners
 *
 * Because the things the card has to avoid are not in the corners. The hotbar, health, hunger and the
 * experience bar sit bottom-centre, the boss bar top-centre, the chat bottom-left — the emptiest,
 * most stable regions of a Minecraft screen are the two side edges, and four corners give a player no
 * way to name either of them. Nine costs one extra fraction per axis and no extra concept: the same
 * two functions resolve all of them.
 *
 * The centre anchor is a poor place for a readout, and it is deliberately hard to land on by
 * accident — [nearest] only chooses it when the card was dragged into the middle third of the room it
 * has to move in, which on any normal window is a deliberate act.
 *
 * ### Deliberately free of Minecraft types
 *
 * Everything here is ints in, ints out. The screen size is a parameter rather than a `Window` lookup,
 * so every case that matters — a window narrower than the card, an offset that no longer fits, each
 * anchor there and back — is checkable without a client. That is not a stylistic preference: this
 * arithmetic is invisible when it is wrong. A card at a slightly wrong offset looks like a card.
 */
object HudPlacement {

    /** Which end of one axis an offset is measured from. */
    enum class Edge { START, CENTRE, END }

    /**
     * One of the nine points a card can hang from.
     *
     * [label] rather than the enum name because the `/sa` screen is lower case throughout, and
     * because `MIDDLE_RIGHT` is not what a player calls the right-hand edge of their screen.
     */
    enum class Anchor(val horizontal: Edge, val vertical: Edge, val label: String) {
        TOP_LEFT(Edge.START, Edge.START, "top left"),
        TOP_CENTRE(Edge.CENTRE, Edge.START, "top"),
        TOP_RIGHT(Edge.END, Edge.START, "top right"),
        MIDDLE_LEFT(Edge.START, Edge.CENTRE, "left"),
        MIDDLE_CENTRE(Edge.CENTRE, Edge.CENTRE, "centre"),
        MIDDLE_RIGHT(Edge.END, Edge.CENTRE, "right"),
        BOTTOM_LEFT(Edge.START, Edge.END, "bottom left"),
        BOTTOM_CENTRE(Edge.CENTRE, Edge.END, "bottom"),
        BOTTOM_RIGHT(Edge.END, Edge.END, "bottom right");

        companion object {
            /**
             * The anchor named [value], or [fallback] for anything else.
             *
             * A hand-edited `config.json` is a supported way to set this file — see [ConfigMigration]
             * — so a misspelt anchor has to cost the anchor and nothing else. Throwing would land in
             * [Config]'s catch-all and discard every *other* setting in the file along with it.
             *
             * [fallback] is a parameter because there are three placeable elements now and they do not
             * share a default: the card starts in the top left, the two centred chips start on the
             * crosshair. A typo has to cost the element's *own* position — falling back to the card's
             * corner would move a popup somewhere nobody has ever put one, which reads as the mod
             * having lost the plot rather than as a misspelt word. See [OverlayPlacement].
             */
            fun of(value: String, fallback: Anchor = DEFAULT_ANCHOR): Anchor =
                entries.firstOrNull { it.name == value } ?: fallback
        }
    }

    /** An anchor and its offsets: a complete placement, and what [nearest] answers with. */
    class Placement(val anchor: Anchor, val offsetX: Int, val offsetY: Int)

    /** The card's top-left corner in GUI pixels. */
    class Origin(val x: Int, val y: Int)

    /**
     * Top left, four pixels in — where the card has always started, stated in the new terms.
     *
     * The same four pixels a fresh install has had since the first version, so nothing about a new
     * config file changes with this.
     */
    val DEFAULT_ANCHOR = Anchor.TOP_LEFT
    const val DEFAULT_OFFSET = 4

    /**
     * Where a card of [cardW]×[cardH] lands on a [screenW]×[screenH] screen.
     *
     * Clamped so the card is always on screen, and clamped *here* rather than by writing a corrected
     * offset back to the config: a window that is briefly too small must not permanently rewrite a
     * placement the player chose for their real one. The stored offset is what they asked for and it
     * comes back the moment there is room for it again.
     *
     * A window narrower or shorter than the card has no honest answer; the card's own top left wins,
     * because that is the corner the room name and the clock hang off.
     */
    fun origin(anchor: Anchor, offsetX: Int, offsetY: Int, screenW: Int, screenH: Int, cardW: Int, cardH: Int) =
        Origin(
            onAxis(anchor.horizontal, offsetX, screenW, cardW),
            onAxis(anchor.vertical, offsetY, screenH, cardH),
        )

    /**
     * The placement that puts a card of [cardW]×[cardH] at exactly ([x], [y]) — which anchor that
     * position reads as, and the offset from it.
     *
     * This is how the placement editor stays a drag: the player moves the card where they want it and
     * the anchor is *derived*, rather than being asked to pick one from a list and then think in
     * offsets from it. Round-trips exactly through [origin] for every anchor, which is the property
     * that makes dropping the card leave it exactly where it was let go.
     *
     * The thirds are of the room the card has to move in, not of the screen: at the far left of the
     * screen a card is anchored left whatever its width, and at the far right, right. Measuring
     * against the raw screen instead would make a wide card unable to reach either end.
     *
     * The position is pulled onto the screen first. The editor never produces one that is not, but
     * the migration of an old absolute position does — those pixels were meant for a screen whose size
     * nothing recorded — and an offset derived from a position off the edge would be an offset that
     * puts the card back off the edge on every larger window it ever sees.
     */
    fun nearest(x: Int, y: Int, screenW: Int, screenH: Int, cardW: Int, cardH: Int): Placement {
        val onX = x.coerceIn(0, (screenW - cardW).coerceAtLeast(0))
        val onY = y.coerceIn(0, (screenH - cardH).coerceAtLeast(0))
        val horizontal = edgeFor(onX, screenW, cardW)
        val vertical = edgeFor(onY, screenH, cardH)
        val anchor = Anchor.entries.first { it.horizontal == horizontal && it.vertical == vertical }
        return Placement(
            anchor,
            offsetOnAxis(horizontal, onX, screenW, cardW),
            offsetOnAxis(vertical, onY, screenH, cardH),
        )
    }

    private fun onAxis(edge: Edge, offset: Int, screen: Int, card: Int): Int {
        val free = screen - card
        val raw = when (edge) {
            Edge.START -> offset
            Edge.CENTRE -> free / 2 + offset
            Edge.END -> free - offset
        }
        return raw.coerceIn(0, free.coerceAtLeast(0))
    }

    private fun offsetOnAxis(edge: Edge, position: Int, screen: Int, card: Int): Int {
        val free = screen - card
        return when (edge) {
            Edge.START -> position
            Edge.CENTRE -> position - free / 2
            Edge.END -> free - position
        }
    }

    /**
     * Which third of its travel a card at [position] is in.
     *
     * A screen with no room to move in — one narrower or shorter than the card itself — anchors to the
     * start of the axis: the offset is zero either way, and flush against the edge is the answer that
     * still means something once the window is a normal size again.
     */
    private fun edgeFor(position: Int, screen: Int, card: Int): Edge {
        val free = screen - card
        if (free <= 0) return Edge.START
        val travelled = position.coerceIn(0, free).toFloat() / free
        return when {
            travelled < 1f / 3f -> Edge.START
            travelled < 2f / 3f -> Edge.CENTRE
            else -> Edge.END
        }
    }
}
