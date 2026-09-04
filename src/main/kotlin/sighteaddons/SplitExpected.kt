package sighteaddons

import com.google.gson.JsonObject

/**
 * The time the player *plans* for each split of each floor — the numbers [RunEstimate] sums.
 *
 * ### A plan, not a record, and why that is not [SplitPbs]
 *
 * [SplitPbs] holds the best seconds ever *measured* for a split; this holds the seconds the player
 * *typed* for it. The two look identical on disk — floor key, split name, a float of wall-clock
 * seconds — and folding them into one store was the obvious move that is wrong for one reason:
 * **an import must never overwrite a plan.** `/sa import` lowers a [SplitPbs] entry whenever Odin's
 * file has a faster run in it, which is correct for a record and destroys a deliberately padded
 * expectation ("I budget 1:10 for terminals because I play healer"). A record moves when the world
 * does; a plan moves when a hand does. Different owners, different stores.
 *
 * ### Wall-clock seconds, [SplitPbs]' unit
 *
 * The whole point of an expectation is being compared against measurements, and the measurements it
 * meets — [Splits.Row.ms] via [RunEstimate], and the PBs that [prefillFromPbs] copies — are wall-clock.
 * A tick-based expectation would be a number that looks like these and cannot be subtracted from them;
 * [SplitPbs] already ruled that "if it is ever wanted it gets its own key space, never this one".
 *
 * Floor keys are [SplitPbs.floorKey]'s (`DungeonF7`), delegated rather than respelled, so the two
 * stores stay diffable against each other in `config.json` and there is one derivation of the prefix.
 */
internal object SplitExpected {

    /** The config key the whole store hangs off. Sits beside `splitPbs` in `config.json`. */
    private const val KEY = "splitExpected"

    /**
     * Floor key to split name to expected seconds.
     *
     * `LinkedHashMap` at both levels for [SplitPbs]' stated reason: `config.json` is rewritten in full
     * on every save, and a store that shuffled its keys would make every diff of it unreadable.
     */
    private val expected = LinkedHashMap<String, LinkedHashMap<String, Float>>()

    /**
     * How many times this store has changed in this process. A cache key, and nothing else reads it.
     * A counter and not a count, for [SplitPbs.revision]'s reason: an edited value leaves the count
     * where it was and changes the number beside it.
     */
    var revision = 0
        private set

    /** One spelling of a floor key in this mod — [SplitPbs.floorKey]'s. */
    internal fun floorKey(floorTag: String): String = SplitPbs.floorKey(floorTag)

    /** The planned seconds for one split, or null if the player never typed one. */
    fun get(floorTag: String, split: String): Float? = expected[floorKey(floorTag)]?.get(split)

    /**
     * Files what the player typed. **Does not save**, for [SplitPbs.record]'s reason — the caller
     * knows how many fields one interaction touched and saves once after them.
     *
     * A non-positive time is refused rather than recorded: a plan of zero seconds is not a plan, and
     * [RunEstimate] summing it would print an estimate the run can only ever miss. Clearing a field is
     * [remove], which is a different intent than typing a bad number.
     */
    fun set(floorTag: String, split: String, seconds: Float) {
        if (seconds <= 0f) return
        val floor = expected.getOrPut(floorKey(floorTag)) { LinkedHashMap() }
        if (floor[split] == seconds) return
        floor[split] = seconds
        revision++
    }

    /** Clears one split's plan. An empty floor is dropped so [write] never files an empty object. */
    fun remove(floorTag: String, split: String) {
        val key = floorKey(floorTag)
        val floor = expected[key] ?: return
        if (floor.remove(split) == null) return
        if (floor.isEmpty()) expected.remove(key)
        revision++
    }

    /**
     * Copies the floor's personal bests over this floor's plan and says how many fields changed.
     *
     * **An overwrite where a PB exists, untouched where none does.** The action's meaning is "start my
     * plan from what I have actually run", and a merge-minimum (the import's semantics) would refuse to
     * *raise* an expectation the player wants raised — the whole reason plans and records are separate
     * stores. A split with no PB keeps whatever was typed, so prefilling never deletes work.
     *
     * Walks [DungeonSplits.chainFor] minus [DungeonSplits.TOTAL] rather than [SplitPbs.of], so an
     * imported store carrying a key this floor's chain never produces (a hand-edited file, a renamed
     * split) cannot plant an expectation [RunEstimate] would then sum invisibly.
     *
     * **Does not save** — the `/sa` action that calls this saves once.
     */
    fun prefillFromPbs(floorTag: String): Int {
        val chain = DungeonSplits.chainFor(floorTag) ?: return 0
        var changed = 0
        for (split in chain.dropLast(1)) {
            val pb = SplitPbs.get(floorTag, split.name) ?: continue
            if (get(floorTag, split.name) == pb) continue
            set(floorTag, split.name, pb)
            changed++
        }
        return changed
    }

    /**
     * What a typed duration means in seconds, or null for anything that is not one.
     *
     * Accepts the shapes a player would type and every shape [Format.seconds] prints, so a committed
     * field re-reads as itself: `"1:05"`, `"1:05.5"`, `"65"`, `"120"` (two minutes — bare seconds are
     * unbounded, because forcing `2:00` on someone thinking in seconds is a null they cannot see the
     * reason for). With minutes present the seconds are bounded below 60 — `"1:75"` is a typo, not
     * ninety-five seconds. A non-positive result is null for [set]'s reason. Lives here rather than in
     * [Format] because that file is display only, and a parser beside the formatters would be the
     * first arrow pointing the other way through it.
     */
    fun parse(text: String): Float? {
        val trimmed = text.trim()
        val match = PATTERN.matchEntire(trimmed) ?: return null
        val minutes = match.groupValues[1].toIntOrNull() ?: 0
        val seconds = match.groupValues[2].toFloat()
        if (minutes > 0 && seconds >= 60f) return null
        val total = minutes * 60f + seconds
        return total.takeIf { it > 0f }
    }

    /** `m:ss(.t)` with the minutes optional — one group for them, one for the seconds. */
    private val PATTERN = Regex("""(?:(\d{1,3}):)?(\d{1,4}(?:\.\d{1,3})?)""")

    // --- Persistence ------------------------------------------------------------------------

    /** Reads the store out of `config.json`. Defensive per level, for [SplitPbs.read]'s reason. */
    fun read(obj: JsonObject) {
        expected.clear()
        revision++
        val root = obj.get(KEY)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for ((floorKey, value) in root.entrySet()) {
            if (!value.isJsonObject) continue
            val floor = LinkedHashMap<String, Float>()
            for ((split, time) in value.asJsonObject.entrySet()) {
                val seconds = time.asFloatOrNull() ?: continue
                if (seconds > 0f) floor[split] = seconds
            }
            if (floor.isNotEmpty()) expected[floorKey] = floor
        }
    }

    fun write(obj: JsonObject) {
        val root = JsonObject()
        for ((floorKey, floor) in expected) {
            val out = JsonObject()
            for ((split, seconds) in floor) out.addProperty(split, seconds)
            root.add(floorKey, out)
        }
        obj.add(KEY, root)
    }

    /** A number, or null for a value of any other shape — [SplitPbs]' reader, restated privately. */
    private fun com.google.gson.JsonElement.asFloatOrNull(): Float? {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
        return try {
            asFloat
        } catch (e: NumberFormatException) {
            null
        }
    }
}
