package sighteaddons.ui.screens

import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import sighteaddons.Cell
import sighteaddons.RoomState
import sighteaddons.SoloPost
import sighteaddons.SoloRuns
import sighteaddons.ui.Format
import sighteaddons.ui.components.Anim
import sighteaddons.ui.components.Badge
import sighteaddons.ui.components.Button
import sighteaddons.ui.components.Controls
import sighteaddons.ui.components.EmptyState
import sighteaddons.ui.components.Popover
import sighteaddons.ui.components.Table
import sighteaddons.ui.components.TextField
import sighteaddons.ui.sk.Chrome
import sighteaddons.ui.sk.Sk
import sighteaddons.ui.sk.Type
import sighteaddons.ui.theme.Tokens

/**
 * The `/sa` solo tab: a list of solo F7/M7 runs, and one run's map and rundown.
 *
 * Its own class rather than more of [sighteaddons.SettingsScreen], which passes 2600 lines; the screen
 * keeps the rail, the header and the footer, and hands this the content column. Everything here that
 * draws goes through [Sk] and is therefore only ever called from the screen's `content()` — rule 2 in
 * `Sk.kt`. The hit tests below work off the numbers the last frame left behind, so a click lands on
 * what was drawn and not on what a second copy of the layout thinks was drawn.
 *
 * [scroll] counts rows on the list and pixels on the detail, the same two-unit contract the screen's
 * own field documents: nothing reads it without knowing which of the two is showing.
 */
internal class SoloPanel {

    private var selectedTs: Long? = null
    private var scroll = 0

    private var rowsKey = -1
    private var rowsDay = 0L
    private var cachedRows: List<SoloRundown.ListRow> = emptyList()

    private var stopsFor: SoloRuns.Record? = null
    private var cachedStops: List<SoloRundown.Stop> = emptyList()

    // The last frame's geometry, for the hit tests.
    private var left = 0
    private var width = 0
    private var top = 0
    private var bottom = 0
    private var firstRow = 0
    private var visibleRows = 1
    private var backX = 0
    private var backY = 0
    private var backW = 0
    private var detailTotal = 0

    // --- Posting to Discord: the button on the run's page, and the popup it opens ----------------

    /** The run whose post popup is open, by `ts`, or null. */
    private var popupFor: Long? = null

    /** The link being typed. Kept across a cancel, so a link pasted once is not pasted twice. */
    private val link = TextField.Edit(maxLength = LINK_MAX)

    /** Why the last submit did not go out, shown under the buttons until the next keystroke. */
    private var linkHint: String? = null

    private var postBtn: IntArray? = null
    private var popupRect: IntArray? = null
    private var sendBtn: IntArray? = null
    private var cancelBtn: IntArray? = null

    private val selected: SoloRuns.Record?
        get() = selectedTs?.let { ts -> SoloRuns.records().firstOrNull { it.ts == ts } }

    private val popupOpen: Boolean get() = popupFor != null && popupFor == selectedTs

    /** Draws the column. Returns the tooltip owed to this frame, if the pointer is over something that has one. */
    fun draw(left: Int, width: Int, top: Int, bottom: Int, pointerX: Int, pointerY: Int, anim: Anim): List<String>? {
        this.left = left
        this.width = width
        this.top = top
        this.bottom = bottom
        previewing = false
        val record = selected
        if (record == null) {
            selectedTs = null
            popupFor = null
            drawList(pointerX, pointerY, anim)
            return null
        }
        val tooltip = drawDetail(record, pointerX, pointerY, anim)
        if (!popupOpen) return tooltip
        // The post went through: the record now carries its time, and the popup has nothing left to ask.
        if (record.postedTs != null) {
            closePopup()
            return tooltip
        }
        drawPopup(record, pointerX, pointerY, anim)
        return null
    }

    /**
     * The detail of one given record, whatever the store holds — the gallery's way of showing the canvas
     * on a machine that has never filed a run. Same geometry contract as [draw].
     */
    fun preview(record: SoloRuns.Record, left: Int, width: Int, top: Int, bottom: Int, pointerX: Int, pointerY: Int, anim: Anim): List<String>? {
        this.left = left
        this.width = width
        this.top = top
        this.bottom = bottom
        previewing = true
        return drawDetail(record, pointerX, pointerY, anim)
    }

    /** Whether the last frame was a [preview], so the wheel scrolls a detail that no selection produced. */
    private var previewing = false

    // --- List -----------------------------------------------------------------------------------

    private fun rows(): List<SoloRundown.ListRow> {
        val now = System.currentTimeMillis()
        val day = now / DAY_MS
        if (SoloRuns.revision != rowsKey || day != rowsDay) {
            rowsKey = SoloRuns.revision
            rowsDay = day
            cachedRows = SoloRundown.listRows(SoloRuns.records(), now)
        }
        return cachedRows
    }

