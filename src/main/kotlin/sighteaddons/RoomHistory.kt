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

    /** "<room>|<kind>" -> fewest ticks ever recorded. Derived from [FILE] at startup. */
    private val best = HashMap<String, Int>()
    private val newThisRun = mutableListOf<String>()

    private var writer: BufferedWriter? = null
    private var loaded = false
    private var entries = 0

    fun startRun() {
        ensureLoaded()
        newThisRun.clear()
    }

    fun newBestsThisRun(): List<String> = newThisRun

    /**
     * Called once when a room turns cleared, and once more when all its secrets are found.
     *
     * Announces who did it and how long they were in the room. Credit goes to whoever spent the most
     * time there — with several players present, one readable line beats one line per player.
     */
    fun onRoomEvent(room: TrackedRoom, secrets: Boolean) {
        val eligible = room.ticks.filterValues { it >= ContributionTracker.MIN_TICKS }
        val (topPlayer, topTicks) = eligible.maxByOrNull { it.value } ?: return

        val verb = if (secrets) "secreted" else "cleared"
        val line = Component.literal(topPlayer).withStyle(ChatFormatting.WHITE)
            .append(Component.literal(" $verb ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(room.label()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(DungeonGrid.formatTicks(topTicks)).withStyle(ChatFormatting.AQUA))

        if (eligible.size > 1) {
            line.append(Component.literal(" (+${eligible.size - 1})").withStyle(ChatFormatting.DARK_GRAY))
        }
        recordOwn(room, secrets)?.let { line.append(it) }
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
        val previous = best[key]
        val improved = previous == null || ticks < previous

        append(room, roomName, kind, ticks, improved)
        if (improved) best[key] = ticks
        if (!improved) return null

        newThisRun.add("$roomName $kind ${DungeonGrid.formatTicks(ticks)}")
        val improvement = if (previous == null) "first record" else "was ${DungeonGrid.formatTicks(previous)}"
        return Component.literal("  PB").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" ($improvement)").withStyle(ChatFormatting.DARK_GRAY))
    }

    /** Chat breakdown at the end of a run. */
    fun printSummary() {
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

    private fun append(room: TrackedRoom, roomName: String, kind: String, ticks: Int, pb: Boolean) {
        val obj = JsonObject()
        obj.addProperty("ts", System.currentTimeMillis())
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

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.exists(FILE)) return
        var malformed = 0
        try {
            Files.newBufferedReader(FILE).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        val obj = JsonParser.parseString(line).asJsonObject
                        val key = "${obj["room"].asString}|${obj["kind"].asString}"
                        val ticks = obj["ticks"].asInt
                        entries++
                        // The file is the source of truth; the record is just its minimum.
                        if (best[key]?.let { ticks < it } != false) best[key] = ticks
                    } catch (_: Exception) {
                        malformed++
                    }
                }
            }
            SighteAddons.LOGGER.info("Room history: {} entries, {} records{}", entries, best.size,
                if (malformed > 0) " ($malformed unreadable lines skipped)" else "")
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
