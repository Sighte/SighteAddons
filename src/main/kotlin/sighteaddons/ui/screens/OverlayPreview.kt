package sighteaddons.ui.screens

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import sighteaddons.ClearPopup
import sighteaddons.StormHud
import sighteaddons.StormTimer
import java.util.Locale

/**
 * A scripted boss phase, for looking at the two centred overlays without one.
 *
 * Same argument as [HudPreview] and a sharper case of it. A personal best needs a dungeon, a record
 * and a room finished faster than it; Storm's countdown needs Storm. `runClient` reaches none of that,
 * so between the moment these two were rebuilt on the design system and the moment somebody plays an
 * M7 with an unreleased build, nothing would have looked at them at all — and both carry a decision
 * that only exists because the colour was taken away. The best used to be gold and is now a chevron, a
 * word and a heavier frame; the storm's four steps used to be four hues and are now a count of filled
 * marks, a frame weight, and an inversion at the end. Neither claim is worth anything unread.
 *
 * The script runs both at once for one stretch on purpose. They are drawn from the same call site in
 * the same frame and sit on opposite sides of the crosshair, and "they cannot really collide" is an
 * argument, not an observation.
 *
 * Timings are the real ones. The popup's life comes from [ClearPopup.LIFE_MS] rather than a number
 * copied here, and the countdown is handed to [StormTimer.readout] — the same total function the live
 * readout goes through — so the four steps land where the thresholds actually put them and not where
 * this file thinks they should.
 */
internal object OverlayPreview {

    /** One full pass. Long enough for the whole countdown, both popups, and a beat of quiet. */
    const val CYCLE_MS = 13_000.0

    private const val MS_PER_TICK = 50.0

    /**
     * The two inherited tick counts, as defaults rather than as [sighteaddons.Config].
     *
     * Reading the settings would make the cycle length depend on them, and a countdown the user had
     * set to twenty seconds would run off the end of the script. The point here is the four steps and
     * how they are told apart, which is the same at any duration.
     */
    const val COUNTDOWN_TICKS = 138
    const val SHOOT_TICKS = 20

    /** When the readout goes away, in milliseconds — the countdown plus its `SHOOT NOW` hold. */
    const val STORM_END_MS = (COUNTDOWN_TICKS + SHOOT_TICKS) * MS_PER_TICK

    /** One scripted popup: when it is shown, and the three things [ClearPopup.drawAt] draws. */
    class Popup(val at: Double, val name: String, val detail: String, val pb: Boolean)

    /**
     * An ordinary clear, then one that set a record.
     *
     * Both are needed and in that order: the plain chip is what the emphasised one has to be different
     * *from*, and a reviewer who only ever saw the best has no way to judge whether the difference
     * reads. The first also overlaps the countdown, which is the only place the two overlays are on
     * screen together.
     */
    val POPUPS = arrayOf(
        Popup(3_400.0, "Water Board", ClearPopup.detail(secrets = false, ticks = 824), pb = false),
        Popup(8_600.0, "Catwalk", ClearPopup.detail(secrets = true, ticks = 304), pb = true),
    )

    /** [elapsedMs] folded into one cycle. Negative input is stepping backwards, which the gallery does. */
    fun phase(elapsedMs: Double): Double = ((elapsedMs % CYCLE_MS) + CYCLE_MS) % CYCLE_MS

    /** What the storm readout says at [ms] into the cycle, or null once it is over. */
    fun stormAt(ms: Double): StormTimer.Readout? =
        StormTimer.readout((ms / MS_PER_TICK).toLong(), COUNTDOWN_TICKS, SHOOT_TICKS)

    /** The popup on screen at [ms], or null. They never overlap each other, so the first hit wins. */
    fun popupAt(ms: Double): Popup? =
        POPUPS.firstOrNull { ms >= it.at && ms < it.at + ClearPopup.LIFE_MS }

    /** How far into its life the popup on screen is. Only meaningful for one [popupAt] returned. */
    fun ageOf(popup: Popup, ms: Double): Long = (ms - popup.at).toLong()

    /**
     * Both overlays as the script has them at [ms], onto a stand-in screen of ([screenWidth],
     * [screenHeight]).
     *
     * Draws through the real files rather than a copy of them: [StormHud.draw] and [ClearPopup.drawAt]
     * are the same functions the game calls, one seam further in than the switches and the world
     * clock. A preview that redrew the chips itself would agree with the game exactly until the day
     * somebody changed one of them.
     */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int, ms: Double) {
        stormAt(ms)?.let { StormHud.draw(graphics, font, screenWidth, screenHeight, it) }
        popupAt(ms)?.let {
            ClearPopup.drawAt(
                graphics, font, screenWidth, screenHeight,
                it.name, it.detail, it.pb, ageOf(it, ms),
            )
        }
    }

    /** What is on screen right now, in words, so a held frame can be named rather than guessed at. */
    fun caption(ms: Double): String {
        val storm = stormAt(ms)?.urgency?.name?.lowercase() ?: "over"
        val popup = popupAt(ms)
        val popupNote = if (popup == null) {
            "none"
        } else {
            "%s  %.1fs of %.1fs".format(
                Locale.ROOT, popup.name,
                ageOf(popup, ms) / 1000.0, ClearPopup.LIFE_MS / 1000.0,
            )
        }
        return "storm · $storm      popup · $popupNote"
    }
}
