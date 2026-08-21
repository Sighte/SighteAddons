package sighteaddons.ui.components

import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * The vertical nav rail: one entry per page, down the left edge of the panel.
 *
 * A rail and not a row of tabs because the thing being switched is a whole page, and vertical entries
 * can carry a longer word without the header running out of room.
 *
 * ### What changed when the renderer did
 *
 * [WIDTH] used to be the constant `88`, with a comment claiming it was "wide enough for the longest
 * page name plus its indicator". That was true of one font at one size, and unverifiable afterwards —
 * exactly the kind of number that is right on the machine it was written on. [widthFor] measures it
 * instead. The measurement has to be passed in as a lambda rather than taken here, for the reason
 * `RecordColumns` already gives: measuring is render-thread-only, and this arithmetic has to stay
 * checkable without a client.
 *
 * [WIDTH] survives as the *fallback* only, for the one frame before anything has been measured.
 */
internal object Nav {

    /** One entry's height. Unchanged by the new font — it was right. */
    const val ROW = 24

    /**
     * The rail's assumed width before anything has been measured.
     *
     * Kept because `Frame` is pure arithmetic that a test walks, and it needs *a* number for the case
     * where no measurement has happened yet. Any frame that has drawn once uses [widthFor].
     */
    const val WIDTH = 88

    /** Padding either side of an entry's label. */
    const val PAD = Tokens.SPACE_12

    /** The selected entry's marker: a short bar at its leading edge. */
    const val INDICATOR = 2f

    /** Where entry [index] starts, measured from the rail's [top]. */
    fun rowY(top: Int, index: Int): Int = top + index * ROW

    /**
     * Which entry a cursor at [mouseY] is over, or `-1`.
     *
     * Pure, so a screen's hit testing and its drawing cannot disagree about where an entry is — the
     * bug that puts a row's highlight one place and its click somewhere else.
     */
    fun rowAt(top: Int, count: Int, mouseY: Int): Int {
        if (mouseY < top) return -1
        val index = (mouseY - top) / ROW
        return if (index in 0 until count) index else -1
    }

    /**
     * How wide the rail has to be to hold [labels] without truncating any of them.
     *
     * Rounded up to a whole pixel so the content column beside it starts on one: a rail at a
     * fractional width puts every row on this screen half a pixel off the grid, which a proportional
     * font makes visible as inconsistent left edges down the page.
     */
    fun widthFor(labels: List<String>, measure: (String) -> Float): Int {
        var widest = 0f
        for (label in labels) widest = maxOf(widest, measure(label))
        return Math.ceil((widest + PAD * 2f + INDICATOR).toDouble()).toInt()
    }

    /**
     * The narrowest the content column may get before the rail has to go.
     *
     * Not invented here: 168 is what the old layout left the content column at the vanilla minimum of
     * 320x240, and the docs record that as the size at which "something has to go". So the floor comes
     * from the one measurement that was already made.
     */
    const val CONTENT_MIN = 168

    /**
     * Whether a panel [width] wide can afford the rail beside a usable content column.
     *
     * The user's call was to measure the collapse point rather than pick one, and this is that
     * measurement. Below it the rail folds into a [Segmented] control in the header: one row of height
     * spent to buy back the rail's whole width, which is the trade the vanilla minimum forces.
     *
     * Pure and takes both widths, so a test can walk the sizes Minecraft's auto scale actually hands
     * out — 480, 456, 427 and 320 — rather than this being asserted by a comment.
     */
    fun railFits(width: Int, railWidth: Int, gap: Int): Boolean =
        width - railWidth - gap >= CONTENT_MIN

    /**
     * One entry.
     *
     * [select] and [hover] stay separate on purpose: the indicator is the state and the wash is the
     * feedback, and a selected entry that is also hovered must not double up on either.
     *
     * The label steps to [Type.MEDIUM] once selected, and that is the whole reason the second weight
     * was bundled. With an achromatic palette there is no colour available to say "this is the current
     * page", so before, brightness had to carry it alone — which is why the rail's unselected entries
     * had to sit as dim as `textTertiary` to leave the selected one somewhere to be.
     */
    fun item(x: Float, y: Float, width: Float, height: Float, label: String, select: Float, hover: Float) {
        Controls.rowHighlight(x, y, width, height, hover, selected = false)
        if (select > 0f) {
            val bar = height - Tokens.SPACE_8
            Sk.fill(x, y + (height - bar) / 2f, INDICATOR * select.coerceIn(0f, 1f), bar, Tokens.accent, INDICATOR / 2f)
        }
        val size = Tokens.TEXT_12.toFloat()
        val family = if (select > 0.5f) Type.MEDIUM else Type.REGULAR
        Sk.text(
            label, x + PAD, Sk.centreY(y, height, size, family), size,
            Controls.blend(Tokens.textTertiary, Tokens.textPrimary, maxOf(select, hover)), family,
        )
    }

