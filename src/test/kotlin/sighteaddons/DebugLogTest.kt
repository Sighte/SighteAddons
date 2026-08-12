package sighteaddons

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The log is read back programmatically, so the record format is a contract worth pinning. */
class DebugLogTest {
    @Test
    fun `record keeps types and escapes freely`() {
        val json = JsonParser.parseString(
            DebugLog.line(
                "room_unmatched",
                42,
                arrayOf(
                    "core" to -1701988142,
                    "column" to """Block{minecraft:stone}Block{minecraft:"quote"}""",
                    "name" to null,
                    "matched" to false,
                    "cell" to Pos(-200, -168),
                ),
            ),
        ).asJsonObject

        assertEquals(42, json["t"].asInt)
        assertEquals("room_unmatched", json["e"].asString)
        // Numbers must survive as numbers, not as strings — the log is filtered on them.
        assertEquals(-1701988142, json["core"].asInt)
        assertTrue(json["name"].isJsonNull)
        assertEquals(false, json["matched"].asBoolean)
        assertEquals("Pos(x=-200, z=-168)", json["cell"].asString)
        assertTrue(json["column"].asString.contains("\"quote\""))
    }

    @Test
    fun `record is a single line so the file stays JSONL`() {
        val line = DebugLog.line("cleared", 1, arrayOf("room" to "Water\nBoard"))
        assertEquals(1, line.lines().size)
    }
}
