package sighteaddons

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.util.Locale
import org.slf4j.LoggerFactory

/**
 * Entry point: samples the dungeon every tick and draws the live room timeline plus standings.
 *
 * Everything here is read-only — item map, tab list, scoreboard, blocks — plus an HUD overlay and
 * client-side chat messages. No packets are sent and nothing is automated.
 */
class SighteAddons : ClientModInitializer {
    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(::onTick)
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            ContributionTracker.onChunkLoad(chunk.pos.x, chunk.pos.z)
        }
        SecretTracker.init()
        // The second parameter marks action bar messages, which is where Hypixel puts the current
        // room's secret progress — everything else is ordinary chat.
        ClientReceiveMessageEvents.GAME.register { text, overlay ->
            if (overlay) onActionBar(text.string) else onChat(text.string)
        }
        // Entering or leaving a dungeon is a server transfer on Hypixel, so this starts each run clean.
        // The history is append-only and flushed per line, so there is nothing to save on the way out.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> DungeonSession.reset() }
        HudElementRegistry.attachElementAfter(VanillaHudElements.OVERLAY_MESSAGE, STANDINGS) { graphics, _ ->
            renderHud(graphics)
        }
    }

    private fun onTick(client: Minecraft) {
        if (!DungeonSession.inDungeon(client)) return
        val map = DungeonMapReader.mapState(client) ?: return
        val wasCalibrated = DungeonSession.calibrated
        if (!DungeonSession.update(client, map)) {
            // In the boss: stop sampling rooms but keep the run clock going, so the summary
            // reports the real run time and not just the clear phase.
            if (DungeonSession.calibrated) DungeonSession.tickClock()
            return
        }
        if (!wasCalibrated) {
            RoomHistory.startRun()
            // Read the roster immediately: waiting for the next 20-tick slot would leave the first
            // second of the run unattributed, with nobody mapped to any decoration.
            PartyTracker.update(client)
            DebugLog.event(
                "run_start",
                "floor" to DungeonSession.floor, "mcVersion" to client.launchedVersion,
                "roomCores" to RoomDatabase.size,
            )
        }

        DungeonSession.tickClock()
        // The party barely changes during a run; re-reading the tab list once a second is plenty.
        if (DungeonSession.runTicks % 20 == 0) PartyTracker.update(client)
        ContributionTracker.tick(client, map)
    }

    private fun onActionBar(text: String) {
        val player = Minecraft.getInstance().player ?: return
        SecretTracker.onActionBar(text, player.x, player.z)
    }

    /** Hypixel prints the results headline when the run ends, e.g. "The Catacombs - Floor VII". */
    private fun onChat(message: String) {
        if (summaryPrinted || !RUN_END.matches(message)) return
        summaryPrinted = true
        DebugLog.event(
            "run_end",
            "floor" to DungeonSession.floor, "roomsCleared" to ContributionTracker.roomsCleared,
            "points" to ContributionTracker.pointsByPlayer().toString(),
            "unnamed" to ContributionTracker.visitedRooms().count { it.name == null },
            "newRecords" to RoomHistory.newBestsThisRun().size,
        )
        RoomHistory.printSummary()
    }

    private fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!DungeonSession.calibrated) return
        val client = Minecraft.getInstance()
        val font = client.font
        var y = 4

        fun line(text: String, color: Int) {
            graphics.text(font, Component.literal(text), 4, y, color)
            y += 10
        }

        line(
            "Sighte ${DungeonSession.floor ?: ""} ${DungeonGrid.formatTicks(DungeonSession.runTicks)}" +
                "  ${ContributionTracker.roomsCleared} rooms",
            WHITE,
        )

        currentRoom(client)?.let { room ->
            val self = client.player?.name?.string
            val inRoom = self?.let { room.ticks[it] } ?: 0
            line("${room.label()}  ${DungeonGrid.formatTicks(inRoom)}", YELLOW)
            // Independent: a room can be cleared long before its last secret is found, or never get one.
            line("  cleared  ${room.clearedAtTick?.let(DungeonGrid::formatTicks) ?: "--:--.-"}", GREY)
            // Two numbers, never a blended one: room total from the action bar, and the subset that
            // coincided with your own interaction.
            val max = room.info?.secrets ?: 0
            val secrets = if (max > 0) "  ${room.secretsFound}/$max (${room.ownSecrets} you)" else ""
            line("  secrets  ${room.secretsAtTick?.let(DungeonGrid::formatTicks) ?: "--:--.-"}$secrets", GREY)
        }

        val points = ContributionTracker.pointsByPlayer()
        PartyTracker.roster()
            .map { it.name to (points[it.name] ?: 0.0) }
            .sortedByDescending { it.second }
            .forEach { (name, earned) ->
                // Locale.ROOT: a German default locale would render "2,50", inconsistent with the JSON log.
                line("%.2f  %s".format(Locale.ROOT, earned, name), GREY)
            }
    }

    private fun currentRoom(client: Minecraft): TrackedRoom? {
        val player = client.player ?: return null
        return ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(player.x, player.z))
    }

    companion object {
        const val ID = "sighteaddons"
        val LOGGER = LoggerFactory.getLogger(ID)!!

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFDD55.toInt()
        private const val GREY = 0xFFAAAAAA.toInt()

        private val STANDINGS: Identifier = Identifier.fromNamespaceAndPath(ID, "standings")
        /** Anchored, so a party member typing the headline cannot suppress the real summary. */
        private val RUN_END = Regex("""^\s*(?:Master Mode )?(?:The )?Catacombs - (?:Floor \w+|Entrance)\s*$""")

        /** Reset by [DungeonSession.reset], so each run prints its summary exactly once. */
        var summaryPrinted = false
    }
}
