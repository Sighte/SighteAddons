package sighteaddons

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ui.hud.Glyphs
import sighteaddons.ui.render.ScaledText
import sighteaddons.ui.render.Surface
import sighteaddons.ui.theme.Tokens

/**
 * Storm's countdown, drawn centred and large — the drawing half of [StormTimer], which decides what
 * it says.
 *
 * Modelled on [ClearPopup] and for its reason rather than by imitation: this is a thing that has to
 * land while the player is looking at a boss, not a standing readout they choose to glance at. So it
 * is centred, at double size, under the crosshair, and it is not part of the corner block — it does
 * not move when the HUD is repositioned and it does not disappear when the HUD is switched off.
 *
 * **The source mod's position and scale settings did not come across, deliberately.** They existed
 * because it drew at an absolute pixel offset with no anchor, so the player had to place it by hand
 * on every GUI scale. Centring on `guiScaledWidth`/`guiScaledHeight` is what those settings were
 * being used to approximate, and a coordinate pair that has one right answer is a worse setting than
 * no setting. The two that did come across are the tick counts, because those have no right answer
 * anybody here knows — see [StormTimer].
 *
 * Below the crosshair, where [ClearPopup] is above it. The two cannot really collide (nothing is
 * cleared during a boss phase) but they are drawn from the same call site in the same frame, and
 * stacking two double-size lines on one pixel row would be a bug found by seeing it once.
 *
 * ### Four states, no colours
 *
 * The source mod said blue, yellow, red, brighter red. This design has no hue, and the neutral ramp
 * cannot stand in for one: `text.tertiary` is 1.27:1 from `text.secondary` (see `Palette`), so four
 * greys would be four states nobody can count. So urgency is carried by **how many of three marks
 * are filled** and by the weight of the chip's frame — both countable at a glance, both intact in a
 * greyscale screenshot — and the window itself is not a fourth step at all but an **inversion**: the
 * chip fills solid and the text drops out of it. Nothing else in this UI inverts, which is exactly
 * what makes it unmistakable at the one moment that matters.
 *
 * The countdown text stays at `text.primary` in all three counting states rather than stepping down
 * with the urgency. Dimming the calm state would make the number hardest to read when there is still
 * time to act on it, and the marks already carry the step.
 *
 * ### No entrance curve, unlike [ClearPopup]
 *
 * A popup announces something that has finished and can afford 140 ms of arriving. This is a warning
 * with a deadline attached, and 140 ms at the head of a 6.9 s countdown is 140 ms in which the number
 * is not yet readable. It appears at full weight and leaves the same way.
 *
 * Nothing here is testable in this repository — it is `Minecraft` calls end to end. Everything it
 * could get wrong that is not a draw call lives in [StormTimer.readout], which `StormTimerTest`
 * drives directly.
 */
internal object StormHud {

    /** Below the crosshair, clear of the hotbar at every GUI scale. */
    private const val OFFSET_Y = 24

    private const val PAD_X = Tokens.SPACE_12
    private const val PAD_Y = Tokens.SPACE_6

    /**
     * The scrim behind the countdown, matching the HUD card's and [ClearPopup]'s — the same one, read
     * from the same setting, for the reason [ClearPopup] states.
     */
    private fun scrimAlpha(): Int = Tokens.scrimAlpha(Config.hudScrim)

    /** One mark per urgency step below the window. Three, because there are three counting states. */
    private const val MARKS = 3

    // Render-path memo. `readoutAt` formats a string and allocates a Readout on every call, and this
    // runs once per frame — up to twelve times per game tick for a value that cannot change more than
    // once per tick. Same argument as `Format.Cached`, and the same shape: hold the inputs, return the
    // last answer while they are unchanged.
    //
    // The key is every input the answer depends on and not just the world time, so a `/sa` edit to
    // either tick count lands on the next frame instead of being held stale behind a clock that has
    // not ticked yet.
    private var keyWorldTime = Long.MIN_VALUE
    private var keyStart: Long? = null
    private var keyCountdown = -1
    private var keyShoot = -1
    private var cached: StormTimer.Readout? = null

    /**
     * [worldTime] is `client.level?.gameTime`, read by the caller, which already has the client in
     * hand. Null — no level — draws nothing.
     */
    fun render(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int, worldTime: Long?) {
        if (!Config.stormTimer) return
        if (worldTime == null) return
        draw(graphics, font, screenWidth, screenHeight, readout(worldTime) ?: return)
    }