    private class Columns(val timeX: Int, val scoreX: Int, val metaX: Int, val wide: Boolean, val labelWidth: Int)

    /** Right to left, measured off what is drawn — [sighteaddons.SettingsScreen]'s `pbLayout` argument. */
    private fun columns(): Columns {
        val lastX = left + width
        val timeW = maxOf(w(TIME_HEADER, LABEL, Type.MEDIUM), w(SAMPLE_TIME, ROW_TEXT, Type.MEDIUM)).toInt()
        val wide = width >= WIDE
        val metaX = lastX
        val metaW = if (wide) w(SAMPLE_META, ROW_TEXT).toInt() else 0
        val scoreX = if (wide) metaX - metaW - GAP else lastX
        val scoreW = if (wide) maxOf(w(SCORE_HEADER, LABEL, Type.MEDIUM), w("307", ROW_TEXT, Type.MEDIUM)).toInt() else 0
        val timeX = if (wide) scoreX - scoreW - GAP else lastX
        return Columns(timeX, scoreX, metaX, wide, (timeX - timeW - GAP - left).coerceAtLeast(0))
    }

    private fun drawList(pointerX: Int, pointerY: Int, anim: Anim) {
        val rows = rows()
        val cols = columns()
        Sk.text(RUN_HEADER, left.toFloat(), top.toFloat(), LABEL, Tokens.textTertiary)
        Sk.textRight(TIME_HEADER, cols.timeX.toFloat(), top.toFloat(), LABEL, Tokens.textTertiary)
        if (cols.wide) {
            Sk.textRight(SCORE_HEADER, cols.scoreX.toFloat(), top.toFloat(), LABEL, Tokens.textTertiary)
            Sk.textRight(META_HEADER, cols.metaX.toFloat(), top.toFloat(), LABEL, Tokens.textTertiary)
        }
        Table.divider(left.toFloat(), (top + Tokens.SPACE_12).toFloat(), width.toFloat())

        firstRow = top + Tokens.SPACE_16
        visibleRows = ((bottom - firstRow) / Table.ROW).coerceAtLeast(1)
        if (rows.isEmpty()) {
            val block = EmptyState.height(Sk.lineHeight(EmptyState.BODY_SIZE), SoloRuns.PATH)
            val y = firstRow + ((bottom - firstRow - block) / 2f).coerceAtLeast(0f)
            EmptyState.draw(
                left.toFloat(), y, width.toFloat(),
                "no solo runs yet", "enter f7 or m7 alone · the run is filed when you leave", SoloRuns.PATH,
            )
            return
        }
        scroll = Scroll.clamp(scroll, rows.size, visibleRows)

        val rowLeft = (left - Tokens.SPACE_8).toFloat()
        val rowWidth = (width + Tokens.SPACE_16).toFloat()
        Sk.clip(0f, firstRow.toFloat(), (left + width + Tokens.SPACE_24).toFloat(), (bottom - firstRow).toFloat())
        for ((index, row) in rows.drop(scroll).take(visibleRows).withIndex()) {
            val y = firstRow + index * Table.ROW
            val over = pointerY in y until (y + Table.ROW) && pointerX in (left - Tokens.SPACE_8)..(left + width)
            val hover = Controls.hover(anim.of("solo.row.${row.ts}"), over)
            Controls.rowHighlight(rowLeft, y.toFloat(), rowWidth, Table.ROW.toFloat(), hover, false)

            val textY = Sk.centreY(y.toFloat(), Table.ROW.toFloat(), ROW_TEXT)
            var labelRoom = cols.labelWidth
            if (row.pb || row.reached == 270) {
                val badge = if (row.pb) PB else "270"
                val badgeW = Badge.width(badge) { Sk.width(it, Badge.SIZE, Type.MEDIUM) }
                labelRoom -= (badgeW + Tokens.SPACE_8).toInt()
            }
            val label = Sk.fit(row.label, labelRoom.toFloat(), ROW_TEXT)
            Sk.text(label, left.toFloat(), textY, ROW_TEXT, Tokens.textSecondary)
            if (row.pb || row.reached == 270) {
                val badgeX = left + w(label, ROW_TEXT) + Tokens.SPACE_8
                val badgeY = (y + (Table.ROW - Badge.HEIGHT) / 2).toFloat()
                if (row.pb) {
                    Badge.draw(badgeX, badgeY, PB, Badge.Style.EARNED)
                } else {
                    Badge.draw(badgeX, badgeY, "270", Badge.Style.OUTLINE)
                }
            }
            right(row.time, cols.timeX, y, present = row.reached == 300)
            if (cols.wide) {
                right(row.score, cols.scoreX, y, present = true)
                Sk.textRight(row.meta, cols.metaX.toFloat(), textY, ROW_TEXT, Tokens.textTertiary)
            }
        }
        Sk.unclip()

        Controls.scrollbar(
            (left + width + Tokens.SPACE_6).toFloat(), firstRow.toFloat(), bottom.toFloat(),
            rows.size, visibleRows, scroll,
        )
    }

