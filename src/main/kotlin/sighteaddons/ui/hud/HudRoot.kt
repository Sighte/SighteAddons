package sighteaddons.ui.hud

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.Config
import sighteaddons.ui.Format
import sighteaddons.ui.motion.Animatable
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.render.Effects
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens
import java.util.Locale

/**
 * The in-run overlay.
 *
 * Reads one [HudSnapshot] and draws it. It holds no tracking state of its own — the only mutable
 * fields here are animation stamps, which exist because "a secret was just found" is a *change*
 * between two snapshots and nothing in the data layer records the moment it happened.
 *
 * Legibility beats beauty when they conflict, because this sits over a bright, moving dungeon: hence
 * a scrim rather than a shadow behind text, and no drop shadow on any glyph. The old HUD drew its text
 * with vanilla's shadow flag on; a scrim is both cheaper and cleaner, and it is the only backdrop
 * available — `blurBeforeThisStratum()` would blur the hotbar, health and XP bar along with the world,
 * because this element is attached after the vanilla overlay message.
 */
internal class HudRoot {

    // Animation state. All of it is "what changed between two snapshots", never tracking data.
    //
    // Instance fields rather than statics so the gallery's synthetic preview can run a second HUD
    // beside the live one without the two fighting over the same stamps — a preview that resets the
    // real HUD's shimmer every frame would be worse than no preview.
    private var lastRoomName = ""
    private var lastOwnSecrets = 0
    private var lastHistoryTop = ""
    private var wipeStartedAt = -1.0
    private var pulseStartedAt = -1.0
    private var shimmerStartedAt = -1.0
    private val cardAlpha = Animatable(0f)
    private val secretsFilled = Animatable(0f)
    private var mounted = false

    // Cached formatting. The clock changes twenty times a second; the frame runs at up to two
    // hundred and forty. Formatting per frame is most of what a HUD render path allocates.
    private val runClock = Format.Cached()
    private val roomClock = Format.Cached()
    private val secretClock = Format.Cached()
    private val clearDelta = Format.Cached(Format::delta)
    private val idleClock = Format.Cached()
    private val navClock = Format.Cached()
    private val historyClocks = Array(HudSnapshot.HISTORY_DEPTH) { Format.Cached() }

    /** Whether the run totals are open. Toggled by [HudKeys], animated here. */
    private var expanded = false
    private val totalsOpen = Animatable(0f)

    fun toggleTotals() {
        expanded = !expanded
        totalsOpen.animateTo(if (expanded) 1f else 0f, Motion.BASE, Easing.STANDARD, Motion.Kind.OPACITY)
    }

    /** The live overlay: config position, live snapshot. */
    fun render(graphics: GuiGraphicsExtractor, font: Font) {
        if (!Config.hud) return
        draw(graphics, font, HudSnapshot.current, Config.hudX, Config.hudY)
    }

