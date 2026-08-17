package sighteaddons

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
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
import sighteaddons.ui.components.ProgressBar
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
import sighteaddons.ui.screens.Frame
import sighteaddons.ui.screens.HudPreview
import sighteaddons.ui.screens.OverlayPreview
import sighteaddons.ui.screens.RecordColumns
import sighteaddons.ui.screens.Scroll
import sighteaddons.ui.screens.SettingsPage
import sighteaddons.ui.screens.StatsOverview
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens

/**
 * The `/sa` screen: the settings, the stats overview and the room history.
 *
 * Built on the design system in `ui/` and, since Phase 4/5, on the components in `ui/components/` —
 * `Nav` for the rail, `Table` for the header cells and the accordion's detail lines, `Tooltip` instead
 * of vanilla's purple-bordered box, `EmptyState`, `Badge`, `Stepper`, `TextField`, `Labels` for every
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
class SettingsScreen(private var tab: Tab = Tab.HUD) : Screen(Component.literal("Sighte Addons")) {

    /** [label] rather than the enum name: the rest of this screen is lower case throughout. */
    enum class Tab(val label: String) {
        HUD("hud"),
        CHAT("chat"),

        /**
         * What the history says about the player as a whole.
         *
         * Its own page and not a band above the table, and the reason is arithmetic rather than taste:
         * at GUI scale 4 a 1080p screen leaves the table 170 pixels, which is six rows. Anything
         * permanently above it takes rows from the only list on the screen, and anything that scrolls
         * with it is a summary you have to scroll back up to read. A page costs one rail entry, which
         * the rail has room for at every scale the game offers, and it sits directly above `rooms`
         * because that is what it is — the layer above the table.
         */
        STATS("stats"),
        RECORDS("rooms"),
        DEBUG("debug"),
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
     * The three things that can be placed on screen, and the only three.
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

    /** Where inside the element it was grabbed, so it does not jump to meet the cursor. */
    private var grabX = 0
    private var grabY = 0

    /** Where the element was when placement started, for the escape hatch. */
    private var placingWas: HudPlacement.Placement? = null

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

    /** The stats page, rebuilt when the history grows. Keyed on the line count for the same reason. */
    private var statsKey = -1
    private var cachedStats: List<SettingsPage.Item> = emptyList()

    /**
     * The tooltip owed to this frame, if any: a room name the column had to cut off.
     *
     * Deferred rather than drawn where it is discovered, because the row that discovers it is inside the
     * table's scissor and a tooltip clipped to the list it came from is a tooltip nobody can read. It is
     * cleared at the top of every frame, so a stale one cannot outlive the row that asked for it.
     */
    private var tooltipText: String? = null
    private var tooltipX = 0
    private var tooltipY = 0

    /**
     * The Hypixel key's edit state, seeded from the config and committed back on blur.
     *
     * [Config.hypixelKey] is not written on every keystroke: [Config.save] writes the file, and typing a
     * 36-character key would be 36 writes of a config nobody has finished editing. Committing on blur is
     * the same decision the placement drag makes about its own hundred frames.
     */
    private val keyEdit = TextField.Edit(Config.hypixelKey, KEY_MAX)
    private var keyFocused = false

    /**
     * Whether the key is currently legible.
     *
     * **An act and not a setting.** It is not in [Config], it is not written anywhere, and it ends the
     * moment focus does — which is what makes a masked field the default rather than a preference the
     * player has to know to re-set. See [Config.hypixelKey] for the objection this answers.
     */
    private var keyRevealed = false

    private val anim = Anim()
    private val previewHud = HudRoot()

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
    private val chipsY get() = bodyTop
    private val columnsY get() = bodyTop + Tokens.SPACE_24
    private val firstRow get() = columnsY + Tokens.SPACE_16
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

    /**
     * Where a stats or settings row's value column ends, with the sample note to its right.
     *
     * A fraction of [content] rather than a constant, for the same reason the table's columns are: at
     * GUI scale 4 the content column is 328 pixels and at scale 2 it is 460, and a figure laid out for
     * the wider one lands underneath its own note on the narrower.
     *
     * 55 rather than 60 because the notes are the longer half — `none in the last week` is 21
     * characters and every value on the page is at most eleven.
     */
    private val valueX get() = contentLeft + content * 55 / 100

    /** The key field's box. Derived, so [mouseClicked] hit-tests the rectangle it is drawn in. */
    private val fieldWidth get() = minOf(FIELD_MAX, content * 60 / 100)
    private val fieldX get() = lastX - fieldWidth

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
        header = { Labels.width(font, it.uppercase()) },
        value = { font.width(it) },
    )

    /** How many table rows fit, which is also how far a press on the list may land. */
    private val tableRows get() = ((listBottom - firstRow) / Table.ROW).coerceAtLeast(1)

    private val narrowing get() = RecordTable.narrowing(query, filter)

    // --- Rendering --------------------------------------------------------------------------

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val window = minecraft.window
        Density.beginFrame(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        Clock.frame(paused = false)
        // **Placing is the one mode that must not paint over the game.** The whole question it exists
        // to answer is where the card sits against a dungeon — the hotbar, the health bar, the boss
        // bar, whatever the player actually looks past it at — and a flat surface behind it answers
        // that question about a flat surface. A settings screen earns an opaque background; a
        // placement tool cannot have one.
        //
        // Not nothing, either: a scrim this light leaves the world plainly readable and still gives
        // the hint line something to sit on.
        if (placing != null) {
            graphics.fill(0, 0, width, height, Tokens.alpha(Tokens.shadow, PLACING_SCRIM))
            return
        }
        graphics.fill(0, 0, width, height, Tokens.surfaceBase)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        placing?.let {
            renderPlacing(graphics, it)
            return
        }
        tooltipText = null

        renderRail(graphics, mouseX, mouseY)
        renderHeader(graphics)

        if (tab == Tab.RECORDS) renderRecords(graphics, mouseX, mouseY) else renderPage(graphics, mouseX, mouseY)

        graphics.text(font, footer(), contentLeft, height - Frame.MARGIN - Tokens.SPACE_6, Tokens.textTertiary, false)

        // Last, and outside every scissor: a floating surface that is clipped to the list it describes
        // is not floating.
        tooltipText?.let { Tooltip.draw(graphics, font, tooltipX, tooltipY, width, height, listOf(it)) }
    }

    /** The nav rail. Each entry carries its own hover and its own selected indicator. */
    private fun renderRail(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val onRail = mouseX in frameLeft..(frameLeft + Nav.WIDTH)
        val over = if (onRail) Nav.rowAt(bodyTop, Tab.entries.size, mouseY) else -1
        for ((index, entry) in Tab.entries.withIndex()) {
            val y = Nav.rowY(bodyTop, index)
            val active = entry == tab
            val hover = Controls.hover(anim.of("rail.${entry.name}"), index == over && !active)
            val select = anim.of("railsel.${entry.name}", if (active) 1f else 0f)
            select.animateTo(if (active) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            Nav.item(graphics, font, frameLeft, y, Nav.WIDTH, Nav.ROW, entry.label, select.value, hover)
        }
        Nav.divider(
            graphics, frameLeft + Nav.WIDTH + Frame.GAP / 2, bodyTop - Tokens.SPACE_12,
            pageHeight + Tokens.SPACE_20,
        )
    }

    private fun renderHeader(graphics: GuiGraphicsExtractor) {
        Labels.draw(graphics, font, "SIGHTE ADDONS", frameLeft, headerY, Tokens.textPrimary)
        val right = if (tab == Tab.RECORDS) {
            build()
            if (query.isEmpty()) {
                "$cachedTotal rooms · ${RoomHistory.entryCount()} attempts"
            } else {
                "\"$query\"  $cachedMatches of $cachedTotal"
            }
        } else {
            VERSION
        }
        val tone = if (tab == Tab.RECORDS && query.isNotEmpty()) Tokens.textPrimary else Tokens.textTertiary
        graphics.text(font, right, lastX - font.width(right), headerY, tone, false)
        Table.divider(graphics, frameLeft, headerY + Tokens.SPACE_16, frameWidth)
    }

    // --- Pages: the three settings tabs and the stats overview -------------------------------

    /**
     * One scrolling page of [SettingsPage.Item]s.
     *
     * Every line is drawn by this one loop and hit-tested by [SettingsPage.itemAt] against the same
     * heights, which is rule 3 held by construction rather than by two people editing the same numbers.
     * Lines entirely outside the viewport are skipped rather than clipped — at GUI scale 4 the debug tab
     * is over twice the height of the space it has, and drawing the half of it nobody can see is a
     * couple of hundred draw calls per frame spent on nothing.
     */
    private fun renderPage(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val items = pageItems()
        if (items.isEmpty()) {
            renderEmpty(graphics, bodyTop, listBottom)
            return
        }

        val tops = SettingsPage.tops(items)
        val total = SettingsPage.total(items)
        scroll = Scroll.clamp(scroll, total, pageHeight)

        graphics.enableScissor(0, bodyTop, width, listBottom)
        for (index in items.indices) {
            val item = items[index]
            val y = bodyTop + tops[index] - scroll
            if (y + item.height <= bodyTop || y >= listBottom) continue
            drawItem(graphics, item, y, mouseX, mouseY)
        }
        graphics.disableScissor()

        Controls.scrollbar(graphics, lastX + Tokens.SPACE_6, bodyTop, listBottom, total, pageHeight, scroll)
    }

    private fun drawItem(graphics: GuiGraphicsExtractor, item: SettingsPage.Item, y: Int, mouseX: Int, mouseY: Int) {
        when (item.kind) {
            SettingsPage.Kind.SECTION -> Labels.sectionHeader(
                graphics, font, item.label.uppercase(), contentLeft, y + Tokens.SPACE_12, content,
                item.meta.takeIf { it.isNotEmpty() },
            )

            // An explanation, indented under the row it explains and truncated rather than wrapped: a
            // note that grows downward as the window narrows pushes every row under it out of the page.
            SettingsPage.Kind.NOTE -> graphics.text(
                font, font.plainSubstrByWidth(item.label, content - NOTE_INDENT),
                contentLeft + NOTE_INDENT, y + (SettingsPage.NOTE - Labels.CAP) / 2,
                Tokens.textTertiary, false,
            )

            SettingsPage.Kind.STAT -> drawStat(graphics, item, y, mouseX, mouseY)
            else -> drawControl(graphics, item, y, mouseX, mouseY)
        }
    }

    /**
     * One figure with the sample it rests on.
     *
     * Three columns and not two: the label says what it is, the value is the figure, and the note says
     * what the figure is made of — `median of 268` against `fastest of 3`. **The note is the state**, and
     * the tone only agrees with it: a thin figure is `textSecondary` rather than `textPrimary`, which is
     * 1.27:1 of separation and therefore not something anybody is asked to read on its own.
     */
    private fun drawStat(graphics: GuiGraphicsExtractor, item: SettingsPage.Item, y: Int, mouseX: Int, mouseY: Int) {
        val textY = y + (SettingsPage.ROW - Labels.CAP) / 2
        graphics.text(font, item.label, contentLeft, textY, Tokens.textSecondary, false)

        val room = valueX - contentLeft - font.width(item.label) - Tokens.SPACE_8
        val value = fit(item.value, room)
        graphics.text(
            font, value, valueX - font.width(value), textY,
            if (item.thin) Tokens.textSecondary else Tokens.textPrimary, false,
        )
        // `most recorded` is the one figure on this page that is a name rather than a number, so it is
        // the one that can be cut off — and a truncated room name with nothing behind it is the exact
        // hole the table fills with a tooltip. Same mechanism, same frame.
        if (value != item.value && mouseY in bodyTop until listBottom &&
            mouseX in rowLeft..lastX && mouseY in y until (y + SettingsPage.ROW)
        ) {
            tooltip(item.value, mouseX, mouseY)
        }

        if (item.meta.isNotEmpty()) {
            val meta = font.plainSubstrByWidth(item.meta, lastX - valueX - Tokens.SPACE_8)
            graphics.text(font, meta, lastX - font.width(meta), textY, Tokens.textTertiary, false)
        }
        // The bar sits under the figure it qualifies rather than beside it. A ratio is the only kind of
        // number a bar can carry honestly, and the number is already written above it — see ProgressBar.
        if (item.fraction >= 0f) {
            ProgressBar.draw(graphics, contentLeft, y + SettingsPage.ROW, content, ProgressBar.HEIGHT, item.fraction)
        }
    }

    private fun drawControl(graphics: GuiGraphicsExtractor, item: SettingsPage.Item, y: Int, mouseX: Int, mouseY: Int) {
        val rowHeight = item.height
        val inViewport = mouseY in bodyTop until listBottom
        val hovered = item.interactive && inViewport &&
            mouseX in rowLeft..lastX && mouseY in y until (y + rowHeight)
        val hover = Controls.hover(anim.of("row.${tab.name}.${item.label}"), hovered)

        if (item.interactive) {
            // The wash, the hover test above and the press test in [clickPage] all run [rowLeft] to
            // [lastX]. Stopping at [lastX] rather than eight pixels past it: the scrollbar lives out
            // there now, and a wash running under it makes the track look like part of the row.
            Controls.rowHighlight(graphics, rowLeft, y, lastX - rowLeft, rowHeight, hover, false)
        }

        val textY = y + (rowHeight - Labels.CAP) / 2
        val plain = item.kind == SettingsPage.Kind.INFO || item.kind == SettingsPage.Kind.FIELD
        graphics.text(
            font, item.label, contentLeft, textY,
            if (plain) Tokens.textSecondary else Tokens.textPrimary, false,
        )

        when (item.kind) {
            // A switch gets a switch. The word "on" was the old screen's only way to say it, and a
            // toggle says the same thing without asking anybody to read.
            SettingsPage.Kind.TOGGLE -> {
                val travel = anim.spring("toggle.${tab.name}.${item.label}", if (item.on) 1f else 0f)
                travel.springTo(if (item.on) 1f else 0f, Motion.BASE)
                Controls.toggle(
                    graphics, lastX - Controls.toggleWidth(TOGGLE_HEIGHT), y + (rowHeight - TOGGLE_HEIGHT) / 2,
                    TOGGLE_HEIGHT, travel.value, enabled = true,
                )
            }

            // Not a switch but still clickable: the position row. The value is the control, so it reads
            // as primary rather than as metadata.
            SettingsPage.Kind.ACTION -> graphics.text(
                font, item.value, lastX - font.width(item.value), textY,
                Controls.blend(Tokens.textSecondary, Tokens.textPrimary, hover), false,
            )

            SettingsPage.Kind.STEPPER -> {
                val stepperWidth = Stepper.width(font, item.value)
                val x = lastX - stepperWidth
                val arm = if (hovered) Stepper.armAt(x, stepperWidth, mouseX) else 0
                Stepper.draw(
                    graphics, font, x, y + (rowHeight - Stepper.HEIGHT) / 2, stepperWidth, Stepper.HEIGHT,
                    item.value, item.fraction.coerceIn(0f, 1f),
                    minusHover = Controls.hover(anim.of("minus.${item.label}"), arm < 0),
                    plusHover = Controls.hover(anim.of("plus.${item.label}"), arm > 0),
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
                graphics.text(
                    font, item.value, sliderX - Tokens.SPACE_8 - font.width(item.value), textY,
                    Controls.blend(Tokens.textSecondary, Tokens.textPrimary, hover), false,
                )
                Slider.draw(
                    graphics, sliderX, y + (rowHeight - Slider.HEIGHT) / 2, SLIDER_WIDTH, Slider.HEIGHT,
                    travel.value, hover = hover, active = held,
                )
            }

            SettingsPage.Kind.FIELD -> TextField.draw(
                graphics, font, fieldX, y + (rowHeight - TextField.HEIGHT) / 2, fieldWidth, TextField.HEIGHT,
                keyEdit,
                placeholder = "paste your key",
                mask = TextField.Mask.DOTS,
                revealed = keyRevealed,
                focus = Controls.hover(anim.of("keyfocus"), keyFocused),
                hover = hover,
                caret = TextField.caretOn(keyFocused),
            )

            // Plain information.
            else -> {
                val room = lastX - contentLeft - font.width(item.label) - Tokens.SPACE_8
                val value = font.plainSubstrByWidth(item.value, room.coerceAtLeast(0))
                graphics.text(font, value, lastX - font.width(value), textY, Tokens.textTertiary, false)
            }
        }
    }

    // --- Rooms page -------------------------------------------------------------------------

    private fun renderRecords(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        build()

        // Widths are derived before drawing rather than returned from it, so hover and hit testing use
        // the same number the chip is actually drawn at. Guessing a width for the hover test is how a
        // chip ends up highlighting from a cursor position that is not on it.
        //
        // **The counts come off when six chips carrying them do not fit.** At GUI scale 4 the content
        // column is 328 pixels and six chips with two-digit counts want about 356, so the last one was
        // being drawn past the right edge of the *screen* — the chip row has no scissor and nothing was
        // clipping it. A chip advertises the number of rows a click produces, which is worth having and
        // is not worth having a chip nobody can click; the counts are what goes.
        val withCounts = RecordTable.Filter.entries.sumOf {
            Controls.chipWidth(font, it.label, cachedCounts[it] ?: 0)
        } + (RecordTable.Filter.entries.size - 1) * Tokens.SPACE_6
        val showCounts = withCounts <= content

        chipHits.clear()
        var chipX = contentLeft
        // Scissored to the panel, and the press zones clipped with it. Without the counts six chips
        // are 278 pixels, which still does not fit the 168 the vanilla minimum GUI size leaves — and
        // the chip row has no list around it, so what did not fit was simply painted over the rest of
        // the screen. Clipped, what can be pressed is exactly what can be seen.
        graphics.enableScissor(contentLeft, chipsY, lastX, chipsY + CHIP_H)
        for (chip in RecordTable.Filter.entries) {
            val count = if (showCounts) cachedCounts[chip] ?: 0 else -1
            val chipWidth = Controls.chipWidth(font, chip.label, count)
            val active = anim.of("chip.${chip.name}", if (chip == filter) 1f else 0f)
            active.animateTo(if (chip == filter) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            val hovered = mouseX in chipX until minOf(chipX + chipWidth, lastX) &&
                mouseY in chipsY until (chipsY + CHIP_H)
            val hover = Controls.hover(anim.of("chiphover.${chip.name}"), hovered)
            Controls.chip(graphics, font, chipX, chipsY, CHIP_H, chip.label, count, active.value, hover)
            if (chipX < lastX) chipHits.add(Triple(chip, chipX, minOf(chipX + chipWidth, lastX)))
            chipX += chipWidth + Tokens.SPACE_6
        }
        graphics.disableScissor()

        // The chevron rotates between the two directions rather than swapping glyphs, so the reversal is
        // visibly the same control changing its mind. One animatable for the whole header row: there is
        // only ever one sorted column, so a second would be a direction nothing is pointing in.
        val flip = anim.of("sortdir", if (sortDesc) 1f else 0f)
        flip.animateTo(if (sortDesc) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
        val layout = tableLayout()
        // The hovered column is resolved once, through the same zones the press uses. Testing each
        // column against the cursor on its own would light two headers wherever their zones met, and
        // the whole point of a partition is that there is exactly one answer.
        val onHeader = if (mouseY in columnsY until (columnsY + Tokens.SPACE_12)) layout.at(mouseX) else null
        for (column in layout.columns) {
            Table.headerCell(
                graphics, font, column.label, column.x0, column.x1, columnsY, column.rightAligned,
                sorted = column.sort == sortBy,
                flip = flip.value,
                hover = Controls.hover(anim.of("col.${column.sort.name}"), column.sort == onHeader?.sort),
            )
        }
        Table.divider(graphics, contentLeft, columnsY + Tokens.SPACE_12, content)

        if (cachedLines.isEmpty()) {
            renderEmpty(graphics, firstRow, listBottom)
            return
        }

        val visible = tableRows
        pageSize = visible
        scroll = Scroll.clamp(scroll, cachedLines.size, visible)

        graphics.enableScissor(0, firstRow, width, listBottom)
        for ((index, line) in cachedLines.drop(scroll).take(visible).withIndex()) {
            val y = firstRow + index * Table.ROW
            if (line.detail != null) {
                renderDetail(graphics, line.detail, y)
            } else {
                renderRecord(graphics, layout, line.row, y, mouseX, mouseY)
            }
        }
        graphics.disableScissor()

        Controls.scrollbar(graphics, lastX + Tokens.SPACE_6, firstRow, listBottom, cachedLines.size, visible, scroll)
    }

    private fun renderRecord(
        graphics: GuiGraphicsExtractor,
        layout: RecordColumns.Layout,
        row: RecordTable.Row,
        y: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val open = row.room == expanded
        val hovered = mouseX in rowLeft..lastX && mouseY in y until (y + Table.ROW)
        val hover = Controls.hover(anim.of("rec.${row.room}"), hovered)
        Controls.rowHighlight(graphics, rowLeft, y, lastX - rowLeft, Table.ROW, hover, open)

        val textY = y + (Table.ROW - Labels.CAP) / 2
        val name = font.plainSubstrByWidth(row.room, layout.nameWidth)
        graphics.text(font, name, contentLeft, textY, if (open) Tokens.textPrimary else Tokens.textSecondary, false)
        // The full name only exists in a tooltip when the column actually cut it off.
        if (name != row.room && hovered) tooltip(row.room, mouseX, mouseY)
        if (layout.showType) graphics.text(font, row.typeLabel, layout.typeX, textY, Tokens.textTertiary, false)

        // A time that exists is primary; a dash is tertiary. Weight and luminance carry the
        // distinction, and the dash is the same width as a time so the column stays a column.
        right(graphics, row.clear.time(), layout.clearX, textY, row.clear != null)
        if (layout.secretsX >= 0) right(graphics, row.secrets.time(), layout.secretsX, textY, row.secrets != null)
        if (layout.runsX >= 0) right(graphics, row.runs.toString(), layout.runsX, textY, false)
        right(graphics, Format.ago(row.lastTs, System.currentTimeMillis()), lastX, textY, false)
    }

    /**
     * One line of an open room's detail, with the rule that ties it to the row above.
     *
     * Drawn at [Table.detail]'s full extent rather than animated open: the growth the component offers
     * is there so the detail reads as coming *out of* the row that was clicked, and a rule that grows
     * past a sparkline which is already fully drawn beside it reads as two elements arriving rather than
     * one. The connector is what earns the component its place here; the animation would need the chart
     * to fade with it, and `Sparkline` draws at one opacity.
     *
     * **The `PB` badge is here rather than on the row above, and that is a size decision.** The room
     * column is 63 pixels at 1080p and 52 at 1280×720 — see [RecordColumns] for where those come from
     * — and a badge is half of it, taken from every room that has one, in the column already most
     * likely to be truncated. The detail line is the whole content width and is where the progression
     * lives, which is what the badge is a statement about: not "this room has a record", since every
     * row shows one of those, but "the last run here was it" — [RoomHistory.Attempt.pb] read straight
     * out of the file rather than a comparison this screen would have to invent.
     */
    private fun renderDetail(graphics: GuiGraphicsExtractor, detail: Detail, y: Int) {
        val badgeRoom = if (detail.badge) Badge.width(font, PB) + Tokens.SPACE_6 else 0
        val plain = Table.contentX(contentLeft)
        val available = lastX - plain - badgeRoom

        // **The chart takes what the sentence does not want, and never the other way round.** The
        // sparkline used to be a fixed fraction of the content column, which at `guiScaledWidth` 456
        // left the summary one pixel short and truncated `268 attempts` to `268 att` — cutting off
        // exactly the sample size that makes the median beside it readable. Below [SPARK_MIN] there is
        // no chart at all: a twenty-attempt line squeezed into thirty pixels is not a trend, and the
        // number it was standing next to is.
        val wanted = available - font.width(detail.text) - Tokens.SPACE_12
        val spark = if (detail.spark != null && wanted >= SPARK_MIN) minOf(wanted, SPARK_MAX) else 0

        Table.detail(
            graphics, font, contentLeft, y, Table.ROW, detail.label,
            if (spark == 0) fit(detail.text, available) else "",
        )
        if (detail.badge) {
            Badge.draw(
                graphics, font, lastX - Badge.width(font, PB), y + (Table.ROW - Badge.HEIGHT) / 2, PB,
            )
        }
        if (spark == 0) return
        Sparkline.draw(graphics, plain, y + 2, spark, Table.ROW - 4, detail.spark!!, detail.cap)
        val textX = plain + spark + Tokens.SPACE_12
        graphics.text(
            font, fit(detail.text, lastX - badgeRoom - textX), textX, y + (Table.ROW - Labels.CAP) / 2,
            Tokens.textSecondary, false,
        )
    }

    /**
     * The empty states.
     *
     * Same key and same words as the footer: the hint has to name which of the two narrowings escape
     * takes off, not say "the search" while a chip is what is hiding every room. The stats page is
     * always the third case — it has no filter of its own, and a chip left active on the table must not
     * make it claim one.
     */
    private fun renderEmpty(graphics: GuiGraphicsExtractor, top: Int, bottom: Int) {
        val blank = tab == Tab.STATS || narrowing == RecordTable.Narrowing.NONE
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
        val y = top + ((bottom - top - EmptyState.height(note)) / 2).coerceAtLeast(0)
        EmptyState.draw(graphics, font, contentLeft, y, content, headline, hint, note)
    }

    /**
     * Placement mode: the element sits where it currently is and is dragged from there.
     *
     * **It shows the element at its own position rather than under the cursor, and that is the change
     * that makes this an editor.** Before, the preview followed the mouse and a click dropped its
     * top-left corner there — so the one thing a player wanted to see, where the HUD *is*, was the
     * one thing the mode never showed, and moving it three pixels meant re-aiming at nothing.
     *
     * One mode for all three, because they are one question asked about three rectangles. It draws the
     * real element in every case: the card with the live run when there is one — [HudSnapshot.current]
     * is what the overlay itself reads, so during a dungeon this is the actual HUD, at actual width,
     * with the actual room in it — and each chip through the same function the game draws it with. Where
     * there is no live data there is a scripted stand-in, the same one the gallery uses, so a preview
     * that drifts from the overlay is visible here too.
     */
    private fun renderPlacing(graphics: GuiGraphicsExtractor, target: Target) {
        val origin = placingOrigin(target)
        val h = placingHeight(target)
        // Drawn through the real files, at the position the real ones read, so this is the overlay and
        // not a picture of it. The card gets the live run when there is one; the two chips get a
        // scripted line, because a popup needs a room you just finished and a countdown needs Storm.
        when (target) {
            Target.CARD -> previewHud.draw(graphics, font, placingSnapshot(), origin.x, origin.y)
            Target.POPUP -> ClearPopup.drawAt(
                graphics, font, width, height,
                PLACING_POPUP.name, PLACING_POPUP.detail, PLACING_POPUP.pb, ClearPopup.PRESENT_MS,
            )
            Target.TIMER -> StormHud.draw(graphics, font, width, height, StormHud.sample())
        }

        // The numbers, beside the element and out of it, so a player who wants an exact position can
        // read one off while dragging rather than guessing at what they landed on.
        //
        // **The anchor is named alongside them, and it updates as the element is dragged.** What is
        // stored is an offset from an edge, and an offset is meaningless without the edge it counts
        // from — "8, 8" is the top left corner or the bottom right one depending on a fact the player
        // would otherwise have to infer. Watching the label change as the element crosses into the next
        // third is also the only way the anchoring is visible at all before a resolution change.
        val text = target.slot.label()
        // Under it, unless there is no room under it. A bottom-anchored element has its own bottom edge
        // against the screen's, and a label drawn below that one is a label nobody can read — which
        // would take the numbers away in exactly the corner where they are hardest to guess.
        val below = origin.y + h + Tokens.SPACE_6
        val labelY = if (below + HudRoot.TEXT_LINE <= height) below else origin.y - Tokens.SPACE_12
        graphics.text(
            font, text,
            origin.x.coerceAtMost(width - font.width(text)).coerceAtLeast(0), labelY.coerceAtLeast(0),
            if (dragging) Tokens.textPrimary else Tokens.textTertiary, false,
        )

        // Two lines, because one that says all of it does not fit: at GUI scale 4 on a 1366×768 window
        // the screen is 342 px wide, and the single line this replaced already measured about 350 before
        // there was anything new to say on it.
        graphics.text(
            font,
            if (dragging) "release to place it" else "drag the ${target.what} · arrows nudge · r resets",
            frameLeft, headerY, Tokens.textTertiary, false,
        )
        graphics.text(
            font, "click off it when done · esc cancels",
            frameLeft, headerY + HudRoot.TEXT_LINE + Tokens.SPACE_2, Tokens.textTertiary, false,
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
    }

    private fun placingHeight(target: Target): Int = when (target) {
        Target.CARD -> previewHud.measure(placingSnapshot())
        Target.POPUP -> ClearPopup.HEIGHT
        Target.TIMER -> StormHud.HEIGHT
    }

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
        return x >= origin.x && x < origin.x + placingWidth(target) &&
            y >= origin.y && y < origin.y + placingHeight(target)
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
        if (keep) Config.save() else was?.let { placing?.slot?.set(it) }
        placing = null
        placingWas = null
        dragging = false
        HudRoot.editing = false
    }

    // --- The pages, as data -----------------------------------------------------------------

    private fun pageItems(): List<SettingsPage.Item> = when (tab) {
        Tab.HUD -> hudItems()
        Tab.CHAT -> chatItems()
        Tab.STATS -> statsItems()
        Tab.DEBUG -> debugItems()
        Tab.RECORDS -> emptyList()
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
            note("under ${Tokens.SCRIM_CONTRAST_PERCENT} % the smallest grey text can drop below 4.5:1")
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
        note(
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
            note(
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
            note("138 and 20 are inherited and unverified")
            stepper(
                "countdown", Config.stormCountdownTicks, StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX,
            ) { back ->
                Config.stormCountdownTicks = StormTimer.step(
                    Config.stormCountdownTicks, StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX, back,
                )
            }
            stepper("shoot window", Config.stormShootTicks, StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX) { back ->
                Config.stormShootTicks = StormTimer.step(
                    Config.stormShootTicks, StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX, back,
                )
            }
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
        note(uploadName())
        info("your upload id", Config.installId)
        note("all the server knows about you hangs off this")

        section("hypixel key", if (Config.hypixelKey.isBlank()) "not set" else "set")
        field("your key")
        note("your own key · never logged, never uploaded")
        note(
            if (Config.hypixelKey.isBlank()) {
                "blank: SecretApi and the audit stay inert"
            } else {
                "the run summary waits for the true counts"
            },
        )

        section("data")
        info("rooms in the database", RoomDatabase.roomCount.toString())
        // Cores and not rooms, which the previous version's single "rooms in the database" row called
        // rooms while showing this. Both are worth having and only one of them is a room count.
        info("room cores", RoomDatabase.size.toString())
        info("lines in the history", RoomHistory.entryCount().toString())
    }

    /**
     * The stats overview as page items, rebuilt when the history grows.
     *
     * Keyed on the line count for the same reason [build] is: the aggregate reads every attempt of every
     * room, and none of it can change between two frames without a line having been appended.
     */
    private fun statsItems(): List<SettingsPage.Item> {
        val count = RoomHistory.entryCount()
        if (count == statsKey) return cachedStats
        statsKey = count
        val overview = StatsOverview.of(
            RoomHistory.records(), RoomHistory::attempts, RoomDatabase.roomCount, System.currentTimeMillis(),
        )
        cachedStats = buildList {
            for (part in overview.sections) {
                section(part.title, part.meta)
                for (line in part.lines) {
                    add(
                        SettingsPage.Item(
                            SettingsPage.Kind.STAT, line.label, line.value, line.meta,
                            thin = line.thin, fraction = line.fraction,
                        ),
                    )
                }
            }
        }
        return cachedStats
    }

    // --- Item builders ----------------------------------------------------------------------

    private fun MutableList<SettingsPage.Item>.section(title: String, meta: String = "") =
        add(SettingsPage.Item(SettingsPage.Kind.SECTION, title, meta = meta))

    private fun MutableList<SettingsPage.Item>.note(text: String) =
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

    private fun MutableList<SettingsPage.Item>.field(label: String) =
        add(SettingsPage.Item(SettingsPage.Kind.FIELD, label))

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
            // The same floor the overview holds itself to, for the same reason and stated there: this
            // "median" is the upper middle element, so on two attempts it is the slower of the two. The
            // record itself is in the row above either way — what the detail adds is the shape of the
            // history, and on three runs there is no shape yet, only three runs.
            val summary = when {
                attempts.size == 1 -> "1 attempt"
                attempts.size < StatsOverview.MIN_SAMPLE -> "${attempts.size} attempts · no median yet"
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
                // The same count the stats page shows, and named rather than repeated: one room's
                // floors and every room's floors are the same list at two scales.
                .take(StatsOverview.FLOORS_SHOWN)
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

        if (tab == Tab.RECORDS) {
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
     * A press on a settings or stats page.
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
        val onField = item != null && item.kind == SettingsPage.Kind.FIELD &&
            mouseX >= fieldX && mouseX < fieldX + fieldWidth
        if (!onField) blurKey(commit = true)

        if (item == null || mouseX !in rowLeft..lastX) {
            return super.mouseClicked(event, doubleClick)
        }

        when (item.kind) {
            SettingsPage.Kind.FIELD -> {
                if (onField) clickField(mouseX)
                return true
            }

            SettingsPage.Kind.STEPPER -> {
                val stepperWidth = Stepper.width(font, item.value)
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

    /**
     * A press inside the key field: the reveal affordance, or the caret.
     *
     * The reveal rectangle is re-derived from [TextField.revealX] rather than remembered from the draw,
     * which is rule 3 — and it matters here more than anywhere, because the affordance is laid out in
     * the width of the longer of its two words so that pressing it does not move it out from under the
     * cursor that pressed it.
     */
    private fun clickField(mouseX: Int) {
        // Focused first, and for both halves of the field. The reveal is documented as ending when the
        // focus does, and a reveal that could be taken without ever taking focus was a reveal whose
        // stated end condition never came round — it survived until something else on the screen
        // happened to blur a field that was not focused. No leak, and a promise that was not kept.
        keyFocused = true
        if (mouseX >= TextField.revealX(font, TextField.Mask.DOTS, fieldX, fieldWidth)) {
            keyRevealed = !keyRevealed
            return
        }
        val offset = TextField.offsetAt(fieldX, mouseX, keyEdit.scroll)
        keyEdit.placeCaret(TextField.indexAt(font, keyEdit.text, TextField.Mask.DOTS, keyRevealed, offset))
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
        // Placement mode draws none of this. Scrolling the page underneath it moves a list nobody can
        // see, and leaves it somewhere else when the element is dropped.
        if (placing != null) return true
        if (tab == Tab.RECORDS) {
            build()
            // Rows, not pixels: the table's rows are a fixed height and it has counted in them since it
            // had a scrollbar at all.
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
        // The focused field is a narrowing in exactly the sense rule 2 means: escape leaves the field
        // and the screen stays where it was.
        if (keyFocused && editKey(event)) return true
        if (tab == Tab.RECORDS) {
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
     * One key for the focused field. Returns whether it was ours.
     *
     * **Copy and cut are swallowed rather than handled**, which is the one deliberate omission. A masked
     * field that will hand its own contents to the clipboard is a masked field in appearance only, and
     * the mod has no reason to be a place credentials are read *out* of — the key arrives from
     * `config.json` or from a paste, and leaves only as a request header. Swallowing rather than
     * ignoring, so the keystroke does not fall through to something else while the field has focus.
     */
    private fun editKey(event: KeyEvent): Boolean {
        when {
            event.key() == GLFW.GLFW_KEY_ESCAPE ||
                event.key() == GLFW.GLFW_KEY_ENTER ||
                event.key() == GLFW.GLFW_KEY_KP_ENTER ||
                event.key() == GLFW.GLFW_KEY_TAB -> blurKey(commit = true)

            event.key() == GLFW.GLFW_KEY_BACKSPACE -> keyEdit.backspace()
            event.key() == GLFW.GLFW_KEY_DELETE -> keyEdit.delete()
            event.isLeft -> keyEdit.move(-1, event.hasShiftDown())
            event.isRight -> keyEdit.move(1, event.hasShiftDown())
            event.key() == GLFW.GLFW_KEY_HOME -> keyEdit.home(event.hasShiftDown())
            event.key() == GLFW.GLFW_KEY_END -> keyEdit.end(event.hasShiftDown())
            event.isSelectAll -> keyEdit.selectAll()
            event.isPaste -> keyEdit.insert(pasted())
            event.isCopy || event.isCut -> Unit
            else -> return false
        }
        return true
    }

    /**
     * The clipboard, as one line.
     *
     * A key copied out of a browser routinely arrives with a newline on it, and `Edit.insert` truncates
     * against its own maximum rather than refusing — so the newline would silently become the last
     * character of a key that is then wrong by exactly one character, which fails identically to a key
     * that was never entered.
     */
    private fun pasted(): String = minecraft.keyboardHandler.clipboard.filterNot { it.isWhitespace() }

    /** Type anywhere to filter. No input box: the query itself is the only thing worth showing. */
    override fun charTyped(event: CharacterEvent): Boolean {
        if (keyFocused) {
            if (event.isAllowedChatCharacter) keyEdit.insert(event.codepointAsString())
            return true
        }
        if (tab != Tab.RECORDS || !event.isAllowedChatCharacter) return super.charTyped(event)
        query += event.codepointAsString()
        scroll = 0
        return true
    }

    // --- Zones and helpers ------------------------------------------------------------------

    private fun selectTab(entry: Tab) {
        blurKey(commit = true)
        tab = entry
        scroll = 0
    }

    /**
     * Leaves the key field, writing it if it changed.
     *
     * The reveal ends with the focus, unconditionally: it is an act and not a setting, so there is no
     * state in which it should survive walking away from the field.
     */
    private fun blurKey(commit: Boolean) {
        keyRevealed = false
        keyFocused = false
        if (commit) commitKey()
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

    /** Writes the key only when it actually changed, so leaving a tab is not a file write. */
    private fun commitKey() {
        if (keyEdit.text == Config.hypixelKey) return
        Config.hypixelKey = keyEdit.text
        Config.save()
    }

    private fun fit(text: String, room: Int): String = font.plainSubstrByWidth(text, room.coerceAtLeast(0))

    /** Owes this frame a tooltip. Anything a column cut off says so the same way. */
    private fun tooltip(text: String, mouseX: Int, mouseY: Int) {
        tooltipText = text
        tooltipX = mouseX
        tooltipY = mouseY
    }

    private fun footer(): String = when {
        keyFocused -> "enter saves the key · esc leaves the field"
        tab == Tab.STATS -> "everything here comes from history.jsonl"
        tab != Tab.RECORDS -> "click a row to change it"
        query.isNotEmpty() -> "esc clears the search"
        filter != RecordTable.Filter.ALL -> "esc shows every room"
        else -> "type to search · click a room for its detail"
    }

    /** The switch says what would leave the machine, so it is legible before the click. */
    private fun uploadName(): String = when {
        !Config.uploadName -> "off: reports carry the id and nothing else"
        !Config.upload -> "${minecraft.user.name} · but reports are off"
        else -> "${minecraft.user.name} rides on every report"
    }

    private fun Int?.time() = this?.let(Format::ticks) ?: Format.MISSING

    private fun right(graphics: GuiGraphicsExtractor, text: String, rightX: Int, y: Int, present: Boolean) {
        graphics.text(
            font, text, rightX - font.width(text), y,
            if (present) Tokens.textPrimary else Tokens.textTertiary, false,
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
        blurKey(commit = true)
        releaseSlider()
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

        /** The key field's widest form. Beyond this it is a very long box for a 36-character string. */
        const val FIELD_MAX = 220

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

        /**
         * The longest key the field will hold.
         *
         * A Hypixel key is a 36-character UUID. The cap is generous rather than exact because a format
         * this file does not own is not a format this file should enforce — what it is for is the paste
         * of a whole web page into a field that would otherwise try to lay out a megabyte.
         */
        const val KEY_MAX = 128

        const val SPARK_MIN = 40
        const val SPARK_MAX = 72

        /** The badge on a room whose last attempt was its best. Two characters used nowhere else. */
        const val PB = "PB"

        const val HISTORY_FILE = "config/sighteaddons/history.jsonl"

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