    private fun right(text: String, rightX: Int, y: Int, present: Boolean) {
        val family = if (present) Type.MEDIUM else Type.REGULAR
        Sk.textRight(
            text, rightX.toFloat(), Sk.centreY(y.toFloat(), Table.ROW.toFloat(), ROW_TEXT, family), ROW_TEXT,
            if (present) Tokens.textPrimary else Tokens.textTertiary, family,
        )
    }

    // --- Detail ---------------------------------------------------------------------------------

    private fun stops(record: SoloRuns.Record): List<SoloRundown.Stop> {
        if (stopsFor !== record) {
            stopsFor = record
            cachedStops = SoloRundown.stops(record)
        }
        return cachedStops
    }

    private fun drawDetail(record: SoloRuns.Record, pointerX: Int, pointerY: Int, anim: Anim): List<String>? {
        val stops = stops(record)
        val map = SoloMapLayout.of(record.layout.cols, record.layout.rows, width)
        val side = SoloMapLayout.sideBySide(width, map.canvasW)
        val summary = SoloRundown.summary(record)
        val summaryH = summary.size * LINE + Tokens.SPACE_16
        val curve = record.score.curve
        val curveH = if (curve.size >= 2) CURVE_H else 0

        val viewport = bottom - top
        scroll = Scroll.clamp(scroll, detailTotal, viewport)
        var tooltip: List<String>? = null
        val inBand = pointerY in top until bottom

        Sk.clip(0f, top.toFloat(), (left + width + Tokens.SPACE_24).toFloat(), viewport.toFloat())
        var y = top - scroll

        // 1. Title row, with the way back on its right.
        val title = SoloRundown.title(record)
        Sk.text(
            Sk.fit(title, (width - w(BACK, ROW_TEXT) - GAP).toFloat(), ROW_TEXT, Type.MEDIUM),
            left.toFloat(), Sk.centreY(y.toFloat(), Table.ROW.toFloat(), ROW_TEXT, Type.MEDIUM),
            ROW_TEXT, Tokens.textPrimary, Type.MEDIUM,
        )
        backW = (w(BACK, ROW_TEXT) + Tokens.SPACE_12).toInt()
        backX = left + width - backW
        backY = y
        val overBack = inBand && pointerX in backX..(left + width) && pointerY in y until (y + Table.ROW)
        val backHover = Controls.hover(anim.of("solo.back"), overBack)
        Sk.textRight(
            BACK, (left + width).toFloat(), Sk.centreY(y.toFloat(), Table.ROW.toFloat(), ROW_TEXT), ROW_TEXT,
            Controls.blend(Tokens.accent, Tokens.accentSoft, backHover),
        )
        y += Table.ROW + Tokens.SPACE_4

        // 2. The canvas, and the summary beside or below it.
        val canvasX = left
        val canvasY = y
        Chrome.card(canvasX.toFloat(), canvasY.toFloat(), map.canvasW.toFloat(), map.canvasH.toFloat())
        val hoverCell = if (inBand) map.cellAt((pointerX - canvasX).toFloat(), (pointerY - canvasY).toFloat()) else null
        drawCanvas(record, stops, map, canvasX, canvasY, hoverCell)
        hoverCell?.let { cell -> record.layout.roomAt(cell)?.let { tooltip = SoloRundown.tooltip(it, stops) } }

        val summaryX: Int
        val summaryY: Int
        val summaryW: Int
        if (side) {
            summaryX = canvasX + map.canvasW + Tokens.SPACE_16
            summaryY = canvasY
            summaryW = width - map.canvasW - Tokens.SPACE_16
            y += maxOf(map.canvasH, summaryH) + Tokens.SPACE_8
        } else {
            summaryX = left
            summaryY = canvasY + map.canvasH + Tokens.SPACE_8
            summaryW = width
            y = summaryY + summaryH + Tokens.SPACE_8
        }
        Chrome.card(summaryX.toFloat(), summaryY.toFloat(), summaryW.toFloat(), summaryH.toFloat())
        var ly = summaryY + Tokens.SPACE_8
        val inner = summaryW - Tokens.SPACE_16
        for ((label, value) in summary) {
            val valueW = w(value, ROW_TEXT, Type.MEDIUM)
            Sk.text(
                Sk.fit(label, (inner - valueW - GAP).toFloat(), LABEL), (summaryX + Tokens.SPACE_8).toFloat(),
                Sk.centreY(ly.toFloat(), LINE.toFloat(), LABEL), LABEL, Tokens.textTertiary,
            )
            Sk.textRight(
                Sk.fit(value, inner.toFloat(), ROW_TEXT, Type.MEDIUM), (summaryX + summaryW - Tokens.SPACE_8).toFloat(),
                Sk.centreY(ly.toFloat(), LINE.toFloat(), ROW_TEXT, Type.MEDIUM), ROW_TEXT, Tokens.textPrimary, Type.MEDIUM,
            )
            ly += LINE
        }

        // 3. The projection over the run, as a line.
        if (curveH > 0) {
            Chrome.card(left.toFloat(), y.toFloat(), width.toFloat(), curveH.toFloat())
            drawCurve(record, left + Tokens.SPACE_8, y + Tokens.SPACE_8, width - Tokens.SPACE_16, curveH - Tokens.SPACE_16)
            y += curveH + Tokens.SPACE_8
        }

        // 4. The way to Discord: a button, or the note that it was already taken.
        y = drawPostRow(record, y, pointerX, pointerY, anim)

        // 5. The route, stop by stop.
        Chrome.groupLabel(left.toFloat(), y.toFloat(), width.toFloat(), ROUTE_LABEL, "${stops.size} stops")
        y += Tokens.SPACE_16
        val rc = routeColumns()
        Sk.text("#", left.toFloat(), y.toFloat(), LABEL, Tokens.textTertiary)
        Sk.text("ROOM", (left + rc.roomX).toFloat(), y.toFloat(), LABEL, Tokens.textTertiary)
        Sk.textRight("IN", rc.inX.toFloat(), y.toFloat(), LABEL, Tokens.textTertiary)
        Sk.textRight("STAY", rc.stayX.toFloat(), y.toFloat(), LABEL, Tokens.textTertiary)
        if (rc.clear) Sk.textRight("CLEAR", rc.clearX.toFloat(), y.toFloat(), LABEL, Tokens.textTertiary)
        if (rc.secrets) Sk.textRight("SECRETS", rc.secretsX.toFloat(), y.toFloat(), LABEL, Tokens.textTertiary)
        Table.divider(left.toFloat(), (y + Tokens.SPACE_12).toFloat(), width.toFloat())
        y += Tokens.SPACE_16
        for (stop in stops) {
            if (stop.walk >= SoloRundown.MIN_STAY) {
                Sk.text(
                    "walk ${Format.ticks(stop.walk)}", (left + rc.roomX).toFloat(),
                    Sk.centreY(y.toFloat(), SettingsPage.NOTE.toFloat(), LABEL), LABEL, Tokens.textDisabled,
                )
                y += SettingsPage.NOTE
            }
            if (y + Table.ROW > top && y < bottom) {
                val textY = Sk.centreY(y.toFloat(), Table.ROW.toFloat(), ROW_TEXT)
                Sk.text(stop.index.toString(), left.toFloat(), textY, ROW_TEXT, Tokens.textTertiary)
                Sk.text(
                    Sk.fit(stop.room.label, rc.roomW.toFloat(), ROW_TEXT), (left + rc.roomX).toFloat(), textY,
                    ROW_TEXT, Tokens.textSecondary,
                )
                right(Format.ticks(stop.enter), rc.inX, y, present = false)
                right(Format.ticks(stop.ticks), rc.stayX, y, present = true)
                if (rc.clear) {
                    val clear = stop.clear
                    right(clear?.let(Format::ticks) ?: Format.MISSING, rc.clearX, y, present = clear != null)
                }
                if (rc.secrets) {
                    val max = stop.room.maxSecrets ?: 0
                    val text = if (max > 0) "${stop.room.secretsFound ?: 0}/$max" else "—"
                    right(text, rc.secretsX, y, present = max > 0)
                }
            }
            y += Table.ROW
        }
        Sk.unclip()

        detailTotal = y + scroll - top
        Controls.scrollbar(
            (left + width + Tokens.SPACE_6).toFloat(), top.toFloat(), bottom.toFloat(),
            detailTotal, viewport, scroll,
        )
        return tooltip
    }

