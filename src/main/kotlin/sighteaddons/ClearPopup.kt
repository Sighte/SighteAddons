package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.Format
import sighteaddons.ui.hud.Glyphs
import sighteaddons.ui.motion.Easing
import sighteaddons.ui.motion.Motion
import sighteaddons.ui.render.ScaledText
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * The short, large line that flashes over the middle of the screen when you finish a room.
 *
 * Deliberately not part of the HUD block in the corner. That one is a standing readout you look at
 * when you choose to; this is a single event that has to land without you looking away from the
 * fight — so it is centred, drawn at double size, and gone again within three seconds.
 *
 * Only your own rooms trigger it, on the same ownership gate the history uses, so the popup can
 * never show a room the records will not have. That promise is kept by the caller and not here:
 * [RoomHistory.onRoomCleared] and [RoomHistory.onSecretRun] each call [show] under exactly the
 * condition under which they append a line ([RoomHistory.ownClear], [RoomHistory.ownSecretRun]),
 * because that is where the room, the roster and the local player are all in hand. The bar used to
 * be one second of presence for a clear and nothing at all for a secret run, which is how a popup
 * came to flash for rooms somebody else had done.
 *
 * Timed in wall-clock milliseconds rather than run ticks: the last room of a run finishes right as
 * the tick counter stops, and a popup frozen on screen from then on would be the one you remember.
 * That is also why it does not read [sighteaddons.ui.motion.Clock] like every other animation in this
 * UI — that clock stops with the game, and a popup that survives a pause menu is the same defect
 * wearing a different hat. The *curves* still come from the motion layer, so reduce-motion reaches
 * this the way it reaches everything else; only the time base is local.
 *
 * ### How a personal best reads without a colour
 *
 * It used to be gold. There is no gold in this design and no hue at all, and the neutral ramp cannot
 * be asked to carry it either — `text.tertiary` sits 1.27:1 from `text.secondary` (see `Palette`), so
 * "the brighter one is the good one" is a distinction a reader can genuinely miss. A best therefore
 * arrives on three channels at once, none of them luminance: **a chevron** — the same upward mark the
 * HUD history puts beside a record — **the word `PB`**, and **a stronger frame** around the whole
 * chip. Any one of them read alone is enough, which is the property the colour never had.
 *
 * The *amount* of the improvement does not ride along, and not for want of room: [show]'s signature
 * carries `pb` as a boolean and nothing else, and the previous record is gone by the time it is
 * called — `RoomHistory.record` has already overwritten it. Adding it is a change to that call site,
 * not to this file.
 *
 * Nothing here is testable end to end — it is `Minecraft` calls from the first line of [render]. What
 * *is* decidable without a game is the opacity ramp, which is why [opacity] and [expired] are pulled
 * out as pure functions and driven by `ClearPopupTest`.
 */
object ClearPopup {

    /** Full presence, then the fade. The two together are the "gone within three seconds" promise. */
    private const val HOLD_MS = 2200L
    private const val FADE_MS = 800

    /**
     * The whole life, which is the promise the two above add up to.
     *
     * Not private because the gallery schedules a scripted popup against it — a preview that guessed
     * at this number would be a preview that stopped agreeing with the popup the moment either half
     * was retuned, and the timing is most of what there is to review here.
     */
    internal const val LIFE_MS = HOLD_MS + FADE_MS

    /**
     * The entrance, which the hold pays for rather than the lifetime: a popup that is fully opaque on
     * the first frame reads as a texture glitch over a moving fight, and 140 ms is the shortest fade
     * that does not. Overlapping the head of the hold keeps the total on screen at 3000 ms exactly.
     */
    private const val RISE_MS = Motion.FAST

    /** Above the crosshair: clear of the melee in the centre, still inside where you are looking. */
    private const val OFFSET_Y = -54

    private const val PAD_X = Tokens.SPACE_12
    private const val PAD_Y = Tokens.SPACE_6

    /**
     * The scrim behind the line, matching the HUD card's — and now literally the same one.
     *
     * A scrim and not a blur, for the reason the HUD gives at length: `blurBeforeThisStratum()` blurs
     * everything already submitted — world, hotbar, health, hunger — and this element is attached
     * after the vanilla overlay message. There is no shaped backdrop available at all.
     *
     * It reads [Config.hudScrim] rather than a constant of its own because "matching the HUD card's"
     * was already the claim, and a claim that holds until the player moves one slider is not one. Three
     * separately-set opacities on three chips that appear within a second of each other would read as
     * three different surfaces.
     */
    private fun scrimAlpha(): Int = Tokens.scrimAlpha(Config.hudScrim)

