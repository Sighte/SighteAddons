package sighteaddons

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * One file per solo run on F7 or M7, and the store that reads them back for the `/sa` solo tab.
 *
 * **Local only, by design.** [RunReport] is the run's telemetry and every key in it is a contract with
 * the receiver — a field `ingest.py` does not know is a `400` that costs the run for good. Nothing here
 * is uploaded, nothing here is read by anything but this mod, so the shape can grow without a deploy
 * on the other side. It lives in `config/sighteaddons/solo/`, a directory [TelemetryUpload] never scans.
 *
 * **Per-room keys mean what [RunReport]'s mean.** `enterTick`, `clearTick`, `secretsTick`,
 * `secretRunTicks`, `ownTicks`, `secretsFound`, `ownSecrets`, `deaths`, `preCleared` are copied off the
 * same [TrackedRoom] with the same names, so a number in one file can be checked against the other.
 * `CLAUDE.md` forbids redefining a metric; this defines none.
 *
 * The codec is pure so a test can round-trip a record without a game directory, and [fold] skips a
 * file it cannot read rather than throwing — the same rule [RoomHistory.fold] and [SoloClear.fold]
 * follow, because a directory one bad file makes unreadable is not a record.
 */
object SoloRuns {
    /** Bumped when a key changes meaning. 1 is the first shape. */
    const val SCHEMA = 1

    private val DIR: Path by lazy { FabricLoader.getInstance().configDir.resolve("sighteaddons/solo") }

    /** Where the files live, for the empty state and the footer. */
    const val PATH = "config/sighteaddons/solo/"

    val THRESHOLDS = intArrayOf(270, 300)

    /** The projection — the number the gate judges — as opposed to Hypixel's live sidebar score. */
    const val PROJECTED = "projected"
    const val SIDEBAR = "sidebar"

    /** The first tick a score crossed [threshold], with Hypixel's clock as it read at that moment. */
    data class Mark(val threshold: Int, val kind: String, val tick: Int, val clock: String?, val ms: Long)

    data class Score(
        val high: Int,
        val projectedHigh: Int,
        val source: String,
        val hypixel: Int?,
        val final: LiveScore.Breakdown?,
        val marks: List<Mark>,
        /** `(tick, projected)` whenever the projection changed. */
        val curve: List<Pair<Int, Int>>,
    )

    data class Room(
        val type: RoomType,
        val state: RoomState,
        val cells: Set<Cell>,
        val name: String?,
        val shape: String?,
        val maxSecrets: Int?,
        val crypts: Int?,
        val enterTick: Int?,
        val clearTick: Int?,
        val secretsTick: Int?,
        val secretRunTicks: Int?,
        val secretsFound: Int?,
        val ownSecrets: Int?,
        val ownTicks: Int?,
        val deaths: Int?,
        val preCleared: Boolean,
    ) {
        /** [TrackedRoom.label]'s wording, for a room the database never named. */
        val label: String get() = name ?: "${type.name.lowercase().replaceFirstChar { it.uppercase() }} (unknown)"
    }

    data class Floor(val cols: Int, val rows: Int, val entrance: Cell, val rooms: List<Room>, val doors: List<Door>) {
        private val byCell: Map<Cell, Room> by lazy {
            HashMap<Cell, Room>().also { map -> for (room in rooms) for (cell in room.cells) map[cell] = room }
        }

        fun roomAt(cell: Cell): Room? = byCell[cell]
    }

    /** One stretch in one lattice cell, raw — border jitter and all. Collapsing is the reader's job. */
    data class Visit(val cell: Cell, val enter: Int, val leave: Int)

    data class Blood(val open: Int?, val done: Int?)

    data class Record(
        val ts: Long,
        val floor: String,
        val player: String?,
        val complete: Boolean,
        val modVersion: String,
        val runTicks: Int,
        val idleTicks: Int,
        val navTicks: Int,
        val roomsCleared: Int,
        val secrets: Int?,
        val secretsPercent: Double?,
        val deaths: Int,
        val clock: String?,
        val score: Score,
        val blood: Blood,
        val layout: Floor,
        val route: List<Visit>,
    ) {
        fun mark(threshold: Int, kind: String = PROJECTED): Mark? =
            score.marks.firstOrNull { it.threshold == threshold && it.kind == kind }

        val to300: Mark? get() = mark(300)
        val to270: Mark? get() = mark(270)
    }