    /**
     * One row: the post button, or what the post left behind.
     *
     * Disabled rather than hidden on a run that never reached 270 — a button that is not there says the
     * feature is not there — and in the gallery, whose run is a script and must not reach a channel.
     */
    private fun drawPostRow(record: SoloRuns.Record, y0: Int, pointerX: Int, pointerY: Int, anim: Anim): Int {
        val y = y0
        val posted = record.postedTs
        val textY = Sk.centreY(y.toFloat(), Button.HEIGHT.toFloat(), LABEL)
        if (posted != null) {
            postBtn = null
            val text = "posted to discord · ${Format.ago(posted, System.currentTimeMillis())}" +
                (record.video?.let { " · $it" } ?: "")
            Sk.text(Sk.fit(text, width.toFloat(), LABEL), left.toFloat(), textY, LABEL, Tokens.textTertiary)
            return y + Button.HEIGHT + Tokens.SPACE_8
        }
        val state = SoloPost.state
        val posting = state is SoloPost.State.Posting && state.ts == record.ts
        val enabled = !previewing && record.headline != null && state !is SoloPost.State.Posting
        val label = if (posting) POSTING_LABEL else POST_LABEL
        val bw = Button.width(label) { w(it, Button.SIZE, Button.family(Button.Variant.PRIMARY)) }
        val over = enabled && pointerY in top until bottom &&
            pointerX in left until (left + bw) && pointerY in y until (y + Button.HEIGHT)
        Button.draw(
            left.toFloat(), y.toFloat(), bw.toFloat(), Button.HEIGHT.toFloat(), label, Button.Variant.PRIMARY,
            hover = Controls.hover(anim.of("solo.post"), over), enabled = enabled,
        )
        postBtn = intArrayOf(left, y, bw, Button.HEIGHT)
        val note = when {
            state is SoloPost.State.Failed && state.ts == record.ts -> "not delivered · ${state.reason}"
            record.headline == null -> "reaches a channel from 270 on"
            previewing -> "a scripted run stays here"
            else -> null
        }
        if (note != null) {
            val nx = left + bw + GAP
            Sk.text(Sk.fit(note, (left + width - nx).toFloat(), LABEL), nx.toFloat(), textY, LABEL, Tokens.textSecondary)
        }
        return y + Button.HEIGHT + Tokens.SPACE_8
    }

