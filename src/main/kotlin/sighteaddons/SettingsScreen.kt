package sighteaddons

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import sighteaddons.ui.Format
import sighteaddons.ui.components.Anim
import sighteaddons.ui.components.Controls
import sighteaddons.ui.components.Sparkline
import sighteaddons.ui.hud.HudRoot
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.render.DevicePixels
import sighteaddons.ui.render.Surface
import sighteaddons.ui.screens.HudPreview
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens
import java.util.concurrent.TimeUnit

/**
 * The `/sa` screen: settings and the room history.
 *
 * Rebuilt on the design system in `ui/`. What changed is layout and drawing — a left nav rail instead
 * of top tabs, real controls instead of the word "on", a sparkline instead of a bar per attempt, and
 * motion on everything that changes state. What did not change is any of the behaviour underneath:
 * the rows, the search, the sort, the accordion and every config key are the same code they were.
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
 */
class SettingsScreen(private var tab: Tab = Tab.HUD) : Screen(Component.literal("Sighte Addons")) {

    /** [label] rather than the enum name: the rest of this screen is lower case throughout. */
    enum class Tab(val label: String) { HUD("hud"), CHAT("chat"), RECORDS("rooms"), DEBUG("debug") }

    /** [on] drives the control; null means the row carries a value or is plain information. */
    private class Row(val label: String, val value: String, val on: Boolean? = null, val click: (() -> Unit)? = null)

    /** A line under an expanded room: a label, its text, and the progression on the first one. */
    private class Detail(val label: String, val text: String, val spark: List<RoomHistory.Attempt>?, val cap: Int)

    /** One rendered line of the history: the room's own row, or one line of its expanded detail. */
    private class Line(val row: RecordTable.Row, val detail: Detail?)

    /** A column header, which is also its own sort button. */
    private class Column(
        val sort: RecordTable.Sort,
        val label: String,
        val x0: Int,
        val x1: Int,
        val rightAligned: Boolean,
    )

    private var placing = false
    private var scroll = 0

    /**
     * Whether shift was held for the click currently being dispatched — the storm tick rows step
     * backwards with it.
     *
     * A field rather than a parameter on [Row.click] because the modifier lives on the mouse event and
     * nothing else on this screen wants it: threading a `Boolean` through every row's lambda would be
     * a dozen ignored parameters to serve two rows.
     */
    private var stepBack = false

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

    private val anim = Anim()
    private val previewHud = HudRoot()

    /** Chip hit zones from the last frame, so a click lands on what was drawn. */
    private val chipHits = ArrayList<Triple<RecordTable.Filter, Int, Int>>()

    // --- Layout -----------------------------------------------------------------------------

    private val frameWidth get() = minOf(RAIL + GAP + CONTENT_MAX, width - MARGIN * 2)
    private val frameLeft get() = (width - frameWidth) / 2
    private val contentLeft get() = frameLeft + RAIL + GAP
    private val content get() = frameWidth - RAIL - GAP

    private val headerY get() = MARGIN + Tokens.SPACE_12
    private val bodyTop get() = headerY + Tokens.SPACE_32
    private val chipsY get() = bodyTop
    private val columnsY get() = bodyTop + Tokens.SPACE_24
    private val firstRow get() = columnsY + Tokens.SPACE_16
    private val listBottom get() = height - MARGIN - Tokens.SPACE_16

    /** Right edges of the record columns as fractions of [content], so nothing overflows at scale 4. */
    private val typeX get() = contentLeft + content * 40 / 100
    private val clearX get() = contentLeft + content * 62 / 100
    private val secretsX get() = contentLeft + content * 78 / 100
    private val runsX get() = contentLeft + content * 87 / 100
    private val lastX get() = contentLeft + content

    /** The `type` column is redundant once a type chip is active — it would repeat that one word. */
    private val showType get() = filter == RecordTable.Filter.ALL

    private val narrowing get() = RecordTable.narrowing(query, filter)

