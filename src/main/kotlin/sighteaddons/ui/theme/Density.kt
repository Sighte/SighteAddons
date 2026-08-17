package sighteaddons.ui.theme

/**
 * The GUI-pixel ↔ device-pixel mapping, and the one place a hairline's thickness is decided.
 *
 * Deliberately free of Minecraft types — [beginFrame] takes four ints so the whole of this can be
 * unit-tested without a window, a GL context or a running client. The render path reads
 * `window.width`, `window.height`, `window.guiScaledWidth`, `window.guiScaledHeight` and hands them
 * over.
 *
 * ### Why not `Window.getGuiScale()`
 *
 * Because it is not the scale that is actually applied. Vanilla computes
 * `guiScaledWidth = ceil(framebufferWidth / guiScale)`, so the effective ratio is
 * `framebufferWidth / guiScaledWidth` — always at or below the nominal scale, usually fractional, and
 * **different on each axis**. At 1366×768 with GUI scale 4 the real values are `sx = 1366/342 =
 * 3.9942` and `sy = 768/192 = 4.0`. Using the nominal 4.0 for x drifts 0.15%, which across a 420 px
 * panel is 0.6 device pixels: enough that the panel's left border lands on a pixel and its right
 * border falls between two and disappears. That is the bug that makes people give up on hairlines.
 */
internal object Density {

    /** Device pixels per GUI pixel, horizontally. */
    var scaleX = 1f
        private set

    /** Device pixels per GUI pixel, vertically. Not always equal to [scaleX]. */
    var scaleY = 1f
        private set

    /**
     * Hairline thickness in device pixels.
     *
     * One, per the spec — a border is one physical pixel at every GUI scale and never a fat slab.
     * This is a `var` on purpose: at GUI scale 4 a true single device pixel sits beside text rendered
     * 36 device pixels tall, and whether that reads as crisp or as broken is a judgement that can
     * only be made by looking at it. If it needs to become `max(1, scale / 2)`, this is the one line
     * that changes and no call site is touched.
     */
    var hairline = 1
        private set

    /**
     * The screen in GUI pixels — what anything anchored to an edge has to measure against.
     *
     * Kept here because this is already the one place per frame that is handed the window's real
     * dimensions, and because the HUD element callback is not: it receives a `GuiGraphics` and a
     * `DeltaTracker` and nothing that says how big the screen is. The alternative is a
     * `Minecraft.getInstance()` inside the render path, which would put a client lookup into code that
     * is deliberately reachable from a unit test.
     *
     * Zero until the first [beginFrame], and zero again for the frames around a window minimise on
     * Windows. Callers treat that as "no screen", never as a screen of zero width.
     */
    var guiWidth = 0
        private set

    /** The screen in GUI pixels, vertically. See [guiWidth]. */
    var guiHeight = 0
        private set

    /** Call once per frame, before anything draws. */
    fun beginFrame(framebufferWidth: Int, framebufferHeight: Int, guiScaledWidth: Int, guiScaledHeight: Int) {
        scaleX = scaleFor(framebufferWidth, guiScaledWidth)
        scaleY = scaleFor(framebufferHeight, guiScaledHeight)
        guiWidth = guiScaledWidth
        guiHeight = guiScaledHeight
    }

    /**
     * The effective device scale for one axis, from the framebuffer size and the GUI-scaled size.
     *
     * Guards a zero denominator: `guiScaledWidth` is zero for the frames around a window minimise on
     * Windows, and a division there would poison every coordinate on screen with `Infinity`.
     */
    fun scaleFor(framebufferPx: Int, guiScaledPx: Int): Float =
        if (guiScaledPx <= 0 || framebufferPx <= 0) 1f else framebufferPx.toFloat() / guiScaledPx

    /**
     * How far the pose origin must move so that it lands exactly on a device-pixel boundary.
     *
     * Applied as a translation *before* scaling into device space. Without it a hairline sits at a
     * fractional device position and covers one pixel or none depending on where the pixel centre
     * happens to fall — there is no antialiasing on the GUI path — so borders blink in and out as the
     * window is resized, and a centred panel's top rule is present at one window width and gone at
     * the next odd one.
     */
    fun snapOffset(translation: Float, scale: Float): Float =
        Math.round(translation * scale) / scale - translation

    /** A GUI-space coordinate in device pixels, rounded to the nearest whole device pixel. */
    fun deviceX(guiX: Float): Int = Math.round(guiX * scaleX)

    /** A GUI-space coordinate in device pixels, rounded to the nearest whole device pixel. */
    fun deviceY(guiY: Float): Int = Math.round(guiY * scaleY)
}
