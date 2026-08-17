package sighteaddons.ui

import sighteaddons.DungeonGrid
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Display formatting for durations, in one place.
 *
 * [DungeonGrid.formatTicks] stays the canonical clock — this wraps it rather than reimplementing it,
 * because the mod already had four ways to turn ticks into text (`m:ss.t`, two different `%.1fs`, and
 * a tick count) and two different ways to say "no time": `"--:--.-"` on the HUD and `"–"` in `/sa`.
 * Two dialects for the same absence is how a clear time and a secret time end up looking like
 * different kinds of number.
 *
 * [MISSING] is six characters wide, the same as `0:41.2`, so a column of times stays a column when
 * some of them are absent.
 */
internal object Format {

    /** No time. Same width as a formatted one. */
    const val MISSING = "-:--.-"

    /** A sentinel for "no value", so hot paths can use `Int` instead of a boxing `Int?`. */
    const val NONE = -1

    /** `m:ss.t`, or [MISSING] for [NONE]. */
    fun ticks(ticks: Int): String =
        if (ticks < 0) MISSING else DungeonGrid.formatTicks(ticks)

    /** The one minus sign in this mod. U+2212, never a hyphen — see [delta]. */
    const val MINUS = "−"

    /**
     * A signed split against a personal best: `−0:04.2` when faster, `+0:01.8` when slower.
     *
     * The sign is always shown. An unsigned delta forces the reader to remember which direction is
     * good, and this UI has no colour to tell them.
     *
     * **One spelling of a duration, and one minus sign.** The magnitude is [DungeonGrid.formatTicks]
     * like every other duration this mod prints — this function used to say `−2.8s`, which put a
     * fourth dialect back into the very class that exists to have collapsed the first three, and put
     * it next to `0:41.2` on the same HUD card and in the same chat line. The minus is U+2212 rather
     * than a hyphen for two reasons that both still hold: at the size the HUD draws this, a hyphen is
     * barely distinguishable from the plus it alternates with, and `Chat.FIELD` chose `·` as its
     * separator on the stated grounds that it cannot be confused with *the* minus sign. There is one,
     * and this is it. [sighteaddons.RoomHistory.pbSuffix] writes its record delta through here.
     */
    fun delta(deltaTicks: Int): String =
        if (deltaTicks < 0) MINUS + DungeonGrid.formatTicks(-deltaTicks) else "+" + DungeonGrid.formatTicks(deltaTicks)

    /**
     * A ClearPoints figure to two decimals, from [hundredths] rather than from the Double it came from.
     *
     * An Int input so it can sit behind a [Cached]: the standings are redrawn every frame and change
     * once a room, and `String.format` on a Double is a `Formatter`, an `Object[]` and a boxed argument
     * each time. [hundredths] is the rounding, kept separate so the cache key is the number the reader
     * can actually see — keying on the raw Double would rebuild the string for a change nothing renders.
     */
    fun points(hundredths: Int): String {
        val sign = if (hundredths < 0) MINUS else ""
        val magnitude = if (hundredths < 0) -hundredths else hundredths
        return String.format(Locale.ROOT, "%s%d.%02d", sign, magnitude / 100, magnitude % 100)
    }

    /** [value] rounded to hundredths, which is what [points] prints and what a cache keys on. */
    fun hundredths(value: Double): Int = Math.round(value * 100.0).toInt()

    /**
     * How long ago [ts] was, coarsely: `today`, `yesterday`, or a day count.
     *
     * A wall-clock timestamp is not a duration, but "when did I last play this" is the only question
     * anybody asks of one here, and it is a question about elapsed time — so it is written the same way
     * in the history table and in the stats overview, which is what this file exists to guarantee. It
     * used to be a private helper on `SettingsScreen`, and a second copy on the overview would have
     * been a second answer to "is a run from eleven hours ago yesterday".
     *
     * A zero [ts] is a record with no timestamp at all — a line written before the field existed — and
     * gets the word rather than "56 years ago". **The word and not [MISSING]:** that constant is a
     * clock with its digits knocked out, which is the right shape for a missing *time* and the wrong
     * one for a missing *date*. A dash where a date belongs reads as a time that failed to format.
     *
     * [now] is a parameter rather than a `currentTimeMillis()` call so the answer is pinnable.
     */
    fun ago(ts: Long, now: Long): String {
        if (ts == 0L) return "never"
        val days = TimeUnit.MILLISECONDS.toDays(now - ts)
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            else -> "${days}d ago"
        }
    }

    /**
     * A string cache for a value that changes far more slowly than the frame rate.
     *
     * The HUD clock changes twenty times a second at most; the frame runs at sixty to two hundred and
     * forty. Formatting on every frame is a `StringBuilder`, a `Formatter`, a boxed argument array and
     * a `String` per line per frame, all of it discarded — which is most of what makes a HUD render
     * path allocate. Holding the last input and returning the last output makes the steady state free.
     */
    class Cached(private val format: (Int) -> String = ::ticks) {
        private var last = Int.MIN_VALUE
        private var text = MISSING

        fun of(value: Int): String {
            if (value != last) {
                last = value
                text = format(value)
            }
            return text
        }
    }

    /**
     * [Cached] for a line that states two numbers — `3/6`, `idle 0:12.0   nav 0:41.2`.
     *
     * Its own class rather than two [Cached]s glued together with a template, because the template is
     * the allocation: caching each half and then concatenating them per frame builds exactly the string
     * the cache was there to avoid. The key is both inputs, so the line is rebuilt when either moves
     * and never otherwise.
     */
    class Cached2(private val format: (Int, Int) -> String) {
        private var lastA = Int.MIN_VALUE
        private var lastB = Int.MIN_VALUE
        private var text = MISSING

        fun of(a: Int, b: Int): String {
            if (a != lastA || b != lastB) {
                lastA = a
                lastB = b
                text = format(a, b)
            }
            return text
        }
    }
}
