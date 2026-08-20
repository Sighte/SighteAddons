package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The best time ever recorded for each split of each floor — Odin's `PersonalBest`, in this mod's
 * config file.
 *
 * ### One number per split, and why that is not [RoomHistory]
 *
 * [RoomHistory] is the other record store, and it is deliberately not this one. It is an append-only
 * log of every room anybody ever finished, in **run ticks**, keyed by room and metric, and its whole
 * contract is that a `kind` never changes meaning ([RoomHistory] documents that at length). A split is
 * a different measurement of a different thing: it spans the party's whole floor rather than one
 * player's time in one room, it is counted in **wall-clock seconds** rather than run ticks, and it is
 * keyed by floor rather than by room. Folding it into `history.jsonl` would either redefine an existing
 * kind or add one whose unit disagrees with every other line in the file. So it lives here, beside the
 * settings, exactly where Odin keeps it.
 *
 * ### Wall-clock seconds, and that is the deliberate choice
 *
 * [BloodClear] made the opposite one — run ticks, so its number could be compared with everything else
 * this mod stores — and said so. This goes the other way for one reason: **these keys are Odin's keys**.
 * `DungeonM7 -> blood open -> 19.6` here means the same thing as `DungeonM7 -> §2Blood Open -> 19.6` in
 * `odin-config.json`, so [importFromOdin] is a rename and nothing else, and a player running both mods
 * sees one set of records rather than two that disagree. A tick-based split record would be a second
 * number that looks like this one; if it is ever wanted it gets its own key space, never this one.
 *
 * ### Floor keys are Odin's too
 *
 * `DungeonE`, `DungeonF1`…`DungeonF7`, `DungeonM1`…`DungeonM7`. The `Dungeon` prefix is redundant while
 * this mod only times dungeons, and it is kept anyway: it is what makes the two files diffable, and it
 * is the room a Kuudra tier would need later without renaming anything that already has records in it.
 *
 * **Master mode keeps its own records** even though it shares F-floor split names ([DungeonSplits]).
 * An M7 blood clear and an F7 blood clear are not the same run.
 */
internal object SplitPbs {

    /** What a submitted time turned out to be. */
    sealed interface Result {
        /** Nothing was on record for this split before. */
        data object First : Result

        /** A record fell. [previous] is what it was. */
        data class Beat(val previous: Float) : Result

        /** The standing record held. [best] is it. */
        data class Missed(val best: Float) : Result
    }

    /** The config key the whole store hangs off. */
    private const val KEY = "splitPbs"

    /**
     * Floor key to split name to best seconds.
     *
     * `LinkedHashMap` at both levels so the written file keeps a stable order: `config.json` is
     * rewritten in full on every save, and a store that shuffled its keys would make every diff of it
     * unreadable — including the one that checks an import landed.
     */
    private val best = LinkedHashMap<String, LinkedHashMap<String, Float>>()

    /**
     * How many times this store has changed in this process. A cache key, and nothing else reads it.
     *
     * **A counter and not [count], because a beaten record leaves the count where it was** and changes
     * the number beside it — and `/sa` does not pause the game, so the table showing this store can be
     * open while a run beats one, and [importFromOdin] can lower a time from a row on another page. A
     * size is the wrong key for a store whose values move. [RunPbs.revision] is the same field for the
     * same reason.
     */
    var revision = 0
        private set

    /** Odin's key for a floor, which is this mod's too. See the class comment. */
    internal fun floorKey(floorTag: String): String = "Dungeon$floorTag"

    /** The standing record for one split, or null if there is none. */
    fun get(floorTag: String, split: String): Float? = best[floorKey(floorTag)]?.get(split)