    /**
     * One [readout], drawn on a screen of the given size.
     *
     * Split from [render] for the gallery, which has neither a world time nor a Storm: the four steps
     * are three seconds apart in a fight nobody can reach from a development client, so the only way
     * to see them is to hand this function a readout directly. Everything above it — the switch, the
     * world clock, the memo — is wiring; everything below is the design decision, and this is the seam
     * between them.
     */
    internal fun draw(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        readout: StormTimer.Readout,
    ) {
        val now = readout.urgency == StormTimer.Urgency.NOW

        val markWidth = if (now) 0 else MARKS * Glyphs.SIZE + Tokens.SPACE_8
        val textWidth = ScaledText.width(font, readout.text)
        val width = PAD_X * 2 + markWidth + textWidth
        val height = ScaledText.HEIGHT + PAD_Y * 2
        val left = (screenWidth - width) / 2
        val top = screenHeight / 2 + OFFSET_Y - PAD_Y
        val textTop = top + PAD_Y

        // A chip, not a card, and the radius is resolved once: `topHighlight` measures a full radius
        // against the width, which on a lozenge collapses the highlight to nothing.
        val radius = Surface.resolveRadius(Tokens.RADIUS_CHIP, width, height)
        if (now) {
            // The inversion. No border and no highlight — both are ways of suggesting an edge on a
            // surface that is barely there, and this one is entirely there.
            Surface.roundedFill(graphics, left, top, width, height, radius, Tokens.accent)
            ScaledText.draw(graphics, font, readout.text, left + PAD_X, textTop, Tokens.accentText)
            return
        }

        Surface.roundedFill(
            graphics, left, top, width, height, radius,
            Tokens.alpha(Tokens.scrim, scrimAlpha()),
        )
        Surface.roundedBorder(graphics, left, top, width, height, radius, border(readout.urgency))
        Surface.topHighlight(graphics, left, top, width, radius, Tokens.highlight)

        // Filled against hollow, never a shade of the same mark — the pairing Glyphs exists for.
        val filled = filled(readout.urgency)
        val markTop = textTop + (ScaledText.HEIGHT - Glyphs.SIZE) / 2
        for (i in 0 until MARKS) {
            val x = left + PAD_X + i * Glyphs.SIZE
            if (i < filled) {
                Glyphs.dotFilled(graphics, x, markTop, Tokens.textPrimary)
            } else {
                Glyphs.dotHollow(graphics, x, markTop, Tokens.textTertiary)
            }
        }

        // Drawn last and outside every scaled scope; see ScaledText for why the chrome above is not
        // inside one.
        ScaledText.draw(graphics, font, readout.text, left + PAD_X + markWidth, textTop, Tokens.textPrimary)
    }

    /**
     * How many of the three marks are lit. The whole ordinal signal, and the only one that counts.
     *
     * **The window lights none of them.** It is not a fourth step on this scale — it is the inversion,
     * which draws no marks at all and returns from [draw] before this is ever asked. The branch said
     * [MARKS], which read as "the window is all three marks filled" and described a chip that has never
     * been on screen; zero is what the window actually draws, so if the early return is ever taken out
     * this stays true instead of quietly starting to lie.
     */
    private fun filled(urgency: StormTimer.Urgency): Int = when (urgency) {
        StormTimer.Urgency.CALM -> 1
        StormTimer.Urgency.CLOSING -> 2
        StormTimer.Urgency.IMMINENT -> 3
        StormTimer.Urgency.NOW -> 0
    }

    /** The frame weight, which says the same thing a second time for a reader who is not counting. */
    private fun border(urgency: StormTimer.Urgency): Int = when (urgency) {
        StormTimer.Urgency.CALM -> Tokens.borderSubtle
        StormTimer.Urgency.CLOSING -> Tokens.borderDefault
        StormTimer.Urgency.IMMINENT -> Tokens.borderStrong
        StormTimer.Urgency.NOW -> Tokens.borderStrong
    }

    /** See the memo fields. Pure pass-through to [StormTimer.readoutAt] on a miss. */
    private fun readout(worldTime: Long): StormTimer.Readout? {
        val start = StormTimer.startTick
        val countdown = Config.stormCountdownTicks
        val shoot = Config.stormShootTicks
        if (worldTime != keyWorldTime || start != keyStart || countdown != keyCountdown || shoot != keyShoot) {
            keyWorldTime = worldTime
            keyStart = start
            keyCountdown = countdown
            keyShoot = shoot
            cached = StormTimer.readoutAt(worldTime)
        }
        return cached
    }
}
