package sighteaddons.ui.hud

import net.minecraft.client.Minecraft
import sighteaddons.ClearScore
import sighteaddons.ContributionTracker
import sighteaddons.DungeonGrid
import sighteaddons.DungeonSession
import sighteaddons.IdleTime
import sighteaddons.PartyTracker
import sighteaddons.RoomHistory
import sighteaddons.TrackedRoom
import sighteaddons.ui.Format

/**
 * Everything the HUD draws, frozen at one tick.
 *
 * The renderer never touches the tracking layer directly, and that is the point. `RoomHistory.records()`,
 * `RoomHistory.attempts()` and `ContributionTracker.pointsByPlayer()` all hand out their live internal
 * `HashMap`s — not copies — and `RoomHistory.ensureLoaded()` does synchronous file I/O on whatever
 * thread calls it first. Reading any of that from the render thread means iterating a map that the
 * client tick may be writing, and stalling a frame on a disk read the first time a screen opens.
 *
 * So the tick thread builds one of these and publishes it by reference swap. The renderer reads a
 * field, gets an object nobody will ever mutate, and is done.
 *
 * Absent values are `-1` ([Format.NONE]) rather than `null`: an `Int?` field is an `Integer` on the
 * JVM, and reading one per line per frame is exactly the boxing this path is supposed to avoid.
 */