    // --- Rendering --------------------------------------------------------------------------

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val window = minecraft.window
        Density.beginFrame(window.width, window.height, window.guiScaledWidth, window.guiScaledHeight)
        Clock.frame(paused = false)
        graphics.fill(0, 0, width, height, Tokens.surfaceBase)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (placing) {
            renderPlacing(graphics, mouseX, mouseY)
            return
        }

        renderRail(graphics, mouseX, mouseY)
        renderHeader(graphics)

        if (tab == Tab.RECORDS) renderRecords(graphics, mouseX, mouseY) else renderSettings(graphics, mouseX, mouseY)

        graphics.text(font, footer(), contentLeft, height - MARGIN - Tokens.SPACE_6, Tokens.textTertiary, false)
    }

    /** The nav rail. Each entry carries its own hover and its own selected indicator. */
    private fun renderRail(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        for ((entry, y) in railZones()) {
            val active = entry == tab
            val hovered = mouseX in frameLeft..(frameLeft + RAIL) && mouseY in y until (y + RAIL_ROW)
            val hover = Controls.hover(anim.of("rail.${entry.name}"), hovered && !active)
            val select = anim.of("railsel.${entry.name}", if (active) 1f else 0f)
            select.animateTo(if (active) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)

            Controls.rowHighlight(graphics, frameLeft, y, RAIL, RAIL_ROW, hover, false)
            Controls.indicator(graphics, frameLeft, y, RAIL_ROW, select.value, Tokens.accent)
            graphics.text(
                font, entry.label, frameLeft + Tokens.SPACE_12, y + (RAIL_ROW - 8) / 2,
                Controls.blend(Tokens.textTertiary, Tokens.textPrimary, maxOf(select.value, hover)),
                false,
            )
        }
        DevicePixels.hairlineV(graphics, frameLeft + RAIL + GAP / 2, bodyTop - Tokens.SPACE_12, listBottom - bodyTop + Tokens.SPACE_20, Tokens.borderSubtle)
    }

    private fun renderHeader(graphics: GuiGraphicsExtractor) {
        graphics.label("SIGHTE ADDONS", frameLeft, headerY, Tokens.textPrimary)
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
        DevicePixels.hairlineH(graphics, frameLeft, headerY + Tokens.SPACE_16, frameWidth, Tokens.borderSubtle)
    }

    // --- Settings pages ---------------------------------------------------------------------

    private fun renderSettings(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val all = rows()
        val rowHeight = settingRowHeight(all.size)
        val toggleHeight = (rowHeight - Tokens.SPACE_6).coerceIn(8, Controls.TOGGLE_HEIGHT)

        for ((index, row) in all.withIndex()) {
            val y = bodyTop + index * rowHeight
            val interactive = row.click != null
            val hovered = interactive && mouseX in contentLeft..lastX && mouseY in y until (y + rowHeight)
            val hover = Controls.hover(anim.of("row.${tab.name}.$index"), hovered)

            if (interactive) {
                Controls.rowHighlight(graphics, contentLeft - Tokens.SPACE_8, y, content + Tokens.SPACE_16, rowHeight, hover, false)
            }

            val labelY = y + (rowHeight - 8) / 2
            graphics.text(
                font, row.label, contentLeft, labelY,
                if (interactive) Tokens.textPrimary else Tokens.textTertiary, false,
            )

            when {
                // A switch gets a switch. The word "on" was the old screen's only way to say it, and a
                // toggle says the same thing without asking anybody to read.
                row.on != null -> {
                    val travel = anim.spring("toggle.${tab.name}.$index", if (row.on) 1f else 0f)
                    travel.springTo(if (row.on) 1f else 0f, Motion.BASE)
                    Controls.toggle(
                        graphics,
                        lastX - Controls.toggleWidth(toggleHeight),
                        y + (rowHeight - toggleHeight) / 2,
                        toggleHeight, travel.value, enabled = true,
                    )
                }
                // Not a switch but still clickable: the position row and the two storm steppers. The
                // value is the control, so it reads as primary rather than as metadata.
                interactive -> graphics.text(
                    font, row.value, lastX - font.width(row.value), labelY,
                    Controls.blend(Tokens.textSecondary, Tokens.textPrimary, hover), false,
                )
                // Plain information.
                else -> graphics.text(font, row.value, lastX - font.width(row.value), labelY, Tokens.textTertiary, false)
            }
        }
    }

    // --- Rooms page -------------------------------------------------------------------------

    private fun renderRecords(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        build()

        // Widths are derived before drawing rather than returned from it, so hover and hit testing use
        // the same number the chip is actually drawn at. Guessing a width for the hover test is how a
        // chip ends up highlighting from a cursor position that is not on it.
        chipHits.clear()
        var chipX = contentLeft
        for (chip in RecordTable.Filter.entries) {
            val count = cachedCounts[chip] ?: 0
            val chipWidth = Controls.chipWidth(font, chip.label, count)
            val active = anim.of("chip.${chip.name}", if (chip == filter) 1f else 0f)
            active.animateTo(if (chip == filter) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
            val hovered = mouseX in chipX until (chipX + chipWidth) && mouseY in chipsY until (chipsY + CHIP_H)
            val hover = Controls.hover(anim.of("chiphover.${chip.name}"), hovered)
            Controls.chip(graphics, font, chipX, chipsY, CHIP_H, chip.label, count, active.value, hover)
            chipHits.add(Triple(chip, chipX, chipX + chipWidth))
            chipX += chipWidth + Tokens.SPACE_6
        }

        for (column in columns()) {
            val active = column.sort == sortBy
            val x = if (column.rightAligned) column.x1 - font.width(column.label) else column.x0
            graphics.text(
                font, column.label.uppercase(), x, columnsY,
                if (active) Tokens.textPrimary else Tokens.textTertiary, false,
            )
            if (active) {
                // The chevron rotates between the two directions rather than swapping glyphs, so the
                // reversal is visibly the same control changing its mind.
                val flip = anim.of("sortdir", if (sortDesc) 1f else 0f)
                flip.animateTo(if (sortDesc) 1f else 0f, Motion.FAST, Easing.STANDARD, Motion.Kind.OPACITY)
                caret(graphics, if (column.rightAligned) x - Tokens.SPACE_8 else column.x0 + font.width(column.label) + 3, columnsY, flip.value)
            }
        }
        DevicePixels.hairlineH(graphics, contentLeft, columnsY + Tokens.SPACE_12, content, Tokens.borderSubtle)

        if (cachedLines.isEmpty()) {
            renderEmpty(graphics)
            return
        }

        val visible = ((listBottom - firstRow) / TABLE_ROW).coerceAtLeast(1)
        pageSize = visible
        scroll = scroll.coerceIn(0, (cachedLines.size - visible).coerceAtLeast(0))

        graphics.enableScissor(0, firstRow, width, listBottom)
        for ((index, line) in cachedLines.drop(scroll).take(visible).withIndex()) {
            val y = firstRow + index * TABLE_ROW
            if (line.detail != null) renderDetail(graphics, line.detail, y) else renderRecord(graphics, line.row, y, mouseX, mouseY)
        }
        graphics.disableScissor()

        Controls.scrollbar(graphics, lastX + Tokens.SPACE_6, firstRow, listBottom, cachedLines.size, visible, scroll)
    }

    private fun renderRecord(
        graphics: GuiGraphicsExtractor,
        row: RecordTable.Row,
        y: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val open = row.room == expanded
        val hovered = mouseX in contentLeft..lastX && mouseY in y until (y + TABLE_ROW)
        val hover = Controls.hover(anim.of("rec.${row.room}"), hovered)
        Controls.rowHighlight(graphics, contentLeft - Tokens.SPACE_8, y, content + Tokens.SPACE_16, TABLE_ROW, hover, open)

        val textY = y + (TABLE_ROW - 8) / 2
        val name = font.plainSubstrByWidth(row.room, nameWidth())
        graphics.text(font, name, contentLeft, textY, if (open) Tokens.textPrimary else Tokens.textSecondary, false)
        // The full name only exists in a tooltip when the column actually cut it off.
        if (name != row.room && hovered) {
            graphics.setTooltipForNextFrame(font, Component.literal(row.room), mouseX, mouseY)
        }
        if (showType) graphics.text(font, row.typeLabel, typeX, textY, Tokens.textTertiary, false)

        // A time that exists is primary; a dash is tertiary. Weight and luminance carry the
        // distinction, and the dash is the same width as a time so the column stays a column.
        right(graphics, row.clear.time(), clearX, textY, row.clear != null)
        right(graphics, row.secrets.time(), secretsX, textY, row.secrets != null)
        right(graphics, row.runs.toString(), runsX, textY, false)
        right(graphics, ago(row.lastTs), lastX, textY, false)
    }

    private fun renderDetail(graphics: GuiGraphicsExtractor, detail: Detail, y: Int) {
        val labelX = contentLeft + DETAIL_INDENT
        val textY = y + (TABLE_ROW - 8) / 2
        graphics.text(font, detail.label, labelX, textY, Tokens.textTertiary, false)
        var textX = labelX + DETAIL_LABEL
        detail.spark?.let {
            Sparkline.draw(graphics, textX, y + 2, SPARK_W, TABLE_ROW - 4, it, detail.cap)
            textX += SPARK_W + Tokens.SPACE_12
        }
        graphics.text(font, detail.text, textX, textY, Tokens.textSecondary, false)
    }

    /**
     * The empty states, built from hairline geometry rather than from a sentence alone.
     *
     * Same key and same words as the footer: the hint has to name which of the two narrowings escape
     * takes off, not say "the search" while a chip is what is hiding every room.
     */
    private fun renderEmpty(graphics: GuiGraphicsExtractor) {
        val boxW = 64
        val boxH = 40
        val boxX = contentLeft + (content - boxW) / 2
        val boxY = firstRow + Tokens.SPACE_24

        Surface.roundedBorder(graphics, boxX, boxY, boxW, boxH, Tokens.RADIUS_MD, Tokens.borderDefault)
        // Three descending rules inside it: the shape of a table with nothing in it.
        for (i in 0..2) {
            DevicePixels.hairlineH(
                graphics, boxX + Tokens.SPACE_12, boxY + Tokens.SPACE_12 + i * Tokens.SPACE_8,
                boxW - Tokens.SPACE_24 - i * Tokens.SPACE_8, Tokens.borderDefault,
            )
        }

        val (headline, hint) = if (narrowing != RecordTable.Narrowing.NONE) {
            "nothing matches this filter" to if (narrowing == RecordTable.Narrowing.SEARCH) {
                "esc clears the search · click \"all\" for every room"
            } else {
                "esc shows every room"
            }
        } else {
            "no history yet" to "finish a dungeon room and it lands here"
        }

        centred(graphics, headline, boxY + boxH + Tokens.SPACE_16, Tokens.textSecondary)
        centred(graphics, hint, boxY + boxH + Tokens.SPACE_16 + Tokens.SPACE_12, Tokens.textTertiary)
        if (narrowing == RecordTable.Narrowing.NONE) {
            centred(graphics, "config/sighteaddons/history.jsonl", boxY + boxH + Tokens.SPACE_16 + Tokens.SPACE_24, Tokens.textTertiary)
        }
    }

    /**
     * Full-screen placement mode: the next left click puts the HUD where the cursor is.
     *
     * Draws the real card with scripted data rather than a five-line mock of it. The old preview was
     * five `flat` calls that happened to resemble the HUD; anything that drifted between them and the
     * actual overlay was invisible until you placed it and looked.
     */
    private fun renderPlacing(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        previewHud.draw(graphics, font, HudPreview.at(Clock.nowMs), mouseX, mouseY)
        graphics.text(
            font, "left click places it · right click cancels",
            frameLeft, headerY, Tokens.textTertiary, false,
        )
    }

    // --- Data (unchanged from the previous version) ------------------------------------------

    private fun rows(): List<Row> = when (tab) {
        Tab.HUD -> listOf(
            Row("show HUD", Config.hud.word(), Config.hud) { Config.hud = !Config.hud },
            Row("position", "${Config.hudX}, ${Config.hudY} · place") { placing = true },
            Row("current room", Config.showRoom.word(), Config.showRoom) { Config.showRoom = !Config.showRoom },
            // "your secrets", not "secrets": the action bar already counts the room's, and the whole
            // point of the line is that this one is about you.
            Row("your secrets", Config.showSecrets.word(), Config.showSecrets) {
                Config.showSecrets = !Config.showSecrets
            },
            // "idle & nav", because the two numbers are the point: standing in a finished room and
            // walking between rooms are different problems and one figure could not tell them apart.
            Row("idle & nav", Config.showIdle.word(), Config.showIdle) {
                Config.showIdle = !Config.showIdle
            },
            Row("standings", Config.showStandings.word(), Config.showStandings) {
                Config.showStandings = !Config.showStandings
            },
            Row("clear popup", Config.clearPopup.word(), Config.clearPopup) {
                Config.clearPopup = !Config.clearPopup
            },
            // On this tab and not the chat one because it is drawn on screen — the mirror of the
            // argument that puts "crit readout" over there.
            Row("storm timer", Config.stormTimer.word(), Config.stormTimer) {
                Config.stormTimer = !Config.stormTimer
            },
            // The two inherited numbers, steppable a tick at a time. They are rows and not constants
            // because nobody here knows where 138 and 20 came from and a wrong one never announces
            // itself — see StormTimer. Shown only while the timer is on: off, they are two rows of
            // arithmetic about something that is not going to be drawn.
        ) + if (!Config.stormTimer) emptyList() else listOf(
            Row("  storm countdown", StormTimer.ticksLabel(Config.stormCountdownTicks)) {
                Config.stormCountdownTicks = StormTimer.step(
                    Config.stormCountdownTicks, StormTimer.COUNTDOWN_MIN, StormTimer.COUNTDOWN_MAX, stepBack,
                )
            },
            Row("  storm shoot window", StormTimer.ticksLabel(Config.stormShootTicks)) {
                Config.stormShootTicks = StormTimer.step(
                    Config.stormShootTicks, StormTimer.SHOOT_MIN, StormTimer.SHOOT_MAX, stepBack,
                )
            },
        )
        Tab.CHAT -> listOf(
            Row("room messages", Config.roomMessages.word(), Config.roomMessages) {
                Config.roomMessages = !Config.roomMessages
            },
            Row("own PBs only", Config.ownPbsOnly.word(), Config.ownPbsOnly) {
                Config.ownPbsOnly = !Config.ownPbsOnly
            },
            Row("run summary", Config.runSummary.word(), Config.runSummary) { Config.runSummary = !Config.runSummary },
            // "crit readout", not "crit": the number the line exists for is the one per Blessing of
            // Power, and nothing about it is sent anywhere — see CritMeter.
            Row("crit readout", Config.critLine.word(), Config.critLine) { Config.critLine = !Config.critLine },
            Row("history is always written", "history.jsonl"),
        )
        Tab.DEBUG -> listOf(
            Row("JSONL telemetry", Config.debugLog.word(), Config.debugLog) { Config.debugLog = !Config.debugLog },
            Row("target", "config/sighteaddons/debug/"),
            Row("upload run reports", Config.upload.word(), Config.upload) { Config.upload = !Config.upload },
            // Off by default and the only switch here that makes data leave identifiable. For the
            // leaderboards: a row needs a name on it, and that is the player's to give.
            Row("send my name", uploadName(), Config.uploadName) {
                Config.uploadName = !Config.uploadName
                // Reports are written at run end and only sent at the next game start, so the switch
                // has to reach the ones already waiting — otherwise a run played minutes ago leaves
                // anonymously despite the consent given just now.
                RunReport.restampPending()
            },
            // Shown so it is the player's to give away rather than ours to hold: everything the
            // server knows about them hangs off this string and nothing else.
            Row("your upload id", Config.installId),
            Row("rooms in the database", RoomDatabase.size.toString()),
            Row("lines in the history", RoomHistory.entryCount().toString()),
        )
        Tab.RECORDS -> emptyList()
    }

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
     * What one room looks like when it is opened: the progression of its clear times, the best time
     * per floor, and what the database knows about the room itself.
     *
     * All of it comes out of `history.jsonl`, which has recorded every attempt with its floor and a
     * personal-best flag since the first version — the table simply never read them back.
     */
    private fun detailLines(row: RecordTable.Row): List<Line> {
        val attempts = RoomHistory.attempts(row.room, RoomHistory.CLEAR)
        val out = mutableListOf<Line>()

        if (attempts.isNotEmpty()) {
            val ticks = attempts.map { it.ticks }.sorted()
            val median = ticks[ticks.size / 2]
            val summary = "best ${Format.ticks(ticks.first())} · " +
                "median ${Format.ticks(median)} · ${attempts.size} attempts"
            out.add(
                Line(
                    row,
                    Detail("clear", summary, attempts.takeLast(Sparkline.MAX_POINTS), (median * 2).coerceAtLeast(1)),
                ),
            )

            // "?" is a line written before the floor was known; it would sort as its own floor.
            val floors = attempts.filter { it.floor != "?" }
                .groupBy { it.floor }
                .map { (floor, runs) -> floor to runs.minOf { it.ticks } }
                .sortedBy { it.second }
                .take(4)
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

        if (placing) {
            placing = false
            if (event.button() == 0) {
                // Clamped so the HUD can never be parked outside the visible screen.
                Config.hudX = mouseX.coerceIn(0, width - 8)
                Config.hudY = mouseY.coerceIn(0, height - 8)
                Config.save()
            }
            return true
        }

        for ((entry, y) in railZones()) {
            if (mouseX in frameLeft..(frameLeft + RAIL) && mouseY in y until (y + RAIL_ROW)) {
                tab = entry
                scroll = 0
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
                columns().firstOrNull { mouseX in it.x0 - Tokens.SPACE_4..it.x1 + Tokens.SPACE_4 }?.let {
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
            if (mouseY >= firstRow && mouseY < listBottom) {
                val index = scroll + (mouseY - firstRow) / TABLE_ROW
                cachedLines.getOrNull(index)?.let { line ->
                    expanded = if (expanded == line.row.room) null else line.row.room
                    return true
                }
            }
            return super.mouseClicked(event, doubleClick)
        }

        val rows = rows()
        val rowHeight = settingRowHeight(rows.size)
        for ((index, row) in rows.withIndex()) {
            val y = bodyTop + index * rowHeight
            if (row.click == null) continue
            if (mouseX in contentLeft..lastX && mouseY in y until (y + rowHeight)) {
                stepBack = event.hasShiftDown()
                row.click.invoke()
                stepBack = false
                Config.save()
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (tab != Tab.RECORDS) return false
        build()
        scroll = (scroll - scrollY.toInt() * 3).coerceIn(0, (cachedLines.size - pageSize).coerceAtLeast(0))
        return true
    }

    /**
     * Backspace and escape. Escape empties the search before it closes the screen: a filtered table
     * is a state you leave, and closing the whole screen to get out of it loses your place.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
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

    /** Type anywhere to filter. No input box: the query itself is the only thing worth showing. */
    override fun charTyped(event: CharacterEvent): Boolean {
        if (tab != Tab.RECORDS || !event.isAllowedChatCharacter) return super.charTyped(event)
        query += event.codepointAsString()
        scroll = 0
        return true
    }

    // --- Zones and helpers ------------------------------------------------------------------

    /**
     * How tall a settings row may be, given how many there are and how much screen there is.
     *
     * At GUI scale 4 a 1080p screen is 270 pixels tall. Ten rows at the comfortable 26 would run
     * three rows off the bottom, and a switch you cannot reach is not a switch — so the grid
     * compresses rather than overflowing. It never goes below [SETTING_ROW_MIN], because past that
     * the label and the control start colliding; a list that long would need scrolling instead, and
     * the longest tab here is ten rows.
     */
    private fun settingRowHeight(count: Int): Int {
        if (count <= 0) return SETTING_ROW
        val available = (listBottom - bodyTop) / count
        return available.coerceIn(SETTING_ROW_MIN, SETTING_ROW)
    }

    private fun railZones(): List<Pair<Tab, Int>> =
        Tab.entries.mapIndexed { index, entry -> entry to (bodyTop + index * RAIL_ROW) }

    private fun columns(): List<Column> = buildList {
        add(Column(RecordTable.Sort.ROOM, "room", contentLeft, contentLeft + nameWidth(), false))
        if (showType) add(Column(RecordTable.Sort.TYPE, "type", typeX, typeX + 40, false))
        add(Column(RecordTable.Sort.CLEAR, "clear", clearX - 40, clearX, true))
        add(Column(RecordTable.Sort.SECRETS, "secrets", secretsX - 44, secretsX, true))
        add(Column(RecordTable.Sort.RUNS, "runs", runsX - 28, runsX, true))
        add(Column(RecordTable.Sort.LAST, "last", lastX - 44, lastX, true))
    }

    private fun nameWidth(): Int = (if (showType) typeX else clearX - 44) - contentLeft - Tokens.SPACE_8

    private fun footer(): String = when {
        tab != Tab.RECORDS -> "click a row to change it"
        query.isNotEmpty() -> "esc clears the search"
        filter != RecordTable.Filter.ALL -> "esc shows every room"
        else -> "type to search · click a room for its detail"
    }

    /** The switch shows what would leave the machine, so it is legible before the click. */
    private fun uploadName(): String = when {
        !Config.uploadName -> "off"
        !Config.upload -> "${minecraft.user.name} · but reports are off"
        else -> minecraft.user.name
    }

    private fun Boolean.word() = if (this) "on" else "off"

    private fun Int?.time() = this?.let(Format::ticks) ?: Format.MISSING

    private fun ago(ts: Long): String {
        if (ts == 0L) return Format.MISSING
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ts)
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            else -> "${days}d ago"
        }
    }

    private fun right(graphics: GuiGraphicsExtractor, text: String, rightX: Int, y: Int, present: Boolean) {
        graphics.text(
            font, text, rightX - font.width(text), y,
            if (present) Tokens.textPrimary else Tokens.textTertiary, false,
        )
    }

    private fun centred(graphics: GuiGraphicsExtractor, text: String, y: Int, argb: Int) {
        graphics.text(font, text, contentLeft + (content - font.width(text)) / 2, y, argb, false)
    }

    /**
     * The sort direction, as a caret that rotates between up and down.
     *
     * [flip] is `0f` for ascending and `1f` for descending; halfway through it is flat, which is what
     * makes the reversal read as one control turning over rather than as two glyphs swapping.
     */
    private fun caret(graphics: GuiGraphicsExtractor, x: Int, y: Int, flip: Float) {
        val lean = (flip - 0.5f) * 2f
        for (step in 0..2) {
            val offset = Math.round((step - 1) * lean)
            val px = x + step * 2
            graphics.fill(px, y + 3 + offset, px + 2, y + 4 + offset, Tokens.accent)
        }
    }

    /** An 11px uppercase label with tracking, drawn glyph by glyph — the bitmap font has no spacing. */
    private fun GuiGraphicsExtractor.label(value: String, x: Int, y: Int, argb: Int) {
        var cursor = x.toFloat()
        for (i in value.indices) {
            val glyph = value.substring(i, i + 1)
            text(font, glyph, Math.round(cursor), y, argb, false)
            cursor += font.width(glyph) + Tokens.TRACKING_LABEL
        }
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val MARGIN = 20
        const val RAIL = 88
        const val GAP = Tokens.SPACE_24
        const val CONTENT_MAX = 460
        const val RAIL_ROW = 24
        const val SETTING_ROW = 26
        const val SETTING_ROW_MIN = 14
        const val TABLE_ROW = 20
        const val CHIP_H = 18
        const val DETAIL_INDENT = 16
        const val DETAIL_LABEL = 44
        const val SPARK_W = 72

        /** Read from the jar rather than typed, so it cannot disagree with what is actually running. */
        private val VERSION: String = TelemetryUpload.modVersion().takeUnless { it == "unknown" } ?: ""
    }
}
