package sighteaddons

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.concurrent.TimeUnit

/**
 * The `/sa` screen: settings and the room history.
 *
 * Drawn entirely from filled rectangles and font calls — no textures, no widget library, no
 * dependency. The style is deliberately flat: a dark wash over the world, hairline rules, one accent
 * colour, everything on a 14px row grid. Minecraft's font is a 9px bitmap and the GUI scale changes
 * under it, so the layout carries the identity, not the typography.
 *
 * Rows are rebuilt from [RecordTable] and reused for hit testing, which keeps the drawing and the
 * click targets from drifting apart — there is no widget tree that could disagree with what is on
 * screen. Nothing here is a glyph outside the bitmap font's own range: the sort arrows and the
 * progression bars are rectangles, because ▲ and ▁ come from the Unifont fallback and stand out
 * against everything around them.
 */
class SettingsScreen(private var tab: Tab = Tab.HUD) : Screen(Component.literal("Sighte Addons")) {
    /** [label] rather than the enum name: the rest of this screen is lower case throughout. */
    enum class Tab(val label: String) { HUD("hud"), CHAT("chat"), RECORDS("history"), DEBUG("debug") }

    /** [on] drives the value colour; null means the row is plain information. */
    private class Row(val label: String, val value: String, val on: Boolean? = null, val click: (() -> Unit)? = null)

    /** A line under an expanded room: a label, its text, and the progression bars on the first one. */
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

    private val content get() = minOf(CONTENT_MAX, width - 2 * GUTTER - 8)
    private val contentLeft get() = (width - content) / 2

    /** Right edges of the record columns as fractions of [content], so nothing overflows at scale 4. */
    private val typeX get() = contentLeft + content * 40 / 100
    private val clearX get() = contentLeft + content * 60 / 100
    private val secretsX get() = contentLeft + content * 76 / 100
    private val runsX get() = contentLeft + content * 86 / 100
    private val lastX get() = contentLeft + content

    private val tabsY get() = HEADER_Y + 20
    private val chipsY get() = HEADER_Y + 40
    private val columnsY get() = HEADER_Y + 58
    private val listTop get() = HEADER_Y + 40
    private val firstRow get() = columnsY + ROW
    private val listBottom get() = height - 24

    /** The `type` column is redundant once a type chip is active — it would repeat that one word. */
    private val showType get() = filter == RecordTable.Filter.ALL

    /** Replaces the vanilla blur and menu texture with the flat wash the style is built on. */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        graphics.fill(0, 0, width, height, BG)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (placing) {
            renderPlacing(graphics, mouseX, mouseY)
            return
        }

        val left = contentLeft
        graphics.flat("SIGHTE ADDONS", left, HEADER_Y, TEXT)
        if (tab == Tab.RECORDS) renderHeadline(graphics) else graphics.right(VERSION, left + content, HEADER_Y, DIM)
        graphics.rule(left, HEADER_Y + 13)

        for ((entry, x0, x1) in tabZones()) {
            val active = entry == tab
            graphics.flat(entry.label, x0, tabsY, if (active) ACCENT else DIM)
            if (active) graphics.fill(x0, tabsY + 11, x1, tabsY + 12, ACCENT)
        }
        graphics.rule(left, HEADER_Y + 33)

        if (tab == Tab.RECORDS) renderRecords(graphics, mouseX, mouseY) else renderRows(graphics, mouseX, mouseY)

