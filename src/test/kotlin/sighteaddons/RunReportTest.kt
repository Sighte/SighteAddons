package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    private fun report() = RunReport.build(
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
}