    /**
     * The question, its one optional field, and the two answers. Drawn last and outside every clip, over
     * the column it belongs to, and never wider than that column.
     */
    private fun drawPopup(record: SoloRuns.Record, pointerX: Int, pointerY: Int, anim: Anim) {
        val pw = minOf(width, POPUP_W)
        val ph = POPUP_H
        val px = left + (width - pw) / 2
        val py = top + ((bottom - top - ph) / 2).coerceAtLeast(0)
        popupRect = intArrayOf(px, py, pw, ph)
        Popover.frame(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat())

        val pad = Popover.PADDING
        val inner = pw - pad * 2
        var cy = py + pad
        Sk.text(
            Sk.fit("post ${record.floor} · ${SoloRundown.title(record).substringAfter(" · ")} to discord?", inner.toFloat(), ROW_TEXT, Type.MEDIUM),
            (px + pad).toFloat(), cy.toFloat(), ROW_TEXT, Tokens.textPrimary, Type.MEDIUM,
        )
        cy += LINE
        Sk.text(
            Sk.fit("youtube link · optional · ctrl+v pastes", inner.toFloat(), LABEL),
            (px + pad).toFloat(), cy.toFloat(), LABEL, Tokens.textTertiary,
        )
        cy += LINE - Tokens.SPACE_2
        val overField = pointerX in (px + pad) until (px + pad + inner) && pointerY in cy until (cy + TextField.HEIGHT)
        TextField.draw(
            (px + pad).toFloat(), cy.toFloat(), inner.toFloat(), TextField.HEIGHT.toFloat(), link,
            placeholder = "https://youtu.be/…", focus = 1f,
            hover = Controls.hover(anim.of("solo.link"), overField), caret = TextField.caretOn(true),
        )
        cy += TextField.HEIGHT + Tokens.SPACE_8

        val state = SoloPost.state
        val posting = state is SoloPost.State.Posting
        val sendLabel = if (posting) POSTING_LABEL else SEND_LABEL
        val sendW = Button.width(sendLabel) { w(it, Button.SIZE, Button.family(Button.Variant.PRIMARY)) }
        val cancelW = Button.width(CANCEL_LABEL) { w(it, Button.SIZE, Button.family(Button.Variant.GHOST)) }
        val sendX = px + pw - pad - sendW
        val cancelX = sendX - Tokens.SPACE_8 - cancelW
        val overSend = !posting && pointerX in sendX until (sendX + sendW) && pointerY in cy until (cy + Button.HEIGHT)
        val overCancel = pointerX in cancelX until (cancelX + cancelW) && pointerY in cy until (cy + Button.HEIGHT)
        Button.draw(
            cancelX.toFloat(), cy.toFloat(), cancelW.toFloat(), Button.HEIGHT.toFloat(), CANCEL_LABEL, Button.Variant.GHOST,
            hover = Controls.hover(anim.of("solo.cancel"), overCancel),
        )
        Button.draw(
            sendX.toFloat(), cy.toFloat(), sendW.toFloat(), Button.HEIGHT.toFloat(), sendLabel, Button.Variant.PRIMARY,
            hover = Controls.hover(anim.of("solo.send"), overSend), enabled = !posting,
        )
        sendBtn = intArrayOf(sendX, cy, sendW, Button.HEIGHT)
        cancelBtn = intArrayOf(cancelX, cy, cancelW, Button.HEIGHT)
        cy += Button.HEIGHT + Tokens.SPACE_6

        val status = when {
            linkHint != null -> linkHint
            state is SoloPost.State.Failed && state.ts == record.ts -> "not delivered · ${state.reason}"
            posting -> "posting…"
            else -> "enter posts · esc cancels"
        }
        Sk.text(Sk.fit(status!!, inner.toFloat(), LABEL), (px + pad).toFloat(), cy.toFloat(), LABEL, Tokens.textTertiary)
    }

