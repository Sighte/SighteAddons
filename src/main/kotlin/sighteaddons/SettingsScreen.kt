package sighteaddons

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.concurrent.TimeUnit

/**
 * The `/sa` screen: settings and the personal-record table.
 *
 * Drawn entirely from filled rectangles and font calls — no textures, no widget library, no
 * dependency. The style is deliberately flat: a dark wash over the world, hairline rules, one accent
 * colour, everything on a 14px row grid. Minecraft's font is a 9px bitmap and the GUI scale changes
 * under it, so the layout carries the identity, not the typography.
 *
 * Rows are rebuilt every frame and reused for hit testing, which keeps the drawing and the click
 * targets from drifting apart — there is no widget tree that could disagree with what is on screen.
 */
class SettingsScreen(private var tab: Tab = Tab.HUD) : Screen(Component.literal("Sighte Addons")) {
    enum class Tab { HUD, CHAT, RECORDS, DEBUG }

    /** Which column the record table is ordered by. */
    private enum class Sort { TYPE, NAME, CLEAR, RUNS }

    /** [on] drives the value colour; null means the row is plain information. */
    private class Row(val label: String, val value: String, val on: Boolean? = null, val click: (() -> Unit)? = null)

    private class RecordRow(
        val room: String,
        val type: String?,
        val clear: Int?,
        val secrets: Int?,
        val runs: Int,
        val lastTs: Long,
    )

    /** One rendered line: a group heading when [row] is null, a record otherwise. */
    private class Line(val header: String?, val row: RecordRow?)

    private var placing = false
    private var scroll = 0

    /** Grouped by type by default — a flat list of 130 rooms is a list you search, not one you read. */
    private var sort = Sort.TYPE

    private val contentLeft get() = (width - CONTENT) / 2
    private val typeHeaderX get() = contentLeft + font.width(NAME_COL) + 12
    private val tabsY get() = HEADER_Y + 20
    private val listTop get() = HEADER_Y + 40
    private val listBottom get() = height - 24

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
        // RECORDS puts its own counts in that corner, so the version would draw on top of them.
        if (tab != Tab.RECORDS) graphics.right(VERSION, left + CONTENT, HEADER_Y, DIM)
        graphics.rule(left, HEADER_Y + 13)

        for ((entry, x0, x1) in tabZones()) {
            val active = entry == tab
            graphics.flat(entry.name, x0, tabsY, if (active) ACCENT else DIM)
            if (active) graphics.fill(x0, tabsY + 11, x1, tabsY + 12, ACCENT)
        }
        graphics.rule(left, HEADER_Y + 33)

        if (tab == Tab.RECORDS) renderRecords(graphics, mouseX, mouseY) else renderRows(graphics, mouseX, mouseY)

