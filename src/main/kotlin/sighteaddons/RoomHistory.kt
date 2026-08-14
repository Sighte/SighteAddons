package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Permanent, append-only history of the local player's room times.
 *
 * Every completed room is appended as one line and **nothing is ever overwritten or removed** — a
 * personal best is simply the minimum over the history, rebuilt in memory at startup. That keeps the
 * full progression available for later analysis instead of collapsing it to a single number, and it
 * removes any chance of the record file and the history disagreeing.
 *
 * Only the local player is recorded. Teammates' times are announced in chat but not stored: in a
 * party-finder group they are strangers, so storing them would grow the file without any use.
 *
 * Two metrics, one file, told apart by the line's `kind`. A [CLEAR] is *time spent in the room*
 * until it turned cleared — not run-relative time, because that is what a player can influence and
 * it stays comparable across runs and floors. A [SECRETS] line is the room's secret run: first
 * secret to last, timed from the secrets themselves rather than from your arrival.
 */
object RoomHistory {
    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/history.jsonl")

    /** Time spent in the room until it turned cleared. */
    const val CLEAR = "clear"

    /**
     * The secret run: the room's first secret to its last.
     *
     * Deliberately not the old `secrets` kind, whose ticks were how long you had been in the room
     * when it turned green. Reusing that name for a shorter measurement would let every new run beat
     * every old entry and announce it as a personal best. The old lines stay in the file — nothing
     * here is ever rewritten — they are simply no longer read.
     */
    const val SECRETS = "secretrun"

    /** The kinds still read back. A line of any other kind stays in the file — see [SECRETS]. */
    private val KINDS = setOf(CLEAR, SECRETS)

    /** "<room>|<kind>" -> the record and how it was reached. Derived from [FILE] at startup. */
    private val best = HashMap<String, Record>()

    /**
     * "<room>|<kind>" -> every attempt, in the order the file has them. The record is the minimum of
     * these; keeping the rest is what lets the `/sa` detail line draw a room's progression and its
     * best time per floor without widening the file format — all of it has been written since the
     * first version and was simply dropped on the way in.
     *
     * ponytail: whole history in memory. ~40 bytes per line, so a heavy account's 20k lines cost
     * under a megabyte. Cap it or read the file on demand if it ever reaches six figures.
     */
    private val log = HashMap<String, MutableList<Attempt>>()

    private val newThisRun = mutableListOf<String>()

    private var writer: BufferedWriter? = null
    private var loaded = false
    private var entries = 0

    /**
     * One room's record: the fewest ticks ever recorded, how many times it was completed at all, and
     * when that last happened. Runs and timestamp are what the `/sa` records table shows next to the
     * time — a record without them says nothing about whether it is one lucky run or a routine.
     */
    data class Record(val ticks: Int, val runs: Int, val lastTs: Long) {
        fun plus(ticks: Int, ts: Long) = Record(minOf(this.ticks, ticks), runs + 1, maxOf(lastTs, ts))
    }

    /**
     * One completed room, as the file has it. [pb] is whether it beat the record *at the time it was
     * written* — a fact about that run, which is exactly what the progression wants to mark.
     * [floor] is "?" for a line written before the floor was known.
     */
    data class Attempt(val ticks: Int, val ts: Long, val floor: String, val pb: Boolean)

    /** [fold]'s result. Malformed lines are counted rather than dropped silently. */
    internal class Records(
        val byKey: Map<String, Record>,
        val attempts: Map<String, List<Attempt>>,
        val malformed: Int,
    ) {
        /** Every valid line raises exactly one key's run count, so the total needs no own counter. */
        val entries get() = byKey.values.sumOf { it.runs }
    }

    fun startRun() {
        ensureLoaded()
        newThisRun.clear()
    }

    fun newBestsThisRun(): List<String> = newThisRun

    /** For the `/sa` records table. Keyed "<room>|<kind>", floors collapsed. */
    fun records(): Map<String, Record> {
        ensureLoaded()
        return best
    }