    private fun openPopup(record: SoloRuns.Record) {
        popupFor = record.ts
        linkHint = null
        SoloPost.dismiss()
    }

    private fun closePopup() {
        popupFor = null
        linkHint = null
        SoloPost.dismiss()
    }

    /** The post button of the popup, and the enter key: validate the link, then hand over to [SoloPost]. */
    private fun submit(record: SoloRuns.Record) {
        if (SoloPost.state is SoloPost.State.Posting) return
        val typed = link.text
        val video = SoloPost.videoLink(typed)
        if (typed.isNotBlank() && video == null) {
            linkHint = "not a youtube link"
            return
        }
        linkHint = null
        SoloPost.send(record, video)
    }

    private fun IntArray.hit(mx: Int, my: Int): Boolean =
        mx >= this[0] && mx < this[0] + this[2] && my >= this[1] && my < this[1] + this[3]

    private class RouteColumns(
        val roomX: Int, val roomW: Int, val inX: Int, val stayX: Int,
        val clear: Boolean, val clearX: Int, val secrets: Boolean, val secretsX: Int,
    )

    /** Right to left again; `secrets` goes first when the column is narrow, then `clear`. */
    private fun routeColumns(): RouteColumns {
        val lastX = left + width
        val timeW = w(SAMPLE_STAY, ROW_TEXT, Type.MEDIUM).toInt()
        val secretsW = maxOf(w("SECRETS", LABEL), w("10/10", ROW_TEXT, Type.MEDIUM)).toInt()
        val secrets = width >= WIDE
        val clear = width >= WIDE - 60
        var x = lastX
        val secretsX = x
        if (secrets) x -= secretsW + GAP
        val clearX = x
        if (clear) x -= timeW + GAP
        val stayX = x
        x -= timeW + GAP
        val inX = x
        x -= timeW + GAP
        val roomX = Tokens.SPACE_16
        return RouteColumns(roomX, (x - left - roomX).coerceAtLeast(0), inX, stayX, clear, clearX, secrets, secretsX)
    }

    private fun drawCanvas(
        record: SoloRuns.Record, stops: List<SoloRundown.Stop>, map: SoloMapLayout,
        canvasX: Int, canvasY: Int, hoverCell: Cell?,
    ) {
        val ox = canvasX.toFloat()
        val oy = canvasY.toFloat()
        fun fill(r: SoloMapLayout.Rect, argb: Int, radius: Float = 0f) = Sk.fill(ox + r.x, oy + r.y, r.w, r.h, argb, radius)

        for (room in record.layout.rooms) {
            val colour = Tokens.fade(SoloMapLayout.fill(room.type), SoloMapLayout.opacity(room.state))
            for (run in SoloMapLayout.runs(room.cells)) fill(map.rect(run), colour, Tokens.RADIUS_XS.toFloat())
            for (upper in SoloMapLayout.bridges(room.cells)) fill(map.bridge(upper), colour)
            for (upperLeft in SoloMapLayout.corners(room.cells)) fill(map.corner(upperLeft), colour)
        }
        for (door in record.layout.doors) fill(map.doorRect(door), SoloMapLayout.door(door.type))

        // The entrance, as a hollow square: the one room that is always where the route starts.
        val entrance = map.square(record.layout.entrance)
        Sk.border(ox + entrance.x, oy + entrance.y, entrance.w, entrance.h, Tokens.textSecondary, Tokens.RADIUS_XS.toFloat())

        for (room in record.layout.rooms) {
            val check = SoloMapLayout.check(room.state) ?: continue
            val (cx, cy) = map.centre(room.cells)
            Sk.circle(ox + cx, oy + cy, map.cell / 7f, check)
        }

        // The route: one line through the stops' rooms, a numbered dot at each first arrival.
        val path = SoloRundown.path(stops)
        var last: Pair<Float, Float>? = null
        for (cells in path) {
            val (cx, cy) = map.centre(cells)
            last?.let { (px, py) -> Sk.line(ox + px, oy + py, ox + cx, oy + cy, Tokens.accent, ROUTE_STROKE) }
            last = cx to cy
        }
        val numbered = HashSet<Set<Cell>>()
        for (stop in stops) {
            if (!numbered.add(stop.room.cells)) continue
            val (cx, cy) = map.centre(stop.room.cells)
            val r = map.cell / 4.5f
            Sk.circle(ox + cx, oy + cy, r, Tokens.surfaceBase)
            Sk.border(ox + cx - r, oy + cy - r, 2 * r, 2 * r, Tokens.accent, r)
            if (map.cell >= NUMBER_CELL) {
                Sk.textCenter(
                    stop.index.toString(), ox + cx, Sk.centreY(oy + cy - r, 2 * r, NUMBER), NUMBER,
                    Tokens.textPrimary, Type.MEDIUM,
                )
            }
        }

        // The room where 300 fell wears the ring; where only 270 did, the same ring says so more quietly.
        val mark = record.to300 ?: record.to270
        if (mark != null) {
            SoloRundown.stopAt(stops, mark.tick)?.let { stop ->
                for (run in SoloMapLayout.runs(stop.room.cells)) {
                    val r = map.rect(run)
                    Sk.border(
                        ox + r.x, oy + r.y, r.w, r.h,
                        if (mark.threshold == 300) Tokens.accent else Tokens.accentSoft, Tokens.RADIUS_XS.toFloat(), RING,
                    )
                }
            }
        }

        hoverCell?.let { cell ->
            if (record.layout.roomAt(cell) != null) {
                val r = map.square(cell)
                Sk.border(ox + r.x, oy + r.y, r.w, r.h, Tokens.textPrimary, Tokens.RADIUS_XS.toFloat())
            }
        }
    }

