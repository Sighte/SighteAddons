package sighteaddons.ui.screens

import sighteaddons.ui.components.Nav
import sighteaddons.ui.components.TextField
import sighteaddons.ui.theme.Tokens

/**
 * The shape of one page of the `/sa` screen: what each line is, how tall it is, and where it sits once
 * the page is taller than the space it has.
 *
 * ### Why the settings pages are a list of these and not ten hand-placed rows
 *
 * The previous version divided the space by the number of rows and squeezed the quotient into
 * `[14, 26]`, which was the only way ten fixed rows fitted a 270-pixel screen at GUI scale 4. At 14 the
 * switch it drew was 8 pixels tall. Sections and one-line explanations make that arithmetic impossible
 * — the debug tab is over thirty lines now — so the pages scroll instead, and a line states its own
 * height rather than being handed one.
 *
 * ### One [Item] with a [Kind] rather than a sealed hierarchy
 *
 * Every page here — three settings tabs and the stats overview — is a flat list of lines drawn by one
 * loop, hit-tested by one function and scrolled by one offset. A sealed hierarchy would put each line's
 * height, hit box and drawing in a different place from the loop that has to agree with all three, and
 * the bug that produces is a row whose highlight is in one place and whose click is in another. The tag
 * is deliberately visible: adding a kind means visiting exactly the loop and this file.
 *
 * ### Deliberately free of Minecraft types
 *
 * Heights and offsets are ints. That is what makes the scroll clamp and the hit test checkable without
 * a client — and both are invisible when wrong: a page that scrolls one row too far looks like a page,
 * and a row that acts on its neighbour looks like a misclick.
 */
internal object SettingsPage {

    /**
     * What a line is.
     *
     * [SECTION] and [NOTE] are the structure, [TOGGLE] to [SLIDER] are the controls, and [STAT] is a
     * figure with its sample beside it — the stats overview's only line kind, here rather than in its
     * own list so both pages share the loop and the scroll.
     *
     * **[NOTE] is no longer what explains a row.** An explanation is [Item.notes] now and appears in a
     * tooltip while the cursor is on the row, because thirty-odd permanent grey sentences made the
     * pages twice as long as the settings on them and pushed the switch somebody came for below the
     * fold. What is left on a [NOTE] line is the handful of sentences that are *not* explanations but
     * statements about the current value — what a consent switch is sending right now, what a low scrim
     * costs, which rows below are inert because the panel above them is closed. Those have to be
     * readable without a cursor on them: a switch that publishes a name has to be legible before the
     * click, not after somebody sees their name on a board.
     */
    enum class Kind { SECTION, NOTE, TOGGLE, ACTION, INFO, STEPPER, SLIDER, STAT }

    /**
     * One line.
     *
     * [click] is what a press does; [step] is the stepper's version of it, taking the direction, so the
     * two arms are one lambda rather than two; [slide] is the slider's, taking the fraction the cursor
     * is asking for. A line with none of the three is not a target and gets no hover wash — which is
     * the only signal separating a fact from a control on a screen with no hue.
     *
     * [slide] takes a fraction rather than a value because the bounds belong to whoever built the item:
     * the scrim's floor is a *measured* contrast limit in `Tokens`, not a number this file or the
     * screen may hold a copy of.
     */
    class Item(
        val kind: Kind,
        val label: String,
        val value: String = "",
        val meta: String = "",
        val on: Boolean = false,
        val thin: Boolean = false,
        val fraction: Float = -1f,
        val click: (() -> Unit)? = null,
        val step: ((back: Boolean) -> Unit)? = null,
        val slide: ((fraction: Float) -> Unit)? = null,
    ) {
        /**
         * What this row does, in a sentence or two, shown as a tooltip while the cursor is on it.
         *
         * **A property filled after construction rather than a constructor argument, and that is a
         * builder buffer and not shared state.** An explanation is written *after* the row it explains
         * in every one of these pages — that is the order somebody reads them in and the order they
         * were in when they were lines — so the builder adds the row and then hangs its sentences off
         * it. Every page but the stats overview is rebuilt from scratch each frame and none of them is
         * kept across one, so nothing here outlives the frame that filled it.
         *
         * A list rather than one string because several rows have two things worth saying, and
         * `Tooltip` already draws as many lines as it is handed.
         */
        val notes = ArrayList<String>()

        /**
         * How tall this line is.
         *
         * A [Kind.STAT] with a bar is taller by exactly the bar: the bar sits under the figure it
         * qualifies rather than beside it, because a bar and a number competing for the same row is a
         * row where neither is read.
         */
        val height: Int
            get() = when (kind) {
                Kind.SECTION -> SECTION
                Kind.NOTE -> NOTE
                Kind.STAT -> if (fraction >= 0f) ROW + BAR else ROW
                else -> ROW
            }

        /** Whether a press on this line does anything, which is what earns it a hover wash. */
        val interactive: Boolean
            get() = click != null || step != null || slide != null
    }

    /** A control row. Tall enough for a switch that is still a switch at GUI scale 4. */
    const val ROW = 20

    /**
     * A statement about the current value, under the row it belongs to. One line, never wrapped.
     *
     * Not an explanation any more — see [Kind] and [Item.notes]. What still earns a line of its own is
     * a sentence a reader must not have to go looking for.
     */
    const val NOTE = 11

    /**
     * A heading and the air above it.
     *
     * The air is part of the heading rather than a gap after the previous row, so the first section on
     * a page provides the page's own top padding and nothing has to special-case index zero.
     */
    const val SECTION = Tokens.SPACE_24

    /** The strip a [Kind.STAT] line's bar occupies under its figure. */
    const val BAR = Tokens.SPACE_6

