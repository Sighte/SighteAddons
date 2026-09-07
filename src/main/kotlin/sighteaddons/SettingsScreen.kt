package sighteaddons

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import sighteaddons.ui.Format
import sighteaddons.ui.components.Anim
import sighteaddons.ui.components.Badge
import sighteaddons.ui.components.Controls
import sighteaddons.ui.components.EmptyState
import sighteaddons.ui.components.Labels
import sighteaddons.ui.components.Nav
import sighteaddons.ui.components.Segmented
import sighteaddons.ui.components.Slider
import sighteaddons.ui.components.Sparkline
import sighteaddons.ui.components.Stepper
import sighteaddons.ui.components.Table
import sighteaddons.ui.components.TextField
import sighteaddons.ui.components.Tooltip
import sighteaddons.ui.hud.HudKeys
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.hud.HudSnapshot
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.render.Zoom
import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.SkScreen
import sighteaddons.ui.sk.Type
import sighteaddons.ui.screens.Frame
import sighteaddons.ui.screens.HudPreview
import sighteaddons.ui.screens.OverlayPreview
import sighteaddons.ui.screens.RecordColumns
import sighteaddons.ui.screens.Scroll
import sighteaddons.ui.screens.SettingsPage
import sighteaddons.ui.screens.SoloPanel
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens

/**
 * The `/sa` screen: the settings and the room history.
 *
 * Built on the design system in `ui/` and, since Phase 4/5, on the components in `ui/components/` —
 * `Nav` for the rail, `Table` for the header cells and the accordion's detail lines, `Tooltip` instead
 * of vanilla's purple-bordered box, `EmptyState`, `Badge`, `Stepper`, `Labels` for every
 * tracked label. What did not change is any of the behaviour underneath: the rows, the search, the
 * sort, the accordion and every config key are the same code they were.
 *
 * Three rules from the previous version are load-bearing and survive verbatim:
 *
 * 1. **One arrangement at a time.** A chip says *which* rooms, a column says *in which order*, and
 *    neither touches the other. Two controls that each reorder the table is two ways to reach a state
 *    neither of them describes.
 * 2. **Type to filter, no input box.** Any character starts a search; escape undoes one narrowing at a
 *    time and only closes the screen once the table shows everything again. A filtered table is a
 *    state you leave, and closing the whole screen to get out of it loses your place.
 * 3. **Hit testing re-derives the drawing layout.** There is still no widget tree, so nothing can
 *    disagree with what is on screen about where it is.
 *
 * Two more earned their place in this pass:
 *
 * 4. **Every page is a list of [SettingsPage.Item] and scrolls.** The previous version divided the
 *    available height by the number of rows and squeezed the result into `[14, 26]` pixels, which is
 *    how a switch ended up 8 pixels tall at GUI scale 4. Sections and one-line explanations make that
 *    arithmetic impossible, so the pages scroll — the mechanism the table has always had, in [Scroll],
 *    now shared by all of them.
 * 5. **Focus is a narrowing too.** With the key field focused, escape leaves the field rather than the
 *    screen, for the same reason escape empties the search before it closes: the state you are in is
 *    the state you want to leave, not the screen you are on.
 * 6. **No layout is a share of the window.** Everything that has to fit beside something else is
 *    measured — the table's columns in [RecordColumns], the sparkline against the sentence beside it,
 *    the empty state against the band it is centred in. Percentages were tried twice, budgeted by hand
 *    against `guiScaledWidth = 480` both times, and both times they were right at 480 and wrong at the
 *    456 and 427 that Minecraft's auto scale hands out on ordinary displays. A layout that can only be
 *    checked by opening the game at one resolution is a layout nobody checks.
 */