    /**
     * Files [seconds] for a split and says what it was.
     *
     * **Does not save.** Odin's `PersonalBest.set` rewrites its whole config file per record, which for
     * a run's final line is three rewrites in a row; the caller here knows how many records a single
     * chat line produced and saves once after them ([Splits]). The mutation still happens immediately,
     * so nothing is lost if a save is skipped — the next one writes it.
     *
     * Odin's sentinel is `9999f` for "no record", which it then announces as the old PB on a first-ever
     * run. [Result.First] is the same fact without the invented number.
     *
     * A non-positive time is refused rather than recorded. Two chat lines arriving on the same
     * millisecond is the case, and it would file an unbeatable record — the same reasoning
     * [BloodClear.onDone] applies to a non-positive span.
     */
    fun record(floorTag: String, split: String, seconds: Float): Result? {
        if (seconds <= 0f) return null
        val floor = best.getOrPut(floorKey(floorTag)) { LinkedHashMap() }
        val previous = floor[split]
        if (previous == null) {
            floor[split] = seconds
            revision++
            return Result.First
        }
        if (seconds < previous) {
            floor[split] = seconds
            revision++
            return Result.Beat(previous)
        }
        return Result.Missed(previous)
    }

    /** Every floor that has at least one record, in insertion order. For the `/sa` records tab. */
    fun floors(): List<String> = best.keys.toList()

    /** One floor's records, or an empty map. */
    fun of(floorKey: String): Map<String, Float> = best[floorKey] ?: emptyMap()

    /**
     * [floorKey]'s inverse: `DungeonM7` back to `M7`, and the key unchanged for anything that is not
     * one of the fifteen floors.
     *
     * The `Dungeon` prefix is what makes this file diffable against Odin's and is not something a
     * reader wants on a heading, so the screen prints the tag. Unrecognised keys are passed through
     * rather than dropped — a hand-edited store is still somebody's records, and
     * [PbTable.order] already has a place for a floor it cannot rank.
     */
    fun tagOf(floorKey: String): String = tagOfOdinFloorKey(floorKey) ?: floorKey

    /** How many records are on file across every floor. */
    fun count(): Int = best.values.sumOf { it.size }

    /**
     * Every record, keyed by floor **tag** — the shape [PbTable.splits] reads.
     *
     * Converted here rather than at the screen so there is one place that knows both spellings of a
     * floor, which is [tagOf]'s whole reason for existing.
     */
    fun records(): Map<String, Map<String, Float>> = best.entries.associate { tagOf(it.key) to it.value }

    /** Drops everything. Only the `/sa` action calls this, and it saves afterwards. */
    fun clear() {
        best.clear()
        revision++
    }

    // --- Persistence ------------------------------------------------------------------------

    /**
     * Reads the store out of `config.json`.
     *
     * Defensive at every level, for [ConfigMigration.intOr]'s reason: hand-editing this file is
     * supported, and a malformed floor must cost that floor rather than every floor after it. A value
     * that is not a number is skipped; so is a floor whose value is not an object.
     */
    fun read(obj: JsonObject) {
        best.clear()
        revision++
        val root = obj.get(KEY)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for ((floorKey, value) in root.entrySet()) {
            if (!value.isJsonObject) continue
            val floor = LinkedHashMap<String, Float>()
            for ((split, time) in value.asJsonObject.entrySet()) {
                val seconds = time.asFloatOrNull() ?: continue
                if (seconds > 0f) floor[split] = seconds
            }
            if (floor.isNotEmpty()) best[floorKey] = floor
        }
    }

    fun write(obj: JsonObject) {
        val root = JsonObject()
        for ((floorKey, floor) in best) {
            val out = JsonObject()
            for ((split, seconds) in floor) out.addProperty(split, seconds)
            root.add(floorKey, out)
        }
        obj.add(KEY, root)
    }

    // --- Import from Odin -------------------------------------------------------------------

    /** What [importFromOdin] did. [found] false means there was no Odin config to read. */
    data class Import(val found: Boolean, val changed: Int)

    /** Where Odin keeps the file, under the same config directory this mod's own file lives in. */
    private fun odinConfig(): Path =
        FabricLoader.getInstance().configDir.resolve("odin").resolve("odin-config.json")