    /** The projection over run ticks, 0..310 on the vertical, with hairlines at 270 and 300. */
    private fun drawCurve(record: SoloRuns.Record, x: Int, y: Int, w: Int, h: Int) {
        val curve = record.score.curve
        val lastTick = maxOf(record.runTicks, curve.last().first, 1)
        fun px(tick: Int) = x + w * tick.toFloat() / lastTick
        fun py(score: Int) = y + h - h * score.coerceIn(0, CURVE_TOP).toFloat() / CURVE_TOP
        for (threshold in SoloRuns.THRESHOLDS) {
            Sk.line(x.toFloat(), py(threshold), (x + w).toFloat(), py(threshold), Tokens.borderDefault, Chrome.HAIRLINE)
            Sk.textRight(threshold.toString(), (x + w).toFloat(), py(threshold) - Sk.lineHeight(NUMBER), NUMBER, Tokens.textDisabled)
        }
        var prev = curve.first()
        for (point in curve.drop(1)) {
            // A step, because the projection is sampled: it holds a value until the next reading.
            Sk.line(px(prev.first), py(prev.second), px(point.first), py(prev.second), Tokens.accent, ROUTE_STROKE)
            Sk.line(px(point.first), py(prev.second), px(point.first), py(point.second), Tokens.accent, ROUTE_STROKE)
            prev = point
        }
        Sk.line(px(prev.first), py(prev.second), px(lastTick), py(prev.second), Tokens.accent, ROUTE_STROKE)
        Sk.text("projected score", x.toFloat(), y.toFloat(), NUMBER, Tokens.textDisabled)
    }

    // --- Input ----------------------------------------------------------------------------------

    /** A press in the column. True when it did something. */
    fun click(mouseX: Int, mouseY: Int): Boolean {
        val record = selected
        if (record != null) {
            if (popupOpen) {
                // Inside the popup the two buttons answer; the field has the keyboard whatever is pressed.
                // Outside it, the press is the answer "not now" — and is swallowed, so it cannot also open
                // whatever it landed on underneath.
                when {
                    sendBtn?.hit(mouseX, mouseY) == true -> submit(record)
                    cancelBtn?.hit(mouseX, mouseY) == true -> closePopup()
                    popupRect?.hit(mouseX, mouseY) == true -> {}
                    else -> closePopup()
                }
                return true
            }
            if (mouseX in backX..(left + width) && mouseY in backY until (backY + Table.ROW)) {
                backToList()
                return true
            }
            val button = postBtn
            if (button != null && button.hit(mouseX, mouseY) && mouseY in top until bottom &&
                !previewing && record.headline != null && SoloPost.state !is SoloPost.State.Posting
            ) {
                openPopup(record)
                return true
            }
            return false
        }
        val rows = rows()
        if (mouseX !in (left - Tokens.SPACE_8)..(left + width)) return false
        if (mouseY !in firstRow until (firstRow + visibleRows * Table.ROW)) return false
        val index = scroll + (mouseY - firstRow) / Table.ROW
        if (index !in rows.indices) return false
        selectedTs = rows[index].ts
        scroll = 0
        return true
    }

