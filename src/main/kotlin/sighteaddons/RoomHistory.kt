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
 * The metric is *time spent in the room* until the event, not run-relative time — that is what a
 * player can influence, and it stays comparable across runs and floors.
 */
object RoomHistory {
    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/history.jsonl")

    /** "<room>|<kind>" -> the record and how it was reached. Derived from [FILE] at startup. */
    private val best = HashMap<String, Record>()
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

    /** [fold]'s result. Malformed lines are counted rather than dropped silently. */
    internal class Records(val byKey: Map<String, Record>, val malformed: Int) {
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

    /** Lines in the history file, i.e. completed rooms — not records. */
    fun entryCount(): Int {
        ensureLoaded()
        return entries
    }

    /**
     * Called once when a room turns cleared, and once more when all its secrets are found.
     *
     * Announces who did it and how long they were in the room. Credit goes to whoever spent the most
     * time there — with several players present, one readable line beats one line per player.
     */
    fun onRoomEvent(room: TrackedRoom, secrets: Boolean) {
        val eligible = room.ticks.filterValues { it >= ContributionTracker.MIN_TICKS }
        val (topPlayer, topTicks) = eligible.maxByOrNull { it.value } ?: return

        // Appended first and unconditionally: the chat settings below hide the message, never the
        // record. A silenced chat that also stopped writing history would lose runs for good.
        val pb = recordOwn(room, secrets)

        // Same bar the history uses, so the popup shows exactly the rooms that were just recorded —
        // and it is independent of the chat settings, being a different channel with its own switch.
        val name = room.name
        val ownTicks = Minecraft.getInstance().player?.name?.string?.let { room.ticks[it] } ?: 0
        if (name != null && ownTicks >= ContributionTracker.MIN_TICKS) {
            ClearPopup.show(name, secrets, ownTicks, pb != null)
        }

        if (!Config.roomMessages) return
        if (Config.ownPbsOnly && pb == null) return

        val verb = if (secrets) "secreted" else "cleared"
        val line = Component.literal(topPlayer).withStyle(ChatFormatting.WHITE)
            .append(Component.literal(" $verb ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(room.label()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(DungeonGrid.formatTicks(topTicks)).withStyle(ChatFormatting.AQUA))

        if (eligible.size > 1) {
            line.append(Component.literal(" (+${eligible.size - 1})").withStyle(ChatFormatting.DARK_GRAY))
        }
        pb?.let { line.append(it) }
        announce(line)
    }

    /** Appends the local player's time for this room. Returns the chat suffix if it beat the record. */
    private fun recordOwn(room: TrackedRoom, secrets: Boolean): Component? {
        val roomName = room.name ?: return null
        val self = Minecraft.getInstance().player?.name?.string ?: return null
        val ticks = room.ticks[self] ?: return null
        if (ticks < ContributionTracker.MIN_TICKS) return null
        ensureLoaded()

        val kind = if (secrets) "secrets" else "clear"
        val key = "$roomName|$kind"
        val previous = best[key]?.ticks
        val improved = previous == null || ticks < previous
        val ts = System.currentTimeMillis()

        append(room, roomName, kind, ticks, improved, ts)
        // plus() keeps the minimum, so one path covers both a new record and an ordinary run.
        best[key] = best[key]?.plus(ticks, ts) ?: Record(ticks, 1, ts)
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
        PartyTracker.roster()
            .map { it.name to (points[it.name] ?: 0.0) }
            .sortedByDescending { it.second }
            .forEach { (name, earned) ->
                val contributed = rooms.count { (it.ticks[name] ?: 0) >= ContributionTracker.MIN_TICKS }
                announce(
                    Component.literal("  %5.2f".format(Locale.ROOT, earned)).withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("  $name ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("($contributed rooms)").withStyle(ChatFormatting.DARK_GRAY)),
                )
            }

        val unattributed = ContributionTracker.roomsCleared - points.values.sum()
        if (unattributed > 0.01) {
            announce(
                Component.literal("  %.2f rooms unattributed".format(Locale.ROOT, unattributed))
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
        }
        val ownSecrets = rooms.sumOf { it.ownSecrets }
        announce(
            Component.literal("  $ownSecrets secrets yours").withStyle(ChatFormatting.AQUA)
                .append(
                    Component.literal(
                        if (newThisRun.isEmpty()) ", no new records" else ", ${newThisRun.size} new records",
                    ).withStyle(if (newThisRun.isEmpty()) ChatFormatting.DARK_GRAY else ChatFormatting.GOLD),
                ),
        )
    }

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
        var malformed = 0
        for (line in lines) {
            if (line.isBlank()) continue
            try {
                val obj = JsonParser.parseString(line).asJsonObject
                val key = "${obj["room"].asString}|${obj["kind"].asString}"
                val ticks = obj["ticks"].asInt
                val ts = obj["ts"].asLong
                out[key] = out[key]?.plus(ticks, ts) ?: Record(ticks, 1, ts)
            } catch (_: Exception) {
                malformed++
            }
        }
        return Records(out, malformed)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.exists(FILE)) return
        try {
            val records = Files.newBufferedReader(FILE).useLines { fold(it) }
            best.putAll(records.byKey)
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
