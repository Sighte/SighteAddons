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

    /**
     * A signed split against a personal best, in seconds: `-4.2s` when faster, `+1.8s` when slower.
     *
     * The sign is always shown. An unsigned delta forces the reader to remember which direction is
     * good, and this UI has no colour to tell them.
     *
     * The minus is U+2212, not a hyphen: at the sizes this is read, a hyphen is barely distinguishable
     * from the plus it alternates with.
     */
    fun delta(deltaTicks: Int): String {
        val seconds = deltaTicks / 20.0
        return if (deltaTicks < 0) {
            String.format(Locale.ROOT, "−%.1fs", -seconds)
        } else {
            String.format(Locale.ROOT, "+%.1fs", seconds)
        }
    }

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
     * gets [MISSING] rather than "56 years ago".
     *
     * [now] is a parameter rather than a `currentTimeMillis()` call so the answer is pinnable.
     */
    fun ago(ts: Long, now: Long): String {
        if (ts == 0L) return MISSING
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
}
