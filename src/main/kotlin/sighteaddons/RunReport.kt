package sighteaddons

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name

/**
 * One JSON file per run: every room with what it cost the party, plus the context that makes those
 * numbers comparable between runs.
 *
 * Per run, not per *finished* run. A room's clear time is a fact about that room whether or not
 * anybody killed the boss afterwards, and abandoning a floor is the most ordinary thing a party does
 * — reporting only completed runs threw away the rooms of every one of them. What the end of the run
 * decides is not whether the rooms count, only whether the run-level numbers describe a whole run,
 * which is what `complete` says out loud.
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
 *
 * Since 0.5.0 the uploader is not named either. The file is keyed by [Config.installId] rather than
 * the Minecraft UUID, and the player's name is not written at all — the mod ships to everyone now,
 * and a permanent per-person history is not something a dungeon tracker should be accumulating about
 * strangers. What the server gets is a run, attached to an identity only its owner can resolve.
 *
 * [Config.uploadName] is the one way out of that, and only for the person who ticks it: it adds a
 * `player` field with their own Minecraft name, which is what a leaderboard needs to put a label on
 * a row. The id stays the identity either way — the name is an annotation on it, not a replacement,
 * so switching it back off leaves the profile intact and merely stops naming it. Teammates are
 * unaffected in both directions; they are not in this file to begin with.
 */
object RunReport {
    /**
     * Bumped whenever a field changes meaning: this data outlives the code that wrote it. 2 dropped
     * `player` and repurposed `uuid` from the Minecraft identity to the install id, so a v1 line and
     * a v2 line with the same `uuid` are about different things. 3 added `enterTick`, without which
     * no line before it can yield a clear duration. 4 added `complete`, and that one changes what
     * every *earlier* line means: up to 3 a report existing at all implied a finished run, so an
     * absent `complete` reads as true and must keep reading that way forever.
     *
     * 5 added no field at all — it changed what `enterTick` *means*, from the first sighting of any
     * party member to the start of the first stay long enough to be work (`clear-001`, see
     * [TrackedRoom.enteredAtTick]). That is the more dangerous kind of change and the reason this
     * constant exists: the key, its type and its optionality are identical, so nothing would reject a
     * v4-anchored report or notice it arriving, and both meanings would fold into one average that
     * describes neither. The receiver routes on this number and only on this number — `roomstats.py`
     * `STAY_ANCHOR_SCHEMA` sends `v` below 5 to its `clear` bucket and 5 or above to `clearStay`,
     * and never mixes them. Shipping the new anchor with this left at 4 is therefore silent: no 400,
     * no log line, just a permanently contaminated mean in an append-only store.
     */
    private const val SCHEMA = 5

    /**
     * Whether this run has already been handed to the queue.
     *
     * **The one guard against reporting a run twice, and it belongs here rather than at a call
     * site.** Since `runloss-001` there are three ways in — the end-of-run headline,
     * `ClientPlayConnectionEvents.JOIN` and `ClientPlayConnectionEvents.DISCONNECT` — and two of
     * them can fire for the same run: quitting to the title screen from inside a floor disconnects
     * (report written) and then joining any server logs in again (report written a second time,
     * from state nothing had reset in between). `SighteAddons.summaryPrinted`, which used to be the
     * guard at the JOIN site, does not cover that pair: it is only ever set by the headline, so it
     * is still false on both passes.
     *
     * Set only when a file actually reached the queue, so a write that failed leaves the later call
     * site its second chance rather than swallowing the run for good.
     *
     * Atomic because the DISCONNECT path does not run on the client thread — see [uploader].
     * Cleared by [DungeonSession.reset], which is what makes it per-run rather than per-process.
     */
    private val reported = AtomicBoolean(false)

    /** Called from [DungeonSession.reset]: a new run may be reported again. */
    fun reset() {
        reported.set(false)
    }

