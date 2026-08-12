package sighteaddons

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files

/**
 * One JSON file per finished run: every room with what it cost the party, plus the context that
 * makes those numbers comparable between runs.
 *
 * Separate from [DebugLog] on purpose. The debug log is a diagnostic — bounded at 20 000 events and
 * switchable off in `/sa` — while this is the permanent record that room scoring is derived from
 * later. Nothing that must survive belongs in a stream the player can silence.
 *
 * Written to `config/sighteaddons/runs/run-<millis>-<uuid>.json`, one closed file per run, which is
 * all [TelemetryUpload] needs to ship it: no offsets, no partially written file. The UUID is in the
 * name so the server can file it under a profile without parsing the body.
 *
 * Teammates appear only in aggregate — party size, classes without names, player-ticks per room.
 * The uploader consented to this by installing the mod; the four strangers from party finder did
 * not, and a permanent server-side record of their names is not ours to create.
 */
object RunReport {
    /** Bumped whenever a field changes meaning: this data outlives the code that wrote it. */
    private const val SCHEMA = 1

    fun write() {
        val client = Minecraft.getInstance()
        val self = client.player ?: return
        val rooms = ContributionTracker.visitedRooms()
        // A run that saw no rooms is a false headline match, not a run.
        if (rooms.isEmpty()) return

        val ts = System.currentTimeMillis()
        val uuid = self.uuid.toString()
        val report = build(
            ts = ts,
            uuid = uuid,
            player = self.name.string,
            floor = DungeonSession.floor ?: "?",
            runTicks = DungeonSession.runTicks,
            roster = PartyTracker.roster(),
            rooms = rooms,
            roomsCleared = ContributionTracker.roomsCleared,
            unattributed = ContributionTracker.roomsCleared - ContributionTracker.pointsByPlayer().values.sum(),
            deaths = ContributionTracker.deaths,
            modVersion = TelemetryUpload.modVersion(),
            mcVersion = client.launchedVersion,
        )

        val dir = FabricLoader.getInstance().configDir.resolve("sighteaddons/runs")
        try {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("run-$ts-$uuid.json"), report.toString())
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Could not write run report to {}", dir, e)
        }
    }

    /**
     * Pure so the payload can be tested without a game: this shape is the contract the server side
     * and every later evaluation are written against.
     */
    internal fun build(
        ts: Long,
        uuid: String,
        player: String,
        floor: String,
        runTicks: Int,
        roster: List<DungeonPlayer>,
        rooms: List<TrackedRoom>,
        roomsCleared: Int,
        unattributed: Double,
        deaths: Int,
        modVersion: String,
        mcVersion: String,
    ): JsonObject {
        val obj = JsonObject()
        obj.addProperty("v", SCHEMA)
        obj.addProperty("ts", ts)
        obj.addProperty("uuid", uuid)
        obj.addProperty("player", player)
        obj.addProperty("floor", floor)
        obj.addProperty("runTicks", runTicks)
        obj.addProperty("partySize", roster.size)
        obj.addProperty("roomsCleared", roomsCleared)
        // Clamped: the point split can leave a tiny negative remainder, and a written profile line
        // can never be corrected afterwards.
        obj.addProperty("unattributed", unattributed.coerceAtLeast(0.0))
        obj.addProperty("deaths", deaths)
        obj.addProperty("modVersion", modVersion)
        obj.addProperty("mcVersion", mcVersion)

        // The living class, not the current one: a player who is dead when the headline prints reads
        // `(DEAD)` in tab, and writing that here would erase the class level of exactly the player
        // whose death shaped the run — permanently, since this file is never revisited.
        val own = roster.firstOrNull { it.name == player }
        obj.addProperty("class", own?.livingClass)
        obj.addProperty("classLevel", own?.livingLevel)
        // Classes without names: the party composition changes what a room costs, the four
        // strangers it consists of are nobody's business.
        val classes = JsonArray()
        roster.forEach { classes.add("${it.livingClass} ${it.livingLevel}".trim()) }
        obj.add("classes", classes)

        val list = JsonArray()
        rooms.forEach { list.add(room(it, player)) }
        obj.add("rooms", list)
        return obj
    }

    private fun room(room: TrackedRoom, player: String): JsonObject {
        val obj = JsonObject()
        // Null when the chunk never streamed in. Kept rather than dropped: how much of a run stays
        // unnamed is itself worth knowing.
        obj.addProperty("name", room.name)
        obj.addProperty("type", room.type.name)
        obj.addProperty("shape", room.info?.shape)
        obj.addProperty("maxSecrets", room.info?.secrets ?: -1)
        obj.addProperty("crypts", room.info?.crypts ?: -1)
        // A four-segment room is four times the walking of a 1x1 at the same clear time.
        obj.addProperty("segments", room.cells.size)
        obj.addProperty("clearTick", room.clearedAtTick)
        obj.addProperty("secretsTick", room.secretsAtTick)
        // Already green on arrival: nobody earned it during this run, so it must not count as a clear.
        obj.addProperty("preCleared", room.preCleared)
        // Total party effort, which is the difficulty signal — one player for 60s and four players
        // for 15s are the same clear time and very different rooms.
        obj.addProperty("playerTicks", room.ticks.values.sum())
        obj.addProperty("playersInRoom", room.ticks.values.count { it >= ContributionTracker.MIN_TICKS })
        obj.addProperty("ownTicks", room.ticks[player] ?: 0)
        obj.addProperty("secretsFound", room.secretsFound)
        obj.addProperty("ownSecrets", room.ownSecrets)
        obj.addProperty("deaths", room.deaths)
        return obj
    }
}
