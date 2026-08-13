package sighteaddons

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The report is the permanent record every later evaluation reads, so its shape is a contract:
 * once runs are on the server in schema 1, nothing can go back and re-derive a field that was
 * never written.
 */
class RunReportTest {
    private fun room(name: String, ticks: Map<String, Int>) =
        TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0), Pos(32, 0))).apply {
            this.name = name
            info = RoomInfo(name, "normal", "1x2", secrets = 4, crypts = 2)
            ticks.forEach { (player, t) -> this.ticks[player] = t }
            enteredAtTick = 120
            clearedAtTick = 400
            secretsFound = 3
            ownSecrets = 2
            deaths = 1
        }

    private fun report(named: Boolean = false) = RunReport.build(
        named = named,
        ts = 1786530882102,
        installId = "0f5e4a1c-1111-2222-3333-444455556666",
        player = "Sighte",
        floor = "M5",
        runTicks = 8000,
        roster = listOf(DungeonPlayer("Sighte", "Berserk", "VII"), DungeonPlayer("Stranger", "Mage", "L")),
        rooms = listOf(room("Catwalk", mapOf("Sighte" to 300, "Stranger" to 15))),
        roomsCleared = 7,
        unattributed = 1.25,
        deaths = 2,
        modVersion = "0.3.0",
        mcVersion = "26.1.2",
    )

    @Test
    fun `run context survives`() {
        val json = report()
        assertEquals(3, json["v"].asInt)
        assertEquals("M5", json["floor"].asString)
        assertEquals(2, json["partySize"].asInt)
        assertEquals(1.25, json["unattributed"].asDouble)
        assertEquals("Berserk", json["class"].asString)
        assertEquals("VII", json["classLevel"].asString)
    }

    @Test
    fun `nobody appears by name, not even the uploader`() {
        val json = report()
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })
        // The stranger never belonged in here. The uploader's own name went with 0.5.0: the mod
        // ships to everyone now, and the install id is what the server files a run under.
        assertFalse(json.toString().contains("Stranger"))
        assertFalse(json.toString().contains("Sighte"))
        assertFalse(json.has("player"))
    }

    /** Everything the server knows about a player hangs off this one string. */
    @Test
    fun `the run is keyed by the install id`() {
        assertEquals("0f5e4a1c-1111-2222-3333-444455556666", report()["uuid"].asString)
    }

    /**
     * Opting in adds the uploader's own name and nothing else. The switch is in `/sa` → debug and it
     * exists for the leaderboards: a row needs a label, and that label is the player's to give.
     */
    @Test
    fun `opting in names the uploader and still nobody else`() {
        val json = report(named = true)
        assertEquals("Sighte", json["player"].asString)
        // The id stays the identity — the name annotates it rather than replacing it, so a profile
        // survives the switch going back off.
        assertEquals("0f5e4a1c-1111-2222-3333-444455556666", json["uuid"].asString)
        // The teammate is not the uploader's to hand over, and no setting of theirs may change that.
        assertFalse(json.toString().contains("Stranger"))
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })

        // The receiver takes the key's presence as the consent, so "not named" has to mean absent
        // rather than null — see check_run in vps/ingest.py, where `player` is the one optional key.
        assertFalse(report(named = false).has("player"))
    }

    /** A player who is dead when the headline prints must not reach the record as class "DEAD". */
    @Test
    fun `a player dead at the end still reports their class`() {
        val json = RunReport.build(
            ts = 1786530882102,
            installId = "0f5e4a1c-1111-2222-3333-444455556666",
            player = "Sighte",
            floor = "M5",
            runTicks = 8000,
            roster = listOf(
                DungeonPlayer("Sighte", "Berserk", "VII"),
                DungeonPlayer("Stranger", "DEAD", "", livingClass = "Mage", livingLevel = "L"),
            ),
            rooms = listOf(room("Catwalk", mapOf("Sighte" to 300))),
            roomsCleared = 7,
            unattributed = 0.0,
            deaths = 2,
            modVersion = "0.3.0",
            mcVersion = "26.1.2",
        )
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })
    }

    @Test
    fun `room carries party effort, not just own time`() {
        val room = report()["rooms"].asJsonArray[0].asJsonObject
        // 300 + 15: everything counts towards effort...
        assertEquals(315, room["playerTicks"].asInt)
        // ...but 15 ticks is walking through, not being in the room.
        assertEquals(1, room["playersInRoom"].asInt)
        assertEquals(300, room["ownTicks"].asInt)
        assertEquals(2, room["segments"].asInt)
        assertEquals(4, room["maxSecrets"].asInt)
        assertEquals(2, room["crypts"].asInt)
        assertEquals(1, room["deaths"].asInt)
        assertTrue(room["secretsTick"].isJsonNull)
    }

    /**
     * Both ticks are run timestamps. Only together are they a clear duration, which is the whole
     * reason `enterTick` exists — the server subtracts them, so a report carrying one without the
     * other contributes nothing.
     */
    @Test
    fun `room carries the clear anchor next to the checkmark`() {
        val room = report()["rooms"].asJsonArray[0].asJsonObject
        assertEquals(120, room["enterTick"].asInt)
        assertEquals(400, room["clearTick"].asInt)
    }

    @Test
    fun `a room nobody was ever seen in reports a null anchor`() {
        val untouched = TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0)))
        val json = RunReport.build(
            ts = 1786530882102,
            installId = "0f5e4a1c-1111-2222-3333-444455556666",
            player = "Sighte",
            floor = "M5",
            runTicks = 8000,
            roster = listOf(DungeonPlayer("Sighte", "Berserk", "VII")),
            rooms = listOf(untouched),
            roomsCleared = 0,
            unattributed = 0.0,
            deaths = 0,
            modVersion = "0.3.0",
            mcVersion = "26.1.2",
        )
        assertTrue(json["rooms"].asJsonArray[0].asJsonObject["enterTick"].isJsonNull)
    }

    // --- restamp: the queue between "written" and "sent" ---

    private val installId = "0f5e4a1c-1111-2222-3333-444455556666"

    /** A queued report, written the way [RunReport.write] names it. */
    private fun queued(dir: Path, ts: Long, named: Boolean = false): Path {
        Files.createDirectories(dir)
        return dir.resolve("run-$ts-$installId.json").also {
            Files.writeString(it, report(named).toString())
        }
    }

    private fun read(file: Path) = JsonParser.parseString(Files.readString(file)).asJsonObject

    /**
     * The gap this closes: a report is written at run end and only handed over at the next game
     * start, so consent given in between has to reach what is still waiting.
     */
    @Test
    fun `switching on names the reports still waiting to be sent`(@TempDir dir: Path) {
        val first = queued(dir, 1786530000000)
        val second = queued(dir, 1786531000000)

        assertEquals(2, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(first)["player"].asString)
        assertEquals("Sighte", read(second)["player"].asString)
        // Nothing else about the run may move: it is the same run, only now attributed.
        assertEquals(installId, read(first)["uuid"].asString)
        assertEquals(1, read(first)["rooms"].asJsonArray.size())
        // Against a freshly built report rather than a literal: restamping must not touch the schema
        // version, and saying so this way survives the next bump instead of failing on it.
        assertEquals(report()["v"].asInt, read(first)["v"].asInt)
        assertEquals(1.25, read(first)["unattributed"].asDouble)
    }

    @Test
    fun `switching off removes the field rather than nulling it`(@TempDir dir: Path) {
        val file = queued(dir, 1786530000000, named = true)

        assertEquals(1, RunReport.restamp(dir, null))
        // Absent, not null. The receiver reads the key's presence as the consent, so a null would be
        // a claim about a name instead of the silence that was asked for.
        assertFalse(read(file).has("player"))
        // The exact key, not the substring: every room legitimately carries `playerTicks`.
        assertFalse(Files.readString(file).contains("\"player\":"))
    }

    @Test
    fun `restamping is idempotent and survives being toggled`(@TempDir dir: Path) {
        val file = queued(dir, 1786530000000)

        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        // Already correct, so nothing is rewritten — the count is changes, not files seen.
        assertEquals(0, RunReport.restamp(dir, "Sighte"))
        assertEquals(1, RunReport.restamp(dir, null))
        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(file)["player"].asString)
        // One entry, not one per toggle.
        assertEquals(1, Regex("\"player\":").findAll(Files.readString(file)).count())
    }

    /** The promise the switch makes: what has left the machine stays as it left. */
    @Test
    fun `already uploaded and rejected reports are out of reach`(@TempDir dir: Path) {
        val pending = queued(dir, 1786530000000)
        // Same valid report name, one directory down — the boundary is the directory, so a matching
        // name must not be enough to get edited.
        val sent = queued(dir.resolve("uploaded"), 1786520000000)
        val rejected = queued(dir.resolve("rejected"), 1786510000000)

        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(pending)["player"].asString)
        assertFalse(read(sent).has("player"))
        assertFalse(read(rejected).has("player"))
    }

    @Test
    fun `an unreadable or foreign file is skipped, not fatal`(@TempDir dir: Path) {
        val good = queued(dir, 1786530000000)
        Files.writeString(dir.resolve("run-1786531000000-$installId.json"), "{not json")
        // Not a report name: a stray file in this directory is none of our business.
        Files.writeString(dir.resolve("notes.txt"), "{}")
        Files.writeString(dir.resolve("run-nope.json"), "{}")

        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(good)["player"].asString)
        assertEquals("{not json", Files.readString(dir.resolve("run-1786531000000-$installId.json")))
        assertEquals("{}", Files.readString(dir.resolve("notes.txt")))
        // No leftovers from the temporary file the replace goes through.
        val names = Files.list(dir).use { paths -> paths.toList() }.map { it.fileName.toString() }
        assertTrue(names.none { it.endsWith(".part") }, "left a .part file behind: $names")
    }

    @Test
    fun `a missing queue is not an error`(@TempDir dir: Path) {
        assertEquals(0, RunReport.restamp(dir.resolve("never-created"), "Sighte"))
        assertEquals(0, RunReport.restamp(dir, "Sighte")) // exists but empty
    }
}