    /**
     * Hands one report to the queue if this run has not been reported yet. Returns whether it did.
     *
     * The claim is taken *before* the write and given back if the write fails, which is the whole
     * behaviour worth stating: a run that could not be written is not a run that was reported, and
     * the later call site — a JOIN after a failed DISCONNECT write — is its second chance. Claiming
     * afterwards instead would leave a window in which both paths write, which is the duplicate this
     * exists to prevent.
     */
    internal fun queue(dir: Path, name: String, body: String): Boolean {
        if (!reported.compareAndSet(false, true)) return false
        if (publish(dir, name, body)) return true
        reported.set(false)
        return false
    }

    /**
     * Who the report is about, and the reason [write] no longer asks the client for it.
     *
     * `ClientPlayConnectionEvents.DISCONNECT` is the only event that fires when the game is quit
     * from inside a dungeon, and it is **not** a safe place to read `Minecraft.getInstance().player`.
     * Measured against `minecraft-merged-043a8b3edf-26.1.2.jar` and
     * `fabric-networking-api-v1-6.3.1+554860db4c.jar` rather than reasoned about — the four steps,
     * each re-checkable with one `javap -p -c`:
     *
     * 1. `Minecraft.destroy()` calls `ClientLevel.disconnect(DEFAULT_QUIT_MESSAGE)` and only *then*
     *    `disconnectWithProgressScreen()`.
     * 2. `ClientLevel.disconnect` reaches `Connection.disconnect`, which is
     *    `channel.close().awaitUninterruptibly()`.
     * 3. Fabric raises DISCONNECT from `ConnectionMixin`, injected at the head of
     *    `Connection.channelInactive` and at `PacketListener.onDisconnect` inside
     *    `Connection.handleDisconnection`. The first of those is a Netty event-loop callback, and
     *    `AbstractNetworkAddon.handleDisconnect` invokes the event straight from it with no hop back
     *    to the client thread.
     * 4. Netty completes the close future before it fires `channelInactive`, so
     *    `awaitUninterruptibly()` can return while the handler is still queued — and the very next
     *    thing `Minecraft.disconnect(Screen, boolean, boolean)` does is `this.player = null`.
     *
     * So the handler races the field it would want to read, on a thread that is not the one nulling
     * it. [PartyTracker.localName] is the answer: the same string, captured every second of the run
     * while the client certainly had a player. It also happens to be the *more* correct one — the
     * room tick maps are keyed by the name that was current during the run, not by whoever the
     * client has now — which is why it is preferred rather than merely used as a fallback.
     *
     * [live] stays as the fallback for the one case the capture cannot cover: a report written
     * before [PartyTracker.update] has ever run.
     */
    internal fun uploader(live: String?, captured: String?): String? = captured ?: live

    /**
     * [complete] is false for a run that was left before the end. Everything below the run level is
     * unaffected — a room that was cleared has its ticks either way — but `runTicks`, `roomsCleared`
     * and `deaths` then describe the part that was played rather than a whole run, and nothing
     * downstream could tell without being told.
     *
     * Returns whether a report reached the queue, so a caller can tell "nothing to report" from
     * "the report was lost". Safe to call more than once per run: only the first success writes.
     */
    fun write(complete: Boolean): Boolean {
        if (reported.get()) return false
        val client = Minecraft.getInstance()
        // Not `client.player` — see [uploader] for why that field is unreadable on the path this
        // whole feature exists for.
        val self = uploader(client.player?.name?.string, PartyTracker.localName) ?: return false
        val rooms = ContributionTracker.visitedRooms()
        // No rooms is a false headline match or a server hop outside a dungeon, not a run.
        if (rooms.isEmpty()) return false

        val ts = System.currentTimeMillis()
        val id = Config.installId
        val report = build(
            ts = ts,
            installId = id,
            // Always needed to pick the uploader's own rows out of the roster and the tick maps.
            // Whether it also reaches the file is [named]'s decision alone.
            player = self,
            named = Config.uploadName,
            floor = DungeonSession.floor ?: "?",
            complete = complete,
            runTicks = DungeonSession.runTicks,
            roster = PartyTracker.roster(),
            rooms = rooms,
            roomsCleared = ContributionTracker.roomsCleared,
            unattributed = ContributionTracker.unattributed(),
            deaths = ContributionTracker.deaths,
            modVersion = TelemetryUpload.modVersion(),
            mcVersion = client.launchedVersion,
        )

        val dir = FabricLoader.getInstance().configDir.resolve("sighteaddons/runs")
        val name = "run-$ts-$id.json"
        if (!queue(dir, name, report.toString())) return false
        // The one line that proves, in a real session file, that a run report was written at all —
        // and with `complete`, which of the ways out of a floor wrote it. `runloss-001`'s evidence is
        // a session that carries no such line, because no call site ever fired.
        DebugLog.event(
            "run_report", "complete" to complete, "rooms" to rooms.size,
            "roomsCleared" to ContributionTracker.roomsCleared, "file" to name,
        )
        return true
    }