    fun wheel(scrollY: Double) {
        scroll = if (previewing || selected != null) {
            Scroll.wheel(scroll, scrollY, SettingsPage.ROW, detailTotal, bottom - top)
        } else {
            Scroll.wheel(scroll, scrollY, 1, rows().size, visibleRows)
        }
    }

    /**
     * Escape leaves the detail for the list; on the list it is not ours, so the screen closes.
     *
     * While the popup is open the field owns the keyboard outright, the same rule the settings screen's
     * fields follow — and unlike those, paste is wanted here: a video link is the one value in this UI
     * that nobody types.
     */
    fun key(event: KeyEvent): Boolean {
        val record = selected ?: return false
        if (popupOpen) {
            linkHint = null
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> closePopup()
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> submit(record)
                GLFW.GLFW_KEY_BACKSPACE -> link.backspace()
                GLFW.GLFW_KEY_DELETE -> link.delete()
                GLFW.GLFW_KEY_LEFT -> link.move(-1, event.hasShiftDown())
                GLFW.GLFW_KEY_RIGHT -> link.move(1, event.hasShiftDown())
                GLFW.GLFW_KEY_HOME -> link.home(event.hasShiftDown())
                GLFW.GLFW_KEY_END -> link.end(event.hasShiftDown())
                GLFW.GLFW_KEY_A -> if (event.hasControlDown()) link.selectAll()
                GLFW.GLFW_KEY_V -> if (event.hasControlDown()) {
                    link.insert(Minecraft.getInstance().keyboardHandler.clipboard.trim())
                }
            }
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            backToList()
            return true
        }
        return false
    }

    /** A character for the link field. True when the popup took it. */
    fun charTyped(text: String): Boolean {
        if (!popupOpen) return false
        linkHint = null
        link.insert(text)
        return true
    }

    fun backToList() {
        selectedTs = null
        popupFor = null
        scroll = 0
    }

    fun footer(): String = when {
        popupOpen -> "enter posts · esc cancels · ctrl+v pastes the link"
        selected != null -> "esc back to the list · hover a room for its times"
        else -> "solo f7 and m7 runs · click one for its map"
    }

    /** What the header states: how many runs, and the fastest 300 among them. */
    fun headerRight(): String {
        val records = SoloRuns.records()
        // Ranked by ticks, shown on Hypixel's clock — see SoloRundown.timeOf.
        val best = records.mapNotNull { r -> r.to300?.let { r to it } }.minByOrNull { it.second.tick }
        val count = "${records.size} solo ${if (records.size == 1) "run" else "runs"}"
        return if (best == null) count else "$count · best ${best.first.floor} ${SoloRundown.timeOf(best.second)}"
    }

    private fun w(text: String, size: Float, family: String = Type.REGULAR): Float = Sk.width(text, size, family)

    private companion object {
        val ROW_TEXT = Tokens.TEXT_12.toFloat()
        val LABEL = Tokens.TEXT_10.toFloat()
        val NUMBER = Tokens.TEXT_10.toFloat()

        /** A summary line: shorter than a table row, because there are a dozen of them. */
        const val LINE = 16

        const val CURVE_H = 56
        const val CURVE_TOP = 310
        const val GAP = Tokens.SPACE_12

        /** The content width at which the list's two extra columns and the route's secrets column fit. */
        const val WIDE = 260

        /** The cell size from which a visit number fits inside its dot. */
        const val NUMBER_CELL = 20

        const val ROUTE_STROKE = 1.5f
        const val RING = 1.5f

        const val DAY_MS = 86_400_000L

        const val PB = "PB"
        const val BACK = "‹ back"

        const val POST_LABEL = "post to discord"
        const val POSTING_LABEL = "posting…"
        const val SEND_LABEL = "post"
        const val CANCEL_LABEL = "cancel"

        /** The popup's box: wide enough for a long watch URL to scroll in, short enough for the 140 px page. */
        const val POPUP_W = 320
        const val POPUP_H = 116

        /** A YouTube URL with parameters is under a hundred characters; twice that is room, not a ceiling. */
        const val LINK_MAX = 200
        const val RUN_HEADER = "RUN"
        const val TIME_HEADER = "TO 300"
        const val SCORE_HEADER = "SCORE"
        const val META_HEADER = "SECRETS · DEATHS"
        const val ROUTE_LABEL = "ROUTE"
        const val SAMPLE_TIME = "10:23.4"
        const val SAMPLE_STAY = "0:41.2"
        const val SAMPLE_META = "99 secrets · 9 deaths"
    }
}