    /**
     * The rooms the records table has a line for.
     *
     * Filtered by kind rather than taken from every key: a room whose only history line is a retired
     * kind — `secrets`, from before it became [SECRETS] — has no record in either column the table
     * draws, and would otherwise get a row of nothing but dashes and a run count of 0.
     */
    internal fun roomsWithRecords(keys: Set<String>): List<String> =
        keys.filter { it.substringAfter('|') in KINDS }.map { it.substringBefore('|') }.distinct()

    /** Every recorded attempt at one room and kind, oldest first. For the `/sa` detail line. */
    fun attempts(room: String, kind: String): List<Attempt> {
        ensureLoaded()
        return log["$room|$kind"] ?: emptyList()
    }

    /** Lines in the history file, i.e. completed rooms — not records. */
    fun entryCount(): Int {
        ensureLoaded()
        return entries
    }

    /**
     * A room turned cleared.
     *
     * Announces who did it and how long they were in the room. Credit goes to whoever spent the most
     * time there — with several players present, one readable line beats one line per player.
     */
    fun onRoomCleared(room: TrackedRoom) {
        val eligible = room.ticks.filterValues { it >= ContributionTracker.MIN_TICKS }
        val (topPlayer, topTicks) = eligible.maxByOrNull { it.value } ?: return

        val ownTicks = Minecraft.getInstance().player?.name?.string?.let { room.ticks[it] } ?: 0
        // Appended first and unconditionally: the chat settings below hide the message, never the
        // record. A silenced chat that also stopped writing history would lose runs for good.
        val pb = if (ownTicks >= ContributionTracker.MIN_TICKS) record(room, CLEAR, ownTicks) else null

        // Same bar the history uses, so the popup shows exactly the rooms that were just recorded —
        // and it is independent of the chat settings, being a different channel with its own switch.
        val name = room.name
        if (name != null && ownTicks >= ContributionTracker.MIN_TICKS) {
            ClearPopup.show(name, secrets = false, ownTicks, pb != null)
        }

        if (!Config.roomMessages) return
        if (Config.ownPbsOnly && pb == null) return

        val line = Component.literal(topPlayer).withStyle(ChatFormatting.WHITE)
            .append(Component.literal(" cleared ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(room.label()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(DungeonGrid.formatTicks(topTicks)).withStyle(ChatFormatting.AQUA))

        if (eligible.size > 1) {
            line.append(Component.literal(" (+${eligible.size - 1})").withStyle(ChatFormatting.DARK_GRAY))
        }
        pb?.let { line.append(it) }
        announce(line)
    }

    /**
     * The room's secret run finished: first secret to last, from [TrackedRoom.onSecret].
     *
     * Unlike a clear this belongs to the room, not to one player — the clock runs from the first
     * secret to the last no matter whose hands took them, so the line names the room instead of
     * crediting somebody this client cannot identify. What *is* known is how many of them were
     * yours, and that rides along.
     */
    fun onSecretRun(room: TrackedRoom) {
        val name = room.name ?: return
        val ticks = room.secretRunTicks ?: return

        val pb = record(room, SECRETS, ticks)
        ClearPopup.show(name, secrets = true, ticks, pb != null)

        if (!Config.roomMessages) return
        if (Config.ownPbsOnly && pb == null) return

        announce(
            Component.literal(name).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(" secrets in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(DungeonGrid.formatTicks(ticks)).withStyle(ChatFormatting.AQUA))
                .append(
                    Component.literal(" (${room.secretsFound}, ${room.ownSecrets} yours)")
                        .withStyle(ChatFormatting.DARK_GRAY),
                )
                .apply { pb?.let { append(it) } },
        )
    }

    /**
     * Appends one line for the local player and returns the chat suffix if it beat the record.
     * [ticks] is whatever [kind] measures: time in the room for a clear, the secret run for secrets.
     */
    private fun record(room: TrackedRoom, kind: String, ticks: Int): Component? {
        val roomName = room.name ?: return null
        ensureLoaded()

        val key = "$roomName|$kind"
        val previous = best[key]?.ticks
        val improved = previous == null || ticks < previous
        val ts = System.currentTimeMillis()

        append(room, roomName, kind, ticks, improved, ts)
        // plus() keeps the minimum, so one path covers both a new record and an ordinary run.
        best[key] = best[key]?.plus(ticks, ts) ?: Record(ticks, 1, ts)
        // Mirrors the line just written, so the room you finished a moment ago is already in its
        // progression without re-reading the file.
        log.getOrPut(key) { mutableListOf() }.add(Attempt(ticks, ts, DungeonSession.floor ?: "?", improved))
        if (!improved) return null

        newThisRun.add("$roomName $kind ${DungeonGrid.formatTicks(ticks)}")
        val improvement = if (previous == null) "first record" else "was ${DungeonGrid.formatTicks(previous)}"
        return Component.literal("  PB").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" ($improvement)").withStyle(ChatFormatting.DARK_GRAY))
    }

    /** Chat breakdown at the end of a run. */
    fun printSummary() {
        if (!Config.runSummary) return
        val points = ContributionTracker.pointsByPlayer()
        announce(
            Component.literal("Sighte ").withStyle(ChatFormatting.GOLD)
                .append(
                    Component.literal(
                        "${DungeonSession.floor ?: "?"} — ${DungeonGrid.formatTicks(DungeonSession.runTicks)}, " +
                            "${ContributionTracker.roomsCleared} rooms",
                    ).withStyle(ChatFormatting.GRAY),
                ),
        )

        val rooms = ContributionTracker.visitedRooms()
        val self = Minecraft.getInstance().player?.name?.string
        PartyTracker.roster()
            .map { it.name to (points[it.name] ?: 0.0) }
            .sortedByDescending { it.second }
            .forEach { (name, earned) ->
                val contributed = rooms.count { (it.ticks[name] ?: 0) >= ContributionTracker.MIN_TICKS }
                // Only the local player's secrets are provable. Hypixel reports secrets per room and
                // only for the room you are standing in, so a teammate's count would be a guess —
                // they get a dash instead, the same way the history refuses an estimated middle number.
                val secrets = if (name == self) rooms.sumOf { it.ownSecrets } else null
                announce(
                    Component.literal("  %5.2f".format(Locale.ROOT, earned)).withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("  $name ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(breakdown(contributed, secrets)).withStyle(ChatFormatting.DARK_GRAY)),
                )
            }

        // Was `> 0.01` against an inline subtraction — the same guard against the same split residue
        // that the run report clamps, written twice and agreeing by coincidence. The figure is now a
        // count of rooms rather than a subtraction of a score from a count, so there is no residue
        // left to guard against and "worth printing" is exactly "not zero". `%.2f` stays: it is what
        // the run report ships and what this line has always read as, and the field is a real number
        // to everything downstream even where this side of it produces whole ones.
        //
        // Rooms, deliberately, not points — a room nobody was in is one unattributed room whatever
        // it was worth. See ContributionTracker.unattributed.
        val unattributed = ContributionTracker.unattributed()
        if (unattributed > 0.0) {
            announce(
                Component.literal("  %.2f rooms unattributed".format(Locale.ROOT, unattributed))
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
        }
        // Secrets now ride on the player lines above, so this one carries the records alone rather
        // than repeating your own count next to them.
        announce(
            Component.literal(
                if (newThisRun.isEmpty()) "  no new records" else "  ${newThisRun.size} new records",
            ).withStyle(if (newThisRun.isEmpty()) ChatFormatting.DARK_GRAY else ChatFormatting.GOLD),
        )
    }

    /**
     * The per-player breakdown behind the points: rooms they were in long enough to earn from, and
     * the secrets they found. [secrets] is null for a teammate, whose secrets this client cannot see.
     */
    internal fun breakdown(rooms: Int, secrets: Int?) =
        "($rooms rooms · ${secrets?.toString() ?: "–"} secrets)"

    private fun append(room: TrackedRoom, roomName: String, kind: String, ticks: Int, pb: Boolean, ts: Long) {
        val obj = JsonObject()
        obj.addProperty("ts", ts)
        obj.addProperty("floor", DungeonSession.floor ?: "?")
        obj.addProperty("room", roomName)
        obj.addProperty("kind", kind)
        obj.addProperty("ticks", ticks)
        // Redundant with ticks, but this file is meant to be readable without doing the maths.
        obj.addProperty("seconds", ticks / 20.0)
        // Two separate numbers on purpose: the room total is party-wide, ownSecrets are the ones that
        // coincided with your own interaction. No estimated third number in between.
        obj.addProperty("secretsInRoom", room.secretsFound)
        obj.addProperty("ownSecrets", room.ownSecrets)
        obj.addProperty("maxSecrets", room.info?.secrets ?: -1)
        // Which room scores were in force when this line was written — the receiver's `generatedTs`,
        // or 0 for the seed values. Room points are not stored here, but they are derived from these
        // scores, and once the fetch layer exists (`scores-fetch-001`) the scores move on their
        // own between launches. A permanent, append-only file is the only place a past run's
        // weighting can still be identified afterwards. Additive: `fold` reads by key, so this is
        // invisible to every line already written and to every reader of them.
        obj.addProperty("scoresTs", RoomStats.scores.generatedTs)
        obj.addProperty("pb", pb)
        try {
            val out = writer ?: open() ?: return
            out.write(obj.toString())
            out.newLine()
            out.flush() // per line, so a crash never costs more than the room in progress
            entries++
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Failed to append room history to {}", FILE, e)
            writer = null
        }
    }

    private fun open(): BufferedWriter? {
        return try {
            Files.createDirectories(FILE.parent)
            Files.newBufferedWriter(FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND).also { writer = it }
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Could not open room history {}", FILE, e)
            null
        }
    }

    /**
     * The file is the source of truth; a record is just its minimum per room and kind. Kept pure and
     * separate from the file handling so it can be tested without a game directory.
     */
    internal fun fold(lines: Sequence<String>): Records {
        val out = HashMap<String, Record>()
        val attempts = HashMap<String, MutableList<Attempt>>()
        var malformed = 0
        for (line in lines) {
            if (line.isBlank()) continue
            try {
                val obj = JsonParser.parseString(line).asJsonObject
                val key = "${obj["room"].asString}|${obj["kind"].asString}"
                val ticks = obj["ticks"].asInt
                val ts = obj["ts"].asLong
                out[key] = out[key]?.plus(ticks, ts) ?: Record(ticks, 1, ts)
                // Optional: lines written before these fields existed are still valid history.
                attempts.getOrPut(key) { mutableListOf() }.add(
                    Attempt(ticks, ts, obj["floor"]?.asString ?: "?", obj["pb"]?.asBoolean ?: false),
                )
            } catch (_: Exception) {
                malformed++
            }
        }
        return Records(out, attempts, malformed)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.exists(FILE)) return
        try {
            val records = Files.newBufferedReader(FILE).useLines { fold(it) }
            best.putAll(records.byKey)
            records.attempts.forEach { (key, list) -> log[key] = list.toMutableList() }
            entries = records.entries
            SighteAddons.LOGGER.info("Room history: {} entries, {} records{}", entries, best.size,
                if (records.malformed > 0) " (${records.malformed} unreadable lines skipped)" else "")
        } catch (e: Exception) {
            // Never let a broken history cost the run. Nothing is truncated — we only append.
            SighteAddons.LOGGER.error("Could not read room history {}", FILE, e)
        }
    }

    private fun announce(text: MutableComponent) {
        val client = Minecraft.getInstance()
        client.schedule { client.gui.chat.addClientSystemMessage(text) }
    }
}