    /**
     * Puts one finished report into the queue, or nothing at all.
     *
     * **Through a temporary file, because the caller may be a client that is on its way out.** The
     * DISCONNECT path runs while `Minecraft.destroy()` is a few statements from `System.exit(0)`
     * (see [uploader]), and a `Files.writeString` interrupted there leaves a truncated `run-*.json`
     * that matches [TelemetryUpload.RUN], gets posted at the next launch, fails `check_run` with a
     * `400`, and is filed under `rejected/` for good. The half-written report is the expensive
     * failure here, not the missing one. `.part` is outside that pattern, so a torn temporary file
     * is invisible to both the uploader and [restamp] and is overwritten by the next attempt.
     *
     * Returns false rather than throwing: telemetry is never a reason for the game to misbehave,
     * and on this path "the game" is a shutdown sequence.
     */
    internal fun publish(dir: Path, name: String, body: String): Boolean = try {
        Files.createDirectories(dir)
        replace(dir.resolve(name), body)
        true
    } catch (e: Exception) {
        SighteAddons.LOGGER.error("Could not write run report {} to {}", name, dir, e)
        false
    }

    /**
     * The `/sa` switch flipped: brings the queue in line with the decision that was just made.
     *
     * A report is written at run end and only handed over at the **next** game start, so between
     * those two moments the queue holds files whose naming reflects a setting the player has since
     * changed. Without this, three runs played before the switch went on would still leave anonymously
     * — the consent was given before they were sent, and the queue is the only place that is still
     * ours to correct.
     *
     * The boundary is `uploaded/`: what has left the machine stays as it left.
     */
    fun restampPending() {
        val player = Minecraft.getInstance().player?.name?.string
        // Switched on with no name to stamp: leave the queue alone rather than stripping it, which
        // would be the opposite of what was just asked for.
        if (Config.uploadName && player == null) return
        val name = player.takeIf { Config.uploadName }

        val dir = FabricLoader.getInstance().configDir.resolve("sighteaddons/runs")
        // ponytail: synchronous, on the click. A daemon thread would be the worse choice here, not the
        // better one — the upload pass at game start walks this same directory, and a concurrent
        // restamp could rewrite a file send() has already read or is moving. The count is bounded by
        // runs since the last launch: single digits, a few kB each.
        val changed = restamp(dir, name)
        if (changed > 0) {
            SighteAddons.LOGGER.info(
                "{} {} queued run report(s) in {}", if (name != null) "Named" else "Unnamed", changed, dir,
            )
        }
    }

    /**
     * Stamps or strips the uploader's own name on the reports that have not left the machine yet.
     * [player] null removes the field. Returns how many files it changed.
     *
     * Takes the directory so it can be tested without a game directory, the same way [build] takes
     * its payload rather than reading the world.
     */
    internal fun restamp(dir: Path, player: String?): Int {
        if (!Files.isDirectory(dir)) return 0
        // Not recursive, so `uploaded/` and `rejected/` are out of reach by construction — and
        // filtered by the uploader's own pattern on top, so both sides agree on what a report is.
        val pending = try {
            Files.list(dir).use { paths -> paths.filter { TelemetryUpload.RUN.matches(it.name) }.toList() }
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not list {} to update the queued reports", dir, e)
            return 0
        }

        var changed = 0
        for (file in pending) {
            try {
                val report = JsonParser.parseString(Files.readString(file)).asJsonObject
                val before = report.get("player")?.asString
                if (before == player) continue
                // Absent, never null: the receiver reads the key's presence as the consent itself.
                // Re-added at the end of the object rather than after `uuid`; check_run walks its own
                // field list, so the order is nothing the server can see.
                if (player == null) report.remove("player") else report.addProperty("player", player)
                replace(file, report.toString())
                changed++
            } catch (e: Exception) {
                // One odd file must not leave the rest of the queue half converted, and it must never
                // make the switch itself fail.
                SighteAddons.LOGGER.warn("Could not update the name in {}; left as it was", file.name, e)
            }
        }
        return changed
    }

