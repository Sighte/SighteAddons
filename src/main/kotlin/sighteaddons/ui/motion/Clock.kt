package sighteaddons.ui.motion

/**
 * The one time source every animation in this UI reads.
 *
 * Two reasons it is a singleton rather than a `System.nanoTime()` call per component:
 *
 * 1. **Consistency.** Two animations started in the same frame must agree on when that frame was, or
 *    a row's fade and its slide drift apart by however long the draw between them took.
 * 2. **Cost.** `nanoTime` is a syscall on some platforms. One per frame, not one per property.
 *
 * Time is real elapsed milliseconds, never ticks — that is what makes an animation look identical at
 * 60, 144 and 240 fps. The mod's only existing animation, `ClearPopup`, already reasoned its way to
 * wall-clock time for the same reason.
 *
 * ### Why it derives rather than accumulates
 *
 * [nowMs] is computed from the wall clock minus however long the game has been paused, rather than by
 * adding up per-frame deltas. That makes [frame] **idempotent within a frame**, which matters because
 * there is more than one thing that could reasonably drive it — the HUD element runs every frame, and
 * so does an open screen. Accumulating deltas would double the rate whenever both ran, and the bug
 * would look like "animations are subtly too fast on the settings screen".
 *
 * A lag spike therefore advances time honestly: an animation that was running during a two-second
 * freeze is finished when the frame finally lands, which is where it should be.
 */
internal object Clock {

    /**
     * Milliseconds since the first frame, excluding every paused stretch.
     *
     * `Double` rather than `Long`: at 240 fps a frame is 4.16 ms, and rounding that to whole
     * milliseconds puts a 4 % error on an 80 ms animation, which is visible as jitter on a fast
     * hover.
     */
    var nowMs: Double = 0.0
        private set

    private var originNanos = 0L
    private var pausedNanos = 0L
    private var pauseStartedNanos = 0L

    /**
     * Advance the clock. Call before anything reads [nowMs]; calling it more than once in a frame is
     * harmless.
     *
     * [paused] freezes time without losing it: the game being paused, or the screen being closed,
     * must not let an in-flight animation run to completion invisibly and reappear finished.
     */
    fun frame(paused: Boolean) {
        val nanos = System.nanoTime()
        if (originNanos == 0L) originNanos = nanos

        if (paused) {
            if (pauseStartedNanos == 0L) pauseStartedNanos = nanos
        } else if (pauseStartedNanos != 0L) {
            pausedNanos += nanos - pauseStartedNanos
            pauseStartedNanos = 0L
        }

        val inCurrentPause = if (pauseStartedNanos != 0L) nanos - pauseStartedNanos else 0L
        nowMs = (nanos - originNanos - pausedNanos - inCurrentPause) / 1_000_000.0
    }

    /**
     * Test seam. Drives [nowMs] directly; do not mix with [frame] in one test, since [frame]
     * recomputes from the wall clock and would discard whatever was set here.
     */
    fun resetForTest(atMs: Double = 0.0) {
        nowMs = atMs
        originNanos = 0L
        pausedNanos = 0L
        pauseStartedNanos = 0L
    }

    /** Test seam. See [resetForTest]. */
    fun advanceForTest(deltaMs: Double) {
        nowMs += deltaMs
    }
}