internal class HudSnapshot(
    val inDungeon: Boolean,
    val floor: String,
    val runTicks: Int,
    val roomsCleared: Int,

    /**
     * Whether the player has crossed into the boss room, which is where the clear phase ends.
     *
     * Carried on the snapshot rather than read by the renderer, for the same reason everything else
     * here is: `DungeonSession.inBoss` needs a live `Minecraft` and the player's position, and the
     * render thread is not allowed to go and get either. The tick already knows — it is the same
     * check that stops room sampling in `SighteAddons.onTick`.
     *
     * Defaulted because the two synthetic call sites (`HudPreview`, the tests) script the clear phase
     * and nothing else; only [build] ever sets it true.
     */
    val inBoss: Boolean = false,

    /** Empty when between rooms — which is a state the HUD shows rather than hides. */
    val roomName: String,
    val roomType: String,
    val roomTicks: Int,
    val roomClearedAt: Int,

    val secretsFound: Int,
    val secretsTotal: Int,
    val ownSecrets: Int,
    val secretRunTicks: Int,

    /** Personal bests for this room, or [Format.NONE]. */
    val clearBest: Int,
    val secretBest: Int,

    val runOwnSecrets: Int,
    val idleTicks: Int,
    val navTicks: Int,

    val history: Array<Row>,
    val standings: Array<Standing>,
) {

    /** A room already finished this run, for the receding history list. */
    class Row(val name: String, val ticks: Int, val clearedAt: Int, val personalBest: Boolean)

    /**
     * One row of the live standings.
     *
     * [estimated] is true when any part of [points] is a guess — a teammate's share of secrets somebody
     * else was seen to find. Never true for the local player: their secrets are counted, not inferred.
     * The card marks the difference, because a number that is partly a guess and a number that is not
     * must not look identical.
     */
    class Standing(val name: String, val points: Double, val secrets: Int, val estimated: Boolean = false)

    val inRoom: Boolean get() = roomName.isNotEmpty()

    /**
     * [roomName] as the card draws it, uppercased here rather than on the render path.
     *
     * A `val` with an initialiser and not a `get()`: this is computed once, when the tick builds the
     * snapshot, which is twenty times a second at most. `uppercase()` allocates a `String` every time
     * it is called, and the caller calling it was the HUD's largest per-frame allocation at 240 fps —
     * `Labels.draw` says in its own note that it does not uppercase for exactly this reason, and its
     * one HUD caller then did it anyway. This is where the string belongs: on the thing that is built
     * once per change, not on the thing that runs once per frame.
     */
    val roomNameUpper: String = roomName.uppercase()

    /**
     * Split against the room's clear record, or [Format.NONE] when there is nothing to compare to.
     *
     * Measured against time *in the room*, which is what `RoomHistory` stores under `clear` — not the
     * run-relative moment the checkmark appeared. Comparing the two would produce a delta that gets
     * worse the later in a run the room is entered.
     */
    val clearDelta: Int
        get() = if (clearBest < 0 || roomTicks <= 0) Format.NONE else roomTicks - clearBest

    val secretDelta: Int
        get() = if (secretBest < 0 || secretRunTicks < 0) Format.NONE else secretRunTicks - secretBest

    companion object {

        val EMPTY = HudSnapshot(
            inDungeon = false, floor = "", runTicks = 0, roomsCleared = 0,
            roomName = "", roomType = "", roomTicks = 0, roomClearedAt = Format.NONE,
            secretsFound = 0, secretsTotal = 0, ownSecrets = 0, secretRunTicks = Format.NONE,
            clearBest = Format.NONE, secretBest = Format.NONE,
            runOwnSecrets = 0, idleTicks = 0, navTicks = 0,
            history = emptyArray(), standings = emptyArray(),
        )

        /** How many finished rooms the HUD keeps behind the current one. */
        const val HISTORY_DEPTH = 3

        /**
         * The published snapshot.
         *
         * `@Volatile` because the client tick writes it and the render thread reads it. The reference
         * swap is the whole synchronisation: neither side ever mutates the object itself, so there is
         * nothing else to guard and no lock on the render path.
         */
        @Volatile
        var current: HudSnapshot = EMPTY
            private set

        /** Called from the client tick, after the trackers have run. */
        fun publish(client: Minecraft) {
            current = build(client)
        }

        /** Called when a run ends or the player leaves, so the HUD does not keep a stale run on screen. */
        fun clear() {
            current = EMPTY
        }

        private fun build(client: Minecraft): HudSnapshot {
            val self = client.player?.name?.string
            val room = currentRoom(client)

            val records = RoomHistory.records()
            val name = room?.label().orEmpty()
            val clearBest = if (name.isEmpty()) Format.NONE else {
                records["$name|${RoomHistory.CLEAR}"]?.ticks ?: Format.NONE
            }
            val secretBest = if (name.isEmpty()) Format.NONE else {
                records["$name|${RoomHistory.SECRETS}"]?.ticks ?: Format.NONE
            }

            val visited = ContributionTracker.visitedRooms()
            return HudSnapshot(
                inDungeon = true,
                inBoss = DungeonSession.inBoss,
                floor = DungeonSession.floor.orEmpty(),
                runTicks = DungeonSession.runTicks,
                roomsCleared = ContributionTracker.roomsCleared,

                roomName = name,
                roomType = room?.info?.type.orEmpty(),
                roomTicks = if (room == null || self == null) 0 else room.ticks[self] ?: 0,
                roomClearedAt = room?.clearedAtTick ?: Format.NONE,

                secretsFound = room?.secretsFound ?: 0,
                secretsTotal = room?.info?.secrets ?: 0,
                ownSecrets = roomOwnSecrets(room),
                secretRunTicks = room?.secretRunElapsed(DungeonSession.runTicks) ?: Format.NONE,

                clearBest = clearBest,
                secretBest = secretBest,

                runOwnSecrets = runOwnSecrets(visited),
                idleTicks = IdleTime.idleTicks,
                navTicks = IdleTime.navTicks,

                history = historyOf(visited, room, self, records),
                standings = standingsOf(visited, self),
            )
        }

        /**
         * The secrets in the current room that attribution credits to the local player.
         *
         * **Never [TrackedRoom.secretsFound], and never a fallback to it.** That count is the party's:
         * a rise in it says somebody found one. Substituting it when nothing was attributed — the
         * obvious-looking fix for a readout that shows `0` in a room a teammate cleared — writes the
         * party's work onto the local player's line, and the whole reason this number exists is that
         * nothing else on screen distinguishes the two. It under-counts on purpose; widening
         * attribution is `ownsecrets-001` and happens one layer down, in [sighteaddons.SecretTracker].
         *
         * A one-line function because it is the *only* place the room's own-secret count is produced,
         * and because [build] needs a live `Minecraft` and therefore cannot be tested. Standing on its
         * own it can be, and `UiHudSecretsTest` is what holds the rule now that the readout it used to
         * live in has moved into this HUD.
         */
        internal fun roomOwnSecrets(room: TrackedRoom?): Int = room?.ownSecrets ?: 0

        /**
         * The run's total, on the same rule and for the same reason.
         *
         * [visited] already contains the current room, so this is a superset of [roomOwnSecrets] and
         * never an addition to it — counting the room a second time would make the run total climb
         * while the player stood still.
         */
        internal fun runOwnSecrets(visited: List<TrackedRoom>): Int = visited.sumOf { it.ownSecrets }

        /**
         * The last few rooms that finished, most recent first.
         *
         * Ordered by when the checkmark appeared rather than by when they were entered, because that
         * is the order the player watched them complete in.
         */
        private fun historyOf(
            visited: List<TrackedRoom>,
            current: TrackedRoom?,
            self: String?,
            records: Map<String, RoomHistory.Record>,
        ): Array<HudSnapshot.Row> {
            if (self == null) return emptyArray()
            val finished = ArrayList<TrackedRoom>(visited.size)
            for (room in visited) {
                if (room === current) continue
                if (room.clearedAtTick == null) continue
                if (room.name == null) continue
                finished.add(room)
            }
            finished.sortByDescending { it.clearedAtTick ?: 0 }

            val depth = minOf(HISTORY_DEPTH, finished.size)
            return Array(depth) { i ->
                val room = finished[i]
                val label = room.label()
                val ticks = room.ticks[self] ?: 0
                val best = records["$label|${RoomHistory.CLEAR}"]?.ticks
                HudSnapshot.Row(
                    name = label,
                    ticks = ticks,
                    clearedAt = room.clearedAtTick ?: Format.NONE,
                    // A record equal to this attempt means this attempt set it: RoomHistory keeps the
                    // minimum, and the line was appended before this snapshot was built.
                    personalBest = best != null && ticks > 0 && ticks <= best,
                )
            }
        }

        /**
         * The live standings: clear points, plus each teammate's estimated share of the secrets this
         * client saw somebody else find. See [ClearScore] for why the estimate exists and what replaces
         * it at the end of a run.
         *
         * The estimate is rebuilt from the rooms every tick rather than accumulated as secrets arrive.
         * That is deliberate and it is the cheaper of the two in the way that matters: a room's tick
         * shares keep changing after its secrets are found, so an increment banked at the moment of a
         * find would be split by a roster snapshot that is already out of date and could never be
         * corrected. Recomputing is a few dozen small maps twenty times a second, on the path that
         * already builds every string this card draws.
         */
        private fun standingsOf(visited: List<TrackedRoom>, self: String?): Array<HudSnapshot.Standing> {
            val guessed = ClearScore.guessedSecretPoints(
                visited.map { ClearScore.Room(it.ticks, (it.secretsFound - it.ownSecrets).coerceAtLeast(0)) },
                self,
                ContributionTracker.MIN_TICKS,
            )
            val rows = ClearScore.live(
                PartyTracker.roster().map { it.name },
                ContributionTracker.clearPointsByPlayer(),
                ContributionTracker.ownSecretPointsByPlayer(),
                guessed,
            )
            return Array(rows.size) { HudSnapshot.Standing(rows[it].name, rows[it].points, Format.NONE, rows[it].estimated) }
        }

        private fun currentRoom(client: Minecraft): TrackedRoom? {
            val player = client.player ?: return null
            return ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(player.x, player.z))
        }
    }
}