    /** Spelled out rather than a glyph: a word survives a screenshot, a scale change and a reader. */
    private const val BADGE = "PB"

    private var room: String? = null
    private var detail = ""
    private var pb = false
    private var shownAt = 0L

    fun reset() {
        room = null
    }

    /**
     * [ticks] is the local player's own time in the room — the same number that goes into the
     * history, not the party's total, so the popup and the record can never disagree.
     *
     * The line is built here and not in [render] for the reason every HUD string in this mod is:
     * `render` runs up to two hundred and forty times a second and this runs once per room. The
     * duration goes through [Format] so it is the same `m:ss.t` the HUD rows and `/sa` use — this
     * file's own `%.1fs` was one of the four dialects that class exists to collapse.
     */
    fun show(room: String, secrets: Boolean, ticks: Int, pb: Boolean) {
        if (!Config.clearPopup) return
        this.room = room
        this.detail = detail(secrets, ticks)
        this.pb = pb
        shownAt = System.currentTimeMillis()
    }

    /**
     * The second half of the line: which of the two things finished, and how long it took.
     *
     * A function rather than two string templates so the gallery's scripted popup reads exactly what a
     * real one reads. A preview with its own wording is a preview that cannot catch a wording change.
     */
    internal fun detail(secrets: Boolean, ticks: Int): String =
        "${if (secrets) "secreted" else "cleared"} in ${Format.ticks(ticks)}"

    /**
     * Whether the popup's whole life is behind it, for an age of [ageMs] and a fade of [fadeMs].
     *
     * Separate from [opacity] because the two answer different questions and only look alike: an age
     * of zero is also fully transparent, and folding the two together would clear the popup on the
     * first frame of its own entrance.
     */
    internal fun expired(ageMs: Long, fadeMs: Int): Boolean = ageMs > HOLD_MS + fadeMs

    /**
     * How present the popup is at [ageMs], `0f..1f` — rise, hold, fall.
     *
     * The fall is [Easing.STANDARD] inverted rather than [Easing.EXIT], which is the curve an exiting
     * element normally takes. `EXIT` accelerates: it holds near full opacity for four fifths of the
     * window and then drops in the last handful of frames, which is precisely the "vanished mid-frame,
     * looks like the mod fell over" artefact this fade exists to avoid. Inverted `STANDARD` leaves
     * quickly and lands softly on zero, which is what "something ended" looks like.
     *
     * Both durations arrive already resolved by [Motion.duration], so reduce-motion is honoured
     * without this function knowing the setting exists — and a resolved duration of zero is a legal
     * input meaning "no animation", handled rather than divided by.
     */
    internal fun opacity(ageMs: Long, riseMs: Int, fadeMs: Int): Float {
        if (ageMs < 0L) return 0f

        val leaving = ageMs - HOLD_MS
        if (leaving >= fadeMs) return 0f
        val falling = if (leaving <= 0L) 1f else 1f - Easing.STANDARD.ease(leaving.toFloat() / fadeMs)

        val rising = if (riseMs <= 0 || ageMs >= riseMs) 1f else Easing.ENTRANCE.ease(ageMs.toFloat() / riseMs)

        // The lower of the two, so a fade window short enough to overlap the entrance still ends at
        // zero instead of the two curves fighting over the same frames.
        return if (rising < falling) rising else falling
    }

    fun render(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int) {
        val name = room ?: return

        val age = System.currentTimeMillis() - shownAt
        if (expired(age, Motion.duration(FADE_MS, Motion.Kind.OPACITY))) {
            room = null
            return
        }
        drawAt(graphics, font, screenWidth, screenHeight, name, detail, pb, age)
    }