    /** How tall the whole page is. */
    fun total(items: List<Item>): Int {
        var height = 0
        for (item in items) height += item.height
        return height
    }

    /**
     * The top of every line, measured from the top of the page.
     *
     * Returned as one array rather than recomputed per line, because the draw and the hit test have to
     * walk the identical sequence: a page whose click positions are summed in a different order than
     * its rows are drawn is a page that works until a line changes height.
     */
    fun tops(items: List<Item>): IntArray {
        val out = IntArray(items.size)
        var cursor = 0
        for (index in items.indices) {
            out[index] = cursor
            cursor += items[index].height
        }
        return out
    }

    /**
     * Which line contains [y], measured from the top of the page, or `-1`.
     *
     * Half-open on both sides, so the last pixel of a row belongs to that row and the first pixel of
     * the next belongs to the next. An inclusive upper bound is how two adjacent switches both respond
     * to one click on the hairline between them.
     */
    fun itemAt(items: List<Item>, y: Int): Int {
        if (y < 0) return -1
        var cursor = 0
        for (index in items.indices) {
            val next = cursor + items[index].height
            if (y < next) return index
            cursor = next
        }
        return -1
    }
}

/**
 * Where the `/sa` screen's panel sits inside a window of a given GUI-scaled size.
 *
 * Extracted from the screen so a test can walk the sizes Minecraft's auto scale actually produces —
 * 480×270 on a 1080p display, 456 on 1366×768, 427 on both 1280×720 and 2560×1440, and the vanilla
 * minimum of 320×240. Every layout bug this screen has had came from being reasoned about at one of
 * those and shipped for all of them, and a formula only the screen knows is a formula only the screen
 * can be wrong about.
 */
internal object Frame {

    const val MARGIN = 20
    const val GAP = Tokens.SPACE_24

    /** The widest the content column is allowed to be, however wide the window is. */
    const val CONTENT_MAX = 460

    fun width(guiWidth: Int): Int = minOf(Nav.WIDTH + GAP + CONTENT_MAX, guiWidth - MARGIN * 2)

    fun left(guiWidth: Int): Int = (guiWidth - width(guiWidth)) / 2

    fun contentLeft(guiWidth: Int): Int = left(guiWidth) + Nav.WIDTH + GAP

    fun content(guiWidth: Int): Int = width(guiWidth) - Nav.WIDTH - GAP

    /** The top of the page area, which is the same at every size — the header above it is fixed. */
    val bodyTop: Int get() = MARGIN + Tokens.SPACE_12 + Tokens.SPACE_32

    fun listBottom(guiHeight: Int): Int = guiHeight - MARGIN - Tokens.SPACE_16

    // --- The records page's bands ------------------------------------------------------------
    //
    // Here rather than on the screen, for this file's own reason: these four numbers decide how many
    // rows a table has, and how many rows a table has is the one thing about this screen that has been
    // wrong at a size nobody opened the game at. A screen cannot be built in a test; this can, so
    // `SettingsPageTest` walks the sizes Minecraft's auto scale actually hands out and states the
    // answer at each of them.

    /** The view chooser's row, at the top of the records page. */
    val chooserTop: Int get() = bodyTop

    /** The type chips, under the chooser. */
    val chipsTop: Int get() = bodyTop + Tokens.SPACE_24

    /**
     * The column headers. [chips] is whether this view has a chip row above them, which only the room
     * history does — the two personal-best views have no filter, so their columns come straight under
     * the chooser and they pay nothing for it.
     */
    fun columnsTop(chips: Boolean): Int = if (chips) chipsTop + Tokens.SPACE_24 else chipsTop

    /** The first data row. */
    fun rowsTop(chips: Boolean): Int = columnsTop(chips) + Tokens.SPACE_16

    /**
     * How many whole rows of [row] pixels fit, which is also how far a press on the list may land.
     *
     * At least one, because a table with no rows is a table whose scroll clamp divides by nothing and
     * whose press zone is empty — and a window that small is one the player is about to resize anyway.
     */
    fun rows(guiHeight: Int, chips: Boolean, row: Int): Int =
        ((listBottom(guiHeight) - rowsTop(chips)) / row).coerceAtLeast(1)
}

/**
 * The scroll offset arithmetic, shared by every list on the `/sa` screen.
 *
 * One clamp for all of them, in whatever unit the caller counts in — the history table counts rows
 * because its rows are a fixed height, the settings and stats pages count pixels because theirs are
 * not. The unit never appears here, which is exactly why one function can serve both: `total`,
 * `visible` and the offset are the same unit as each other and that is the whole contract.
 *
 * Separate from the screen because both failures are silent. Clamp too generously and the list scrolls
 * past its own end into blank space, which reads as the data having been lost; clamp too tightly and
 * the last row is unreachable, which reads as the data never having been written.
 */
internal object Scroll {

    /** Units per wheel notch, in whatever the caller's unit is. Three, as the table has always used. */
    const val NOTCHES = 3

    /** [offset] brought inside `[0, total - visible]`, which is empty when everything fits. */
    fun clamp(offset: Int, total: Int, visible: Int): Int =
        offset.coerceIn(0, (total - visible).coerceAtLeast(0))

    /**
     * The offset after a wheel event of [scrollY], where one notch moves [step] units.
     *
     * [scrollY] is positive upward, which is why it is subtracted: scrolling up moves the *window*
     * toward the top of the list, which is a smaller offset.
     */
    fun wheel(offset: Int, scrollY: Double, step: Int, total: Int, visible: Int): Int =
        clamp(offset - scrollY.toInt() * NOTCHES * step, total, visible)
}