    /**
     * Writes a queued report in one step, whether or not one is already there. A half-written report
     * is a 400 at the receiver and then a permanent resident of `rejected/`, which is far more
     * expensive than the temporary file — the server takes the same precaution on its own side of
     * the wire. [publish] uses it for the first write for the same reason.
     */
    private fun replace(file: Path, body: String) {
        val tmp = file.resolveSibling("${file.name}.part")
        Files.writeString(tmp, body)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Pure so the payload can be tested without a game: this shape is the contract the server side
     * and every later evaluation are written against.
     */
    internal fun build(
        ts: Long,
        installId: String,
        /** Used to find the uploader's own class and ticks. Written to the report only when [named]. */
        player: String,
        floor: String,
        /** Whether the run reached its end. Required rather than defaulted: a run wrongly stored as
         *  complete is a permanent claim about a whole run that nobody played. */
        complete: Boolean,
        runTicks: Int,
        roster: List<DungeonPlayer>,
        rooms: List<TrackedRoom>,
        roomsCleared: Int,
        unattributed: Double,
        deaths: Int,
        modVersion: String,
        mcVersion: String,
        /** [Config.uploadName]. Defaulted so every existing caller stays anonymous by construction. */
        named: Boolean = false,
    ): JsonObject {
        val obj = JsonObject()
        obj.addProperty("v", SCHEMA)
        obj.addProperty("ts", ts)
        obj.addProperty("uuid", installId)
        // Absent rather than null when the player has not asked to be named: the receiver reads the
        // key's presence as the consent, and a null would be a claim about a name instead of silence.
        // No schema bump — the field has been the contract's one optional key since the receiver was
        // written, precisely so this could be turned on without invalidating a single stored report.
        if (named) obj.addProperty("player", player)
        obj.addProperty("floor", floor)
        // Sits in front of the three numbers it qualifies: they are the part of the run that was
        // played, and only this field separates that from a whole one.
        obj.addProperty("complete", complete)
        obj.addProperty("runTicks", runTicks)
        obj.addProperty("partySize", roster.size)
        obj.addProperty("roomsCleared", roomsCleared)
        // Settled here rather than at the call site: [build] is the contract, so a caller that
        // computes this figure itself gets the same treatment as [write] does. The point split
        // leaves a residue of either sign and a written profile line can never be corrected
        // afterwards — see ContributionTracker.settle for what "either sign" cost us once already.
        obj.addProperty("unattributed", ContributionTracker.settle(unattributed))
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
        // Both of these are run timestamps, not durations. `enterTick` is the anchor that turns the
        // clear one into "how long the room took" rather than "how far into the run it happened" —
        // the server averages the difference per room, and since schema 5 it averages it in a
        // separate bucket, because the anchor is now a stay rather than a sighting. Null is a real
        // answer here and not a hole to be filled: it says nobody stayed long enough for the span to
        // mean anything, and the server drops the visit instead of averaging in a wrong number.
        obj.addProperty("enterTick", room.enteredAtTick)
        obj.addProperty("clearTick", room.clearedAtTick)
        obj.addProperty("secretsTick", room.secretsAtTick)
        // The raced part of the room: first secret to last. Null when the run never started, was
        // joined half-finished, or was abandoned — those are absences, not zeroes.
        obj.addProperty("secretRunTicks", room.secretRunTicks)
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