        graphics.flat(if (tab == Tab.RECORDS) "esc  close · click a column to sort" else "esc  close",
            left, height - 14, RULE_TEXT)
    }

    private fun renderRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val left = contentLeft
        for ((index, row) in rows().withIndex()) {
            val y = listTop + index * ROW
            if (row.click != null && hovering(mouseX, mouseY, y)) {
                graphics.fill(left - GUTTER, y - 3, left + CONTENT + GUTTER, y + ROW - 3, HOVER)
            }
            graphics.flat(row.label, left, y, if (row.click == null) DIM else TEXT)
            graphics.right(row.value, left + CONTENT, y, if (row.on == true) ACCENT else DIM)
        }
    }

    private fun renderRecords(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val left = contentLeft
        val lines = recordLines()
        val rooms = lines.count { it.row != null }
        graphics.right("${RoomHistory.entryCount()} lines · $rooms rooms", left + CONTENT, HEADER_Y, DIM)

        graphics.flat(NAME_COL, left, listTop, if (sort == Sort.NAME) ACCENT else DIM)
        graphics.flat(TYPE_COL, typeHeaderX, listTop, if (sort == Sort.TYPE) ACCENT else DIM)
        graphics.right(CLEAR_COL, left + CLEAR_X, listTop, if (sort == Sort.CLEAR) ACCENT else DIM)
        graphics.right("secrets", left + SECRETS_X, listTop, DIM)
        graphics.right(RUNS_COL, left + RUNS_X, listTop, if (sort == Sort.RUNS) ACCENT else DIM)
        graphics.right("last", left + CONTENT, listTop, DIM)

        if (lines.isEmpty()) {
            graphics.flat("no history yet · config/sighteaddons/history.jsonl", left, listTop + ROW * 2, DIM)
            return
        }

        val first = listTop + ROW
        val visible = ((listBottom - first) / ROW).coerceAtLeast(1)
        scroll = scroll.coerceIn(0, (lines.size - visible).coerceAtLeast(0))

        graphics.enableScissor(0, first, width, listBottom)
        for ((index, line) in lines.drop(scroll).take(visible).withIndex()) {
            val y = first + index * ROW
            val row = line.row
            if (row == null) {
                // Group heading: the accent plus a rule carries it, rather than a second type column
                // repeating "normal" on a hundred rows.
                graphics.flat(line.header ?: "", left, y, ACCENT)
                graphics.fill(left + font.width(line.header ?: "") + 8, y + 4, left + CONTENT, y + 5, RULE)
                continue
            }
            if (hovering(mouseX, mouseY, y)) {
                graphics.fill(left - GUTTER, y - 3, left + CONTENT + GUTTER, y + ROW - 3, HOVER)
            }
            graphics.flat(font.plainSubstrByWidth(row.room, CLEAR_X - 60), left, y, TEXT)
            graphics.right(row.clear.time(), left + CLEAR_X, y, if (row.clear != null) ACCENT else RULE_TEXT)
            graphics.right(row.secrets.time(), left + SECRETS_X, y, DIM)
            graphics.right(row.runs.toString(), left + RUNS_X, y, DIM)
            graphics.right(ago(row.lastTs), left + CONTENT, y, RULE_TEXT)
        }
        graphics.disableScissor()

        // Scroll position as a plain accent bar; a textured scrollbar would break the flat style.
        if (lines.size > visible) {
            val track = listBottom - first
            val bar = (track * visible / lines.size).coerceAtLeast(8)
            val offset = (track - bar) * scroll / (lines.size - visible)
            graphics.fill(left + CONTENT + GUTTER, first + offset, left + CONTENT + GUTTER + 2, first + offset + bar, ACCENT)
        }
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
            Row("rooms in the database", RoomDatabase.size.toString()),
            Row("lines in the history", RoomHistory.entryCount().toString()),
        )
        Tab.RECORDS -> emptyList()
    }

    /**
     * One line per room, both record kinds side by side. [runs] counts clears rather than clears plus
     * secrets: a room is completed once per run, and its secrets line is an extra event in the same
     * run, not a second one.
     */
    private fun recordRows(): List<RecordRow> {
        val records = RoomHistory.records()
        val rows = records.keys.map { it.substringBefore('|') }.distinct().map { room ->
            val clear = records["$room|clear"]
            val secrets = records["$room|secrets"]
            RecordRow(
                room = room,
                // Null for a room the database does not know — it still gets a line, under "other".
                type = RoomDatabase.infoByName(room)?.type,
                clear = clear?.ticks,
                secrets = secrets?.ticks,
                runs = clear?.runs ?: secrets?.runs ?: 0,
                lastTs = maxOf(clear?.lastTs ?: 0L, secrets?.lastTs ?: 0L),
            )
        }
        return when (sort) {
            Sort.TYPE -> rows.sortedWith(compareBy({ group(it.type) }, { it.room }))
            Sort.NAME -> rows.sortedBy { it.room }
            // Rooms without a clear record sort last instead of leading with a missing value.
            Sort.CLEAR -> rows.sortedBy { it.clear ?: Int.MAX_VALUE }
            Sort.RUNS -> rows.sortedByDescending { it.runs }
        }
    }

    /** Records with a heading before each group, or the plain list under any other sort. */
    private fun recordLines(): List<Line> {
        val rows = recordRows()
        if (sort != Sort.TYPE) return rows.map { Line(null, it) }
        val out = mutableListOf<Line>()
        // Already sorted by group, so groupBy keeps both the group order and the names inside them.
        for ((group, inGroup) in rows.groupBy { group(it.type) }) {
            out.add(Line("${GROUPS[group]}  ${inGroup.size}", null))
            inGroup.forEach { out.add(Line(null, it)) }
        }
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

        if (tab == Tab.RECORDS) {
            if (mouseY in listTop until listTop + ROW) {
                val left = contentLeft
                sort = when {
                    mouseX < left + font.width(NAME_COL) -> Sort.NAME
                    mouseX in typeHeaderX..typeHeaderX + font.width(TYPE_COL) -> Sort.TYPE
                    mouseX in left + CLEAR_X - font.width(CLEAR_COL)..left + CLEAR_X -> Sort.CLEAR
                    mouseX in left + RUNS_X - font.width(RUNS_COL)..left + RUNS_X -> Sort.RUNS
                    else -> sort
                }
                // The headings shift every row down, so a stale offset would land mid-group.
                scroll = 0
                return true
            }
            return true
        }

        val rows = rows()
        // Same origin the hover band uses, or a click near a row's bottom edge would toggle the row
        // above the one that is highlighted.
        val bands = listTop - 3
        val index = if (mouseY >= bands) (mouseY - bands) / ROW else -1
        if (mouseX in contentLeft - GUTTER..contentLeft + CONTENT + GUTTER && index in rows.indices) {
            val row = rows[index]
            if (row.click != null) {
                row.click.invoke()
                Config.save()
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (tab != Tab.RECORDS) return false
        scroll = (scroll - scrollY.toInt() * 3).coerceAtLeast(0)
        return true
    }

    /** Tab label hit zones, so drawing and clicking share one layout. */
    private fun tabZones(): List<Triple<Tab, Int, Int>> {
        var x = contentLeft
        return Tab.entries.map { entry ->
            val label = font.width(entry.name)
            Triple(entry, x, x + label).also { x += label + 24 }
        }
    }

    private fun hovering(mouseX: Int, mouseY: Int, rowY: Int) =
        mouseY in rowY - 3 until rowY + ROW - 3 &&
            mouseX in contentLeft - GUTTER..contentLeft + CONTENT + GUTTER

    private fun ago(ts: Long): String {
        if (ts == 0L) return "--"
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ts)
        return when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            else -> "${days}d ago"
        }
    }

    /**
     * Index into [GROUPS]. Puzzles, traps and rare rooms come first on purpose: they are a handful
     * each and the ones worth comparing, while the hundred-odd normal rooms are the tail you scroll
     * into rather than past.
     */
    private fun group(type: String?) = when (type) {
        "PUZZLE" -> 0
        "TRAP" -> 1
        "RARE" -> 2
        "NORMAL" -> 3
        else -> 4 // champion, blood, fairy, entrance, and anything the database does not name
    }

    private fun Int?.time() = this?.let(DungeonGrid::formatTicks) ?: "--:--.-"

    private fun Boolean.word() = if (this) "on" else "off"

    private fun GuiGraphicsExtractor.flat(str: String, x: Int, y: Int, color: Int) =
        text(font, str, x, y, color, false)

    private fun GuiGraphicsExtractor.right(str: String, rightX: Int, y: Int, color: Int) =
        text(font, str, rightX - font.width(str), y, color, false)

    private fun GuiGraphicsExtractor.rule(left: Int, y: Int) =
        fill(left - GUTTER, y, left + CONTENT + GUTTER, y + 1, RULE)

    companion object {
        private const val CONTENT = 420
        private const val GUTTER = 16
        private const val ROW = 14
        private const val HEADER_Y = 32

        /** Group headings, indexed by [group]. */
        private val GROUPS = listOf("puzzles", "traps", "rare rooms", "normal rooms", "other")

        /** Column headers that are also click targets, so drawing and hit testing cannot drift apart. */
        private const val NAME_COL = "room"
        private const val TYPE_COL = "type"
        private const val CLEAR_COL = "clear"
        private const val RUNS_COL = "runs"

        /** Right edges of the record columns, relative to the content's left edge. */
        private const val CLEAR_X = 250
        private const val SECRETS_X = 320
        private const val RUNS_X = 360

        private val BG = 0xE60B0E13.toInt()
        private val TEXT = 0xFFE8E8EA.toInt()
        private val DIM = 0xFF8A8F98.toInt()
        private val ACCENT = 0xFFFFC13B.toInt() // the same gold a PB gets in chat
        private val RULE = 0xFF1E232B.toInt()
        private val RULE_TEXT = 0xFF5C636E.toInt()
        private val HOVER = 0x14FFFFFF

        private val VERSION: String = FabricLoader.getInstance().getModContainer(SighteAddons.ID)
            .map { it.metadata.version.friendlyString }
            .orElse("")
    }
}
