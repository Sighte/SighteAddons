package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Reading a real Odin config into this mod's record store.
 *
 * **This is the one part of the port that touches data somebody already owns.** A player's split
 * records are years of runs and there is no second copy: a key mapped wrongly does not fail, it files
 * the time under a name nothing will ever look up again, and a merge that overwrote instead of taking
 * the minimum would replace a good record with a worse one and there would be no way back. So the
 * mapping, the floor filter and the direction of the merge are pinned here.
 *
 * The fixture below is the shape of the real file — a flat array of modules, `settings` as a map, split
 * names carrying a legacy colour code as the first two characters of the key, and Kuudra's totals
 * carrying none. It is trimmed, not invented.
 */
class SplitPbsTest {

    @BeforeEach
    fun clearRecords() = SplitPbs.clear()

    /** Odin's file, as it is actually written: the module list, with `Splits` somewhere in it. */
    private val odinConfig = """
        [
          { "name": "Room Clear", "enabled": false, "settings": { "Mode": "Both" } },
          { "name": "Splits", "enabled": true, "settings": {
              "Splits Display HUD": { "x": 0, "y": 546, "scale": 2.6000001, "enabled": true },
              "Show Tick Time": true,
              "Split Location": "Both",
              "DungeonF7": { "§2Blood Open": 0.604, "§6Terminals": 80.396, "§1Total": 328.604 },
              "DungeonM7": { "§2Blood Open": 0.372, "§5Maxor": 24.608, "§1Total": 276.517 },
              "DungeonF2": {},
              "KuudraT5": { "§2Supplies": 28.545, "Total": 69.929 }
          } }
        ]
    """.trimIndent()

    private fun splitsSettings(): JsonObject {
        val settings = SplitPbs.splitsSettings(odinConfig)
        assertNotNull(settings, "the Splits module is in there")
        return settings!!
    }

    @Test
    fun `odin's colour-coded keys become this mod's names`() {
        // The code is the first two characters of the key itself, so it is dropped rather than searched
        // for; the lower case is this UI's, which has no capitals in a label anywhere.
        assertEquals("blood open", SplitPbs.nameOfOdinKey("§2Blood Open"))
        assertEquals("terminals", SplitPbs.nameOfOdinKey("§6Terminals"))
        // Odin spells the dungeon total with a colour code and the Kuudra one without. Both are the
        // same split of the same kind and they come out as one name here.
        assertEquals("total", SplitPbs.nameOfOdinKey("§1Total"))
        assertEquals("total", SplitPbs.nameOfOdinKey("Total"))
    }

    @Test
    fun `only the fifteen dungeon floors come across`() {
        assertEquals("M7", SplitPbs.tagOfOdinFloorKey("DungeonM7"))
        assertEquals("E", SplitPbs.tagOfOdinFloorKey("DungeonE"))
        // Kuudra is in the same object and this mod does not time it yet. Importing it would put records
        // in the file that no run here could have produced.
        assertNull(SplitPbs.tagOfOdinFloorKey("KuudraT5"))
        // Not every key under `settings` is a floor — most of them are settings.
        assertNull(SplitPbs.tagOfOdinFloorKey("Show Tick Time"))
        assertNull(SplitPbs.tagOfOdinFloorKey("DungeonF8"))
    }

    @Test
    fun `a merge brings the records in and says how many it changed`() {
        assertEquals(6, SplitPbs.merge(splitsSettings()), "three from F7 and three from M7")
        assertEquals(328.604f, SplitPbs.get("F7", "total"))
        assertEquals(276.517f, SplitPbs.get("M7", "total"))
        assertEquals(24.608f, SplitPbs.get("M7", "maxor"))
        // An empty floor is a floor with no records, not a floor of zeroes.
        assertEquals(emptyMap<String, Float>(), SplitPbs.of(SplitPbs.floorKey("F2")))
        assertNull(SplitPbs.get("T5", "supplies"))
    }

    @Test
    fun `the merge takes the faster of the two and is idempotent`() {
        // A record set in this mod that is better than Odin's must survive the import, and one that is
        // worse must be replaced: both stores hold the same quantity, so the smaller number is the
        // answer whichever file it came from.
        SplitPbs.record("M7", "total", 200f)
        SplitPbs.record("M7", "maxor", 30f)

        assertEquals(5, SplitPbs.merge(splitsSettings()), "maxor improves, total does not")
        assertEquals(200f, SplitPbs.get("M7", "total"), "our faster total stands")
        assertEquals(24.608f, SplitPbs.get("M7", "maxor"), "odin's faster maxor wins")

        // Running it twice is how a player finds out whether they already did, so it has to be free.
        assertEquals(0, SplitPbs.merge(splitsSettings()))
    }

    @Test
    fun `a record is only filed when it is actually faster`() {
        assertEquals(SplitPbs.Result.First, SplitPbs.record("F7", "storm", 48.6f))
        assertEquals(SplitPbs.Result.Beat(48.6f), SplitPbs.record("F7", "storm", 45.1f))
        assertEquals(SplitPbs.Result.Missed(45.1f), SplitPbs.record("F7", "storm", 50f))
        assertEquals(45.1f, SplitPbs.get("F7", "storm"))
        // Two chat lines on one millisecond is the case, and an unbeatable zero would sit on the board
        // for good — the same refusal BloodClear.onDone makes about a non-positive span.
        assertNull(SplitPbs.record("F7", "storm", 0f))
    }

    @Test
    fun `the store survives a round trip through the config file`() {
        SplitPbs.merge(splitsSettings())
        val written = JsonObject().also { SplitPbs.write(it) }

        SplitPbs.clear()
        SplitPbs.read(JsonParser.parseString(written.toString()).asJsonObject)
        assertEquals(276.517f, SplitPbs.get("M7", "total"))

        // A hand-edited file is a supported way to set this one, so a floor of the wrong shape has to
        // cost that floor and nothing below it — ConfigMigration's argument, one level down.
        SplitPbs.clear()
        SplitPbs.read(
            JsonParser.parseString(
                """{"splitPbs": {"DungeonF7": "nonsense", "DungeonM7": {"total": 1.5, "maxor": "x"}}}""",
            ).asJsonObject,
        )
        assertEquals(1.5f, SplitPbs.get("M7", "total"))
        assertNull(SplitPbs.get("M7", "maxor"))
        assertNull(SplitPbs.get("F7", "total"))
    }

    @Test
    fun `a file with no Splits module is not an import`() {
        assertNull(SplitPbs.splitsSettings("""[{"name": "Room Clear", "enabled": false, "settings": {}}]"""))
        // And neither is something that is not Odin's file at all. This reads a path another mod owns,
        // so anything but the expected shape has to be a no-op rather than an exception.
        assertNull(SplitPbs.splitsSettings("""{"name": "Splits"}"""))
    }
}