    // --- Codec --------------------------------------------------------------------------------

    fun encode(r: Record): JsonObject {
        val obj = JsonObject()
        obj.addProperty("v", SCHEMA)
        obj.addProperty("ts", r.ts)
        obj.addProperty("floor", r.floor)
        obj.addProperty("player", r.player)
        obj.addProperty("solo", true)
        obj.addProperty("complete", r.complete)
        obj.addProperty("modVersion", r.modVersion)
        obj.addProperty("runTicks", r.runTicks)
        obj.addProperty("idleTicks", r.idleTicks)
        obj.addProperty("navTicks", r.navTicks)
        obj.addProperty("roomsCleared", r.roomsCleared)
        obj.addProperty("secrets", r.secrets)
        obj.addProperty("secretsPercent", r.secretsPercent)
        obj.addProperty("deaths", r.deaths)
        obj.addProperty("clock", r.clock)

        val score = JsonObject()
        score.addProperty("high", r.score.high)
        score.addProperty("projectedHigh", r.score.projectedHigh)
        score.addProperty("source", r.score.source)
        score.addProperty("hypixel", r.score.hypixel)
        r.score.final?.let { b ->
            val final = JsonObject()
            final.addProperty("time", b.time)
            final.addProperty("explore", b.explore)
            final.addProperty("skill", b.skill)
            final.addProperty("bonus", b.bonus)
            final.addProperty("total", b.total)
            score.add("final", final)
        }
        val marks = JsonArray()
        for (m in r.score.marks) {
            val mark = JsonObject()
            mark.addProperty("threshold", m.threshold)
            mark.addProperty("kind", m.kind)
            mark.addProperty("tick", m.tick)
            mark.addProperty("clock", m.clock)
            mark.addProperty("ms", m.ms)
            marks.add(mark)
        }
        score.add("marks", marks)
        val curve = JsonArray()
        for ((tick, value) in r.score.curve) curve.add(JsonArray().apply { add(tick); add(value) })
        score.add("curve", curve)
        obj.add("score", score)

        val blood = JsonObject()
        blood.addProperty("open", r.blood.open)
        blood.addProperty("done", r.blood.done)
        obj.add("blood", blood)

        val layout = JsonObject()
        layout.addProperty("cols", r.layout.cols)
        layout.addProperty("rows", r.layout.rows)
        layout.add("entrance", cell(r.layout.entrance))
        val rooms = JsonArray()
        for (room in r.layout.rooms) {
            val o = JsonObject()
            o.addProperty("type", room.type.name)
            o.addProperty("state", room.state.name)
            o.add("cells", JsonArray().apply { room.cells.forEach { add(cell(it)) } })
            o.addProperty("name", room.name)
            o.addProperty("shape", room.shape)
            o.addProperty("maxSecrets", room.maxSecrets)
            o.addProperty("crypts", room.crypts)
            o.addProperty("enterTick", room.enterTick)
            o.addProperty("clearTick", room.clearTick)
            o.addProperty("secretsTick", room.secretsTick)
            o.addProperty("secretRunTicks", room.secretRunTicks)
            o.addProperty("secretsFound", room.secretsFound)
            o.addProperty("ownSecrets", room.ownSecrets)
            o.addProperty("ownTicks", room.ownTicks)
            o.addProperty("deaths", room.deaths)
            o.addProperty("preCleared", room.preCleared)
            rooms.add(o)
        }
        layout.add("rooms", rooms)
        val doors = JsonArray()
        for (door in r.layout.doors) {
            val o = JsonObject()
            o.add("a", cell(door.a))
            o.add("b", cell(door.b))
            o.addProperty("type", door.type.name)
            doors.add(o)
        }
        layout.add("doors", doors)
        obj.add("layout", layout)

        val route = JsonArray()
        for (v in r.route) {
            val o = JsonObject()
            o.add("cell", cell(v.cell))
            o.addProperty("enter", v.enter)
            o.addProperty("leave", v.leave)
            route.add(o)
        }
        obj.add("route", route)
        return obj
    }

    private fun cell(c: Cell) = JsonArray().apply { add(c.gx); add(c.gz) }

