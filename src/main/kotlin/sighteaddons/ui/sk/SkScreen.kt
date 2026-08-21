package sighteaddons.ui.sk

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.api.screen.SkijaOverlay
import org.blackaddons.blackskija.api.screen.SkijaScreen
import sighteaddons.ui.theme.Tokens

/**
 * The base every screen in this mod is drawn from, and the thing that guarantees one of them can
 * always be opened.
 *
 * ### Why this class exists at all
 *
 * BlackSkija's own [SkijaScreen] is nearly enough: it routes the overlay to a `draw()` callback and
 * restores whatever was there on close. What it does not do is tell you when nothing is being drawn —
 * and there are three separate ways for that to happen, none of which is an error:
 *
 * 1. **The native has not landed yet.** Skija's ~11 MB native is fetched off-thread at startup, so for
 *    the first seconds of a session — or forever, on a first launch with no network — the compositor
 *    discards every queued draw and never invokes the content callback.
 * 2. **The GPU API cannot be adapted.** The compositor clears [SkijaOverlay.enabled] and degrades.
 * 3. **A frame threw.** Same handling, same result.
 *
 * Only the second and third clear [SkijaOverlay.enabled]. The first — much the most likely, because
 * every cold start passes through it — leaves that flag reading perfectly healthy while nothing is
 * drawn at all. So a screen that trusted the flag would open onto an empty backdrop and stay there,
 * which is precisely the failure a fallback is for.
 *
 * ### So the check measures the effect, not the flag
 *
 * [draw] is called by the compositor and by nothing else. Counting the frames since it last ran
 * answers "is anything actually reaching the screen" directly, and answers it the same way for all
 * three causes above plus any future fourth. [GRACE] frames of silence is the threshold — a couple of
 * frames rather than one, because the frame a screen opens on legitimately has no draw behind it yet,
 * and flashing the fallback for 16 ms on every open would be its own bug.
 *
 * Recovery is automatic and needs no code: the moment the native lands, [draw] runs, the counter
 * resets and the fallback is gone on the next frame.
 *
 * ### The re-arm
 *
 * [SkijaOverlay.enabled] is set true every frame rather than once in `init`, so a single failed frame
 * does not disable the screen for as long as it stays open. On a GPU that genuinely cannot be adapted
 * this means we ask again each frame and are refused each frame, which costs a type check — the
 * price of the second and third causes above being indistinguishable from the recoverable first.
 */
internal abstract class SkScreen(title: Component) : SkijaScreen(title) {

    /**
     * Frames since [draw] last ran. Zero every frame in the healthy case, because the compositor
     * draws once per frame between one [extractRenderState] and the next.
     */
    private var quiet = 0

    /** Whether the Skija half is reaching the screen. Read it before deciding anything visual. */
    protected val degraded: Boolean get() = quiet > GRACE

    /**
     * Where the pointer was this frame.
     *
     * Kept as fields because [draw] takes no arguments — the compositor calls it — while hover, the
     * tooltip and every hit test need the position that arrived with [extractRenderState] earlier in
     * the same frame. One frame of latency is not introduced here: extraction and compositing are the
     * same frame.
     */
    protected var pointerX = 0
        private set

    protected var pointerY = 0
        private set

    /** Draw the screen. Called only by the compositor, so [Sk] is safe to use — see its rule 2. */
    protected abstract fun content()

    /**
     * Per-frame bookkeeping, run during extraction and therefore *before* [content].
     *
     * This is where the frame clock and the device-density snapshot belong. They used to sit in
     * `extractBackground`, which [SkijaScreen] makes final, and they have to happen on the extraction
     * pass anyway: by the time [content] runs, anything that reads them has already been asked.
     */
    protected open fun beginFrame() {}

    final override fun draw() {
        quiet = 0
        content()
    }

    /**
     * Anything that still has to be drawn through Minecraft's own renderer, on the extraction pass.
     *
     * There is exactly one caller and one reason: the placement editor draws the *real* HUD elements,
     * and the HUD has not been ported to Skija yet. Everything issued here lands **below** everything
     * [content] draws, because the Skija overlay composites as a single layer over the GUI — which
     * happens to be the right way round for that one case (the element underneath, its readout on top)
     * and would be exactly the wrong way round for anything sitting inside a panel.
     *
     * So this is a migration seam, not an extension point. When the HUD draws through [Sk] it goes.
     */
    protected open fun extractExtra(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    final override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        pointerX = mouseX
        pointerY = mouseY
        beginFrame()
        extractExtra(graphics, mouseX, mouseY, delta)

        if (degraded) fallback(graphics)

        // Incremented after the check, and reset by draw() later this frame. A healthy frame therefore
        // always sees zero here, and no arithmetic depends on how the two are ordered within the frame.
        quiet++
        SkijaOverlay.enabled = true
    }

    /**
     * The page shown while the Skija half is not reaching the screen.
     *
     * Drawn with the vanilla font through Minecraft's own renderer, which is the whole point: it is
     * the one thing on this screen that cannot itself be affected by what has gone wrong. It says what
     * is missing and what fixes it, and it says that nothing was lost — a player who opens the
     * settings and sees an explanation has a different day from one who sees a black rectangle.
     *
     * Overridable, but the default is meant to be enough for every screen in the mod.
     */
    protected open fun fallback(graphics: GuiGraphicsExtractor) {
        graphics.fill(0, 0, width, height, Tokens.surfaceBase)

        val lines = FALLBACK
        val top = (height - lines.size * LINE) / 2
        graphics.text(
            font, TITLE, (width - font.width(TITLE)) / 2, top - LINE * 2, Tokens.textPrimary, false,
        )
        for ((i, line) in lines.withIndex()) {
            val colour = if (i == 0) Tokens.textSecondary else Tokens.textTertiary
            graphics.text(font, line, (width - font.width(line)) / 2, top + i * LINE, colour, false)
        }
    }

    private companion object {

        /**
         * Frames of silence before the fallback is shown.
         *
         * Two, not one: a screen's first frame has no draw behind it by construction, and one is also
         * what a single dropped frame looks like. At any frame rate this is a few tens of
         * milliseconds, so a real failure is reported effectively instantly and a healthy open never
         * flickers.
         */
        const val GRACE = 2

        const val LINE = 12

        const val TITLE = "Sighte Addons"

        /**
         * Deliberately not a stack trace and deliberately not "an error occurred".
         *
         * The overwhelmingly common cause is the first launch on a machine that was offline, which is
         * neither the player's fault nor broken — it fixes itself on the next online start. So the
         * text names the cause, names the fix, and says the settings are untouched, because the one
         * thing a player will assume about a settings screen that will not draw is that it ate their
         * settings.
         */
        val FALLBACK = listOf(
            "this screen is drawn on the GPU, and that part is not running yet",
            "",
            "the renderer downloads once, on the first launch with a connection.",
            "if this is that first launch and it was offline, start once online.",
            "",
            "nothing was lost — every setting is exactly where you left it.",
            "esc closes this.",
        )
    }
}