    /**
     * Draws [snapshot] at ([originX], [originY]).
     *
     * Separated from [render] so the gallery can feed it scripted data at an arbitrary position. That
     * is not a convenience: `runClient` cannot reach Hypixel, so a real room transition, a real secret
     * pickup and a real personal best are all unreachable in a dev client, and this is the only way
     * any of those animations can be looked at before they ship.
     */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, snapshot: HudSnapshot, originX: Int, originY: Int) {
        if (!snapshot.inDungeon) {
            reset()
            return
        }

        observe(snapshot)

        if (!mounted) {
            mounted = true
            cardAlpha.snapTo(0f)
            cardAlpha.animateTo(1f, Motion.BASE, Easing.ENTRANCE, Motion.Kind.OPACITY)
        }
        val appear = cardAlpha.value
        if (appear <= 0.01f) return

        val x = originX
        // Card mount rises four pixels into place. Under reduce-motion the animation is instant, so
        // this is zero and the card simply appears.
        val y = originY + Math.round((1f - appear) * 4f)

        val height = measure(snapshot)
        drawCard(graphics, x, y, height, appear)

        var cursor = y + PADDING
        cursor = drawCurrentRoom(graphics, font, snapshot, x, cursor, appear)
        cursor = drawSecrets(graphics, font, snapshot, x, cursor, appear)
        cursor = drawHistory(graphics, font, snapshot, x, cursor, appear)
        drawTotals(graphics, font, snapshot, x, cursor, appear)

        // Last, over everything, and clipped to the card: the wipe is a statement about the card as a
        // whole, not about any one row in it.
        if (wipeStartedAt >= 0.0) {
            Effects.wipe(graphics, x, y, WIDTH, height, wipeStartedAt, WIPE_MS)
        }
    }

    /**
     * Turns two consecutive snapshots into the events the animations key off.
     *
     * The data layer records what is true, never when it became true — `RoomHistory` has no notion of
     * "just now" and `TrackedRoom.ownSecrets` is a count, not an event. Comparing successive snapshots
     * is what recovers the moment, and doing it here keeps that inference out of the tracking layer,
     * which is not allowed to change.
     */
    private fun observe(snapshot: HudSnapshot) {
        if (snapshot.roomName != lastRoomName) {
            lastRoomName = snapshot.roomName
            if (snapshot.inRoom) wipeStartedAt = Clock.nowMs
            // A new room resets the progress indicator rather than animating down from the old room's
            // count, which would read as secrets being taken away.
            secretsFilled.snapTo(0f)
        }

        if (snapshot.ownSecrets > lastOwnSecrets) pulseStartedAt = Clock.nowMs
        lastOwnSecrets = snapshot.ownSecrets

        val top = snapshot.history.firstOrNull()
        val topKey = if (top == null) "" else "${top.name}|${top.clearedAt}"
        if (topKey != lastHistoryTop) {
            lastHistoryTop = topKey
            // Only an improvement earns motion. A slower run gets nothing at all, which is the whole
            // of how this UI states a regression.
            if (top != null && top.personalBest) shimmerStartedAt = Clock.nowMs
        }

        secretsFilled.animateTo(
            snapshot.secretsFound.toFloat(),
            PROGRESS_MS, Easing.STANDARD, Motion.Kind.TRANSFORM,
        )
    }

    private fun reset() {
        mounted = false
        lastRoomName = ""
        lastOwnSecrets = 0
        lastHistoryTop = ""
        wipeStartedAt = -1.0
        pulseStartedAt = -1.0
        shimmerStartedAt = -1.0
    }

    private fun measure(snapshot: HudSnapshot): Int {
        var height = PADDING * 2
        height += ROW + 10                       // room name and the big clock
        if (Config.showSecrets) height += ROW + 4
        if (snapshot.history.isNotEmpty()) height += Tokens.SPACE_6 + snapshot.history.size * ROW
        if (Config.showStandings || Config.showIdle) {
            height += Tokens.SPACE_6 + ROW
            // The card grows with the panel rather than after it, so the border never lags behind the
            // content it is supposed to be containing.
            var rows = 0
            if (Config.showStandings) rows += snapshot.standings.size
            if (Config.showIdle) rows += 1
            height += Math.round(totalsOpen.value * rows * ROW)
        }
        return height
    }

    private fun drawCard(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int, appear: Float) {
        // The scrim is the backdrop, and it is opacity-configurable because how much of the dungeon a
        // player wants to see through it is genuinely personal.
        val scrim = Tokens.alpha(Tokens.shadow, Math.round(scrimAlpha() * appear))
        Surface.roundedFill(graphics, x, y, WIDTH, height, Tokens.RADIUS_CARD, scrim)
        Surface.roundedBorder(
            graphics, x, y, WIDTH, height, Tokens.RADIUS_CARD,
            Tokens.fade(Tokens.borderSubtle, appear),
        )
        Surface.topHighlight(graphics, x, y, WIDTH, Tokens.RADIUS_CARD, Tokens.fade(Tokens.highlight, appear))
    }

    private fun drawCurrentRoom(
        graphics: GuiGraphicsExtractor, font: Font, snapshot: HudSnapshot,
        x: Int, y: Int, appear: Float,
    ): Int {
        val left = x + PADDING
        val right = x + WIDTH - PADDING

        if (!snapshot.inRoom) {
            // Between rooms is a state worth showing rather than a gap to hide: it is exactly when
            // the idle and navigation counters are the numbers that are moving.
            graphics.label(font, "BETWEEN ROOMS", left, y + 2, Tokens.fade(Tokens.textTertiary, appear))
            return y + ROW + 10
        }

        // The active-room mark breathes. One of only two ambient loops allowed on screen at once.
        val breathing = Effects.breathe() * appear
        Glyphs.roomType(
            graphics, left, y, snapshot.roomType,
            Tokens.fade(Tokens.textSecondary, breathing),
        )

        graphics.label(
            font, snapshot.roomName.uppercase(),
            left + Glyphs.SIZE + Tokens.SPACE_6, y + 1,
            Tokens.fade(Tokens.textPrimary, appear),
        )

        // The clock is the largest element on the card, per its priority. Scaled through the pose
        // because the bundled TTF is not in yet and the bitmap font has exactly one size; this is the
        // interim, and it is why the digits are not yet truly tabular.
        val clock = roomClock.of(snapshot.roomTicks)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(right - font.width(clock) * CLOCK_SCALE, (y + ROW).toFloat())
        pose.scale(CLOCK_SCALE, CLOCK_SCALE)
        graphics.text(font, clock, 0, 0, Tokens.fade(Tokens.textPrimary, appear), false)
        pose.popMatrix()

        // The split against this room's record, stated once, with a glyph so it does not depend on
        // being able to tell two greys apart.
        val delta = snapshot.clearDelta
        if (delta != Format.NONE) {
            val improved = delta < 0
            val text = clearDelta.of(delta)
            val tone = if (improved) Tokens.textSecondary else Tokens.textTertiary
            val textX = right - font.width(text)
            graphics.text(font, text, textX, y + ROW + 12, Tokens.fade(tone, appear), false)
            Glyphs.chevron(
                graphics, textX - Glyphs.SIZE - Tokens.SPACE_2, y + ROW + 13, improved,
                Tokens.fade(tone, appear),
            )
        }

        return y + ROW + 10
    }

    private fun drawSecrets(
        graphics: GuiGraphicsExtractor, font: Font, snapshot: HudSnapshot,
        x: Int, y: Int, appear: Float,
    ): Int {
        if (!Config.showSecrets) return y
        val left = x + PADDING
        val right = x + WIDTH - PADDING
        val total = snapshot.secretsTotal

        val counts = "${snapshot.secretsFound}/${if (total > 0) total else "?"}"
        val own = "${snapshot.ownSecrets} you"
        graphics.text(font, counts, left, y + 8, Tokens.fade(Tokens.textPrimary, appear), false)
        graphics.text(
            font, own, left + font.width(counts) + Tokens.SPACE_8, y + 8,
            Tokens.fade(Tokens.textTertiary, appear), false,
        )

        val secretRun = secretClock.of(snapshot.secretRunTicks)
        graphics.text(
            font, secretRun, right - font.width(secretRun), y + 8,
            Tokens.fade(Tokens.textSecondary, appear), false,
        )

        // Segmented rather than a continuous bar while the total is small enough to count: four of six
        // should be readable without reading the number, and 66 % looks like 70 % on any bar.
        if (total in 1..SEGMENT_LIMIT) {
            val barWidth = right - left
            Surface.segments(
                graphics, left, y + 2, barWidth, SEGMENT_HEIGHT, total, secretsFilled.value,
                Tokens.fade(Tokens.accent, appear),
                Tokens.fade(Tokens.borderDefault, appear),
            )
            if (pulseStartedAt >= 0.0) {
                Effects.pulse(graphics, left, y + 2, barWidth, SEGMENT_HEIGHT, pulseStartedAt)
            }
        }

        return y + ROW + 4
    }

    private fun drawHistory(
        graphics: GuiGraphicsExtractor, font: Font, snapshot: HudSnapshot,
        x: Int, y: Int, appear: Float,
    ): Int {
        if (snapshot.history.isEmpty()) return y
        val left = x + PADDING
        val right = x + WIDTH - PADDING
        var cursor = y + Tokens.SPACE_6

        for (i in snapshot.history.indices) {
            val row = snapshot.history[i]
            // Receding opacity is what makes this read as history rather than as four equal rooms.
            val recede = RECEDE[if (i < RECEDE.size) i else RECEDE.size - 1] * appear
            val name = font.plainSubstrByWidth(row.name, right - left - 52)
            graphics.text(font, name, left, cursor, Tokens.fade(Tokens.textSecondary, recede), false)

            val time = historyClocks[i].of(row.ticks)
            graphics.text(
                font, time, right - font.width(time), cursor,
                Tokens.fade(if (row.personalBest) Tokens.textPrimary else Tokens.textTertiary, recede),
                false,
            )
            if (row.personalBest) {
                Glyphs.chevron(
                    graphics, right - font.width(time) - Glyphs.SIZE - Tokens.SPACE_2, cursor + 1, true,
                    Tokens.fade(Tokens.textPrimary, recede),
                )
            }

            // One sweep, on the row that just arrived, and only when it beat its record.
            if (i == 0 && shimmerStartedAt >= 0.0) {
                Effects.shimmer(graphics, left, cursor - 1, right - left, ROW, shimmerStartedAt)
            }
            cursor += ROW
        }
        return cursor
    }

    /**
     * The run, collapsed to one line — and expanded to the standings and the idle/navigation split
     * when asked for.
     *
     * Collapsed by default because during a fight none of it is actionable; one keypress away because
     * between rooms all of it is. Both halves of what the old corner readout showed live in here: the
     * per-player ClearPoints standings and the two run counters. Neither was dropped in the rebuild —
     * they moved behind a key rather than occupying five permanent lines.
     */
    private fun drawTotals(
        graphics: GuiGraphicsExtractor, font: Font, snapshot: HudSnapshot,
        x: Int, y: Int, appear: Float,
    ): Int {
        if (!Config.showStandings && !Config.showIdle) return y
        val left = x + PADDING
        val right = x + WIDTH - PADDING
        var cursor = y + Tokens.SPACE_6

        val floor = if (snapshot.floor.isEmpty()) "?" else snapshot.floor
        val summary = "$floor · ${snapshot.roomsCleared} rooms · ${snapshot.runOwnSecrets} secrets"
        graphics.text(font, summary, left, cursor, Tokens.fade(Tokens.textTertiary, appear), false)

        val run = runClock.of(snapshot.runTicks)
        graphics.text(
            font, run, right - font.width(run), cursor,
            Tokens.fade(Tokens.textSecondary, appear), false,
        )
        cursor += ROW

        val reveal = totalsOpen.value
        if (reveal <= 0.01f) return cursor

        // The panel slides down out of the summary line rather than appearing beside it, so the
        // summary stays the thing that was pressed and the detail reads as coming from it.
        val slide = Math.round((1f - reveal) * 4f)
        val alpha = appear * reveal

        if (Config.showStandings) {
            for (i in snapshot.standings.indices) {
                val standing = snapshot.standings[i]
                val points = String.format(Locale.ROOT, "%.2f", standing.points)
                graphics.text(
                    font, points, left, cursor - slide,
                    Tokens.fade(Tokens.textSecondary, alpha), false,
                )
                graphics.text(
                    font, standing.name, left + Tokens.SPACE_32, cursor - slide,
                    Tokens.fade(Tokens.textTertiary, alpha), false,
                )
                cursor += ROW
            }
        }

        if (Config.showIdle) {
            val idle = "idle ${idleClock.of(snapshot.idleTicks)}   nav ${navClock.of(snapshot.navTicks)}"
            graphics.text(font, idle, left, cursor - slide, Tokens.fade(Tokens.textTertiary, alpha), false)
            cursor += ROW
        }

        return cursor
    }

    /** Config carries the scrim's opacity as a percentage; the spec's default range is 55–70 %. */
    private fun scrimAlpha(): Int = 160

    /**
     * An 11px uppercase label with tracking, drawn glyph by glyph.
     *
     * The bitmap font has no letter-spacing, so tracking costs one draw per character. That is the
     * strongest single argument for the bundled TTF, and it is deliberately visible here rather than
     * hidden behind a helper that quietly drops the tracking.
     */
    private fun GuiGraphicsExtractor.label(font: Font, value: String, x: Int, y: Int, argb: Int) {
        var cursor = x.toFloat()
        for (i in value.indices) {
            val glyph = value.substring(i, i + 1)
            text(font, glyph, Math.round(cursor), y, argb, false)
            cursor += font.width(glyph) + Tokens.TRACKING_LABEL
        }
    }

    companion object {
        /** The one attached to the HUD element. */
        val live = HudRoot()

        /** Card width in GUI pixels. Fixed so the layout does not reflow as room names change. */
        const val WIDTH = 196

        private const val PADDING = Tokens.SPACE_12
        private const val ROW = 12
        private const val CLOCK_SCALE = 2f
        private const val SEGMENT_LIMIT = 12
        private const val SEGMENT_HEIGHT = 3
        private const val PROGRESS_MS = 300
        private const val WIPE_MS = 320.0
        private val RECEDE = floatArrayOf(1.0f, 0.6f, 0.35f)
    }
}