    /** Null when a required key is missing or malformed; optional keys read as null. */
    fun decode(obj: JsonObject): Record? = try {
        val score = obj.getAsJsonObject("score")
        val layout = obj.getAsJsonObject("layout")
        val final = score.opt("final")?.asJsonObject?.let {
            LiveScore.Breakdown(it["time"].asInt, it["explore"].asInt, it["skill"].asInt, it["bonus"].asInt, it["total"].asInt)
        }
        Record(
            ts = obj["ts"].asLong,
            floor = obj["floor"].asString,
            player = obj.optString("player"),
            complete = obj["complete"].asBoolean,
            modVersion = obj.optString("modVersion") ?: "",
            runTicks = obj["runTicks"].asInt,
            idleTicks = obj.optInt("idleTicks") ?: 0,
            navTicks = obj.optInt("navTicks") ?: 0,
            roomsCleared = obj.optInt("roomsCleared") ?: 0,
            secrets = obj.optInt("secrets"),
            secretsPercent = obj.opt("secretsPercent")?.asDouble,
            deaths = obj.optInt("deaths") ?: 0,
            clock = obj.optString("clock"),
            score = Score(
                high = score.optInt("high") ?: 0,
                projectedHigh = score.optInt("projectedHigh") ?: 0,
                source = score.optString("source") ?: "none",
                hypixel = score.optInt("hypixel"),
                final = final,
                marks = score.getAsJsonArray("marks").map { e ->
                    val m = e.asJsonObject
                    Mark(m["threshold"].asInt, m["kind"].asString, m["tick"].asInt, m.optString("clock"), m.optLong("ms") ?: 0L)
                },
                curve = (score.opt("curve")?.asJsonArray ?: JsonArray()).map { e ->
                    val pair = e.asJsonArray
                    pair[0].asInt to pair[1].asInt
                },
            ),
            blood = obj.opt("blood")?.asJsonObject?.let { Blood(it.optInt("open"), it.optInt("done")) } ?: Blood(null, null),
            layout = Floor(
                cols = layout["cols"].asInt,
                rows = layout["rows"].asInt,
                entrance = cell(layout["entrance"]),
                rooms = layout.getAsJsonArray("rooms").map { e ->
                    val o = e.asJsonObject
                    Room(
                        type = RoomType.valueOf(o["type"].asString),
                        state = RoomState.valueOf(o["state"].asString),
                        cells = o.getAsJsonArray("cells").mapTo(LinkedHashSet()) { cell(it) },
                        name = o.optString("name"),
                        shape = o.optString("shape"),
                        maxSecrets = o.optInt("maxSecrets"),
                        crypts = o.optInt("crypts"),
                        enterTick = o.optInt("enterTick"),
                        clearTick = o.optInt("clearTick"),
                        secretsTick = o.optInt("secretsTick"),
                        secretRunTicks = o.optInt("secretRunTicks"),
                        secretsFound = o.optInt("secretsFound"),
                        ownSecrets = o.optInt("ownSecrets"),
                        ownTicks = o.optInt("ownTicks"),
                        deaths = o.optInt("deaths"),
                        preCleared = o.opt("preCleared")?.asBoolean ?: false,
                    )
                },
                doors = (layout.opt("doors")?.asJsonArray ?: JsonArray()).map { e ->
                    val o = e.asJsonObject
                    Door(cell(o["a"]), cell(o["b"]), DoorType.valueOf(o["type"].asString))
                },
            ),
            route = (obj.opt("route")?.asJsonArray ?: JsonArray()).map { e ->
                val o = e.asJsonObject
                Visit(cell(o["cell"]), o["enter"].asInt, o["leave"].asInt)
            },
        )
    } catch (_: Exception) {
        null
    }

    private fun cell(e: JsonElement): Cell {
        val a = e.asJsonArray
        return Cell(a[0].asInt, a[1].asInt)
    }

    private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
    private fun JsonObject.optInt(key: String): Int? = opt(key)?.asInt
    private fun JsonObject.optLong(key: String): Long? = opt(key)?.asLong
    private fun JsonObject.optString(key: String): String? = opt(key)?.asString

    /** Every readable body as a record, newest first. Unreadable ones are skipped, not fatal. */
    fun fold(bodies: Sequence<String>): List<Record> {
        val out = ArrayList<Record>()
        for (body in bodies) {
            val record = try {
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject?.let(::decode)
            } catch (_: Exception) {
                null
            }
            if (record != null) out.add(record)
        }
        out.sortByDescending { it.ts }
        return out
    }