    /** The hairline that separates the rail from the content beside it. */
    fun divider(x: Float, y: Float, height: Float) = Chrome.ruleV(x, y, height)
}

/**
 * A segmented control: two to four exclusive options with a thumb that slides between them.
 *
 * Not a duplicate of `Controls.chip`, and the line between them is worth stating because getting it
 * wrong is how a UI ends up with two controls that look like each other and mean different things. A
 * chip is a *filter*: there can be eight of them, they carry counts, and they wrap onto a second line.
 * A segmented control is a *switch*: a fixed handful of options, no counts, and the thumb's travel
 * between them is what tells you which way you just moved — a chip set has no travel because nothing
 * moves, it merely lights up somewhere else.
 *
 * Segments are laid out at a uniform width for the same reason. Proportional segments would make the
 * thumb change size as it travels, which reads as the control resizing rather than as a selection
 * moving.
 *
 * It now has a second job: it is what the rail becomes when the panel is too narrow to carry one. See
 * [Nav.railFits].
 */
internal object Segmented {

    const val HEIGHT = 20

    /** Padding inside one segment, either side of its label. */
    const val PADDING = Tokens.SPACE_12

    /**
     * The uniform width of one segment: the widest label plus padding.
     *
     * Takes [measure] rather than a font, so the arithmetic below stays usable from a test. Rounded up
     * to a whole pixel because [indexAt] divides by it — a fractional segment width makes the hit test
     * and the drawing disagree by an accumulating fraction across four segments, and the segment that
     * loses the argument is always the last one.
     */
    fun segmentWidth(labels: List<String>, measure: (String) -> Float): Int {
        var widest = 0f
        for (label in labels) widest = maxOf(widest, measure(label))
        return Math.ceil((widest + PADDING * 2).toDouble()).toInt()
    }

    /** The whole control's width. */
    fun width(labels: List<String>, measure: (String) -> Float): Int =
        segmentWidth(labels, measure) * labels.size

    /** Which segment a cursor at [mouseX] is over, or `-1`. */
    fun indexAt(labels: List<String>, each: Int, x: Int, mouseX: Int): Int {
        if (labels.isEmpty() || each <= 0) return -1
        if (mouseX < x || mouseX >= x + each * labels.size) return -1
        return (mouseX - x) / each
    }

    /**
     * One control.
     *
     * [travel] is the thumb's animated position in segment units — `1.5f` is halfway between the second
     * and third — and [active] is the segment actually selected, which is what decides the label
     * colours. The two are separate so the thumb can still be in flight while the labels have already
     * swapped: they swap at the crossing, not at the arrival, or a label spends the whole animation
     * unreadable on top of an arriving thumb.
     */
    fun draw(
        x: Float, y: Float, height: Float, each: Float,
        labels: List<String>,
        active: Int, travel: Float,
        hover: Int = -1,
        enabled: Boolean = true,
    ) {
        if (labels.isEmpty() || height <= 0f) return
        val total = each * labels.size
        val radius = height / 2f

        Sk.fill(x, y, total, height, Tokens.surfaceActive, radius)
        Sk.border(x, y, total, height, Tokens.borderSubtle, radius)

        if (enabled) {
            val at = travel.coerceIn(0f, (labels.size - 1).toFloat())
            Sk.fill(x + at * each + 1f, y + 1f, each - 2f, height - 2f, Tokens.accent, radius - 1f)
        }

        val size = Tokens.TEXT_11.toFloat()
        for (i in labels.indices) {
            val label = labels[i]
            val selected = enabled && i == active
            val colour = when {
                !enabled -> Tokens.textDisabled
                selected -> Tokens.accentText
                i == hover -> Tokens.textPrimary
                else -> Tokens.textSecondary
            }
            // Medium on the selected segment as well as the inverted fill. The fill alone was enough
            // when the thumb was a hard-edged rectangle; against a rounded, anti-aliased thumb the
            // weight is what keeps the active label reading as the active one at small sizes.
            val family = if (selected) Type.MEDIUM else Type.REGULAR
            Sk.textCenter(
                label, x + i * each + each / 2f,
                Sk.centreY(y, height, size, family), size, colour, family,
            )
        }
    }
}