    /**
     * The popup as it looks at [ageMs] of its life, on a screen of the given size.
     *
     * Split out of [render] so the gallery can drive it from a scripted clock instead of from the
     * fields above. Nothing else would do: the state here is set by a real room clearing, the age is
     * wall-clock, and a preview that called [show] would both need a dungeon and leave a popup queued
     * for the live HUD. This takes the four things a popup is and draws them, and owns no state at all
     * — which also means the gallery can hold, step and rewind it, and the real one cannot tell.
     */
    internal fun drawAt(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        name: String,
        detail: String,
        pb: Boolean,
        ageMs: Long,
    ) {
        val rise = Motion.duration(RISE_MS, Motion.Kind.OPACITY)
        val fade = Motion.duration(FADE_MS, Motion.Kind.OPACITY)
        val appear = opacity(ageMs, rise, fade)
        if (appear <= 0.01f) return

        // Measured before anything is placed, because the chip is centred and every piece inside it
        // is positioned from the left edge that measurement decides.
        //
        // The chevron and the badge are fixed — they are there or they are not — so the screen's limit
        // falls entirely on the two text runs, and the detail is served first. That order is the
        // judgement here: the popup fires because *you* just finished *this* room, so which room it
        // was is the half you already know and how long it took is the half that is news. The badge is
        // never given up at all; a record that arrives without its `PB` is the one reading this whole
        // chip exists to deliver.
        val markWidth = if (pb) Glyphs.SIZE + Tokens.SPACE_6 else 0
        val badgeWidth = if (pb) Tokens.SPACE_8 + ScaledText.width(font, BADGE) else 0

        val budget = textBudget(screenWidth, markWidth, badgeWidth)
        val fittedDetail = ScaledText.fit(font, detail, budget)
        val detailWidth = ScaledText.width(font, fittedDetail)
        val fittedName = ScaledText.fit(font, name, budget - detailWidth)
        val nameWidth = ScaledText.width(font, fittedName)

        val width = PAD_X * 2 + markWidth + nameWidth + Tokens.SPACE_8 + detailWidth + badgeWidth
        val height = ScaledText.HEIGHT + PAD_Y * 2
        val left = leftEdge(screenWidth, width)
        val top = screenHeight / 2 + OFFSET_Y - PAD_Y
        val textTop = top + PAD_Y

        // A chip, not a card: this is an event passing through, not a surface anything else sits on.
        // The radius is resolved once and passed on, because `topHighlight` measures a full radius
        // against the width — on a lozenge that resolves to half the *width* and the highlight
        // collapses to nothing.
        val radius = Surface.resolveRadius(Tokens.RADIUS_CHIP, width, height)
        Surface.roundedFill(
            graphics, left, top, width, height, radius,
            Tokens.alpha(Tokens.scrim, Math.round(scrimAlpha() * appear)),
        )
        Surface.roundedBorder(
            graphics, left, top, width, height, radius,
            Tokens.fade(if (pb) Tokens.borderStrong else Tokens.borderSubtle, appear),
        )
        Surface.topHighlight(graphics, left, top, width, radius, Tokens.fade(Tokens.highlight, appear))

        // Everything above is drawn in GUI space and everything below that is text enters the scaled
        // pose one run at a time — see ScaledText for why a border must never be inside that scope.
        var x = left + PAD_X
        if (pb) {
            Glyphs.chevron(
                graphics, x, textTop + (ScaledText.HEIGHT - Glyphs.SIZE) / 2, true,
                Tokens.fade(Tokens.textPrimary, appear),
            )
            x += markWidth
        }

        ScaledText.draw(graphics, font, fittedName, x, textTop, Tokens.fade(Tokens.textPrimary, appear))
        x += nameWidth + Tokens.SPACE_8
        ScaledText.draw(graphics, font, fittedDetail, x, textTop, Tokens.fade(Tokens.textSecondary, appear))
        if (pb) {
            x += detailWidth + Tokens.SPACE_8
            ScaledText.draw(graphics, font, BADGE, x, textTop, Tokens.fade(Tokens.textPrimary, appear))
        }
    }

    /**
     * The GUI pixels the room name and the detail have to share on a [screenWidth]-wide screen.
     *
     * Everything else on the chip is fixed — two paddings, the gap between the two runs, and the
     * chevron and badge when there is a record — so this is what is left, and never less than nothing.
     *
     * There was no budget at all before this. The chip took whatever width its text asked for and was
     * centred by `(screenWidth - width) / 2`, so a wide line simply produced a **negative** left edge:
     * at GUI scale 4 on a 1366×768 window the screen is 342 px, and `Cobble Wall Pillar` — the longest
     * name in Odin's database — with its detail and a `PB` measures about 439 px at this scale. The
     * name ran off the left of the screen and the badge off the right, which is the one reading the
     * player was owed.
     */
    internal fun textBudget(screenWidth: Int, markWidth: Int, badgeWidth: Int): Int =
        (screenWidth - PAD_X * 2 - markWidth - Tokens.SPACE_8 - badgeWidth).coerceAtLeast(0)

    /**
     * Where a chip [width] wide starts on a [screenWidth]-wide screen: centred, and never off the left.
     *
     * The clamp is the second half of the fix and not a redundant one. [textBudget] keeps the text
     * inside the screen, but the padding and the chevron are not text and a screen narrower than those
     * alone still exists — a 60 px GUI-scaled window is legal and vanilla will hand it over.
     */
    internal fun leftEdge(screenWidth: Int, width: Int): Int = ((screenWidth - width) / 2).coerceAtLeast(0)
}
