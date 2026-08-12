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
            clearedAtTick = 400
            secretsFound = 3
            ownSecrets = 2
            deaths = 1
        }

    private fun report() = RunReport.build(
        ts = 1786530882102,
        uuid = "0f5e4a1c-1111-2222-3333-444455556666",
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
        assertEquals(1, json["v"].asInt)
        assertEquals("M5", json["floor"].asString)
        assertEquals(2, json["partySize"].asInt)
        assertEquals(1.25, json["unattributed"].asDouble)
        assertEquals("Berserk", json["class"].asString)
        assertEquals("VII", json["classLevel"].asString)
    }

    @Test
    fun `teammates appear as classes, never as names`() {
        val json = report()
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })
        // The uploader's own name is their own data; the party-finder stranger's is not.
        assertTrue(json.toString().contains("Sighte"))
        assertFalse(json.toString().contains("Stranger"))
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
}