        graphics.flat(footer(), left, height - 14, RULE_TEXT)
    }

    /** What escape is currently for. The footer names it, [keyPressed] does it, neither decides it. */
    private val narrowing get() = RecordTable.narrowing(query, filter)

    private fun footer() = when {
        tab != Tab.RECORDS -> "esc  close"
        narrowing == RecordTable.Narrowing.SEARCH -> "esc  clear the search · backspace  delete"
        narrowing == RecordTable.Narrowing.CHIP -> "esc  show every room · type to filter"
        else -> "esc  close · type to filter · click a row for detail"
    }

    /** Top right: the counts, or what the search is currently doing to them. */
    private fun renderHeadline(graphics: GuiGraphicsExtractor) {
        build()
        if (query.isEmpty()) {
            graphics.right("$cachedTotal rooms · ${RoomHistory.entryCount()} attempts", lastX, HEADER_Y, DIM)
        } else {
            graphics.right("\"$query\"  $cachedMatches of $cachedTotal", lastX, HEADER_Y, ACCENT)
        }
    }

    private fun renderRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val left = contentLeft
        for ((index, row) in rows().withIndex()) {
            val y = listTop + index * ROW
            if (row.click != null && hovering(mouseX, mouseY, y)) highlight(graphics, y)
            graphics.flat(row.label, left, y, if (row.click == null) DIM else TEXT)
            graphics.right(row.value, left + content, y, if (row.on == true) ACCENT else DIM)
        }
    }

    private fun renderRecords(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val left = contentLeft
        build()

        for ((chip, x0, x1) in chipZones()) {
            val active = chip == filter
            graphics.flat(chip.label, x0, chipsY, if (active) ACCENT else DIM)
            graphics.flat((cachedCounts[chip] ?: 0).toString(), x0 + font.width("${chip.label} "), chipsY, RULE_TEXT)
            if (active) graphics.fill(x0, chipsY + 11, x1, chipsY + 12, ACCENT)
        }
        graphics.rule(left, HEADER_Y + 54)

        for (column in columns()) {
            val active = column.sort == sortBy
            graphics.flat(column.label, column.x0, columnsY, if (active) ACCENT else DIM)
            if (active) {
                graphics.caret(if (column.rightAligned) column.x0 - 8 else column.x1 + 3, columnsY + 3, sortDesc)
            }
        }

        if (cachedLines.isEmpty()) {
            renderEmpty(graphics)
            return
        }

        val visible = ((listBottom - firstRow) / ROW).coerceAtLeast(1)
        pageSize = visible
        scroll = scroll.coerceIn(0, (cachedLines.size - visible).coerceAtLeast(0))

        graphics.enableScissor(0, firstRow, width, listBottom)
        for ((index, line) in cachedLines.drop(scroll).take(visible).withIndex()) {
            val y = firstRow + index * ROW
            if (line.detail != null) renderDetail(graphics, line.detail, y) else renderRecord(graphics, line.row, y, mouseX, mouseY)
        }
        graphics.disableScissor()

        renderScrollbar(graphics, visible)
    }

    private fun renderRecord(
        graphics: GuiGraphicsExtractor,
        row: RecordTable.Row,
        y: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val left = contentLeft
        val open = row.room == expanded
        if (hovering(mouseX, mouseY, y) || open) highlight(graphics, y, open)

        val name = font.plainSubstrByWidth(row.room, nameWidth())
        graphics.flat(name, left, y, if (open) ACCENT else TEXT)
        // The full name only exists in a tooltip when the column actually cut it off.
        if (name != row.room && hovering(mouseX, mouseY, y)) {
            graphics.setTooltipForNextFrame(font, Component.literal(row.room), mouseX, mouseY)
        }
        if (showType) graphics.flat(row.typeLabel, typeX, y, DIM)
        graphics.right(row.clear.time(), clearX, y, if (row.clear != null) ACCENT else RULE_TEXT)
        graphics.right(row.secrets.time(), secretsX, y, if (row.secrets != null) DIM else RULE_TEXT)
        graphics.right(row.runs.toString(), runsX, y, DIM)
        graphics.right(ago(row.lastTs), lastX, y, RULE_TEXT)
    }

    private fun renderDetail(graphics: GuiGraphicsExtractor, detail: Detail, y: Int) {
        val labelX = contentLeft + DETAIL_INDENT
        graphics.flat(detail.label, labelX, y, RULE_TEXT)
        var textX = labelX + DETAIL_LABEL
        detail.spark?.let {
            graphics.spark(textX, y + 1, it, detail.cap)
            textX += SPARK_MAX * SPARK_PITCH + 10
        }
        graphics.flat(detail.text, textX, y, DIM)
    }

    private fun renderEmpty(graphics: GuiGraphicsExtractor) {
        val left = contentLeft
        val y = firstRow + ROW
        // Same key, same words as the footer: the hint says which of the two narrowings escape takes
        // off, not "the search" while a chip is what is actually hiding every room.
        if (narrowing != RecordTable.Narrowing.NONE) {
            graphics.flat("nothing matches this filter", left, y, DIM)
            val hint = if (narrowing == RecordTable.Narrowing.SEARCH) {
                "esc clears the search · click \"all\" for every room"
            } else {
                "esc shows every room"
            }
            graphics.flat(hint, left, y + ROW, RULE_TEXT)
        } else {
            graphics.flat("no history yet", left, y, DIM)
            graphics.flat("finish a dungeon room and it lands here", left, y + ROW, RULE_TEXT)
            graphics.flat("config/sighteaddons/history.jsonl", left, y + ROW * 2, RULE_TEXT)
        }
    }

    /** Position as an accent thumb on a hairline track — a textured bar would break the flat style. */
    private fun renderScrollbar(graphics: GuiGraphicsExtractor, visible: Int) {
        if (cachedLines.size <= visible) return
        val x = contentLeft + content + GUTTER
        val track = listBottom - firstRow
        graphics.fill(x, firstRow, x + 2, listBottom, RULE)
        val bar = (track * visible / cachedLines.size).coerceAtLeast(8)
        val offset = (track - bar) * scroll / (cachedLines.size - visible)
        graphics.fill(x, firstRow + offset, x + 2, firstRow + offset + bar, ACCENT)
    }

    /** Full-screen placement mode: the next left click puts the HUD where the cursor is. */
    private fun renderPlacing(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.flat("Sighte F7 3:12.5  23 rooms", mouseX, mouseY, TEXT)
        graphics.flat("Water Board  0:41.2", mouseX, mouseY + 10, ACCENT)
        graphics.flat("  cleared  02:58.6", mouseX, mouseY + 20, DIM)
        graphics.flat("left click places it · right click cancels", contentLeft, HEADER_Y, DIM)
    }

    private fun rows(): List<Row> = when (tab) {
        Tab.HUD -> listOf(
            Row("show HUD", Config.hud.word(), Config.hud) { Config.hud = !Config.hud },
            Row("position", "${Config.hudX}, ${Config.hudY} · place") { placing = true },
            Row("current room", Config.showRoom.word(), Config.showRoom) { Config.showRoom = !Config.showRoom },
            Row("standings", Config.showStandings.word(), Config.showStandings) {
                Config.showStandings = !Config.showStandings
            },
            Row("clear popup", Config.clearPopup.word(), Config.clearPopup) {
                Config.clearPopup = !Config.clearPopup
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
            val summary = "best ${DungeonGrid.formatTicks(ticks.first())} · " +
                "median ${DungeonGrid.formatTicks(median)} · ${attempts.size} attempts"
            out.add(Line(row, Detail("clear", summary, attempts.takeLast(SPARK_MAX), (median * 2).coerceAtLeast(1))))

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
                        Detail(
                            "floors",
                            floors.joinToString(" · ") { "${it.first} ${DungeonGrid.formatTicks(it.second)}" },
                            null,
                            0,
                        ),
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

        if (mouseY in tabsY until tabsY + 12) {
            tabZones().firstOrNull { mouseX in it.second..it.third }?.let {
                tab = it.first
                scroll = 0
            }
            return true
        }

        if (tab == Tab.RECORDS) return recordsClicked(mouseX, mouseY) || super.mouseClicked(event, doubleClick)

        val rows = rows()
        // Same origin the hover band uses, or a click near a row's bottom edge would toggle the row
        // above the one that is highlighted.
        val bands = listTop - 3
        val index = if (mouseY >= bands) (mouseY - bands) / ROW else -1
        if (mouseX in contentLeft - GUTTER..contentLeft + content + GUTTER && index in rows.indices) {
            val row = rows[index]
            if (row.click != null) {
                row.click.invoke()
                Config.save()
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    /**
     * Chips, column headers and rows. Returns whether the click landed on one — unlike before, a
     * click outside the table falls through instead of being swallowed by the tab.
     */
    private fun recordsClicked(mouseX: Int, mouseY: Int): Boolean {
        if (mouseX !in contentLeft - GUTTER..contentLeft + content + GUTTER) return false
        build()

        if (mouseY in chipsY until chipsY + 12) {
            chipZones().firstOrNull { mouseX in it.second..it.third }?.let {
                filter = it.first
                scroll = 0
            }
            return true
        }

        if (mouseY in columnsY - 3 until columnsY + ROW - 3) {
            columns().firstOrNull { mouseX in it.x0 - 4..it.x1 + 4 }?.let {
                // Same column twice reverses it; a new column starts in its own natural direction.
                sortDesc = if (it.sort == sortBy) !sortDesc else it.sort == RecordTable.Sort.LAST ||
                    it.sort == RecordTable.Sort.RUNS
                sortBy = it.sort
                scroll = 0
            }
            return true
        }

        val bands = firstRow - 3
        if (mouseY < bands || mouseY >= listBottom) return false
        val line = cachedLines.getOrNull((mouseY - bands) / ROW + scroll) ?: return false
        // Clicking anywhere in the open block closes it again, detail lines included.
        expanded = if (line.row.room == expanded) null else line.row.room
        return true
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

    /** Tab label hit zones, so drawing and clicking share one layout. */
    private fun tabZones(): List<Triple<Tab, Int, Int>> {
        var x = contentLeft
        return Tab.entries.map { entry ->
            val label = font.width(entry.label)
            Triple(entry, x, x + label).also { x += label + 24 }
        }
    }

    /** The same for the type chips, counts included — they are part of the click target. */
    private fun chipZones(): List<Triple<RecordTable.Filter, Int, Int>> {
        var x = contentLeft
        return RecordTable.Filter.entries.map { chip ->
            val label = font.width("${chip.label} ${cachedCounts[chip] ?: 0}")
            Triple(chip, x, x + label).also { x += label + 14 }
        }
    }

    /** Column headers, which are also the sort buttons. `type` only exists while it carries values. */
    private fun columns(): List<Column> {
        val left = contentLeft
        val out = mutableListOf(Column(RecordTable.Sort.ROOM, "room", left, left + font.width("room"), false))
        if (showType) out.add(Column(RecordTable.Sort.TYPE, "type", typeX, typeX + font.width("type"), false))
        for ((sort, label, x) in listOf(
            Triple(RecordTable.Sort.CLEAR, "clear", clearX),
            Triple(RecordTable.Sort.SECRETS, "secrets", secretsX),
            Triple(RecordTable.Sort.RUNS, "runs", runsX),
            Triple(RecordTable.Sort.LAST, "last", lastX),
        )) {
            out.add(Column(sort, label, x - font.width(label), x, true))
        }
        return out
    }

    /** Room names stop before the next column rather than running under it. */
    private fun nameWidth() = (if (showType) typeX else clearX - font.width("0:00.0") - 12) - contentLeft - 8

    /**
     * The name itself rather than the word "on": this row is the whole disclosure, so what would
     * leave the machine has to be legible before the click and not only after it.
     *
     * A name switched on while reports are switched off is sent nowhere, and a row that read like it
     * was working would be the reason somebody wonders why no leaderboard ever knows them.
     */
    private fun uploadName(): String {
        if (!Config.uploadName) return "off"
        val name = Minecraft.getInstance().player?.name?.string ?: "on"
        return if (Config.upload) name else "$name · upload is off"
    }

    private fun hovering(mouseX: Int, mouseY: Int, rowY: Int) =
        mouseY in rowY - 3 until rowY + ROW - 3 &&
            mouseX in contentLeft - GUTTER..contentLeft + content + GUTTER

    /** A band plus an accent tick in the gutter — quieter than a bright band across the full width. */
    private fun highlight(graphics: GuiGraphicsExtractor, y: Int, accent: Boolean = false) {
        graphics.fill(contentLeft - GUTTER, y - 3, contentLeft + content + GUTTER, y + ROW - 3, HOVER)
        if (accent) graphics.fill(contentLeft - GUTTER, y - 3, contentLeft - GUTTER + 2, y + ROW - 3, ACCENT)
    }

    private fun ago(ts: Long): String {
        if (ts == 0L) return MISSING
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ts)
        return when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            else -> "${days}d ago"
        }
    }

    private fun Int?.time() = this?.let(DungeonGrid::formatTicks) ?: MISSING

    private fun Boolean.word() = if (this) "on" else "off"

    private fun GuiGraphicsExtractor.flat(str: String, x: Int, y: Int, color: Int) =
        text(font, str, x, y, color, false)

    private fun GuiGraphicsExtractor.right(str: String, rightX: Int, y: Int, color: Int) =
        text(font, str, rightX - font.width(str), y, color, false)

    private fun GuiGraphicsExtractor.rule(left: Int, y: Int) =
        fill(left - GUTTER, y, left + content + GUTTER, y + 1, RULE)

    /** Sort direction as a 5x3 triangle of rectangles. */
    private fun GuiGraphicsExtractor.caret(x: Int, y: Int, down: Boolean) {
        for (step in 0..2) {
            val row = if (down) y + step else y + 2 - step
            fill(x + step, row, x + 5 - step, row + 1, ACCENT)
        }
    }

    /**
     * One bar per attempt, oldest on the left, height proportional to the time — so a room you are
     * getting faster at slopes downwards. Personal bests are the accent ones.
     *
     * [cap] is twice the median: without it a single run where the party wiped would flatten every
     * other bar to one pixel.
     */
    private fun GuiGraphicsExtractor.spark(x: Int, y: Int, attempts: List<RoomHistory.Attempt>, cap: Int) {
        for ((index, attempt) in attempts.withIndex()) {
            val bar = (SPARK_H * attempt.ticks.coerceAtMost(cap) / cap).coerceIn(1, SPARK_H)
            val barX = x + index * SPARK_PITCH
            fill(barX, y + SPARK_H - bar, barX + 2, y + SPARK_H, if (attempt.pb) ACCENT else RULE_TEXT)
        }
    }

    companion object {
        /** Widest the content gets; it shrinks with the window so scale 4 does not clip the table. */
        private const val CONTENT_MAX = 420
        private const val GUTTER = 16
        private const val ROW = 14
        private const val HEADER_Y = 32

        private const val DETAIL_INDENT = 12
        private const val DETAIL_LABEL = 44

        /**
         * One mark for every kind of missing value, times and dates alike — two different dashes in
         * adjacent columns read as two different meanings. An en dash rather than a masked `--:--.-`,
         * because fifty of those under each other are noise; the same one the chat summary already
         * prints for a teammate's unknown secret count.
         */
        private const val MISSING = "–"

        /** Bars are 2px on a 3px pitch, so the whole progression is 72px wide. */
        private const val SPARK_MAX = 24
        private const val SPARK_PITCH = 3
        private const val SPARK_H = 8

        private val BG = 0xE60B0E13.toInt()
        private val TEXT = 0xFFE8E8EA.toInt()
        private val DIM = 0xFF8A8F98.toInt()
        private val ACCENT = 0xFFFFC13B.toInt() // the same gold a PB gets in chat
        private val RULE = 0xFF1E232B.toInt()
        private val RULE_TEXT = 0xFF5C636E.toInt()
        private val HOVER = 0x0FFFFFFF

        /** Same lookup the uploader stamps its requests with; blank rather than "unknown" in a header. */
        private val VERSION: String = TelemetryUpload.modVersion().takeUnless { it == "unknown" } ?: ""
    }
}
