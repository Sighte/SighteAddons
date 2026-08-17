package sighteaddons.ui.hud

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.Config
import sighteaddons.ui.Format
import sighteaddons.ui.components.Labels
import sighteaddons.ui.motion.Animatable
import sighteaddons.ui.motion.Clock
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.render.Effects
import sighteaddons.ui.render.ScaledText
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Density
import sighteaddons.ui.theme.Tokens

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
    private val historyClocks = Array(HudSnapshot.HISTORY_DEPTH) { Format.Cached() }

    // The rest of what this card says, on the same argument as the clocks above. Every one of these
    // was a string template or a `String.format` evaluated per line per frame — at 240 fps, for values
    // that change at most twenty times a second, and most of them once a room. `TODO.md` states the
    // reachable target as zero allocation *on our side*; these are what stood between here and it.
    private val counts = Format.Cached2 { found, total -> "$found/${if (total > 0) total else "?"}" }
    private val ownCount = Format.Cached { "$it you" }
    // One cache for the whole line rather than one per clock in it: two cached halves still have to be
    // concatenated, and the concatenation is the allocation.
    private val idleLine = Format.Cached2 { idle, nav -> "idle ${Format.ticks(idle)}   nav ${Format.ticks(nav)}" }
    private val standingPoints = Array(STANDINGS_CAP) { Format.Cached2(::pointsLabel) }
    private val roomNameFit = Labels.Fitter()
    private val historyNames = Array(HudSnapshot.HISTORY_DEPTH) { Trimmed() }

    // The run summary mixes a name with two counts, which is one shape more than Format.Cached2 covers
    // and not worth a class of its own for one call site.
    private var summaryFloor = ""
    private var summaryRooms = Int.MIN_VALUE
    private var summarySecrets = Int.MIN_VALUE
    private var summaryText = ""

    /**
     * How open the run totals are, animated. **Whether** they are open is [Config.totalsOpen].
     *
     * The state used to be a field here, flipped by [HudKeys] and reset to closed on every game start —
     * which meant a player who wanted the panel had to press a key every session, and a player whose key
     * was unbound could not have it at all. It is a setting now, so both the `/sa` row and the keybind
     * change one value, and this class holds only the curve between the two ends of it.
     */
    private val totalsOpen = Animatable(0f)

    /** The live overlay: config position, live snapshot. */
    fun render(graphics: GuiGraphicsExtractor, font: Font) {
        if (!Config.hud) return
        // The placement editor draws the card itself, from the same config this reads. Both at once
        // is two cards: this one at the position being edited away from, the editor's under the
        // cursor — and no way to tell which is the one being moved.
        if (editing) return
        val snapshot = HudSnapshot.current
        // Measured before it is placed, because a bottom- or middle-anchored card is positioned by an
        // edge it does not own: it has to know its own height to know where its top goes. That also
        // makes it grow upwards out of a bottom anchor as the totals open, instead of downwards off
        // the screen.
        val origin = Config.hudOrigin(Density.guiWidth, Density.guiHeight, WIDTH, measure(snapshot))
        draw(graphics, font, snapshot, origin.x, origin.y)
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
        }

        // The card is the clear phase, and the boss room is where the clear phase ends. Every number
        // on it — the room clock, the split against that room's record, the secrets bar, the rooms
        // behind you — is frozen from the moment the boss starts and cannot change again, so what is
        // left is a dead readout sitting in front of the one fight that wants the whole screen.
        //
        // Faded rather than cut, and on the exit curve: a card that disappears between two frames
        // reads as the mod having fallen over, which is the last thing to suggest at the start of a
        // boss. The run clock keeps running underneath — `onTick` still publishes during the boss —
        // so the summary is unaffected and the card comes back on its entrance curve if the player
        // is ever outside the boss area again.
        //
        // Called unconditionally: `animateTo` ignores the target it is already heading to, so this
        // costs one comparison per frame and needs no state of its own to remember it ran.
        cardAlpha.animateTo(
            if (snapshot.inBoss) 0f else 1f,
            Motion.BASE,
            if (snapshot.inBoss) Easing.EXIT else Easing.ENTRANCE,
            Motion.Kind.OPACITY,
        )

        // The panel follows the setting, on the same terms and for the same reason as the line above:
        // `animateTo` ignores a target it is already heading to, so this costs one comparison per frame
        // and needs no field to remember that it ran. It also means the `/sa` switch animates the panel
        // while the screen is open, which is the only way to see what that switch does.
        totalsOpen.animateTo(
            if (Config.totalsOpen) 1f else 0f,
            Motion.BASE, Easing.STANDARD, Motion.Kind.OPACITY,
        )

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

    /**
     * The card's height, which must be the exact sum of what the draw functions advance by.
     *
     * Kept as one arithmetic expression mirroring the draw order rather than as a constant, because
     * the two drifting apart is invisible in code and obvious on screen — a card whose border stops
     * above its own last row, or an element drawn where the one below it already is.
     */
    internal fun measure(snapshot: HudSnapshot): Int {
        var height = PADDING * 2
        // The same condition as `drawCurrentRoom`'s, and it has to be: a block measured but not drawn
        // is a hole in the card, and one drawn but not measured is a row over the border. The switch
        // was read nowhere at all before this — `/sa` saved it, the file kept it, and the block stayed.
        if (Config.showRoom) height += ROOM_BLOCK
        if (Config.showSecrets) height += SECRETS_BLOCK
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
        // player wants to see through it is genuinely personal. Its own token and not `shadow`: a
        // shadow is dark in both ramps, and a backdrop the light ramp's near-black text has to be
        // read on cannot be. See Palette.scrim.
        val scrim = Tokens.alpha(Tokens.scrim, Math.round(scrimAlpha() * appear))
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
        if (!Config.showRoom) return y
        val left = x + PADDING
        val right = x + WIDTH - PADDING

        if (!snapshot.inRoom) {
            // Between rooms is a state worth showing rather than a gap to hide: it is exactly when
            // the idle and navigation counters are the numbers that are moving.
            Labels.draw(graphics, font, "BETWEEN ROOMS", left, y + 2, Tokens.fade(Tokens.textTertiary, appear))
            return y + ROOM_BLOCK
        }

        // The clock is drawn first and measured before anything else is placed, because it is the
        // largest element on the card and everything else has to fit around it rather than beside a
        // guess at its width. It spans both text rows on the right; the name and the split share the
        // left.
        //
        // Through [ScaledText] rather than its own pose: that file is where "the bitmap font has one
        // size, so the only way up is a whole-number pose scale" is written down, and it names this
        // clock as one of its three users. A second copy of the push/translate/scale/pop is a second
        // place the rule "never a border inside the scaled scope" has to be remembered.
        val clock = roomClock.of(snapshot.roomTicks)
        val clockWidth = ScaledText.width(font, clock)
        ScaledText.draw(
            graphics, font, clock, right - clockWidth, y + CLOCK_Y,
            Tokens.fade(Tokens.textPrimary, appear),
        )

        // The active-room mark breathes. One of only two ambient loops allowed on screen at once.
        val breathing = Effects.breathe() * appear
        Glyphs.roomType(
            graphics, left, y, snapshot.roomType,
            Tokens.fade(Tokens.textSecondary, breathing),
        )

        val nameLeft = left + Glyphs.SIZE + Tokens.SPACE_6
        val nameRoom = right - clockWidth - Tokens.SPACE_8 - nameLeft
        Labels.draw(
            graphics, font, roomNameFit.of(font, snapshot.roomNameUpper, nameRoom),
            nameLeft, y + NAME_Y,
            Tokens.fade(Tokens.textPrimary, appear),
        )

        // The split against this room's record, stated once, with a glyph so it does not depend on
        // being able to tell two greys apart. Under the name, on the left, clear of the clock.
        val delta = snapshot.clearDelta
        if (delta != Format.NONE) {
            val improved = delta < 0
            val text = clearDelta.of(delta)
            val tone = if (improved) Tokens.textSecondary else Tokens.textTertiary
            Glyphs.chevron(graphics, left, y + DELTA_Y, improved, Tokens.fade(tone, appear))
            graphics.text(font, text, nameLeft, y + DELTA_Y, Tokens.fade(tone, appear), false)
        }

        return y + ROOM_BLOCK
    }

    private fun drawSecrets(
        graphics: GuiGraphicsExtractor, font: Font, snapshot: HudSnapshot,
        x: Int, y: Int, appear: Float,
    ): Int {
        if (!Config.showSecrets) return y
        val left = x + PADDING
        val right = x + WIDTH - PADDING
        val total = snapshot.secretsTotal

        val found = counts.of(snapshot.secretsFound, total)
        val own = ownCount.of(snapshot.ownSecrets)
        graphics.text(font, found, left, y + COUNTS_Y, Tokens.fade(Tokens.textPrimary, appear), false)
        graphics.text(
            font, own, left + font.width(found) + Tokens.SPACE_8, y + COUNTS_Y,
            Tokens.fade(Tokens.textTertiary, appear), false,
        )

        val secretRun = secretClock.of(snapshot.secretRunTicks)
        graphics.text(
            font, secretRun, right - font.width(secretRun), y + COUNTS_Y,
            Tokens.fade(Tokens.textSecondary, appear), false,
        )

        // Segmented rather than a continuous bar while the total is small enough to count: four of six
        // should be readable without reading the number, and 66 % looks like 70 % on any bar.
        if (total in 1..SEGMENT_LIMIT) {
            val barWidth = right - left
            Surface.segments(
                graphics, left, y + SEGMENT_Y, barWidth, SEGMENT_HEIGHT, total, secretsFilled.value,
                Tokens.fade(Tokens.accent, appear),
                Tokens.fade(Tokens.borderDefault, appear),
            )
            if (pulseStartedAt >= 0.0) {
                Effects.pulse(graphics, left, y + SEGMENT_Y, barWidth, SEGMENT_HEIGHT, pulseStartedAt)
            }
        }

        return y + SECRETS_BLOCK
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
            val name = historyNames[i].of(font, row.name, right - left - 52)
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
     *
     * **"By default" is now the whole of that claim.** [Config.totalsOpen] can hold the panel open for a
     * player who wants the numbers there all the time, which the rebuild had no way of expressing: the
     * state was a field here, so the only answer to "I want to see this" was a keybind pressed every
     * session. The argument above is why the *default* is still closed and not why the choice should not
     * exist — five permanent lines is a bad default, not a forbidden preference.
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
        val summary = summary(floor, snapshot.roomsCleared, snapshot.runOwnSecrets)
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
                // Hundredths, so the cache is keyed on the number the reader can actually see. Keying
                // on the raw Double would rebuild the string on every point of a fractional split. The
                // estimate mark is part of the cached string and therefore part of the key: it changes
                // at most once per player per run, and a second draw call to place one character beside
                // a number would have to know that character's width to keep the column.
                val estimated = if (standing.estimated) 1 else 0
                val points = if (i < STANDINGS_CAP) {
                    standingPoints[i].of(Format.hundredths(standing.points), estimated)
                } else {
                    pointsLabel(Format.hundredths(standing.points), estimated)
                }
                // **Right-aligned on the column, so the digits line up and the mark hangs off the
                // left.** Left-aligned, `~1.25` put its digits one character further right than the
                // row above it, which reads as a different quantity rather than as the same one with a
                // caveat — and a column of figures that does not line up is the one thing a column of
                // figures is for.
                graphics.text(
                    font, points, left + POINTS_COLUMN - font.width(points), cursor - slide,
                    Tokens.fade(Tokens.textSecondary, alpha), false,
                )
                graphics.text(
                    font, standing.name, left + POINTS_COLUMN + Tokens.SPACE_6, cursor - slide,
                    Tokens.fade(Tokens.textTertiary, alpha), false,
                )
                cursor += ROW
            }
        }

        if (Config.showIdle) {
            val idle = idleLine.of(snapshot.idleTicks, snapshot.navTicks)
            graphics.text(font, idle, left, cursor - slide, Tokens.fade(Tokens.textTertiary, alpha), false)
            cursor += ROW
        }

        return cursor
    }

    /**
     * A history row's name, cut to the width beside its clock and held while neither has moved.
     *
     * `Font.plainSubstrByWidth` builds a measuring sink on every call whether or not it ends up cutting
     * anything, and a finished room's name does not change until the row scrolls off the card. Its own
     * class rather than [Labels.Fitter] because these rows are drawn as plain text: there is no
     * tracking between the glyphs, so measuring them as though there were would cut a name short of
     * the space it actually has.
     */
    private class Trimmed {
        private var source = ""
        private var width = Int.MIN_VALUE
        private var text = ""

        fun of(font: Font, value: String, maxWidth: Int): String {
            if (maxWidth != width || value != source) {
                source = value
                width = maxWidth
                text = if (maxWidth <= 0) "" else font.plainSubstrByWidth(value, maxWidth)
            }
            return text
        }
    }

    /** The run summary line, rebuilt only when one of the three things it states changes. */
    private fun summary(floor: String, rooms: Int, secrets: Int): String {
        if (rooms != summaryRooms || secrets != summarySecrets || floor != summaryFloor) {
            summaryFloor = floor
            summaryRooms = rooms
            summarySecrets = secrets
            summaryText = "$floor · $rooms rooms · $secrets secrets"
        }
        return summaryText
    }

    /**
     * The scrim's opacity, as the player set it.
     *
     * [Config.hudScrim] carries it as a percentage and [Tokens.scrimAlpha] is what turns that into an
     * alpha and holds it inside the range the slider offers — the clamp is deliberately not here,
     * because the numbers it clamps to are properties of the palette rather than of this card. Since the
     * range opened downwards to 30 %, that range no longer guarantees 4.5:1; the *measurement* still
     * exists as `Tokens.SCRIM_CONTRAST_PERCENT` and the `/sa` row is where a player is told.
     *
     * The same setting reaches [sighteaddons.ClearPopup] and [sighteaddons.StormHud], which is the point
     * of it being one number: three chips that appear within a second of each other must not read as
     * three different materials.
     */
    private fun scrimAlpha(): Int = Tokens.scrimAlpha(Config.hudScrim)

    companion object {
        /** The one attached to the HUD element. */
        val live = HudRoot()

        /**
         * Whether the placement editor owns the screen right now, in which case [live] stands down.
         *
         * Cleared by `SettingsScreen.removed`, not only by leaving placement mode: a screen can be
         * closed from under itself — the game can force it, a keybind can replace it — and a flag
         * left true would take the HUD off the player's screen with no way to bring it back.
         */
        var editing = false

        /** Card width in GUI pixels. Fixed so the layout does not reflow as room names change. */
        const val WIDTH = 196

        /**
         * One standings figure, with the mark that says part of it is a guess.
         *
         * `~` and not a colour, a shade or a bracket: this design has no hue, the neutral ramp's two
         * lower tones sit 1.27:1 apart and cannot carry a distinction anybody has to notice, and a
         * bracketed figure reads as a footnote rather than as an approximation. A tilde in front of a
         * number is the one notation that means "about this much" without a legend.
         *
         * [estimated] is an Int because this is [Format.Cached2]'s formatter and the cache keys on two
         * Ints — see the call site for why the mark belongs inside the cached string.
         */
        fun pointsLabel(hundredths: Int, estimated: Int): String =
            if (estimated == 1) "~" + Format.points(hundredths) else Format.points(hundredths)

        /**
         * Where the standings' figures end and their names begin.
         *
         * The figures are right-aligned on this and the names start [Tokens.SPACE_6] past it, which is
         * six pixels more than the names used to have. `~12.50` measures 32 px in vanilla's font — the
         * whole of the old column — so without the gap a five-player floor with a two-figure leader
         * would run its mark straight into the name beside it.
         */
        private const val POINTS_COLUMN = Tokens.SPACE_32

        private const val PADDING = Tokens.SPACE_12
        private const val ROW = 12

        // The block geometry below is `internal` rather than private so UiHudTest can hold it to its
        // own arithmetic. It is not decoration: the first version of this card drew the clock from
        // y+12 to y+30, the split at y+24 and the next block from y+22 — three elements in one place,
        // which compiles, passes every test, and is only visible to somebody actually in a dungeon.

        /** Vanilla's bitmap font. Every row below is placed against this. */
        const val TEXT_LINE = 9

        /**
         * How many standings rows get a cache of their own.
         *
         * A dungeon party is five, and the roster this reads from cannot exceed it — but the array is
         * a fixed allocation and a sixth row must not index off the end of it, so the loop falls back
         * to formatting rather than to a crash. One row reformatting per frame in a case that cannot
         * happen is the right price for not having to be certain that it cannot.
         */
        private const val STANDINGS_CAP = 5

        /** Where the clock's top edge sits inside the room block. */
        const val CLOCK_Y = 2

        /** Where the room name's top edge sits. */
        const val NAME_Y = 1

        /**
         * The current-room block: the name row and the split row on the left, with the double-height
         * clock spanning both on the right. 18 of these 24 pixels are the clock itself.
         */
        const val ROOM_BLOCK = 24
        const val DELTA_Y = 13

        /** The segmented bar, then the counts and the secret-run clock beneath it. */
        const val SECRETS_BLOCK = 20
        const val SEGMENT_Y = 2
        const val COUNTS_Y = 8
        private const val SEGMENT_LIMIT = 12
        private const val SEGMENT_HEIGHT = 3
        private const val PROGRESS_MS = 300
        private const val WIPE_MS = 320.0
        private val RECEDE = floatArrayOf(1.0f, 0.6f, 0.35f)
    }
}