    /**
     * Folds the records out of a local Odin install into this store.
     *
     * **Reading another mod's config, and only when a hand asks for it.** This is wired to a `/sa`
     * action rather than to startup: a mod that silently went through a neighbour's files on every
     * launch would be doing something nobody asked for, and there is no moment during play when the
     * answer would have changed.
     *
     * **Not a table checked into this repository**, which was the other way to give a player their
     * existing records back. Two people use this mod. A seed compiled into the jar would hand the
     * second one the first one's times as their own personal bests, on a floor they had never run, and
     * they could never be beaten off the board again. Reading the Odin config that is actually on the
     * machine gives each install its own history and nobody else's.
     */
    fun importFromOdin(): Import {
        val path = odinConfig()
        if (!Files.exists(path)) return Import(found = false, changed = 0)
        val settings = try {
            splitsSettings(Files.readString(path))
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not read {}", path, e)
            null
        } ?: return Import(found = false, changed = 0)
        return Import(found = true, changed = merge(settings))
    }

    /**
     * The `settings` object of Odin's `Splits` module, out of the whole file, or null.
     *
     * Odin's config is a flat array of `{name, enabled, settings}`, one per module — the shape is
     * [`ModuleConfig`][merge]'s and this only has to find one element of it.
     */
    internal fun splitsSettings(json: String): JsonObject? {
        val root = JsonParser.parseString(json)
        if (!root.isJsonArray) return null
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val module = element.asJsonObject
            if (module.get("name")?.asStringOrNull() != "Splits") continue
            return module.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
        }
        return null
    }

    /**
     * Merges Odin's per-floor maps into [best] and returns how many records changed.
     *
     * **The merge is a minimum, not an overwrite.** Both stores hold the same quantity — the best
     * wall-clock seconds ever seen for that split — so the smaller of two candidates is the answer
     * whichever file it came from. That also makes the import idempotent: running it twice changes
     * nothing the second time, which is what stops a player wondering whether they have already done it.
     *
     * Kuudra's five tiers are in the same object and are skipped. This mod does not time Kuudra yet, and
     * writing keys nothing reads would put records in the file that no run could ever have produced.
     *
     * Pure, so `SplitPbsTest` can drive it with the real bytes of a real config.
     */
    internal fun merge(settings: JsonObject): Int {
        var changed = 0
        for ((odinFloorKey, value) in settings.entrySet()) {
            if (!value.isJsonObject) continue
            val tag = tagOfOdinFloorKey(odinFloorKey) ?: continue
            for ((odinSplitKey, time) in value.asJsonObject.entrySet()) {
                val seconds = time.asFloatOrNull() ?: continue
                if (seconds <= 0f) continue
                val name = nameOfOdinKey(odinSplitKey)
                val floor = best.getOrPut(floorKey(tag)) { LinkedHashMap() }
                val previous = floor[name]
                if (previous != null && previous <= seconds) continue
                floor[name] = seconds
                changed++
                revision++
            }
        }
        return changed
    }

    /**
     * `DungeonM7` to `M7`, and null for anything that is not one of the fifteen dungeon floors.
     *
     * Odin's own keys, so this is a check rather than a conversion — [DungeonSplits.numberOf] is what
     * says which tags exist, and asking it here is what keeps `KuudraT5` and any key Odin adds later out
     * without a second list of floor names to maintain.
     */
    internal fun tagOfOdinFloorKey(key: String): String? {
        val tag = key.removePrefix("Dungeon")
        if (tag == key) return null
        return if (DungeonSplits.numberOf(tag) == null) null else tag
    }

    /**
     * `§2Blood Open` to `blood open`, `Total` to `total`.
     *
     * Odin's split names carry a legacy colour code as the first two characters of the key itself, and
     * its Kuudra totals carry none — the same name spelled two ways in one file. Stripping the code and
     * lower-casing gives one spelling and is exactly [DungeonSplits]' naming, which is what makes the
     * import a rename rather than a translation table somebody has to keep in step.
     */
    internal fun nameOfOdinKey(key: String): String {
        val stripped = if (key.length > 2 && key[0] == '§') key.substring(2) else key
        return stripped.lowercase()
    }

    /** A number, or null for a value of any other shape — including a string that looks like one. */
    private fun com.google.gson.JsonElement.asFloatOrNull(): Float? {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
        return try {
            asFloat
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
}