    /** The fastest tick to the projected 300 on [floor], over [records]. Runs that never got there do not count. */
    fun bestTo300(records: List<Record>, floor: String): Int? =
        records.filter { it.floor == floor }.mapNotNull { it.to300?.tick }.minOrNull()

    // --- Store --------------------------------------------------------------------------------

    private var loaded = false
    private val list = ArrayList<Record>()

    /** Bumped whenever [records] would answer differently, so a screen can cache its rows against it. */
    var revision = 0
        private set

    fun records(): List<Record> {
        ensureLoaded()
        return list
    }

    /** A run just written by [SoloRecorder]: on the list without a relaunch, newest first. */
    fun add(record: Record) {
        ensureLoaded()
        list.add(0, record)
        revision++
    }

    fun bestTo300(floor: String): Int? = bestTo300(records(), floor)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        revision++
        if (!Files.isDirectory(DIR)) return
        try {
            val files = Files.list(DIR).use { paths -> paths.filter { FILE.matches(it.name) }.toList() }
            val bodies = files.asSequence().mapNotNull { path ->
                try {
                    Files.readString(path)
                } catch (e: Exception) {
                    SighteAddons.LOGGER.warn("Could not read solo run {}", path, e)
                    null
                }
            }
            list.addAll(fold(bodies))
            SighteAddons.LOGGER.info("Solo runs: {} of {} files readable", list.size, files.size)
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not list {}", DIR, e)
        }
    }

    internal val FILE = Regex("""^solo-\d+\.json$""")

    fun dir(): Path = DIR

    // --- Sample -------------------------------------------------------------------------------

    /**
     * A believable M7 for the gallery and the tests: a 6×6 floor, a route through eleven rooms, the
     * projection crossing 270 and 300, and the run left at the blood door.
     */
    fun sample(now: Long = 1_757_100_000_000L): Record {
        fun room(
            type: RoomType, state: RoomState, cells: List<Cell>, name: String?, secrets: Int = 0,
            enter: Int? = null, clear: Int? = null, found: Int = 0, ticks: Int? = null,
        ) = Room(
            type, state, LinkedHashSet(cells), name, shape = when (cells.size) { 1 -> "1x1"; 2 -> "1x2"; 3 -> "L"; else -> "2x2" },
            maxSecrets = secrets, crypts = 0, enterTick = enter, clearTick = clear,
            secretsTick = if (state == RoomState.GREEN) clear else null,
            secretRunTicks = if (state == RoomState.GREEN && secrets > 0) 120 else null,
            secretsFound = found, ownSecrets = found, ownTicks = ticks, deaths = 0, preCleared = false,
        )
        val rooms = listOf(
            room(RoomType.ENTRANCE, RoomState.GREEN, listOf(Cell(2, 5)), "Entrance"),
            room(RoomType.ROOM, RoomState.GREEN, listOf(Cell(2, 4), Cell(3, 4)), "Atlas", 6, 40, 520, 6, 480),
            room(RoomType.PUZZLE, RoomState.CLEARED, listOf(Cell(1, 4)), "Water Board", 0, 560, 900, 0, 340),
            room(RoomType.ROOM, RoomState.GREEN, listOf(Cell(1, 3), Cell(1, 2)), "Crypt", 5, 940, 1400, 5, 460),
            room(RoomType.TRAP, RoomState.CLEARED, listOf(Cell(2, 3)), "Trap", 0, 1440, 1600, 0, 160),
            room(RoomType.ROOM, RoomState.GREEN, listOf(Cell(3, 3), Cell(4, 3), Cell(4, 2)), "Rabbit Hole", 4, 1640, 2200, 4, 560),
            room(RoomType.MINIBOSS, RoomState.CLEARED, listOf(Cell(3, 2)), "Shadow Assassin", 0, 2240, 2500, 0, 260),
            room(RoomType.ROOM, RoomState.GREEN, listOf(Cell(2, 2), Cell(2, 1)), "Fountain", 3, 2540, 3000, 3, 460),
            room(RoomType.FAIRY, RoomState.GREEN, listOf(Cell(3, 1)), "Fairy", 0, 3040, 3120, 0, 80),
            room(RoomType.ROOM, RoomState.GREEN, listOf(Cell(4, 1), Cell(5, 1), Cell(4, 0), Cell(5, 0)), "Arena", 4, 3160, 3900, 4, 740),
            room(RoomType.PUZZLE, RoomState.CLEARED, listOf(Cell(5, 2)), "Tic Tac Toe", 0, 3940, 4200, 0, 260),
            room(RoomType.ROOM, RoomState.DISCOVERED, listOf(Cell(5, 3)), "Overgrown", 3),
            room(RoomType.UNKNOWN, RoomState.UNOPENED, listOf(Cell(0, 2)), null),
            room(RoomType.BLOOD, RoomState.DISCOVERED, listOf(Cell(0, 4)), "Blood"),
        )
        val doors = listOf(
            Door(Cell(2, 5), Cell(2, 4), DoorType.NORMAL),
            Door(Cell(1, 4), Cell(2, 4), DoorType.NORMAL),
            Door(Cell(1, 4), Cell(1, 3), DoorType.NORMAL),
            Door(Cell(1, 3), Cell(2, 3), DoorType.NORMAL),
            Door(Cell(2, 3), Cell(3, 3), DoorType.NORMAL),
            Door(Cell(3, 3), Cell(3, 2), DoorType.WITHER),
            Door(Cell(2, 2), Cell(3, 2), DoorType.NORMAL),
            Door(Cell(2, 1), Cell(3, 1), DoorType.NORMAL),
            Door(Cell(3, 1), Cell(4, 1), DoorType.NORMAL),
            Door(Cell(5, 1), Cell(5, 2), DoorType.NORMAL),
            Door(Cell(5, 2), Cell(5, 3), DoorType.WITHER),
            Door(Cell(0, 2), Cell(1, 2), DoorType.NORMAL),
            Door(Cell(0, 4), Cell(1, 4), DoorType.BLOOD),
        )
        val route = listOf(
            Visit(Cell(2, 5), 0, 40), Visit(Cell(2, 4), 40, 300), Visit(Cell(3, 4), 300, 520), Visit(Cell(2, 4), 520, 540),
            Visit(Cell(1, 4), 540, 900), Visit(Cell(1, 3), 900, 1100), Visit(Cell(1, 2), 1100, 1400), Visit(Cell(1, 3), 1400, 1430),
            Visit(Cell(2, 3), 1430, 1600), Visit(Cell(3, 3), 1600, 1900), Visit(Cell(4, 3), 1900, 2100), Visit(Cell(4, 2), 2100, 2200),
            Visit(Cell(3, 3), 2200, 2230), Visit(Cell(3, 2), 2230, 2500), Visit(Cell(2, 2), 2500, 2800), Visit(Cell(2, 1), 2800, 3000),
            Visit(Cell(3, 1), 3000, 3120), Visit(Cell(4, 1), 3120, 3500), Visit(Cell(5, 1), 3500, 3700), Visit(Cell(5, 0), 3700, 3900),
            Visit(Cell(5, 1), 3900, 3930), Visit(Cell(5, 2), 3930, 4200), Visit(Cell(5, 1), 4200, 4230), Visit(Cell(4, 1), 4230, 4300),
            Visit(Cell(3, 1), 4300, 4340), Visit(Cell(2, 1), 4340, 4400), Visit(Cell(2, 2), 4400, 4460), Visit(Cell(2, 3), 4460, 4520),
            Visit(Cell(1, 3), 4520, 4560), Visit(Cell(1, 4), 4560, 4620), Visit(Cell(0, 4), 4620, 4820),
        )
        val curve = listOf(
            0 to 22, 520 to 61, 900 to 88, 1400 to 121, 1600 to 133, 2200 to 176, 2500 to 194,
            3000 to 228, 3120 to 236, 3900 to 279, 4200 to 293, 4620 to 301, 4700 to 307,
        )
        return Record(
            ts = now, floor = "M7", player = "Sighte", complete = false, modVersion = "sample",
            runTicks = 4820, idleTicks = 210, navTicks = 640, roomsCleared = 11,
            secrets = 27, secretsPercent = 100.0, deaths = 0, clock = "04m 01s",
            score = Score(
                high = 265, projectedHigh = 307, source = SIDEBAR, hypixel = null,
                final = LiveScore.Breakdown(100, 100, 100, 7, 307),
                marks = listOf(
                    Mark(270, PROJECTED, 3800, "03m 10s", now - 60_000L),
                    Mark(300, PROJECTED, 4620, "03m 51s", now - 10_000L),
                ),
                curve = curve,
            ),
            blood = Blood(open = 4560, done = null),
            layout = Floor(6, 6, Cell(2, 5), rooms, doors),
            route = route,
        )
    }
}