internal class SettingsScreen(
    private var tab: Tab = Tab.HUD,
    private var view: View = View.ROOMS,
) : SkScreen(Component.literal("Sighte Addons")) {

    /**
     * [label] rather than the enum name: the rest of this screen is lower case throughout.
     *
     * There was a `STATS` entry between `chat` and `records` — medians and coverage computed out of
     * the history. Removed on 04.09.2026 because its owner never found a number on it worth having;
     * `git log` has the page and `StatsOverview` if the answer changes.
     */
    enum class Tab(val label: String) {
        HUD("hud"),
        CHAT("chat"),

        /**
         * The three record stores, behind one rail entry and a segmented control.
         *
         * **Not three rail entries, and the reason is arithmetic rather than taste.** The rail is
         * [Nav.ROW] per entry with no scroll, and the space it has is [pageHeight] — 140 pixels at the
         * vanilla minimum GUI size of 320×240, which is five entries and not seven. A sixth would be
         * drawn past the bottom of the panel and a seventh past the bottom of the screen, and neither
         * would be reachable. So the rail keeps at most the five it can always draw, and [View] is
         * what picks between the three tables underneath this one.
         */
        RECORDS("records"),

        /**
         * Solo F7/M7 runs, each with its map and rundown — [SoloPanel] draws it. **The fifth entry,
         * and the rail is full with it:** the next tab has to go behind a segmented control like the
         * record stores did, or the arithmetic above stops holding at 320×240.
         */
        SOLO("solo"),
        DEBUG("debug"),
    }

    /**
     * Which record store the [Tab.RECORDS] page is showing.
     *
     * Three, because there are three stores and they are three on purpose: [RoomHistory] is one
     * player's time in one room in run ticks, [SplitPbs] is the party's time across a floor in
     * wall-clock seconds, and [RunPbs] is a whole run keyed by party size on Hypixel's clock. Each of
     * those objects argues at length that its numbers are not the others', and a single table with a
     * `kind` column would be that argument thrown away — three units in one column, sorted against
     * each other.
     *
     * A [Segmented] control and not a chip row, on that component's own distinction: this is a switch
     * between three whole tables, and the thumb's travel is what says which way the page just moved. The
     * chips below it stay what they have always been — a filter on the rows of one table.
     */
    enum class View(val label: String) {
        ROOMS("rooms"),
        SPLITS("splits"),
        RUNS("runs"),
    }

    /**
     * A line under an expanded room: a label, its text, the progression on the first one, and whether
     * that first one earns a badge.
     */
    private class Detail(
        val label: String,
        val text: String,
        val spark: List<RoomHistory.Attempt>?,
        val cap: Int,
        val badge: Boolean = false,
    )

    /** One rendered line of the history: the room's own row, or one line of its expanded detail. */
    private class Line(val row: RecordTable.Row, val detail: Detail?)

    /**
     * The things that can be placed on screen, and the only ones.
     *
     * Each is an [OverlayPlacement] in [Config] plus two words: [what] names it in the hint line, and
     * [label] is the settings row it is reached from. The editor asks a [Target] for nothing else —
     * the size and the preview are one `when` each, because a card that draws a run and a chip that
     * draws a scripted line have nothing to share but the rectangle they end up in.
     */
    private enum class Target(val slot: OverlayPlacement, val what: String, val label: String) {
        CARD(Config.hudPlacement, "card", "position"),
        POPUP(Config.clearPopupPlacement, "popup", "popup position"),
        TIMER(Config.stormPlacement, "countdown", "timer position"),
        SPLITS(Config.splitsPlacement, "splits", "splits position"),
        SPLITS_CURRENT(Config.splitsCurrentPlacement, "split clock", "clock position"),
    }

    /**
     * Which overlay the placement editor has, or null when this screen is its ordinary self.
     *
     * A target and not a flag, because there are three placeable things now. Everything the editor does
     * — measure, draw, grab, drop, cancel — is the same code for all three and differs only in what it
     * asks for the size and the preview of, which is [Target]'s whole content.
     */
    private var placing: Target? = null

    /** Whether the element is currently held. Only true between a press on it and the release. */
    private var dragging = false

    /**
     * The index of the slider currently held, or `-1`.
     *
     * An index and not a flag, because a drag has to reach *the* slider that was pressed. Items are
     * rebuilt every frame — they close over live config — so the item object cannot be kept, and
     * finding one again by its [SettingsPage.Kind] works only while there is exactly one on the page.
     *
     * Separate from [dragging], which belongs to the placement editor and is a different mode of the
     * screen entirely — one field for both would make a release during placement write the scrim.
     */
    private var sliderHeld = -1

    /**
     * Which expected-time field has the keyboard, as `floor to split`, or null.
     *
     * **A key and not an index**, unlike [sliderHeld], because a field outlives clicks in a way a drag
     * does not: the page reflows while one is focused — the parent toggle above it can close, the floor
     * stepper can move — and an index into a list that is rebuilt every frame would then commit the
     * typed time onto whichever row inherited the number.
     */
    private var fieldFocus: Pair<String, String>? = null

    /** The focused field's caret and text. One, because at most one field has the keyboard. */
    private val fieldEdit = TextField.Edit()

    /** What every *unfocused* field draws through — reset per row, so the component stays stateless. */
    private val fieldScratch = TextField.Edit()

    /**
     * Which floor's expected times the splits section is editing.
     *
     * Screen state like [view], not config: which page of a plan was open last is not a setting.
     * Seeded from the sidebar so the floor somebody is standing in is the one already showing, and F7
     * otherwise — the floor anybody thinking about run time is thinking about.
     */
    private var estimateFloor: String =
        SoloClear.floorTag(DungeonSession.floor).takeIf { DungeonSplits.numberOf(it) != null } ?: "F7"

    /** Where inside the element it was grabbed, so it does not jump to meet the cursor. */
    private var grabX = 0
    private var grabY = 0

    /**
     * Where the element was, and how big, when placement started — for the escape hatch.
     *
     * [OverlayPlacement.Saved] and not [HudPlacement.Placement] because the editor changes four things
     * now and escape has to undo all four: a wheel that resized an element and an escape that only put
     * its position back would be an undo that undid half of what was done.
     */
    private var placingWas: OverlayPlacement.Saved? = null

    /**
     * The current page's scroll offset — rows on the history table, pixels everywhere else.
     *
     * One field for all of them because only one page is on screen at a time and switching pages resets
     * it. Two units in one field is safe for exactly that reason and for no other: nothing ever reads
     * this without also knowing which page it belongs to.
     */
    private var scroll = 0

    private var sortBy = RecordTable.Sort.LAST

    /** Newest first: after a run the rooms you just played are the ones you opened this for. */
    private var sortDesc = true

    private var filter = RecordTable.Filter.ALL
    private var query = ""

    /** The room whose detail is open, at most one — an accordion keeps the list from jumping. */
    private var expanded: String? = null

    /** Rows fitting on screen, from the last frame. Only the renderer knows the height. */
    private var pageSize = 1

    private var cacheKey: String? = null
    private var cachedLines: List<Line> = emptyList()
    private var cachedCounts: Map<RecordTable.Filter, Int> = emptyMap()
    private var cachedTotal = 0
    private var cachedMatches = 0

    /**
     * The two personal-best tables, rebuilt when their store changes.
     *
     * Keyed on [SplitPbs.revision] and [RunPbs.revision] rather than on a record count, which is what
     * those two fields exist to say: a beaten record leaves the count where it was, this screen does
     * not pause the game, and the odin import lowers times from a row on the debug page. Cached at all
     * because the splits table is fifteen floors of ten lines and every line is a formatted duration —
     * rebuilding that per frame is exactly what [Format.Cached] exists to have stopped doing.
     */
    private var splitsKey = -1
    private var cachedSplitLines: List<PbTable.Line> = emptyList()
    private var runsKey = -1
    private var cachedRunLines: List<PbTable.Line> = emptyList()

    /**
     * The chips over the two personal-best tables, one filter per table because they filter on
     * different axes: the splits by half of the game, the runs by party. Screen state like [filter].
     */
    private var splitsFilter = PbTable.FloorFilter.ALL
    private var runsFilter = PbTable.PartyFilter.ALL

    /** The personal-best chips' press zones from the last frame: index, x0, x1. */
    private val pbChipHits = ArrayList<Triple<Int, Int, Int>>()

    /**
     * The tooltip owed to this frame, if any: a row's explanation, or a name a column had to cut off.
     *
     * Deferred rather than drawn where it is discovered, because the row that discovers it is inside the
     * list's scissor and a tooltip clipped to the list it came from is a tooltip nobody can read. It is
     * cleared at the top of every frame, so a stale one cannot outlive the row that asked for it.
     *
     * One field and one tooltip per frame, which is what makes the priority between the two callers
     * decidable at all: [hint] runs before the row is drawn, so a value the row had to truncate takes
     * the box off it. A name with nothing behind it is more urgent than a sentence that is the same
     * sentence every time.
     */
    private var tooltipLines: List<String>? = null
    private var tooltipX = 0
    private var tooltipY = 0

    private val anim = Anim()
    private val previewHud = HudRoot()

    /** The solo tab's content column, with its own list-or-detail state. Draws only from [content]. */
    private val solo = SoloPanel()

    /** Chip hit zones from the last frame, so a click lands on what was drawn. */
    private val chipHits = ArrayList<Triple<RecordTable.Filter, Int, Int>>()

    // --- Layout -----------------------------------------------------------------------------

    // The panel's own geometry lives in [Frame], so a test can walk the GUI-scaled sizes Minecraft's
    // auto scale actually hands out rather than the one this file was reasoned about at.
    private val frameWidth get() = Frame.width(width)
    private val frameLeft get() = Frame.left(width)
    private val contentLeft get() = Frame.contentLeft(width)
    private val content get() = Frame.content(width)

    private val headerY get() = Frame.MARGIN + Tokens.SPACE_12
    private val bodyTop get() = Frame.bodyTop

    /**
     * The records page's four bands, out of [Frame] so a test can walk them at every real window size.
     *
     * **The chooser costs the history table one row, and that is the price of the two views beside
     * it.** It is [Tokens.SPACE_24] the rooms view did not used to spend: six visible rows become five
     * at the sizes auto scale hands out, and five become three at the vanilla minimum of 320×240.
     * `SettingsPageTest` states both numbers rather than leaving them to be discovered.
     *
     * The alternatives were all worse. Three more rail entries do not fit the rail at that same minimum
     * — [Tab.RECORDS] does that arithmetic — and a chooser sharing one row with the chips wants 153
     * pixels beside their 278 in a content column that is 168 wide there, so the chips at the far end
     * would be clipped and a filter nobody can reach is worse than a table with fewer rows. The two
     * personal-best views have no chip row, so they pay nothing.
     */
    private val segmentsY get() = Frame.chooserTop
    private val chipsY get() = Frame.chipsTop
    // Every view has a chip row now — the two personal-best tables gained theirs on 07.09.2026 — so
    // the bands are the same on all three and the argument above about the views paying nothing no
    // longer applies to them.
    private val columnsY get() = Frame.columnsTop(true)
    private val firstRow get() = Frame.rowsTop(true)
    private val listBottom get() = Frame.listBottom(height)

    /** How much vertical room a scrolling page has. The one number [Scroll] measures against. */
    private val pageHeight get() = listBottom - bodyTop

    private val lastX get() = contentLeft + content

    /**
     * The left edge of an interactive row's band — its wash, its hover test and its press test alike.
     *
     * One number for all three because they were three, eight pixels apart: the wash was drawn from
     * here, the hover test started at [contentLeft], and the press test rejected anything left of here.
     * The leftmost eight pixels of every row therefore switched a setting without ever lighting up,
     * which is rule 3 broken inside a single function.
     */
    private val rowLeft get() = contentLeft - Tokens.SPACE_8

    /** The key field's box. Derived, so [mouseClicked] hit-tests the rectangle it is drawn in. */

    /**
     * The scrim slider's track, hard against the right edge like every other control on a row.
     *
     * Derived rather than remembered from the draw for the same reason the field's box is, and it
     * matters more here: the knob is grabbed by its middle, so `Slider.fractionAt` measures against the
     * knob's *travel* rather than the track's width, and a hit test built from a second copy of the
     * geometry would reach the maximum a knob-width before the drawing does.
     */
    private val sliderX get() = lastX - SLIDER_WIDTH

    /**
     * The table's columns for this frame, measured rather than apportioned.
     *
     * Recomputed at each call rather than cached: it is a dozen short strings through the font, the
     * window can change size between two frames, and a cached layout is exactly how the drawing and the
     * hit testing come to disagree — see rule 3. [RecordColumns] is where the arithmetic lives and why.
     *
     * The `type` column is asked for only while no type chip is active, because it would otherwise
     * repeat that one word in every row; whether there is *room* for it is [RecordColumns]' call.
     */
    private fun tableLayout(): RecordColumns.Layout = RecordColumns.of(
        contentLeft, content,
        wantType = filter == RecordTable.Filter.ALL,
        // Both measured in the face and at the size the cell is actually drawn in, which is the whole
        // point of this being two lambdas. Measuring a header with one font and drawing it in another
        // compiles perfectly and puts every column in the wrong place — and a header set in Medium is
        // wider than the same word in Regular, so the sorted column is the one that overflows.
        header = { Math.ceil(w(it.uppercase(), Table.HEADER_SIZE, Type.MEDIUM).toDouble()).toInt() },
        value = { Math.ceil(w(it, Table.CELL_SIZE, Type.MEDIUM).toDouble()).toInt() },
    )

    /** How many table rows fit, which is also how far a press on the list may land. */
    private val tableRows get() = Frame.rows(height, true, Table.ROW)

    private val narrowing get() = RecordTable.narrowing(query, filter)

    // --- Rendering --------------------------------------------------------------------------

    /**
     * The type sizes this screen draws at.
     *
     * They are the existing scale in [Tokens], not a new one — the scale was fine; what changed is that
     * every one of these is now a real rasterisation of a proportional face rather than `pose().scale`
     * on a nine-pixel bitmap. [Type.MEDIUM] is spent only on values, because with an achromatic palette
     * the weight is the whole of what separates a figure from the word that names it.
     */
    /**
     * What separates an ACTION row's reading from the verb at the end of it.
     *
     * A constant because two places depend on it agreeing: the builders that compose these values, and
     * the draw that tints only the last field. Split on the *last* one, so a value with several fields
     * still gives up only its verb.
     */
    private val VERB_SEPARATOR = " · "

    private val rowText get() = Tokens.TEXT_12.toFloat()
    private val noteText get() = Tokens.TEXT_11.toFloat()
    private val labelText get() = Tokens.TEXT_10.toFloat()

    /** Measure, at the size this screen's rows are drawn at. Render-thread only, like everything in [Sk]. */
    private fun w(text: String, size: Float = rowText, family: String = Type.REGULAR): Float =
        Sk.width(text, size, family)

    /**
     * The panel's own box, which is [Frame]'s geometry plus the air the panel needs around it.
     *
     * The frame arithmetic is untouched — it is measured against the GUI-scaled sizes Minecraft's auto
     * scale actually hands out and `SettingsPageTest` walks all of them. What is new is that there is
     * now a *surface* around it, so a layout that does not fit has a visible edge to run past instead of
     * bleeding into the window.
     */
    private val panelPad get() = Tokens.SPACE_16
    private val panelLeft get() = frameLeft - panelPad
    private val panelWidth get() = frameWidth + panelPad * 2
    private val panelTop get() = Frame.MARGIN - Tokens.SPACE_8
    private val panelHeight get() = height - (Frame.MARGIN - Tokens.SPACE_8) * 2

    /**
     * The blurred world behind the panel, dimmed — except in placement mode, which keeps its scrim.
     *
     * The old screen filled the window with [Tokens.surfaceBase] and its comment argued that a settings
     * screen earns an opaque background. That was the right call for a renderer whose only tool was
     * `fill`: a "floating panel" would have been a rectangle of slightly different grey. With a real
     * blur, a real shadow and real corners the trade reverses, and the user chose the panel.
     *
     * **Placing is still the one mode that must not paint over the game.** The whole question it exists
     * to answer is where the card sits against a dungeon, and a blurred surface behind it answers that
     * question about a blurred surface. So no blur and no panel there — only a wash light enough to read
     * the world through, which is exactly what it had before.
     */
    override val backdrop: Backdrop
        get() = if (placing != null) Backdrop.NONE else Backdrop.BLUR

    override val backdropDimTop: Int
        get() = if (placing != null) {
            Tokens.alpha(Tokens.shadow, Chrome.PLACING_DIM_ALPHA)
        } else {
            Tokens.alpha(Tokens.scrim, Chrome.DIM_ALPHA)
        }

    override val backdropDimBottom: Int get() = backdropDimTop

    /**
     * Per-frame bookkeeping, on the extraction pass.
     *
     * This used to sit at the top of `extractBackground`, which [SkScreen] now owns. It has to happen
     * here rather than in [content]: by the time the compositor calls that back, anything reading the
     * frame clock or the device density has already been asked.
     */
    override fun beginFrame() {
        val window = minecraft.window
        Density.beginFrame(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        Clock.frame(paused = false)
    }

    /**
     * The placement editor's HUD elements, which are still drawn by Minecraft's own renderer.
     *
     * See [SkScreen.extractExtra] for why this seam exists. It lands under everything [content] draws,
     * which is the right way round here: the element below, its position readout on top.
     */
    override fun extractExtra(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        placing?.let { renderPlacingElement(graphics, it) }
    }

    override fun content() {
        placing?.let {
            renderPlacingChrome(it)
            return
        }
        tooltipLines = null

        Chrome.panel(panelLeft.toFloat(), panelTop.toFloat(), panelWidth.toFloat(), panelHeight.toFloat())

        renderRail()
        renderHeader()

        when (tab) {
            Tab.RECORDS -> renderRecords()
            // The panel returns the tooltip it owes rather than drawing it, for [tooltipLines]' reason:
            // it is discovered inside the panel's clip and has to be drawn outside every clip.
            Tab.SOLO -> solo.draw(contentLeft, content, bodyTop, listBottom, pointerX, pointerY, anim)
                ?.let { tooltip(it, pointerX, pointerY) }
            else -> renderPage()
        }

        // Truncated to the content column like every other line on this screen. It was once the one
        // string drawn with neither a scissor nor a fit, so a sentence longer than the column — and at
        // the vanilla minimum the column is 168 pixels — was painted across the rest of the screen.
        Sk.text(
            fit(footer(), content, noteText), contentLeft.toFloat(),
            (height - Frame.MARGIN - Tokens.SPACE_6).toFloat(), noteText, Tokens.textTertiary,
        )

        // Last, and outside every clip: a floating surface that is clipped to the list it describes is
        // not floating.
        tooltipLines?.let { Tooltip.draw(tooltipX, tooltipY, width, height, it) }
    }

    /** The nav rail. Each entry carries its own hover and its own selected indicator. */
    private fun renderRail() {
        val onRail = pointerX in frameLeft..(frameLeft + Nav.WIDTH)
        val over = if (onRail) Nav.rowAt(bodyTop, Tab.entries.size, pointerY) else -1
        for ((index, entry) in Tab.entries.withIndex()) {
            val y = Nav.rowY(bodyTop, index)
            val active = entry == tab
            val hover = Controls.hover(anim.of("rail.${entry.name}"), index == over && !active)
            val select = anim.of("railsel.${entry.name}", if (active) 1f else 0f)
            select.animateTo(if (active) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            Nav.item(
                frameLeft.toFloat(), y.toFloat(), Nav.WIDTH.toFloat(), Nav.ROW.toFloat(),
                entry.label, select.value, hover,
            )
        }
        Nav.divider(
            (frameLeft + Nav.WIDTH + Frame.GAP / 2).toFloat(), (bodyTop - Tokens.SPACE_12).toFloat(),
            (pageHeight + Tokens.SPACE_20).toFloat(),
        )
    }

    private fun renderHeader() {
        Sk.text(
            "SIGHTE ADDONS", frameLeft.toFloat(), headerY.toFloat(), labelText,
            Tokens.textSecondary, Type.MEDIUM,
        )
        val right = when {
            tab == Tab.SOLO -> solo.headerRight()
            tab != Tab.RECORDS -> VERSION
            view != View.ROOMS -> pbSummary()
            else -> {
                build()
                if (query.isEmpty()) {
                    "$cachedTotal rooms · ${RoomHistory.entryCount()} attempts"
                } else {
                    "\"$query\"  $cachedMatches of $cachedTotal"
                }
            }
        }
        // Only the search readout earns the brighter tone and the weight, and only the rooms view has a
        // search. Everything else up here is a fact about the build, not a state you are in.
        val searching = tab == Tab.RECORDS && view == View.ROOMS && query.isNotEmpty()
        val tone = if (searching) Tokens.accentSoft else Tokens.textTertiary
        val family = if (searching) Type.MEDIUM else Type.REGULAR
        if (searching) {
            // A narrowing is a state you are *in*, and this screen's rule is that escape leaves it one
            // step at a time. A tone alone said so too quietly to be the thing a hand reaches for; a
            // pill in the accent is the same claim the rail's selected entry makes, which is what a
            // narrowing actually is.
            val pillWidth = w(right, labelText, family) + Tokens.SPACE_12
            val pillHeight = Sk.lineHeight(labelText, family) + Tokens.SPACE_6
            val pillY = (headerY - Tokens.SPACE_2).toFloat()
            Sk.fill(
                lastX - pillWidth, pillY, pillWidth, pillHeight,
                Tokens.alpha(Tokens.accent, Tokens.ACCENT_WASH_ALPHA), pillHeight / 2f,
            )
            Sk.border(
                lastX - pillWidth, pillY, pillWidth, pillHeight,
                Tokens.alpha(Tokens.accent, Tokens.BORDER_WASH_ALPHA), pillHeight / 2f,
            )
        }
        Sk.textRight(right, (lastX - if (searching) Tokens.SPACE_6 else 0).toFloat(), headerY.toFloat(), labelText, tone, family)
        Table.divider(frameLeft.toFloat(), (headerY + Tokens.SPACE_16).toFloat(), frameWidth.toFloat())
    }

    // --- Pages: the three settings tabs and the stats overview -------------------------------

    /**
     * One scrolling page of [SettingsPage.Item]s.
     *
     * Every line is drawn by this one loop and hit-tested by [SettingsPage.itemAt] against the same
     * heights, which is rule 3 held by construction rather than by two people editing the same numbers.
     * Lines entirely outside the viewport are skipped rather than clipped — at GUI scale 4 the debug tab
     * is over twice the height of the space it has, and drawing the half of it nobody can see is a
     * couple of hundred queued draws per frame spent on nothing.
     */
    private fun renderPage() {
        val items = pageItems()
        if (items.isEmpty()) {
            renderEmpty(bodyTop, listBottom)
            return
        }

        val tops = SettingsPage.tops(items)
        val total = SettingsPage.total(items)
        scroll = Scroll.clamp(scroll, total, pageHeight)

        val cardLeft = rowLeft.toFloat()
        val cardWidth = (lastX - rowLeft).toFloat()

        Sk.clip(0f, bodyTop.toFloat(), width.toFloat(), (listBottom - bodyTop).toFloat())

        // **The cards first, all of them, before any row.** They are containers, so every one of them
        // has to be behind every row — drawing a card immediately before its own rows would put the
        // second card over the first card's last row wherever two groups touch. Off-screen groups are
        // skipped for the same reason the rows are: the debug tab is over twice the height of the space
        // it has.
        val groups = SettingsPage.groups(items)
        for (group in groups) {
            val top = bodyTop + tops[group.first] - scroll
            val last = group.last
            val bottom = bodyTop + tops[last] + items[last].height - scroll
            if (bottom <= bodyTop || top >= listBottom) continue
            Chrome.card(cardLeft, top.toFloat(), cardWidth, (bottom - top).toFloat())
        }

        // Which lines get a rule above them: every row unit but the first of its card. Resolved from
        // the same [SettingsPage.groups] the cards came from, so a rule cannot land where no card is.
        val ruled = HashSet<Int>()
        for (group in groups) {
            var first = true
            for (index in group) {
                if (!SettingsPage.startsRow(items[index].kind)) continue
                if (first) first = false else ruled.add(index)
            }
        }

        for (index in items.indices) {
            val item = items[index]
            val y = bodyTop + tops[index] - scroll
            if (y + item.height <= bodyTop || y >= listBottom) continue
            if (index in ruled) Chrome.rowRule(cardLeft, y.toFloat(), cardWidth)
            // Before the row is drawn, so a value the row has to truncate can still take the tooltip
            // off it — see [tooltipLines].
            hint(item, y)
            drawItem(item, y)
        }
        Sk.unclip()

        Controls.scrollbar(
            (lastX + Tokens.SPACE_6).toFloat(), bodyTop.toFloat(), listBottom.toFloat(),
            total, pageHeight, scroll,
        )
    }

    /**
     * Owes the frame this row's explanation, if the cursor is on the row and the row has one.
     *
     * **In the loop rather than in [drawControl], and that is the reason there is a function.** A note
     * hangs off whatever row it was written under, and that includes the rows nothing can click — the
     * upload id, the line that says the history is written whatever the switch does. [drawControl]
     * computes its hover from [SettingsPage.Item.interactive], because a hover *wash* is the signal
     * that a row is a control and a fact must not carry one; a tooltip is not that signal and must not
     * inherit that condition.
     *
     * **Instant, with no dwell.** Every other tooltip on this screen appears the moment the cursor is
     * over what owes it, and a settings row whose explanation arrived on a delay would be the one place
     * a hand had to learn to wait. The band check is what keeps a row scrolled half under the header
     * from answering for a cursor that is over the header.
     */
    private fun hint(item: SettingsPage.Item, y: Int) {
        if (item.notes.isEmpty()) return
        if (pointerY !in bodyTop until listBottom) return
        if (pointerX !in rowLeft..lastX || pointerY !in y until (y + item.height)) return
        tooltip(item.notes, pointerX, pointerY)
    }

    private fun drawItem(item: SettingsPage.Item, y: Int) {
        when (item.kind) {
            SettingsPage.Kind.SECTION -> Chrome.groupLabel(
                contentLeft.toFloat(), (y + Tokens.SPACE_12).toFloat(), content.toFloat(),
                item.label.uppercase(), item.meta,
            )

            // An explanation, indented under the row it explains and truncated rather than wrapped: a
            // note that grows downward as the window narrows pushes every row under it out of the page.
            SettingsPage.Kind.NOTE -> Sk.text(
                fit(item.label, content - NOTE_INDENT, noteText),
                (contentLeft + NOTE_INDENT).toFloat(),
                Sk.centreY(y.toFloat(), SettingsPage.NOTE.toFloat(), noteText),
                noteText, Tokens.textTertiary,
            )

            else -> drawControl(item, y)
        }
    }

    private fun drawControl(item: SettingsPage.Item, y: Int) {
        val rowHeight = item.height
        val inViewport = pointerY in bodyTop until listBottom
        val hovered = item.interactive && inViewport &&
            pointerX in rowLeft..lastX && pointerY in y until (y + rowHeight)
        val hover = Controls.hover(anim.of("row.${tab.name}.${item.label}"), hovered)

        if (item.interactive) {
            // The wash, the hover test above and the press test in [clickPage] all run [rowLeft] to
            // [lastX]. Stopping at [lastX] rather than eight pixels past it: the scrollbar lives out
            // there now, and a wash running under it makes the track look like part of the row.
            Controls.rowHighlight(
                rowLeft.toFloat(), y.toFloat(), (lastX - rowLeft).toFloat(), rowHeight.toFloat(), hover, false,
            )
        }

        val textY = Sk.centreY(y.toFloat(), rowHeight.toFloat(), rowText)
        val plain = item.kind == SettingsPage.Kind.INFO
        Sk.text(
            item.label, contentLeft.toFloat(), textY, rowText,
            if (plain) Tokens.textSecondary else Tokens.textPrimary,
        )

        when (item.kind) {
            // A switch gets a switch. The word "on" was the old screen's only way to say it, and a
            // toggle says the same thing without asking anybody to read.
            SettingsPage.Kind.TOGGLE -> {
                val travel = anim.spring("toggle.${tab.name}.${item.label}", if (item.on) 1f else 0f)
                travel.springTo(if (item.on) 1f else 0f, Motion.BASE)
                Controls.toggle(
                    (lastX - Controls.toggleWidth(TOGGLE_HEIGHT)).toFloat(),
                    (y + (rowHeight - TOGGLE_HEIGHT) / 2).toFloat(),
                    TOGGLE_HEIGHT.toFloat(), travel.value, enabled = true,
                )
            }

            // Not a switch but still clickable: the position row. The value is the control, so it reads
            // as primary rather than as metadata.
            //
            // **The verb is tinted and the reading is not.** These values are built as
            // `"top right · 141, 40 · move"` — a fact and then the thing a click does. Colouring the
            // whole string would put the accent on a coordinate, which is not a link and not a state;
            // colouring nothing would leave the one clickable word on the screen looking like the
            // metadata beside it. So the trailing verb takes the accent and the rest does not.
            SettingsPage.Kind.ACTION -> {
                val baseline = Sk.centreY(y.toFloat(), rowHeight.toFloat(), rowText, Type.MEDIUM)
                val cut = item.value.lastIndexOf(VERB_SEPARATOR)
                if (cut < 0) {
                    Sk.textRight(
                        item.value, lastX.toFloat(), baseline, rowText,
                        Controls.blend(Tokens.textSecondary, Tokens.textPrimary, hover), Type.MEDIUM,
                    )
                } else {
                    val verb = item.value.substring(cut + VERB_SEPARATOR.length)
                    val head = item.value.substring(0, cut + VERB_SEPARATOR.length)
                    val verbWidth = w(verb, rowText, Type.MEDIUM)
                    Sk.textRight(
                        verb, lastX.toFloat(), baseline, rowText,
                        Controls.blend(Tokens.accentSoft, Tokens.textPrimary, hover), Type.MEDIUM,
                    )
                    Sk.textRight(
                        head, lastX - verbWidth, baseline, rowText,
                        Controls.blend(Tokens.textSecondary, Tokens.textPrimary, hover), Type.MEDIUM,
                    )
                }
            }

            SettingsPage.Kind.STEPPER -> {
                val stepperWidth = Stepper.width(item.value) { w(it, Stepper.SIZE, Type.MEDIUM) }
                val x = lastX - stepperWidth
                val arm = if (hovered) Stepper.armAt(x, stepperWidth, pointerX) else 0
                Stepper.draw(
                    x.toFloat(), (y + (rowHeight - Stepper.HEIGHT) / 2).toFloat(),
                    stepperWidth.toFloat(), Stepper.HEIGHT.toFloat(),
                    item.value, item.fraction.coerceIn(0f, 1f),
                    minusHover = Controls.hover(anim.of("minus.${item.label}"), arm < 0),
                    plusHover = Controls.hover(anim.of("plus.${item.label}"), arm > 0),
                )
            }

            // A typed value. The box is a fixed width sized off the widest time the panel prints —
            // WIDEST_TIME's argument — so nine of them in a column stay a column whatever is in them.
            // The focused row draws the live [fieldEdit]; every other row funnels its stored value
            // through the one scratch Edit, which is what keeps TextField stateless and this screen the
            // only owner of a caret.
            SettingsPage.Kind.FIELD -> {
                val boxWidth =
                    Math.ceil(w("10:00.0", TextField.SIZE).toDouble()).toInt() + TextField.PADDING * 2
                val focused = item.meta == focusedFieldKey()
                val edit = if (focused) fieldEdit else fieldScratch.also { it.set(item.value) }
                TextField.draw(
                    (lastX - boxWidth).toFloat(), (y + (rowHeight - FIELD_HEIGHT) / 2).toFloat(),
                    boxWidth.toFloat(), FIELD_HEIGHT.toFloat(),
                    edit,
                    placeholder = Format.MISSING,
                    focus = if (focused) 1f else 0f,
                    hover = hover,
                    caret = TextField.caretOn(focused),
                )
            }

            SettingsPage.Kind.SLIDER -> {
                // Snapped while held and sprung otherwise. A spring under the cursor is a knob that
                // trails the hand that is moving it, which reads as the control resisting; a click on
                // the bare track, where there is no hand to keep up with, is exactly where the spring
                // belongs.
                val travel = anim.spring("slider.${item.label}", item.fraction)
                val held = sliderHeld >= 0
                if (held) travel.snapTo(item.fraction) else travel.springTo(item.fraction, Motion.BASE)
                Sk.textRight(
                    item.value, (sliderX - Tokens.SPACE_8).toFloat(),
                    Sk.centreY(y.toFloat(), rowHeight.toFloat(), rowText, Type.MEDIUM), rowText,
                    Controls.blend(Tokens.textSecondary, Tokens.textPrimary, hover), Type.MEDIUM,
                )
                Slider.draw(
                    sliderX.toFloat(), (y + (rowHeight - Slider.HEIGHT) / 2).toFloat(),
                    SLIDER_WIDTH.toFloat(), Slider.HEIGHT.toFloat(),
                    travel.value, hover = hover, active = held,
                )
            }

            // Plain information.
            else -> {
                val room = lastX - contentLeft - w(item.label).toInt() - Tokens.SPACE_8
                Sk.textRight(
                    fit(item.value, room, noteText), lastX.toFloat(),
                    Sk.centreY(y.toFloat(), rowHeight.toFloat(), noteText), noteText, Tokens.textTertiary,
                )
            }
        }
    }

    // --- Records page -----------------------------------------------------------------------

    /**
     * The records page: the view chooser, and whichever of the three tables it points at.
     *
     * The chooser is drawn first and unconditionally, so it is in the same place whichever table is
     * under it — a control that moves when the thing it controls changes is a control you have to find
     * again after every use.
     */
    private fun renderRecords() {
        renderChooser()
        when (view) {
            View.ROOMS -> renderRooms()
            View.SPLITS, View.RUNS -> renderPbs()
        }
    }

    /** The measured width of one segment of the view chooser, shared by the draw and the hit test. */
    private fun segmentWidth(): Int = Segmented.segmentWidth(VIEWS) { w(it, Tokens.TEXT_11.toFloat()) }

    /**
     * The three-way view chooser.
     *
     * The thumb is sprung rather than eased, which is `Segmented`'s own distinction put to work: the
     * travel between segments is what says which way the page just moved, and a spring overshoots
     * slightly in the direction it travelled. [segmentWidth] is what both the hover and the press
     * resolve against, so rule 3 holds without either side keeping its own copy.
     *
     * Clipped for the chip row's reason. Three labels want about 153 pixels and the narrowest content
     * column this panel ever has is 168, so it fits at every size the game offers — and the one thing
     * that must not happen if that ever stops being true is a segment painted over the rest of the
     * screen with nothing clipping it.
     */
    private fun renderChooser() {
        val travel = anim.spring("view", view.ordinal.toFloat())
        travel.springTo(view.ordinal.toFloat(), Motion.BASE)
        val each = segmentWidth()
        val over = if (pointerY in segmentsY until (segmentsY + Segmented.HEIGHT)) {
            Segmented.indexAt(VIEWS, each, contentLeft, pointerX)
        } else {
            -1
        }
        Sk.clip(contentLeft.toFloat(), segmentsY.toFloat(), content.toFloat(), Segmented.HEIGHT.toFloat())
        Segmented.draw(
            contentLeft.toFloat(), segmentsY.toFloat(), Segmented.HEIGHT.toFloat(), each.toFloat(),
            VIEWS, view.ordinal, travel.value, hover = over,
        )
        Sk.unclip()
    }

    // --- Rooms view -------------------------------------------------------------------------

    private fun renderRooms() {
        build()

        // Widths are derived before drawing rather than returned from it, so hover and hit testing use
        // the same number the chip is actually drawn at. Guessing a width for the hover test is how a
        // chip ends up highlighting from a cursor position that is not on it.
        //
        // **The counts come off when six chips carrying them do not fit.** At GUI scale 4 the content
        // column is 328 pixels and six chips with two-digit counts want about 356, so the last one was
        // being drawn past the right edge of the *screen* — the chip row has no clip and nothing was
        // stopping it. A chip advertises the number of rows a click produces, which is worth having and
        // is not worth having a chip nobody can click; the counts are what goes.
        val chipMeasure: (String) -> Float = { w(it, Tokens.TEXT_11.toFloat()) }
        val withCounts = RecordTable.Filter.entries.sumOf {
            Math.ceil(Controls.chipWidth(it.label, cachedCounts[it] ?: 0, chipMeasure).toDouble()).toInt()
        } + (RecordTable.Filter.entries.size - 1) * Tokens.SPACE_6
        val showCounts = withCounts <= content

        chipHits.clear()
        var chipX = contentLeft
        // Clipped to the panel, and the press zones clipped with it. Without the counts six chips are
        // 278 pixels, which still does not fit the 168 the vanilla minimum GUI size leaves — and the
        // chip row has no list around it, so what did not fit was simply painted over the rest of the
        // screen. Clipped, what can be pressed is exactly what can be seen.
        Sk.clip(contentLeft.toFloat(), chipsY.toFloat(), content.toFloat(), CHIP_H.toFloat())
        for (chip in RecordTable.Filter.entries) {
            val count = if (showCounts) cachedCounts[chip] ?: 0 else -1
            val chipWidth = Math.ceil(Controls.chipWidth(chip.label, count, chipMeasure).toDouble()).toInt()
            val active = anim.of("chip.${chip.name}", if (chip == filter) 1f else 0f)
            active.animateTo(if (chip == filter) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            val hovered = pointerX in chipX until minOf(chipX + chipWidth, lastX) &&
                pointerY in chipsY until (chipsY + CHIP_H)
            val hover = Controls.hover(anim.of("chiphover.${chip.name}"), hovered)
            Controls.chip(
                chipX.toFloat(), chipsY.toFloat(), CHIP_H.toFloat(),
                chip.label, count, active.value, hover,
            )
            if (chipX < lastX) chipHits.add(Triple(chip, chipX, minOf(chipX + chipWidth, lastX)))
            chipX += chipWidth + Tokens.SPACE_6
        }
        Sk.unclip()

        // The caret rotates between the two directions rather than swapping glyphs, so the reversal is
        // visibly the same control changing its mind. One animatable for the whole header row: there is
        // only ever one sorted column, so a second would be a direction nothing is pointing in.
        val flip = anim.of("sortdir", if (sortDesc) 1f else 0f)
        flip.animateTo(if (sortDesc) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
        val layout = tableLayout()
        // The hovered column is resolved once, through the same zones the press uses. Testing each
        // column against the cursor on its own would light two headers wherever their zones met, and
        // the whole point of a partition is that there is exactly one answer.
        val onHeader = if (pointerY in columnsY until (columnsY + Tokens.SPACE_12)) layout.at(pointerX) else null
        for (column in layout.columns) {
            Table.headerCell(
                column.label, column.x0.toFloat(), column.x1.toFloat(), columnsY.toFloat(),
                column.rightAligned,
                sorted = column.sort == sortBy,
                flip = flip.value,
                hover = Controls.hover(anim.of("col.${column.sort.name}"), column.sort == onHeader?.sort),
            )
        }
        Table.divider(contentLeft.toFloat(), (columnsY + Tokens.SPACE_12).toFloat(), content.toFloat())

        if (cachedLines.isEmpty()) {
            renderEmpty(firstRow, listBottom)
            return
        }

        val visible = tableRows
        pageSize = visible
        scroll = Scroll.clamp(scroll, cachedLines.size, visible)

        Sk.clip(0f, firstRow.toFloat(), width.toFloat(), (listBottom - firstRow).toFloat())
        for ((index, line) in cachedLines.drop(scroll).take(visible).withIndex()) {
            val y = firstRow + index * Table.ROW
            if (line.detail != null) {
                renderDetail(line.detail, y)
            } else {
                renderRecord(layout, line.row, y)
            }
        }
        Sk.unclip()

        Controls.scrollbar(
            (lastX + Tokens.SPACE_6).toFloat(), firstRow.toFloat(), listBottom.toFloat(),
            cachedLines.size, visible, scroll,
        )
    }

    private fun renderRecord(layout: RecordColumns.Layout, row: RecordTable.Row, y: Int) {
        val open = row.room == expanded
        val hovered = pointerX in rowLeft..lastX && pointerY in y until (y + Table.ROW)
        val hover = Controls.hover(anim.of("rec.${row.room}"), hovered)
        Controls.rowHighlight(
            rowLeft.toFloat(), y.toFloat(), (lastX - rowLeft).toFloat(), Table.ROW.toFloat(), hover, open,
        )

        val textY = Sk.centreY(y.toFloat(), Table.ROW.toFloat(), rowText)
        val name = fit(row.room, layout.nameWidth, rowText)
        Sk.text(
            name, contentLeft.toFloat(), textY, rowText,
            if (open) Tokens.textPrimary else Tokens.textSecondary,
        )
        // The full name only exists in a tooltip when the column actually cut it off.
        if (name != row.room && hovered) tooltip(row.room, pointerX, pointerY)
        if (layout.showType) {
            Sk.text(row.typeLabel, layout.typeX.toFloat(), textY, noteText, Tokens.textTertiary)
        }

        // A time that exists is primary and set in Medium; a dash is tertiary and is not. Weight,
        // luminance and the dash being the same width as a time all carry it, so the column stays a
        // column whichever of the two it holds.
        right(row.clear.time(), layout.clearX, y, row.clear != null)
        if (layout.secretsX >= 0) right(row.secrets.time(), layout.secretsX, y, row.secrets != null)
        if (layout.runsX >= 0) right(row.runs.toString(), layout.runsX, y, false)
        right(Format.ago(row.lastTs, System.currentTimeMillis()), lastX, y, false)
    }

    /**
     * One line of an open room's detail, with the rule that ties it to the row above.
     *
     * Drawn at [Table.detail]'s full extent rather than animated open: the growth the component offers
     * is there so the detail reads as coming *out of* the row that was clicked, and a rule that grows
     * past a sparkline which is already fully drawn beside it reads as two elements arriving rather than
     * one. The connector is what earns the component its place here.
     *
     * **The `PB` badge is here rather than on the row above, and that is a size decision.** The room
     * column is 63 pixels at 1080p and 52 at 1280×720 — see [RecordColumns] for where those come from
     * — and a badge is half of it, taken from every room that has one, in the column already most
     * likely to be truncated. The detail line is the whole content width and is where the progression
     * lives, which is what the badge is a statement about: not "this room has a record", since every
     * row shows one of those, but "the last run here was it" — [RoomHistory.Attempt.pb] read straight
     * out of the file rather than a comparison this screen would have to invent.
     */
    private fun renderDetail(detail: Detail, y: Int) {
        val badgeMeasure: (String) -> Float = { w(it, Badge.SIZE, Type.MEDIUM) }
        val badgeWidth = Badge.width(PB, badgeMeasure)
        val badgeRoom = if (detail.badge) Math.ceil(badgeWidth.toDouble()).toInt() + Tokens.SPACE_6 else 0
        val plain = Table.contentX(contentLeft)
        val available = lastX - plain - badgeRoom

        // **The chart takes what the sentence does not want, and never the other way round.** The
        // sparkline used to be a fixed fraction of the content column, which at `guiScaledWidth` 456
        // left the summary one pixel short and truncated `268 attempts` to `268 att` — cutting off
        // exactly the sample size that makes the median beside it readable. Below [SPARK_MIN] there is
        // no chart at all: a twenty-attempt line squeezed into thirty pixels is not a trend, and the
        // number it was standing next to is.
        val wanted = available - w(detail.text, noteText).toInt() - Tokens.SPACE_12
        val spark = if (detail.spark != null && wanted >= SPARK_MIN) minOf(wanted, SPARK_MAX) else 0

        Table.detail(
            contentLeft.toFloat(), y.toFloat(), Table.ROW.toFloat(), detail.label,
            if (spark == 0) fit(detail.text, available, noteText) else "",
        )
        if (detail.badge) {
            // `EARNED`, not `SOLID`: this badge says the last run in this room was the player's own
            // best, read straight out of `RoomHistory.Attempt.pb`. That is the single fact
            // `Tokens.positive` exists for.
            Badge.draw(
                (lastX - badgeWidth), (y + (Table.ROW - Badge.HEIGHT) / 2).toFloat(), PB,
                style = Badge.Style.EARNED,
            )
        }
        if (spark == 0) return
        Sparkline.draw(
            plain.toFloat(), (y + 2).toFloat(), spark.toFloat(), (Table.ROW - 4).toFloat(),
            detail.spark!!, detail.cap,
        )
        val textX = plain + spark + Tokens.SPACE_12
        Sk.text(
            fit(detail.text, lastX - badgeRoom - textX, noteText), textX.toFloat(),
            Sk.centreY(y.toFloat(), Table.ROW.toFloat(), noteText), noteText, Tokens.textSecondary,
        )
    }

    // --- Splits and runs views --------------------------------------------------------------

    /**
     * The lines of whichever personal-best table is showing, or nothing on the rooms view.
     *
     * Two caches rather than one, keyed on their own store's revision: the splits table and the runs
     * table change for different reasons and at different moments, and a shared key would rebuild both
     * whenever either moved.
     */
    private fun pbLines(): List<PbTable.Line> = when (view) {
        View.ROOMS -> emptyList()

        View.SPLITS -> {
            if (SplitPbs.revision != splitsKey) {
                splitsKey = SplitPbs.revision
                cachedSplitLines = PbTable.splits(
                    SplitPbs.records(),
                    // The chain rather than a second list of split names: DungeonSplits is the one
                    // place that says what order a run happens in, and a copy of it here would be a
                    // second thing to correct the day Hypixel renames a boss line.
                    { tag -> DungeonSplits.chainFor(tag)?.map { it.name } ?: emptyList() },
                    Format::seconds,
                )
            }
            cachedSplitLines
        }

        View.RUNS -> {
            if (RunPbs.revision != runsKey) {
                runsKey = RunPbs.revision
                cachedRunLines = PbTable.runs(RunPbs.records(), Format::seconds)
            }
            cachedRunLines
        }
    }

    /**
     * Where a personal-best table's columns go.
     *
     * Two of them, measured right to left from what will actually be drawn, which is [RecordColumns]'
     * argument in miniature: `BEST` is a fixed span of capitals and a run total is a fixed span of
     * digits, neither of them a share of the window, and whatever survives on the left is the label's.
     * Nothing is ever dropped here — with two columns there is nothing to drop, and at the narrowest
     * content column this panel has the label still keeps well over half of it.
     *
     * The sample is a **run** total rather than a room clear. `10:23.4` is seven characters where
     * `0:41.2` is six, and a whole M7 does pass ten minutes — a column budgeted against the shorter one
     * puts the slowest record it will ever hold one character into its own header.
     */
    private class PbLayout(val header: String, val labelWidth: Int, val timeX: Int)

    private fun pbLayout(): PbLayout {
        val time = maxOf(
            w(BEST_HEADER, labelText, Type.MEDIUM),
            w(SAMPLE_RUN_TIME, rowText, Type.MEDIUM),
        ).toInt()
        return PbLayout(
            header = if (view == View.RUNS) PARTY_HEADER else SPLIT_HEADER,
            labelWidth = (content - time - RecordColumns.GAP - Table.INDENT).coerceAtLeast(0),
            timeX = lastX,
        )
    }

    /**
     * One personal-best table: two headers, then a floor heading and its records, floor after floor.
     *
     * **The headers are labels and not [Table.headerCell]s, because there is nothing to sort by.**
     * [PbTable.splits] states why the order inside a floor is the run's own and not a column's, and a
     * header cell that grows a caret under the cursor is a promise that a click will reorder the table.
     * The absence of that caret is the signal, which is exactly the three-state design that component
     * already documents — this is its "neither" state, permanently.
     */
    private fun renderPbs() {
        renderPbChips()
        val layout = pbLayout()
        Sk.text(layout.header, contentLeft.toFloat(), columnsY.toFloat(), labelText, Tokens.textTertiary)
        Sk.textRight(BEST_HEADER, layout.timeX.toFloat(), columnsY.toFloat(), labelText, Tokens.textTertiary)
        Table.divider(contentLeft.toFloat(), (columnsY + Tokens.SPACE_12).toFloat(), content.toFloat())

        val all = pbLines()
        if (all.isEmpty()) {
            renderPbEmpty()
            return
        }
        val lines = pbShown()
        if (lines.isEmpty()) {
            val block = EmptyState.height(Sk.lineHeight(EmptyState.BODY_SIZE))
            val y = firstRow + ((listBottom - firstRow - block) / 2f).coerceAtLeast(0f)
            EmptyState.draw(contentLeft.toFloat(), y, content.toFloat(), "nothing on this chip", "esc shows every record")
            return
        }

        val visible = tableRows
        pageSize = visible
        scroll = Scroll.clamp(scroll, lines.size, visible)

        Sk.clip(0f, firstRow.toFloat(), width.toFloat(), (listBottom - firstRow).toFloat())
        for ((index, line) in lines.drop(scroll).take(visible).withIndex()) {
            renderPbLine(layout, line, firstRow + index * Table.ROW)
        }
        Sk.unclip()

        Controls.scrollbar(
            (lastX + Tokens.SPACE_6).toFloat(), firstRow.toFloat(), listBottom.toFloat(),
            lines.size, visible, scroll,
        )
    }

    /** The personal-best lines the current chip lets through. Cheap enough per frame: at most 150 lines. */
    private fun pbShown(): List<PbTable.Line> = when (view) {
        View.SPLITS -> PbTable.narrow(pbLines(), keepFloor = { splitsFilter.matches(it) })
        View.RUNS -> PbTable.narrow(pbLines(), keepFloor = { true }, keepRow = { runsFilter.matches(it.label) })
        View.ROOMS -> emptyList()
    }

    /** The chip labels for the current personal-best view, with how many records each would show. */
    private fun pbChips(): List<Pair<String, Int>> {
        val lines = pbLines()
        return when (view) {
            View.SPLITS -> PbTable.FloorFilter.entries.map { f ->
                f.label to PbTable.count(PbTable.narrow(lines, keepFloor = { f.matches(it) }))
            }
            View.RUNS -> PbTable.PartyFilter.entries.map { f ->
                f.label to PbTable.count(PbTable.narrow(lines, keepFloor = { true }, keepRow = { f.matches(it.label) }))
            }
            View.ROOMS -> emptyList()
        }
    }

    private val pbActiveChip: Int
        get() = if (view == View.SPLITS) splitsFilter.ordinal else runsFilter.ordinal

    private fun selectPbChip(index: Int) {
        when (view) {
            View.SPLITS -> PbTable.FloorFilter.entries.getOrNull(index)?.let { splitsFilter = it }
            View.RUNS -> PbTable.PartyFilter.entries.getOrNull(index)?.let { runsFilter = it }
            View.ROOMS -> {}
        }
        scroll = 0
    }

    /** The chip row over a personal-best table — the rooms view's row, for a filter with three answers. */
    private fun renderPbChips() {
        val chips = pbChips()
        val chipMeasure: (String) -> Float = { w(it, Tokens.TEXT_11.toFloat()) }
        val withCounts = chips.sumOf {
            Math.ceil(Controls.chipWidth(it.first, it.second, chipMeasure).toDouble()).toInt()
        } + (chips.size - 1) * Tokens.SPACE_6
        val showCounts = withCounts <= content
        pbChipHits.clear()
        var chipX = contentLeft
        Sk.clip(contentLeft.toFloat(), chipsY.toFloat(), content.toFloat(), CHIP_H.toFloat())
        for ((index, chip) in chips.withIndex()) {
            val count = if (showCounts) chip.second else -1
            val chipWidth = Math.ceil(Controls.chipWidth(chip.first, count, chipMeasure).toDouble()).toInt()
            val on = index == pbActiveChip
            val active = anim.of("pbchip.${view.name}.$index", if (on) 1f else 0f)
            active.animateTo(if (on) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            val hovered = pointerX in chipX until minOf(chipX + chipWidth, lastX) &&
                pointerY in chipsY until (chipsY + CHIP_H)
            val hover = Controls.hover(anim.of("pbchiphover.${view.name}.$index"), hovered)
            Controls.chip(chipX.toFloat(), chipsY.toFloat(), CHIP_H.toFloat(), chip.first, count, active.value, hover)
            if (chipX < lastX) pbChipHits.add(Triple(index, chipX, minOf(chipX + chipWidth, lastX)))
            chipX += chipWidth + Tokens.SPACE_6
        }
        Sk.unclip()
    }

    /**
     * One line: a floor heading, or one record indented under it.
     *
     * **No hover wash on a record row, and that is deliberate rather than unfinished.** This screen's
     * one signal separating a fact from a control is that only a control lights up under the cursor —
     * [SettingsPage.Item] says so about its own non-interactive lines. A personal best is a fact: there
     * is nothing behind it to expand, because the store holds one number per key and that number is
     * already on the row.
     *
     * The heading is drawn through the same [Chrome.groupLabel] the settings pages use, so a floor
     * boundary on a table and a section boundary on a page are the same shape. Its meta is the floor's
     * headline time, and it is absent rather than dashed when there is none — see [PbTable].
     */
    private fun renderPbLine(layout: PbLayout, line: PbTable.Line, y: Int) {
        if (line.heading) {
            Chrome.groupLabel(
                contentLeft.toFloat(), Sk.centreY(y.toFloat(), Table.ROW.toFloat(), labelText),
                content.toFloat(), line.floor.uppercase(), line.time,
            )
            return
        }
        Sk.text(
            fit(line.label!!, layout.labelWidth, rowText), (contentLeft + Table.INDENT).toFloat(),
            Sk.centreY(y.toFloat(), Table.ROW.toFloat(), rowText), rowText, Tokens.textSecondary,
        )
        right(line.time, layout.timeX, y, present = true)
    }

    /**
     * The empty state of a personal-best table.
     *
     * Its own function rather than a fourth case in [renderEmpty], because neither of this screen's two
     * narrowings exists here — there is no search and no chip on these two views, so every sentence
     * [renderEmpty] can produce is about a filter that is not there. What a reader needs instead is
     * where the records would come from, which is a different fact per view.
     */
    private fun renderPbEmpty() {
        val splits = view == View.SPLITS
        val note = if (splits) CONFIG_FILE else RUNPBS_FILE
        val block = EmptyState.height(Sk.lineHeight(EmptyState.BODY_SIZE), note)
        val y = firstRow + ((listBottom - firstRow - block) / 2f).coerceAtLeast(0f)
        EmptyState.draw(
            contentLeft.toFloat(), y, content.toFloat(),
            if (splits) "no split records yet" else "no run records yet",
            if (splits) {
                // Both ways in, because the switch is the common case and the import is the one that
                // gives somebody who has played for a year their records back on the first launch.
                "switch on run splits in hud · or import from odin on debug"
            } else {
                "finish a floor and its clear time lands here"
            },
            note,
        )
    }

    /**
     * The empty states.
     *
     * Same key and same words as the footer: the hint has to name which of the two narrowings escape
     * takes off, not say "the search" while a chip is what is hiding every room.
     */
    private fun renderEmpty(top: Int, bottom: Int) {
        val blank = narrowing == RecordTable.Narrowing.NONE
        val note = if (blank) HISTORY_FILE else null
        val headline = if (blank) "no history yet" else "nothing matches this filter"
        val hint = when {
            blank -> "finish a dungeon room and it lands here"
            narrowing == RecordTable.Narrowing.SEARCH -> "esc clears the search · click \"all\" for every room"
            else -> "esc shows every room"
        }
        // **Centred in the band it was given rather than pushed down from the top of it.** A fixed
        // offset is a fixed offset at every window size, and at `guiScaledHeight` 240 — the vanilla
        // minimum, and a 320×240 GUI is what a small window gets — the block ran into the footer. This
        // is the first thing a fresh install sees on this screen, and it was the one arrangement never
        // laid out against a real height.
        val block = EmptyState.height(Sk.lineHeight(EmptyState.BODY_SIZE), note)
        val y = top + ((bottom - top - block) / 2f).coerceAtLeast(0f)
        EmptyState.draw(contentLeft.toFloat(), y, content.toFloat(), headline, hint, note)
    }

    /**
     * Placement mode's HUD element, drawn through Minecraft's own renderer.
     *
     * **It shows the element at its own position rather than under the cursor, and that is the change
     * that makes this an editor.** Before, the preview followed the mouse and a click dropped its
     * top-left corner there — so the one thing a player wanted to see, where the HUD *is*, was the one
     * thing the mode never showed, and moving it three pixels meant re-aiming at nothing.
     *
     * One mode for all three, because they are one question asked about three rectangles. It draws the
     * real element in every case: the card with the live run when there is one — [HudSnapshot.current]
     * is what the overlay itself reads, so during a dungeon this is the actual HUD, at actual width,
     * with the actual room in it — and each chip through the same function the game draws it with.
     *
     * Still on the old renderer because the HUD is, and that is the whole reason
     * [SkScreen.extractExtra] exists. When the HUD draws through [Sk] this merges back into
     * [renderPlacingChrome].
     */
    private fun renderPlacingElement(graphics: GuiGraphicsExtractor, target: Target) {
        val origin = placingOrigin(target)
        when (target) {
            // The card and the splits panel draw at an origin the editor hands them, so the size is
            // applied here — exactly as HudRoot.render and SplitsHud.render do it for the live ones.
            // The other three resolve their own placement and scale themselves.
            Target.CARD -> Zoom.at(graphics, origin.x, origin.y, target.slot.scale) {
                previewHud.draw(graphics, font, placingSnapshot(), 0, 0)
            }
            Target.POPUP -> ClearPopup.drawAt(
                graphics, font, width, height,
                PLACING_POPUP.name, PLACING_POPUP.detail, PLACING_POPUP.pb, ClearPopup.PRESENT_MS,
            )
            Target.TIMER -> StormHud.draw(graphics, font, width, height, StormHud.sample())
            // The scripted mid-run F7: the tallest the panel gets, and the one state that shows all
            // three of its tones at once. See Splits.sample.
            Target.SPLITS -> Zoom.at(graphics, origin.x, origin.y, target.slot.scale) {
                SplitsHud.draw(graphics, font, Splits.sample(), 0, 0)
            }
            Target.SPLITS_CURRENT -> SplitsCurrentHud.draw(
                graphics, font, width, height, SplitsCurrentHud.sample(),
            )
        }
    }

    /** Placement mode's readouts, which are this screen's own and therefore drawn through [Sk]. */
    private fun renderPlacingChrome(target: Target) {
        val origin = placingOrigin(target)
        val h = placedHeight(target)

        // The numbers, beside the element and out of it, so a player who wants an exact position can
        // read one off while dragging rather than guessing at what they landed on.
        //
        // **The anchor is named alongside them, and it updates as the element is dragged.** What is
        // stored is an offset from an edge, and an offset is meaningless without the edge it counts
        // from — "8, 8" is the top left corner or the bottom right one depending on a fact the player
        // would otherwise have to infer. Watching the label change as the element crosses into the next
        // third is also the only way the anchoring is visible at all before a resolution change.
        val text = target.slot.label()
        val textWidth = w(text, noteText, Type.MEDIUM)
        // Under it, unless there is no room under it. A bottom-anchored element has its own bottom edge
        // against the screen's, and a label drawn below that one is a label nobody can read — which
        // would take the numbers away in exactly the corner where they are hardest to guess.
        val below = origin.y + h + Tokens.SPACE_6
        val labelY = if (below + HudRoot.TEXT_LINE <= height) below else origin.y - Tokens.SPACE_12
        Sk.text(
            text,
            origin.x.toFloat().coerceAtMost(width - textWidth).coerceAtLeast(0f),
            labelY.coerceAtLeast(0).toFloat(), noteText,
            if (dragging) Tokens.textPrimary else Tokens.textTertiary, Type.MEDIUM,
        )

        // Two lines, because one that says all of it does not fit: at GUI scale 4 on a 1366×768 window
        // the screen is 342 px wide, and the single line this replaced already measured about 350 before
        // there was anything new to say on it.
        Sk.text(
            if (dragging) {
                "release to place it · wheel resizes it"
            } else {
                "drag the ${target.what} · wheel resizes · arrows nudge · r resets"
            },
            frameLeft.toFloat(), headerY.toFloat(), noteText, Tokens.textTertiary,
        )
        Sk.text(
            "click off it when done · esc cancels",
            frameLeft.toFloat(), (headerY + HudRoot.TEXT_LINE + Tokens.SPACE_2).toFloat(),
            noteText, Tokens.textTertiary,
        )
    }

    /** The live run when there is one, the scripted preview when there is not. */
    private fun placingSnapshot(): HudSnapshot =
        HudSnapshot.current.takeIf { it.inDungeon } ?: HudPreview.at(Clock.nowMs)

    /**
     * How wide and how tall [target]'s overlay is on this screen — the rectangle a hand grabs.
     *
     * Every one of these is the same call the element makes when it draws itself: the card's measured
     * height, and each chip's own width function. That is not tidiness. An editor that measured a chip
     * its own way would derive an anchor from a rectangle that was never on screen, and the element
     * would settle a few pixels from where it was dropped — the class of bug this screen's rule 3 is
     * about, one file further out.
     */
    private fun placingWidth(target: Target): Int = when (target) {
        Target.CARD -> HudRoot.WIDTH
        Target.POPUP -> ClearPopup.width(font, PLACING_POPUP.name, PLACING_POPUP.detail, PLACING_POPUP.pb, width)
        Target.TIMER -> StormHud.width(font, StormHud.sample())
        Target.SPLITS -> SplitsHud.WIDTH
        Target.SPLITS_CURRENT -> SplitsCurrentHud.width(font, SplitsCurrentHud.sample())
    }

    private fun placingHeight(target: Target): Int = when (target) {
        Target.CARD -> previewHud.measure(placingSnapshot())
        Target.POPUP -> ClearPopup.HEIGHT
        Target.TIMER -> StormHud.HEIGHT
        Target.SPLITS -> SplitsHud.measure(Splits.sample())
        Target.SPLITS_CURRENT -> SplitsCurrentHud.HEIGHT
    }

    /**
     * The same two measurements at the size the element is actually drawn — the rectangle a hand
     * grabs, the one a drag is clamped against, and the one the label hangs under.
     *
     * Everything the editor does about *position* goes through these rather than through the two above,
     * for the reason [placingWidth] gives about measuring a chip its own way: an element scaled to 150%
     * but grabbed by an unscaled rectangle can be picked up off its own bottom right corner, and a drag
     * clamped by one can be dropped a third of the way off the screen. [OverlayPlacement.origin] applies
     * the same factor at the other end, so the two agree by construction.
     */
    private fun placedWidth(target: Target): Int = target.slot.scaled(placingWidth(target))

    private fun placedHeight(target: Target): Int = target.slot.scaled(placingHeight(target))

    /**
     * [target]'s top-left corner on this screen, resolved from the stored anchor and offset.
     *
     * Everything in placement mode goes through this rather than through the stored numbers, so what
     * is drawn, what can be grabbed and what the overlay will show in a dungeon are all the same
     * arithmetic — [HudPlacement.origin] — applied to the same screen.
     *
     * The card goes via [Config.hudOrigin] because that call is also where a still-pending migration is
     * finished, which is the one thing the two chips have no equivalent of.
     */
    private fun placingOrigin(target: Target): HudPlacement.Origin {
        val w = placingWidth(target)
        val h = placingHeight(target)
        if (target == Target.CARD) return Config.hudOrigin(width, height, w, h)
        return target.slot.origin(width, height, w, h)
    }

    /** Whether ([x], [y]) is on the element being placed, which is what can be grabbed. */
    private fun onPlaced(target: Target, x: Int, y: Int): Boolean {
        val origin = placingOrigin(target)
        // Never smaller than a thumb: at 50% the split clock is about 30×11 px, and an element too
        // small to hit is one whose size cannot be scrolled back up.
        val w = placedWidth(target).coerceAtLeast(GRAB_MIN)
        val h = placedHeight(target).coerceAtLeast(GRAB_MIN)
        return x >= origin.x && x < origin.x + w && y >= origin.y && y < origin.y + h
    }

    /**
     * Moves the element so the point that was grabbed stays under the cursor, and re-derives the anchor
     * it now hangs from.
     *
     * **The drag stays a drag.** The element is put where the hand stopped and [HudPlacement.nearest]
     * reads the anchor off that, rather than asking the player to choose one of nine from a list and
     * then think in offsets from it — nobody wants to name a corner, they want the thing over there.
     *
     * Clamped against the element's real size, which is [HudPlacement.nearest]'s job now. The clamp
     * itself is not new and is not optional: the click-to-place version clamped a token 8 pixels,
     * being the only part of the card it knew about, so a card placed near the right edge hung off the
     * screen by everything except its first eight pixels.
     */
    private fun dragTo(target: Target, mouseX: Int, mouseY: Int) =
        moveTo(target, mouseX - grabX, mouseY - grabY)

    /**
     * Keeps the held point under the cursor after the element changed size in the middle of a drag.
     *
     * The element grows from its own top-left corner, so without this the thing being dragged slides
     * out from under the hand — one notch of the wheel on a 200 px card moves its far edge 20 px away
     * from the cursor, and a resize that is also a move is two changes for one gesture.
     *
     * The offset is **scaled**, not recomputed from the cursor: what survives is the *proportion* of
     * the element the hand is holding, so a chip grabbed by its right end is still held by its right
     * end when it is twice as big, rather than snapping to whichever pixel the cursor is over. Then it
     * is moved to match, because the offset and the position are two halves of one statement.
     */
    private fun regrab(target: Target, mouseX: Int, mouseY: Int, wasW: Int, wasH: Int) {
        grabX = held(grabX, wasW, placedWidth(target))
        grabY = held(grabY, wasH, placedHeight(target))
        moveTo(target, mouseX - grabX, mouseY - grabY)
    }

    /** [grabbed] pixels into something [before] wide, expressed in something [after] wide. */
    private fun held(grabbed: Int, before: Int, after: Int): Int =
        if (before <= 0) 0 else Math.round(grabbed.toFloat() / before * after)

    /** Puts the element's top-left corner at ([x], [y]): where a drag, or a nudge, has just put it. */
    private fun moveTo(target: Target, x: Int, y: Int) = Config.place(
        target.slot, x, y, width, height, placingWidth(target), placingHeight(target),
    )

    /**
     * The arrow key on [event] as a step in screen pixels, or null if it is not one.
     *
     * **In screen pixels and not in offsets**, because an offset's sign depends on the edge it counts
     * from: at a right-hand anchor a larger `offsetX` moves the element *left*, and "right arrow moves
     * it right" has to hold at all nine anchors. So a nudge is a position and goes through the same
     * [moveTo] a drag does, anchor re-derivation included — a nudge across a boundary does what a drag
     * across the same pixel does.
     *
     * A drag gets an element roughly where it belongs and cannot do the last part: the offsets worth
     * having are *numbers* — 0 for exactly centred, 4 for the same inset the card has — and landing a
     * hand on a number is luck. This is what makes the position adjustable rather than only movable.
     */
    private fun nudge(event: KeyEvent): Pair<Int, Int>? {
        val step = if (event.hasShiftDown()) NUDGE_FAR else 1
        return when (event.key()) {
            GLFW.GLFW_KEY_LEFT -> -step to 0
            GLFW.GLFW_KEY_RIGHT -> step to 0
            GLFW.GLFW_KEY_UP -> 0 to -step
            GLFW.GLFW_KEY_DOWN -> 0 to step
            else -> null
        }
    }

    /**
     * Opens the editor on [target].
     *
     * Only the card's own overlay has to stand down: it draws from the config being edited, so leaving
     * it on renders two cards — the real one stuck at the old spot and the one under the cursor — and
     * the player has to work out which of them they are moving. The two chips are drawn here from the
     * same config, so a live one would land on exactly the same pixels rather than beside them.
     */
    private fun startPlacing(target: Target) {
        placing = target
        dragging = false
        placingWas = target.slot.snapshot()
        HudRoot.editing = target == Target.CARD
    }

    /** Leaves placement mode, keeping [keep] or putting the element back where it was picked up. */
    private fun stopPlacing(keep: Boolean) {
        val was = placingWas
        if (keep) Config.save() else was?.let { placing?.slot?.restore(it) }
        placing = null
        placingWas = null
        dragging = false
        HudRoot.editing = false
    }

    // --- The pages, as data -----------------------------------------------------------------

    private fun pageItems(): List<SettingsPage.Item> = when (tab) {
        Tab.HUD -> hudItems()
        Tab.CHAT -> chatItems()
        Tab.DEBUG -> debugItems()
        Tab.RECORDS, Tab.SOLO -> emptyList()
    }

    /**
     * The HUD tab, in three sections: the card itself, what is written on it, and what is drawn
     * elsewhere.
     *
     * The grouping is not decoration. Ten equally ranked rows is a list somebody has to read all of to
     * find one switch, and three of these settings are not about the card at all — [Config.clearPopup]
     * and [Config.stormTimer] each have their own KDoc saying exactly that, and both of them say it
     * because somebody once expected switching off the card to take them with it.
     */
    private fun hudItems(): List<SettingsPage.Item> = buildList {
        section("the card")
        toggle("show HUD", Config.hud) { Config.hud = !Config.hud }
        place(Target.CARD)
        note("the offset counts inward from the anchor")

        // **Its own section, and not part of "the card".** One number carries the backdrop under all
        // three overlays — see Config.hudScrim for why that is the design and not an economy — and while
        // the row sat under a heading that says "the card", the only way to find that out was to move
        // the slider and watch a popup. The heading's meta names the scope; the notes under it spell it
        // out.
        section("backdrop", "card + both chips")
        // The one control on this screen that is a sweep rather than a correction, which is the whole
        // of `Stepper` against `Slider`: nobody has a number in mind for a backdrop, they move it until
        // the dungeon behind it looks right.
        //
        // **The bounds are read from Tokens and never written here.** They are two facts now: how far
        // the slider goes, and where `textTertiary` over a white world stops clearing 4.5:1 — measured,
        // and still pinned in UiThemeTest. The second used to be the first. It is a note rather than a
        // limit because the two people who use this mod asked for the range, and a control that refuses
        // to reach the value somebody wants is not a control.
        slider("scrim", Config.hudScrim, Tokens.SCRIM_MIN_PERCENT, Tokens.SCRIM_MAX_PERCENT) {
            Config.hudScrim = it
        }
        // The number is the backdrop's *opacity*, so the note has to be about the backdrop. "How much
        // of the dungeon shows through" is the same quantity read backwards, and it made the value and
        // the sentence disagree about which way the slider was going: at 90 % it said 90, and ten per
        // cent of the dungeon was showing.
        note("how much of the dungeon the backdrop covers")
        // What the low end costs, said only when it is being paid. A permanent warning about a value
        // nobody has chosen is a warning people learn to read past, and this is the one setting on the
        // screen whose cost is invisible in the room it is set in: a card that reads perfectly against
        // the black corridor behind the settings screen is the same card over a snow floor.
        if (Config.hudScrim < Tokens.SCRIM_CONTRAST_PERCENT) {
            state("under ${Tokens.SCRIM_CONTRAST_PERCENT} % the smallest grey text can drop below 4.5:1")
        }

        section("lines on the card")
        toggle("current room", Config.showRoom) { Config.showRoom = !Config.showRoom }
        // "your secrets", not "secrets": the action bar already counts the room's, and the whole
        // point of the line is that this one is about you.
        toggle("your secrets", Config.showSecrets) { Config.showSecrets = !Config.showSecrets }
        note("the ones this client can prove were yours")

        // **Their own section, and the keybind above them, because these two are not lines on the
        // card.** They are inside the run-totals panel, the panel's only handle is a keybind, and that
        // keybind ships unbound — so switching on "idle & nav" produced nothing at all and there was
        // nothing on this screen that could have said why. See HudKeys. The grouping is the fix and the
        // row is the rest of it: knowing the key is missing is no use without a way to set it.
        section("run totals")
        // **The panel's own switch, which is the same value the keybind flips.** One state with two ways
        // in, so this row can never disagree with what is on screen, and a player who wants the panel
        // permanently switches it on once instead of pressing a key every session — see
        // Config.totalsOpen for why it used to be neither.
        toggle("show the panel", Config.totalsOpen) { Config.totalsOpen = !Config.totalsOpen }
        state(
            if (Config.totalsOpen) {
                "stays open — in every run, and after a restart"
            } else {
                "closed: the two lines below are inside it"
            },
        )
        HudKeys.expand?.let { key ->
            action("expand key", if (key.isUnbound) "unbound · bind" else "${key.translatedKeyMessage.string} · change") {
                // Vanilla's screen rather than a bind capture of our own: this screen has no widget tree
                // (rule 3), capturing a key is a modal state machine, and vanilla already lists our
                // category — `key.category.sighteaddons.main` is "Sighte Addons" in the lang file. This
                // screen as the parent means Done comes back here.
                minecraft.setScreen(KeyBindsScreen(this@SettingsScreen, minecraft.options))
            }
            // Three states and three sentences, because the useless one is a *pair* of settings rather
            // than either of them: a closed panel with an unbound key is the case where switching on
            // "idle & nav" does nothing at all, and it is the only case worth a warning.
            state(
                when {
                    !key.isUnbound -> "press it in a run to open and close the panel"
                    Config.totalsOpen -> "the panel is open above, so this is optional"
                    else -> "unbound and closed: nothing below can show"
                },
            )
        }
        // "idle & nav", because the two numbers are the point: standing in a finished room and
        // walking between rooms are different problems and one figure could not tell them apart.
        toggle("idle & nav", Config.showIdle) { Config.showIdle = !Config.showIdle }
        note("time standing still and time walking, apart")
        toggle("standings", Config.showStandings) { Config.showStandings = !Config.showStandings }

        section("elsewhere on screen")
        toggle("clear popup", Config.clearPopup) { Config.clearPopup = !Config.clearPopup }
        note("the large line when you clear a room")
        // Both chips are placeable now, and each row is only offered while its chip is switched on: a
        // placement editor for something that is not going to be drawn is a mode with nothing in it.
        // The rows carry distinct labels rather than three "position"s, because a row's hover and its
        // toggle animation are keyed by that label — three of them would share one animation, and
        // hovering any one would light all three.
        if (Config.clearPopup) place(Target.POPUP)
        // On this tab and not the chat one because it is drawn on screen — the mirror of the
        // argument that puts "crit readout" over there.
        toggle("storm timer", Config.stormTimer) { Config.stormTimer = !Config.stormTimer }
        // The two inherited numbers, steppable a tick at a time. They are rows and not constants
        // because nobody here knows where 138 and 20 came from and a wrong one never announces
        // itself — see StormTimer. Shown only while the timer is on: off, they are two rows of
        // arithmetic about something that is not going to be drawn.
        if (Config.stormTimer) {
            place(Target.TIMER)
            // On both steppers rather than once above them. As a line it sat over the pair and read
            // as being about both; as a tooltip it has to be on the row a hand is actually over, and
            // the row a hand is over is one of the two numbers the sentence is about.
            val inherited = "138 and 20 are inherited and unverified"
            stepper(
                "countdown", Config.stormCountdownTicks, StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX,
            ) { back ->
                Config.stormCountdownTicks = StormTimer.step(
                    Config.stormCountdownTicks, StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX, back,
                )
            }
            note(inherited)
            stepper("shoot window", Config.stormShootTicks, StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX) { back ->
                Config.stormShootTicks = StormTimer.step(
                    Config.stormShootTicks, StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX, back,
                )
            }
            note(inherited)
        }

        // Its own section rather than another row under "elsewhere on screen", because it is five
        // settings and two placeable elements — the same reason that heading exists at all.
        section("splits", if (Config.splits) "timed" else "off")
        toggle("run splits", Config.splits) { Config.splits = !Config.splits }
        note("blood, portal and every boss phase, timed from mort")
        if (Config.splits) {
            place(Target.SPLITS)
            toggle("tick column", Config.splitsTickTime) { Config.splitsTickTime = !Config.splitsTickTime }
            // The distinction the second column exists for, in the row that switches it on. Two times
            // side by side with no explanation is the one thing a reader cannot work out from the panel.
            note("the same span in server ticks, free of lag")
            toggle("boss entry row", Config.splitsBossEntry) { Config.splitsBossEntry = !Config.splitsBossEntry }
            note("mort's line to the boss's first, summed")
            toggle("splits in chat", Config.splitsSendToChat) { Config.splitsSendToChat = !Config.splitsSendToChat }
            note("one line per split when the run ends")
            toggle("lag row", Config.splitsLag) { Config.splitsLag = !Config.splitsLag }
            // What the number is, in the row that switches it on: the two columns minus each other,
            // which is not something a reader can be expected to infer from the word "lag".
            note("the run's wall clock minus its server ticks, summed")
            toggle("estimate row", Config.splitsEstimate) { Config.splitsEstimate = !Config.splitsEstimate }
            note("closed splits as run, plus your expected times for the rest")
            if (Config.splitsEstimate) {
                // The floor chooser is a stepper over the fifteen tags rather than fifteen rows of
                // fields: the chains are up to nine splits long, and every floor's plan on one page
                // would be a hundred rows deep. PbTable's reading order — masters first, sevens first
                // — so the floor being run tonight is a step away, not fourteen.
                add(
                    SettingsPage.Item(
                        SettingsPage.Kind.STEPPER, "expected times for", estimateFloor,
                        fraction = Slider.fractionOf(FLOOR_TAGS.indexOf(estimateFloor), 0, FLOOR_TAGS.lastIndex),
                        step = { back ->
                            commitField()
                            estimateFloor = FLOOR_TAGS.neighbour(estimateFloor, back)
                        },
                    ),
                )
                note("master floors keep their own plan — an m7 is not an f7")
                val chain = DungeonSplits.chainFor(estimateFloor)
                chain?.dropLast(1)?.forEach { split ->
                    add(
                        SettingsPage.Item(
                            SettingsPage.Kind.FIELD, split.name,
                            value = SplitExpected.get(estimateFloor, split.name)?.let(Format::seconds) ?: "",
                            // The identity the draw and the focus agree on — see [fieldKey].
                            meta = fieldKey(estimateFloor, split.name),
                            click = { focusField(estimateFloor, split.name) },
                        ),
                    )
                    note("type a time — 1:05, 65 or 65.5 all read as themselves")
                }
                action("prefill from PBs", "this floor's records${VERB_SEPARATOR}copy") {
                    SplitExpected.prefillFromPbs(estimateFloor)
                }
                note("copies each split's personal best over this floor's plan")
                state(estimatePlanState(chain))
            }
            toggle("split clock", Config.splitsCurrent) { Config.splitsCurrent = !Config.splitsCurrent }
            note("the running split alone, large, for a boss fight")
            if (Config.splitsCurrent) place(Target.SPLITS_CURRENT)
        }
    }

    /**
     * The estimate section's standing sentence: whether the row can draw at all on this floor.
     *
     * A [state] line and not a tooltip, for [SettingsPage.Kind]'s rule — it is a statement about the
     * current value, and it is the answer to the one question this feature will actually be asked:
     * "why is there no EST. RUN row on my panel". All-or-nothing is [RunEstimate]'s contract.
     */
    private fun estimatePlanState(chain: List<DungeonSplits.Split>?): String {
        val names = chain?.dropLast(1)?.map { it.name } ?: return "unknown floor"
        val planned = names.count { SplitExpected.get(estimateFloor, it) != null }
        return when (planned) {
            names.size -> "all ${names.size} splits planned — the row can draw on $estimateFloor"
            else -> "$planned of ${names.size} splits planned — the row needs all of them"
        }
    }

    private fun chatItems(): List<SettingsPage.Item> = buildList {
        section("room lines")
        toggle("room messages", Config.roomMessages) { Config.roomMessages = !Config.roomMessages }
        toggle("own PBs only", Config.ownPbsOnly) { Config.ownPbsOnly = !Config.ownPbsOnly }
        note("hides every line that was not a record of yours")

        section("end of run")
        toggle("run summary", Config.runSummary) { Config.runSummary = !Config.runSummary }
        note("the breakdown when a floor ends")
        // "crit readout", not "crit": the number the line exists for is the one per Blessing of
        // Power, and nothing about it is sent anywhere — see CritMeter.
        toggle("crit readout", Config.critLine) { Config.critLine = !Config.critLine }
        note("per Blessing of Power, and never sent anywhere")

        section("history")
        info("history is always written", "history.jsonl")
        note("every room you finish is appended, switch or not")
    }

    private fun debugItems(): List<SettingsPage.Item> = buildList {
        section("logging")
        toggle("JSONL telemetry", Config.debugLog) { Config.debugLog = !Config.debugLog }
        info("target", "config/sighteaddons/debug/")

        section("upload")
        toggle("upload run reports", Config.upload) { Config.upload = !Config.upload }
        // Off by default and the only switch here that makes data leave identifiable. For the
        // leaderboards: a row needs a name on it, and that is the player's to give.
        toggle("send my name", Config.uploadName) {
            Config.uploadName = !Config.uploadName
            // Reports are written at run end and only sent at the next game start, so the switch
            // has to reach the ones already waiting — otherwise a run played minutes ago leaves
            // anonymously despite the consent given just now.
            RunReport.restampPending()
        }
        // What would actually leave the machine, which the previous version computed and then never
        // drew: its renderer chose a toggle whenever a row had one, and the string was dead code
        // behind a KDoc promising it was legible before the click. A note is where it fits.
        state(uploadName())
        info("your upload id", Config.installId)
        note("all the server knows about you hangs off this")

        section("solo clears", if (Config.soloClears) "announced" else "off")
        // A separate consent from the two above, not a sub-setting of them: this one puts a name and a
        // time in a chat channel other people read, which is not what a run report does.
        toggle("announce in discord", Config.soloClears) { Config.soloClears = !Config.soloClears }
        // Moved up onto the switch it is about. It used to be the last line of the section, which is
        // where a line can sit and a tooltip cannot: nobody hovers the score row to find out where a
        // solo clear is written down.
        note("kept in soloclears.jsonl, scored by this mod's own reckoning")
        // Three stops rather than a free number: S+ and S are the two thresholds anybody means, and 0
        // is "announce everything". A stepper over 0..300 would offer 298 as if it meant something.
        action("minimum score", scoreGate()) {
            Config.soloClearMinScore = when (Config.soloClearMinScore) {
                0 -> 270
                270 -> 300
                else -> 0
            }
        }
        state(soloClears())

        section("run records", if (Config.runPbs) "on the leaderboard" else "kept here only")
        // A third consent and not a sub-setting of the two above: this one is a standing public row
        // with a name on it, where a report is anonymous and an announcement is one line in a channel.
        // Config.runPbs argues it in full; this row is where a hand reaches it.
        toggle("send new bests", Config.runPbs) { Config.runPbs = !Config.runPbs }
        state(runPbs())
        note("recorded either way, in runpbs.jsonl, per floor and party size")

        // **Blank is the normal state now, not a missing prerequisite.** The heading used to read
        // "not set" over a note saying the feature stayed inert, which was true when every player
        // needed their own key and is a lie now that the receiver holds one — see SecretApi.Source.
        // Somebody reading this row has to be able to tell "nothing to do" from "switched off".
        section("data")
        info("rooms in the database", RoomDatabase.roomCount.toString())
        // Cores and not rooms, which the previous version's single "rooms in the database" row called
        // rooms while showing this. Both are worth having and only one of them is a room count.
        info("room cores", RoomDatabase.size.toString())
        info("lines in the history", RoomHistory.entryCount().toString())
        // **The two record counts that used to be here are on the records page now**, each in the
        // header of the table it counts. A number beside the rows it is a count of is something a
        // reader can act on; the same two numbers in a `data` section three pages away from the
        // records were two facts somebody had to carry there and back.
        //
        // The import stays, because it is an action and not a number: a hand asks for it, it is never
        // done on startup — reading a neighbouring mod's config unprompted is not something anybody
        // asked for, and the answer cannot change while playing — and this page is where the mod's
        // other one-off actions are.
        // The answer goes to chat rather than into a row that changes, because it has to survive
        // the screen closing — and because [Chat] is this mod's one place for saying anything at all.
        // The row and `/sa import` are the same call, so neither can word the outcome differently.
        action("import from odin", "config/odin/ · run") { SplitImport.run() }
        note("takes the faster of the two, safe to run twice")
        note("or type /sa import, which is the same thing")
        note("the records themselves are on the records page")
    }

    /**
     * What leaving the run-record switch on actually sends, named field by field.
     *
     * The same job [uploadName]'s note does, and for the same reason: a switch about a public row has
     * to be legible before it is clicked, not after somebody sees their name on a board. The floor and
     * the party size are in there because they are what makes the row comparable at all.
     */
    private fun runPbs(): String = when {
        !Config.runPbs -> "off: bests are recorded, nothing leaves this machine"
        else -> "${minecraft.user.name}, the floor, the party size and the time, on every new best"
    }

    // --- Item builders ----------------------------------------------------------------------

    private fun MutableList<SettingsPage.Item>.section(title: String, meta: String = "") =
        add(SettingsPage.Item(SettingsPage.Kind.SECTION, title, meta = meta))

    /**
     * What the row above does, for the tooltip that appears when the cursor is on it.
     *
     * **The last row that is a row.** A [state] line is itself a sentence about the row above it, so a
     * note landing on one would be an explanation of an explanation — and nothing would ever show it,
     * because a hand hovers the switch and not the grey line under it. Skipping back past them is what
     * lets the two be written in whichever order reads best at the call site.
     *
     * Nothing at all if there is no row yet, which is a source mistake rather than a state: a lost
     * sentence is a page that still works, and a settings screen must not be the thing that crashes.
     */
    private fun MutableList<SettingsPage.Item>.note(text: String) {
        lastOrNull { it.kind != SettingsPage.Kind.NOTE }?.notes?.add(text)
    }

    /**
     * A sentence that stays on the page as its own line, because it is about the current value.
     *
     * The whole of the distinction [SettingsPage.Kind] now draws: [note] explains what a row *is* and
     * is the same sentence forever, so it can wait behind a cursor. This says what the row is *doing* —
     * `$name rides on every report`, `unbound and closed: nothing below can show`, what a scrim under
     * the measured contrast floor costs — and a reader who has to hover to find that out is a reader
     * who finds it out too late.
     */
    private fun MutableList<SettingsPage.Item>.state(text: String) =
        add(SettingsPage.Item(SettingsPage.Kind.NOTE, text))

    private fun MutableList<SettingsPage.Item>.toggle(label: String, on: Boolean, click: () -> Unit) =
        add(SettingsPage.Item(SettingsPage.Kind.TOGGLE, label, on = on, click = click))

    private fun MutableList<SettingsPage.Item>.action(label: String, value: String, click: () -> Unit) =
        add(SettingsPage.Item(SettingsPage.Kind.ACTION, label, value, click = click))

    /**
     * One element's position row: where it is, and the word that opens the editor on it.
     *
     * The anchor is named before the offset because it is the half that decides what the offset means —
     * see [renderPlacing] — and the label comes from [OverlayPlacement.label] so this row and the one
     * under the element being dragged cannot come to word the same fact differently.
     */
    private fun MutableList<SettingsPage.Item>.place(target: Target) =
        action(target.label, "${target.slot.label()} · move") { startPlacing(target) }

    private fun MutableList<SettingsPage.Item>.info(label: String, value: String) =
        add(SettingsPage.Item(SettingsPage.Kind.INFO, label, value))

    /**
     * A tick count, with its position in its own range under it.
     *
     * The range comes from [StormTimer] and the wrap still comes from [StormTimer.step], so the two
     * numbers keep the bounds and the wrap-around they have always had — only the way a hand reaches
     * them changed.
     */
    private fun MutableList<SettingsPage.Item>.stepper(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        step: (Boolean) -> Unit,
    ) = add(
        SettingsPage.Item(
            SettingsPage.Kind.STEPPER, label, StormTimer.ticksLabel(value),
            fraction = Slider.fractionOf(value, min, max), step = step,
        ),
    )

    /**
     * A percentage swept between two bounds the caller owns.
     *
     * [min] and [max] are parameters and are converted here, so the only thing that ever crosses into
     * the drawing is a `0f..1f` — no part of this screen holds a copy of a limit that was measured
     * somewhere else and can move when the palette does.
     */
    private fun MutableList<SettingsPage.Item>.slider(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        set: (Int) -> Unit,
    ) = add(
        SettingsPage.Item(
            SettingsPage.Kind.SLIDER, label, "$value %",
            fraction = Slider.fractionOf(value, min, max),
            slide = { set(Slider.valueAt(min, max, it)) },
        ),
    )

    // --- Data -------------------------------------------------------------------------------

    /**
     * Rebuilds the visible lines when something they depend on changed.
     *
     * Sorting a hundred rows per frame was already free, but the search adds a string match per row
     * and an expanded room reads its whole progression back — and none of it can change between two
     * frames without one of these inputs changing with it.
     */
    private fun build() {
        val key = "$sortBy|$sortDesc|$filter|$query|$expanded|${RoomHistory.entryCount()}"
        if (key == cacheKey) return
        cacheKey = key

        val all = RecordTable.rows(RoomHistory.records()) { RoomDatabase.infoByName(it)?.type }
        val found = RecordTable.search(all, query)
        cachedTotal = all.size
        cachedMatches = found.size
        cachedCounts = RecordTable.counts(found)

        val rows = RecordTable.sort(found.filter { filter.matches(it.type) }, sortBy, sortDesc)
        cachedLines = rows.flatMap { row ->
            if (row.room == expanded) listOf(Line(row, null)) + detailLines(row) else listOf(Line(row, null))
        }
    }

    /**
     * What one room looks like when it is opened: the progression of its times, the best time per
     * floor, and what the database knows about the room itself.
     *
     * All of it comes out of `history.jsonl`, which has recorded every attempt with its floor and a
     * personal-best flag since the first version — the table simply never read them back.
     *
     * **The progression follows the same kind the row's clear column shows**, blood room first, which is
     * `RecordTable.rows`' own order. A blood room writes no `clear` line at all since 0.16.0, so reading
     * only `clear` here left the one room with a boss fight in it showing no progression while its
     * record sat in the column above.
     */
    private fun detailLines(row: RecordTable.Row): List<Line> {
        val blood = RoomHistory.attempts(row.room, RoomHistory.BLOOD)
        val label = if (blood.isNotEmpty()) "blood" else "clear"
        val attempts = blood.ifEmpty { RoomHistory.attempts(row.room, RoomHistory.CLEAR) }
        val out = mutableListOf<Line>()

        if (attempts.isNotEmpty()) {
            val ticks = attempts.map { it.ticks }.sorted()
            val median = ticks[ticks.size / 2]
            // Below MIN_SAMPLE there is no median worth the word: it is the upper middle element, so
            // on two attempts it is the slower of the two. The record itself is in the row above
            // either way — what the detail adds is the shape of the history, and on three runs there
            // is no shape yet, only three runs.
            val summary = when {
                attempts.size == 1 -> "1 attempt"
                attempts.size < MIN_SAMPLE -> "${attempts.size} attempts · no median yet"
                else -> "median ${Format.ticks(median)} · ${attempts.size} attempts"
            }
            out.add(
                Line(
                    row,
                    Detail(
                        label, summary, attempts.takeLast(Sparkline.MAX_POINTS), (median * 2).coerceAtLeast(1),
                        badge = attempts.last().pb,
                    ),
                ),
            )

            // "?" is a line written before the floor was known; it would sort as its own floor.
            val floors = attempts.filter { it.floor != "?" }
                .groupBy { it.floor }
                .map { (floor, runs) -> floor to runs.minOf { it.ticks } }
                .sortedBy { it.second }
                // Bounded and named: the accordion is one detail line, and a room that has been run
                // on six floors would otherwise push the line past the column it lives in.
                .take(FLOORS_SHOWN)
            if (floors.isNotEmpty()) {
                out.add(
                    Line(
                        row,
                        Detail("floors", floors.joinToString(" · ") { "${it.first} ${Format.ticks(it.second)}" }, null, 0),
                    ),
                )
            }
        }

        RoomDatabase.infoByName(row.room)?.let {
            out.add(Line(row, Detail("room", "${it.shape} · ${it.secrets} secrets · ${it.crypts} crypts", null, 0)))
        }
        // A room whose only history is a secret run has nothing above; an empty accordion reads as a
        // broken click rather than as an answer.
        if (out.isEmpty()) out.add(Line(row, Detail("", "only a secret run recorded for this room", null, 0)))
        return out
    }

    // --- Input ------------------------------------------------------------------------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()

        val target = placing
        if (target != null) {
            // Right click is the same cancel it has always been, and now Escape is too.
            if (event.button() != 0) {
                stopPlacing(keep = false)
                return true
            }
            if (onPlaced(target, mouseX, mouseY)) {
                // The grab offset is the whole of drag-and-drop feeling right: without it the element
                // jumps so its corner meets the cursor the moment you touch it, and every drag starts
                // by throwing the thing you were aiming at.
                dragging = true
                val origin = placingOrigin(target)
                grabX = mouseX - origin.x
                grabY = mouseY - origin.y
                return true
            }
            // Anywhere off it means done. There is nothing else on this screen to hit.
            stopPlacing(keep = true)
            return true
        }

        if (mouseX in frameLeft..(frameLeft + Nav.WIDTH)) {
            val entry = Nav.rowAt(bodyTop, Tab.entries.size, mouseY)
            if (entry >= 0) {
                selectTab(Tab.entries[entry])
                return true
            }
        }

        // The panel hit-tests against what it drew last frame; anything it did not take is vanilla's.
        if (tab == Tab.SOLO) return solo.click(mouseX, mouseY) || super.mouseClicked(event, doubleClick)

        if (tab == Tab.RECORDS) {
            if (mouseY in segmentsY until (segmentsY + Segmented.HEIGHT)) {
                // Resolved through the same call that drew the hover, so rule 3 holds without either
                // side keeping a copy of the segment width.
                val segment = Segmented.indexAt(VIEWS, segmentWidth(), contentLeft, mouseX)
                if (segment >= 0) {
                    selectView(View.entries[segment])
                    return true
                }
            }
            // The two personal-best views have a chip row and nothing else to press: no column to sort
            // by, nothing behind a row to open.
            if (view != View.ROOMS) {
                if (mouseY in chipsY until (chipsY + CHIP_H)) {
                    pbChipHits.firstOrNull { mouseX in it.second..it.third }?.let {
                        selectPbChip(it.first)
                        return true
                    }
                }
                return super.mouseClicked(event, doubleClick)
            }
            if (mouseY in chipsY until (chipsY + CHIP_H)) {
                chipHits.firstOrNull { mouseX in it.second..it.third }?.let {
                    filter = it.first
                    scroll = 0
                    return true
                }
            }
            if (mouseY in columnsY until (columnsY + Tokens.SPACE_12)) {
                tableLayout().at(mouseX)?.let {
                    // A second click on the same column reverses it; a different column starts in its
                    // own natural direction rather than inheriting the last one's. `last` and `runs`
                    // are the two where the interesting end is the large one — most recent, and most
                    // played — so they open descending and everything else opens with its fastest
                    // time first.
                    sortDesc = if (it.sort == sortBy) {
                        !sortDesc
                    } else {
                        it.sort == RecordTable.Sort.LAST || it.sort == RecordTable.Sort.RUNS
                    }
                    sortBy = it.sort
                    scroll = 0
                    return true
                }
            }
            // Clamped to the rows that were actually drawn, not to the band they were drawn in. The
            // list holds a whole number of rows and the leftover strip at the bottom — ten pixels at
            // 1080p and sixteen at 1366×768 — belonged to the *next* row's press zone: a press there
            // closed the room you could see and opened one you could not.
            if (mouseY >= firstRow && mouseY < firstRow + tableRows * Table.ROW) {
                val index = scroll + (mouseY - firstRow) / Table.ROW
                cachedLines.getOrNull(index)?.let { line ->
                    expanded = if (expanded == line.row.room) null else line.row.room
                    return true
                }
            }
            return super.mouseClicked(event, doubleClick)
        }

        return clickPage(event, mouseX, mouseY, doubleClick)
    }

    /**
     * A press on a settings page.
     *
     * The item is resolved first and the field's focus decided from it, because "anything that is not
     * the field commits and closes it" needs to know what was hit before it can know that — and a commit
     * that fired before the hit test would write the config on a click that landed on the field itself.
     */
    private fun clickPage(event: MouseButtonEvent, mouseX: Int, mouseY: Int, doubleClick: Boolean): Boolean {
        val items = pageItems()
        val index = if (mouseY in bodyTop until listBottom) {
            SettingsPage.itemAt(items, mouseY - bodyTop + scroll)
        } else {
            -1
        }
        val item = items.getOrNull(index)

        if (item == null || mouseX !in rowLeft..lastX) {
            // A press on nothing still ends the field — clicking away is the commit gesture, and it
            // has to work on the page's empty margin as well as on another row.
            commitField()
            return super.mouseClicked(event, doubleClick)
        }
        // Anything that is not a field commits and closes the one that was open. A field press does
        // not: its own click handler decides whether it is a refocus or a move to a different field.
        if (item.kind != SettingsPage.Kind.FIELD) commitField()

        when (item.kind) {
            SettingsPage.Kind.FIELD -> {
                item.click?.invoke()
                return true
            }

            SettingsPage.Kind.STEPPER -> {
                val stepperWidth = Stepper.width(item.value) { w(it, Stepper.SIZE, Type.MEDIUM) }
                val arm = Stepper.armAt(lastX - stepperWidth, stepperWidth, mouseX)
                // `0` is the value between the arms, which is deliberately not a target — see
                // Stepper.armAt. Shift no longer reverses anything here because the minus arm is what
                // shift was standing in for.
                if (arm != 0) {
                    item.step?.invoke(arm < 0)
                    Config.save()
                }
                return true
            }

            // No save here, and that is the point: the file is written on release, the same decision
            // `mouseReleased` makes about the placement drag. A sweep across the track is a hundred
            // frames, and a hundred writes of a config nobody has finished choosing.
            SettingsPage.Kind.SLIDER -> {
                if (mouseX >= sliderX) {
                    // The *index* is remembered, not the kind. Finding the slider again by kind works
                    // exactly as long as there is one of them, and the second one somebody adds would
                    // silently drag the first.
                    sliderHeld = index
                    item.slide?.invoke(Slider.fractionAt(sliderX, SLIDER_WIDTH, mouseX))
                }
                return true
            }

            SettingsPage.Kind.TOGGLE, SettingsPage.Kind.ACTION -> {
                item.click?.invoke()
                Config.save()
                return true
            }

            // A section heading, a note or a fact: nothing to do, and nothing behind this screen for the
            // press to reach either.
            else -> return super.mouseClicked(event, doubleClick)
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val target = placing
        if (dragging && target != null) {
            dragTo(target, event.x().toInt(), event.y().toInt())
            return true
        }
        if (sliderHeld >= 0) {
            // The item is looked up again rather than held from the press: items are rebuilt every
            // frame because they close over live config, so a kept reference would be a lambda writing
            // a value that was read before the drag started. The index is what identifies it.
            pageItems().getOrNull(sliderHeld)
                ?.takeIf { it.kind == SettingsPage.Kind.SLIDER }
                ?.slide?.invoke(Slider.fractionAt(sliderX, SLIDER_WIDTH, event.x().toInt()))
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    /**
     * Dropping it — the card, or the slider knob. Saved here rather than on every frame of the drag:
     * [Config.save] writes the file, and a drag across the screen is a hundred writes of a config
     * nobody asked to have written yet.
     */
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (releaseSlider()) return true
        if (!dragging) return super.mouseReleased(event)
        dragging = false
        Config.save()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // In placement mode the wheel is the element's size.
        //
        // **The same gesture as moving it, without letting go.** Size and position are one question — a
        // thing is too big *there*, over the thing it covers — and answering it used to mean leaving the
        // editor, finding a number, and coming back to see what that did to the position. Here it
        // happens while the element is still under the hand, mid-drag included, which is the moment a
        // player finds out it does not fit where they are putting it.
        //
        // The page underneath keeps its scroll either way: scrolling a list nobody can see is not what
        // the wheel is for here, and it would be left somewhere else the moment the element was dropped.
        val target = placing
        if (target != null) {
            val steps = Math.round(scrollY).toInt()
            if (steps != 0) {
                val wasW = placedWidth(target)
                val wasH = placedHeight(target)
                target.slot.zoom(steps)
                if (dragging) regrab(target, mouseX.toInt(), mouseY.toInt(), wasW, wasH)
            }
            return true
        }
        if (tab == Tab.SOLO) {
            // Rows on its list, pixels on its detail — the panel knows which it is showing.
            solo.wheel(scrollY)
            return true
        }
        if (tab == Tab.RECORDS) {
            // Rows, not pixels: every table on this page holds a whole number of fixed-height rows, and
            // the history table has counted in them since it had a scrollbar at all.
            if (view != View.ROOMS) {
                scroll = Scroll.wheel(scroll, scrollY, 1, pbShown().size, tableRows)
                return true
            }
            build()
            scroll = Scroll.wheel(scroll, scrollY, 1, cachedLines.size, pageSize)
            return true
        }
        val items = pageItems()
        scroll = Scroll.wheel(scroll, scrollY, SettingsPage.ROW, SettingsPage.total(items), pageHeight)
        return true
    }

    /**
     * Backspace and escape. Escape empties the search before it closes the screen: a filtered table
     * is a state you leave, and closing the whole screen to get out of it loses your place.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        // The focused field owns the keyboard outright — see [fieldKeyPressed].
        if (fieldFocus != null) return fieldKeyPressed(event)
        // Escape out of placing puts the element back, rather than closing the screen with a position
        // the player was in the middle of changing their mind about.
        val target = placing
        if (target != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                stopPlacing(keep = false)
                return true
            }
            // Back to the default, which for the two chips is the crosshair and is otherwise unreachable
            // by hand: the offset that means "exactly centred" is zero, and landing a drag on zero takes
            // more patience than anybody has. Not written here — escape still undoes it, and clicking off
            // it is still what commits.
            //
            // The size goes back with it. One key for "as it shipped" is the only reading of this that
            // does not need explaining, and a reset that left an element at 240% would look like the
            // reset having failed.
            if (event.key() == GLFW.GLFW_KEY_R) {
                target.slot.reset()
                return true
            }
            nudge(event)?.let { (dx, dy) ->
                val origin = placingOrigin(target)
                moveTo(target, origin.x + dx, origin.y + dy)
                return true
            }
        }
        // Escape out of a run's detail is back to the list, the same one-step-at-a-time rule the search
        // follows below; on the list the panel declines and vanilla closes the screen.
        if (tab == Tab.SOLO && solo.key(event)) return true
        // A chip on a personal-best view is a state to leave first, the same one-step rule as below.
        if (tab == Tab.RECORDS && view != View.ROOMS && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (view == View.SPLITS && splitsFilter != PbTable.FloorFilter.ALL) {
                splitsFilter = PbTable.FloorFilter.ALL
                scroll = 0
                return true
            }
            if (view == View.RUNS && runsFilter != PbTable.PartyFilter.ALL) {
                runsFilter = PbTable.PartyFilter.ALL
                scroll = 0
                return true
            }
        }
        // The search and both narrowings are the rooms table's. On the other two views escape is
        // vanilla's again, which is what closes the screen — there is no state on them to leave first.
        if (tab == Tab.RECORDS && view == View.ROOMS) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE && query.isNotEmpty()) {
                query = query.dropLast(1)
                scroll = 0
                return true
            }
            // Escape undoes one narrowing at a time and only closes the screen once the table shows
            // everything again. Which one is [RecordTable.narrowing]'s call, so the footer cannot
            // promise a different key than this takes.
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                when (narrowing) {
                    RecordTable.Narrowing.SEARCH -> query = ""
                    RecordTable.Narrowing.CHIP -> filter = RecordTable.Filter.ALL
                    RecordTable.Narrowing.NONE -> return super.keyPressed(event)
                }
                scroll = 0
                return true
            }
        }
        return super.keyPressed(event)
    }

    /**
     * One key for the focused field. Always swallows, so a keystroke cannot fall through to something
     * else — the records search, or vanilla — while the field has focus.
     *
     * Enter is the commit and escape is the revert, which is the same pair the placement editor answers
     * to: clicking off something keeps it, escape puts it back. Everything unlisted is deliberately
     * inert — paste included, because a field that takes nine characters at most is not the field a
     * clipboard was filled for, and the character filter in [charTyped] would have to be re-argued
     * against arbitrary pasted text.
     */
    private fun fieldKeyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitField()
            GLFW.GLFW_KEY_ESCAPE -> revertField()
            GLFW.GLFW_KEY_BACKSPACE -> fieldEdit.backspace()
            GLFW.GLFW_KEY_DELETE -> fieldEdit.delete()
            GLFW.GLFW_KEY_LEFT -> fieldEdit.move(-1, event.hasShiftDown())
            GLFW.GLFW_KEY_RIGHT -> fieldEdit.move(1, event.hasShiftDown())
            GLFW.GLFW_KEY_HOME -> fieldEdit.home(event.hasShiftDown())
            GLFW.GLFW_KEY_END -> fieldEdit.end(event.hasShiftDown())
            GLFW.GLFW_KEY_A -> if (event.hasControlDown()) fieldEdit.selectAll()
        }
        return true
    }

    /** Type anywhere to filter. No input box: the query itself is the only thing worth showing. */
    override fun charTyped(event: CharacterEvent): Boolean {
        // The field first, and only the characters a duration is made of. Everything else is swallowed
        // while one is focused, for [fieldKeyPressed]'s reason — and the filter is what keeps the IME
        // and every exotic input path out of a value that has to parse.
        if (fieldFocus != null) {
            val typed = event.codepointAsString()
            if (typed.all { it in '0'..'9' || it == ':' || it == '.' }) fieldEdit.insert(typed)
            return true
        }
        // The solo tab's link field, while its popup is open. Chat characters only, like the search.
        if (tab == Tab.SOLO && event.isAllowedChatCharacter && solo.charTyped(event.codepointAsString())) return true
        if (tab != Tab.RECORDS || view != View.ROOMS || !event.isAllowedChatCharacter) {
            return super.charTyped(event)
        }
        query += event.codepointAsString()
        scroll = 0
        return true
    }

    // --- The expected-time fields -----------------------------------------------------------

    /**
     * One spelling of a field's identity, carried in [SettingsPage.Item.meta] so the draw can ask "is
     * this row the focused one" without holding an index into a list that is rebuilt every frame.
     */
    private fun fieldKey(floor: String, split: String): String = "$floor|$split"

    /** [fieldKey] for the field that has the keyboard, or the empty string. */
    private fun focusedFieldKey(): String = fieldFocus?.let { fieldKey(it.first, it.second) } ?: ""

    /**
     * Gives [split]'s field the keyboard, committing whichever field had it.
     *
     * Seeded selected, so typing replaces: the act this field exists for is retyping a whole time, not
     * editing the tenth of one, and a caret that has to be steered first would make nine fields nine
     * chores. It also spares v1 any caret hit-testing — a second click still just selects everything.
     */
    private fun focusField(floor: String, split: String) {
        if (fieldFocus == floor to split) {
            // A second click on the field that already has the keyboard re-selects what is in it —
            // and must not reseed from the store, which would throw away what was typed so far.
            fieldEdit.selectAll()
            return
        }
        commitField()
        fieldFocus = floor to split
        fieldEdit.set(SplitExpected.get(floor, split)?.let(Format::seconds) ?: "")
        fieldEdit.selectAll()
    }

    /**
     * Writes the focused field into [SplitExpected] and drops the focus. Safe to call with none.
     *
     * **Every way out of the field runs through here** — a click anywhere that is not it, enter, the
     * floor stepper, a tab or view switch, [removed] — because a blur path that skips it loses a typed
     * value silently, which on a screen with no colour for errors is a value that was never anywhere.
     * The one deliberate exception is escape, which is the *revert* gesture everywhere on this screen
     * (the placement editor documents it) and therefore drops the focus without writing.
     *
     * Blank means "no plan" and clears the key; a string [SplitExpected.parse] refuses reverts to the
     * stored value — the field snaps back, the same register as escape, because writing a guess at what
     * a typo meant would put a number in the estimate nobody chose.
     */
    private fun commitField() {
        val (floor, split) = fieldFocus ?: return
        fieldFocus = null
        val typed = fieldEdit.text.trim()
        if (typed.isEmpty()) {
            SplitExpected.remove(floor, split)
        } else {
            SplitExpected.parse(typed)?.let { SplitExpected.set(floor, split, it) }
        }
        Config.save()
    }

    /** Drops the focus without writing — what escape does. */
    private fun revertField() {
        fieldFocus = null
    }

    // --- Zones and helpers ------------------------------------------------------------------

    private fun selectTab(entry: Tab) {
        commitField()
        tab = entry
        scroll = 0
        // A run left open on the solo tab would be the first thing shown on the way back; a tab switch
        // is a fresh start there like the scroll is everywhere else.
        solo.backToList()
    }

    /**
     * Switches which record table is showing.
     *
     * The scroll goes back to the top, for [selectTab]'s reason and one more of its own: it counts rows,
     * and the three tables have nothing like the same length. An offset carried out of a three-hundred
     * row history into a twelve-row runs table lands past its end, and a table opened past its end is a
     * table that looks empty.
     */
    private fun selectView(entry: View) {
        commitField()
        view = entry
        scroll = 0
    }

    /**
     * Lets go of the slider, writing the value it was left at. Returns whether one was held.
     *
     * Called from [mouseReleased] and again from [removed], because the screen can go away with the
     * button still down — a keybind opening another screen, the game taking it — and a scrim the player
     * had already dragged to where they wanted it would then be the one thing on this screen that was
     * chosen and not kept.
     */
    private fun releaseSlider(): Boolean {
        if (sliderHeld < 0) return false
        sliderHeld = -1
        Config.save()
        return true
    }

    /**
     * Truncate [text] to [room] pixels at the size it will be drawn at.
     *
     * It used to be `font.plainSubstrByWidth`, which could binary-search a bitmap font's integer
     * widths. The size and face now have to be named at the call site, because with a proportional
     * face the answer depends on both — and a truncation measured at one size and drawn at another is
     * a string that either overflows its column or stops short of it.
     */
    private fun fit(
        text: String, room: Int,
        size: Float = rowText, family: String = Type.REGULAR,
    ): String = Sk.fit(text, room.coerceAtLeast(0).toFloat(), size, family)

    /** Owes this frame a tooltip. Anything a column cut off says so the same way. */
    private fun tooltip(text: String, mouseX: Int, mouseY: Int) = tooltip(listOf(text), mouseX, mouseY)

    private fun tooltip(lines: List<String>, mouseX: Int, mouseY: Int) {
        tooltipLines = lines
        tooltipX = mouseX
        tooltipY = mouseY
    }

    /**
     * What the header states about a personal-best table: how many records, over how many floors.
     *
     * The floor count is not decoration. Eleven records on one floor and eleven spread over six are
     * different situations, and the second is the one where the scrollbar beside them is the point.
     * Counted off the lines rather than asked of the stores, so the number cannot disagree with the
     * table under it — a heading is a line and a record is a line, and [PbTable.count] is which.
     */
    private fun pbSummary(): String {
        val lines = pbLines()
        val records = PbTable.count(lines)
        return "$records records · ${lines.size - records} floors"
    }

    private fun footer(): String = when {
        tab == Tab.SOLO -> solo.footer()
        // **The one place the settings pages say their explanations exist.** With the sentences off the
        // page there is nothing on it that says hovering a row does anything, and a marker on every row
        // that has one would put back the clutter taking the sentences off did away with. A hint line
        // already exists, and this is what it is for.
        tab != Tab.RECORDS -> "click to change · hover for the detail"
        // The two personal-best views come before the two narrowings, because they have neither: a
        // footer promising that escape clears a search would be promising it on a view with no search.
        view == View.SPLITS && splitsFilter != PbTable.FloorFilter.ALL -> "esc shows every floor"
        view == View.RUNS && runsFilter != PbTable.PartyFilter.ALL -> "esc shows every party"
        view == View.SPLITS -> "the best of every split, in the run's order"
        view == View.RUNS -> "hypixel's clock, and ours where there was none"
        query.isNotEmpty() -> "esc clears the search"
        filter != RecordTable.Filter.ALL -> "esc shows every room"
        else -> "type to search · click a room for its detail"
    }

    /** The switch says what would leave the machine, so it is legible before the click. */
    /**
     * The gate as a label, with the grade it corresponds to — the number alone does not say why 300.
     *
     * **[DungeonScore]'s reckoning, not the number on the sidebar.** Hypixel's live score is the score
     * earned so far and cannot reach 300 before the boss dies, so a gate on it never fires solo; the
     * projection is what crosses a threshold while the run is on, and Hypixel's own `Team Score:` may
     * raise it at the end. See [SoloClear.gateScore], and [LiveScore] for the measurement.
     *
     * Above 0 a score that cannot be worked out at all fails the gate, so this row also says what a run
     * costs when the rows never parse: nothing is announced. See [SoloClear.passes].
     */
    private fun scoreGate(): String = when (val gate = Config.soloClearMinScore) {
        0 -> "any solo clear"
        270 -> "270 · S"
        300 -> "300 · S+"
        else -> gate.toString()
    }

    /**
     * What the switch above actually does, spelled out before the click rather than after it — the same
     * correction [uploadName] is here for. The name is not optional on this route: the receiver refuses
     * a clear without one, because a personal best nobody can be told apart from another is not an
     * announcement.
     */
    private fun soloClears(): String = when {
        !Config.soloClears -> "off: nothing is sent and nothing is recorded"
        Config.soloClearMinScore <= 0 -> "${minecraft.user.name} and your clear time, every solo floor"
        // The gated case is a different measurement, not a filtered one: the time at which the run
        // reached the score, on the two floors where that is a result. Saying "from 300 up" would read
        // as the same message with a threshold on it.
        else -> "${minecraft.user.name}, F7 and M7, the moment the run reaches ${Config.soloClearMinScore}"
    }

    private fun uploadName(): String = when {
        !Config.uploadName -> "off: reports carry the id and nothing else"
        !Config.upload -> "${minecraft.user.name} · but reports are off"
        else -> "${minecraft.user.name} rides on every report"
    }

    private fun Int?.time() = this?.let(Format::ticks) ?: Format.MISSING

    /**
     * A right-aligned cell. [y] is the row's top, not the text's — the row is what the caller has.
     *
     * A value that exists is set in Medium and lands on `textPrimary`; a dash is Regular and tertiary.
     * Three signals for one distinction, which is what this palette has instead of a colour.
     */
    private fun right(text: String, rightX: Int, y: Int, present: Boolean) {
        val family = if (present) Type.MEDIUM else Type.REGULAR
        Sk.textRight(
            text, rightX.toFloat(), Sk.centreY(y.toFloat(), Table.ROW.toFloat(), rowText, family), rowText,
            if (present) Tokens.textPrimary else Tokens.textTertiary, family,
        )
    }

    /**
     * The screen going away for any reason, which is not only the ways this screen knows about.
     *
     * [HudRoot.editing] hides the live overlay, and a screen closed from under itself — by the game,
     * by a keybind that opens another one — would leave it hidden with nothing left running to turn
     * it back on. The position is kept: a drag that reached this point was released.
     *
     * The key field is committed here for the mirror of that reason: it is the one control on this
     * screen whose value is not written by the click that changes it, so leaving is its commit — and
     * leaving includes every way out that this screen never hears about.
     */
    override fun removed() {
        releaseSlider()
        commitField()
        HudRoot.editing = false
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val CHIP_H = 18

        /** How far an explanation is indented under the row it explains. */
        const val NOTE_INDENT = Tokens.SPACE_12

        /** A switch on a 20-pixel row, with air above and below it. */
        const val TOGGLE_HEIGHT = 16

        /** [TOGGLE_HEIGHT]'s reason: a field filling its whole 20-pixel row would touch the rules. */
        const val FIELD_HEIGHT = 16

        /**
         * The fewest attempts a median is quoted from, in the records accordion.
         *
         * Lived on the deleted `StatsOverview` (its stats page quoted the same medians) and moved here
         * with its reasoning intact: the "median" is the upper middle element, and below five samples
         * quoting it dignifies what is still just a couple of runs.
         */
        const val MIN_SAMPLE = 5

        /** How many floors an accordion's floors line names before it would outgrow its column. */
        const val FLOORS_SHOWN = 4

        /**
         * The fifteen floors in [PbTable.order]'s reading order — masters first, sevens first — which
         * is asked of that function rather than respelled, so the records page and this stepper cannot
         * come to walk the floors differently.
         */
        val FLOOR_TAGS: List<String> =
            ((1..7).flatMap { listOf("M$it", "F$it") } + "E").sortedBy(PbTable::order)

        /** The tag one step from [tag], wrapping at both ends like [StormTimer.step]. */
        fun List<String>.neighbour(tag: String, back: Boolean): String {
            val index = indexOf(tag).coerceAtLeast(0)
            return this[(index + (if (back) -1 else 1) + size) % size]
        }

        /**
         * The scrim slider's track.
         *
         * Fixed rather than a fraction of the content column: a sweep is a sweep at any width, and
         * widening it with the window would only spread the same stops further apart.
         *
         * It spanned thirteen whole percents when it was written and spans seventy-one now — the range
         * opened downwards to 30 %. A percent is therefore under two pixels of travel instead of ten,
         * which is survivable for the one control here that is a sweep rather than a correction, and is
         * survivable *because* the number is drawn beside the track: the hand finds the look and the
         * readout is what makes it repeatable.
         */
        const val SLIDER_WIDTH = 120

        const val SPARK_MIN = 40
        const val SPARK_MAX = 72

        /** The badge on a room whose last attempt was its best. Two characters used nowhere else. */
        const val PB = "PB"

        const val HISTORY_FILE = "config/sighteaddons/history.jsonl"

        /** Where the split records live: beside the settings, which is [SplitPbs]' whole argument. */
        const val CONFIG_FILE = "config/sighteaddons/config.json"

        const val RUNPBS_FILE = "config/sighteaddons/runpbs.jsonl"

        /**
         * The view chooser's labels, built once.
         *
         * The order is [View]'s, and the selected index is its `ordinal` — one list, so the thumb cannot
         * travel to a segment other than the one whose table is showing.
         */
        val VIEWS: List<String> = View.entries.map { it.label }

        /**
         * The personal-best tables' headers, already upper case.
         *
         * [Labels.draw] draws tracked capitals and says outright that callers uppercase their own
         * strings; these are drawn every frame, so the conversion happens here no times instead.
         */
        const val BEST_HEADER = "BEST"
        const val SPLIT_HEADER = "SPLIT"
        const val PARTY_HEADER = "PARTY"

        /**
         * The widest time a personal-best column ever holds.
         *
         * A whole run and not a room clear: a bad M7 passes ten minutes, so seven characters, and a
         * column measured against the six of `0:41.2` would put its slowest record inside its own header.
         */
        const val SAMPLE_RUN_TIME = "10:23.4"

        /**
         * The smallest rectangle the editor lets a hand aim at, in GUI pixels.
         *
         * The floor exists because the wheel can make an element genuinely tiny, and the thing that has
         * to stay possible is grabbing it again to undo that. Applied to the grab only and never to the
         * drawing: an outline bigger than the element would be the editor lying about where it is.
         */
        private const val GRAB_MIN = 12

        /**
         * Alpha of the scrim behind placement mode, out of 255.
         *
         * Low on purpose. It is there so the hint line has something to sit on, not to dim the game —
         * the game is the reference the card is being placed against, and anything that greys it out
         * makes this tool answer a question about a grey rectangle.
         */
        const val PLACING_SCRIM = 48

        /**
         * A shift-held nudge, in GUI pixels.
         *
         * Eight and not ten, because every space in this design system is a multiple of it and the
         * offsets worth landing on are too — the card's own default inset is four.
         */
        const val NUDGE_FAR = 8

        /**
         * The popup the placement editor drags: the gallery script's personal best.
         *
         * Borrowed rather than invented, on the argument [ClearPopup.detail] is a function for — a
         * second wording is a second thing to keep in step. The record one of the two on purpose: it
         * carries the chevron and the `PB` badge, which is the *widest* a popup gets, and an anchor
         * derived from the widest chip is one the narrow ones also fit behind.
         */
        val PLACING_POPUP = OverlayPreview.POPUPS.first { it.pb }

        /** Read from the jar rather than typed, so it cannot disagree with what is actually running. */
        private val VERSION: String = TelemetryUpload.modVersion().takeUnless { it == "unknown" } ?: ""
    }
}
