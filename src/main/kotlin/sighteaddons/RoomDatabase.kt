package sighteaddons

import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

data class RoomInfo(
    val name: String,
    val type: String,
    val shape: String,
    val secrets: Int,
    val crypts: Int,
)

/**
 * Identifies a dungeon room by name, which is what makes cross-run personal bests possible:
 * rooms sit at different grid positions in every run, so the position cannot be the key.
 *
 * The identity is a "core" hash of the block column through the centre of a room segment.
 * The algorithm and the bundled `rooms.json` are ported from Odin, so the hashes match its
 * data — any deviation in the string built below invalidates the whole database.
 *
 * Derived from Odin (https://github.com/odtheking/Odin), BSD 3-Clause, Copyright (c) 2025 odtheking.
 * Redistribution must retain that copyright notice, this list of conditions and the disclaimer
 * in the accompanying LICENSE-Odin file.
 */
object RoomDatabase {
    /** Sample column sits at the segment centre: 15 blocks in from the northwest corner. */
    private const val CENTRE_OFFSET = 15
    private const val TOP_Y = 140
    private const val BOTTOM_Y = 12

    private val byCore: Map<Int, RoomInfo> by lazy { load() }

    /** Several cores share one room, so this collapses them back to one entry per name. */
    private val byName: Map<String, RoomInfo> by lazy { byCore.values.associateBy { it.name } }

    val size get() = byCore.size

    fun lookup(core: Int): RoomInfo? = byCore[core]

    /**
     * The room behind a name. Cores are per grid cell and the history only stores names, so this is
     * what lets the records table group rooms by type without widening the history format.
     */
    fun infoByName(name: String): RoomInfo? = byName[name]

    /**
     * The block column through the centre of the segment at [corner]; its `hashCode()` is the core
     * the database is keyed by. An all-'0' result means an empty grid cell outside the layout.
     *
     * Walks down from [TOP_Y], padding with '0' for every air (and gold block, which Hypixel
     * uses for the run-start barrier) above the room's roof, then appends each block below it.
     * Planks and chests are skipped because they vary between instances of the same room, and
     * the walk stops once it hits air below the floor's bedrock layer.
     */
    fun columnAt(level: Level, corner: Pos): String {
        val builder = StringBuilder(1024)
        val x = corner.x + CENTRE_OFFSET
        val z = corner.z + CENTRE_OFFSET
        var foundHighest = false
        var bedrock = 0

        for (y in TOP_Y downTo BOTTOM_Y) {
            val state = level.getBlockState(BlockPos(x, y, z))

            if (!foundHighest) {
                if (!state.isAir && state.block !== Blocks.GOLD_BLOCK) foundHighest = true
                else builder.append('0')
            }

            if (foundHighest) {
                if (state.isAir && bedrock >= 2 && y < 69) {
                    repeat(y - BOTTOM_Y + 1) { builder.append('0') }
                    break
                }
                if (state.block === Blocks.BEDROCK) {
                    bedrock++
                } else {
                    bedrock = 0
                    if (state.block === Blocks.OAK_PLANKS ||
                        state.block === Blocks.TRAPPED_CHEST ||
                        state.block === Blocks.CHEST
                    ) continue
                }
                builder.append(state.block)
            }
        }
        return builder.toString()
    }

    private fun load(): Map<Int, RoomInfo> {
        val stream = RoomDatabase::class.java.getResourceAsStream("/assets/sighteaddons/rooms.json")
        if (stream == null) {
            SighteAddons.LOGGER.error("rooms.json missing — room names and personal bests are disabled")
            return emptyMap()
        }
        val result = HashMap<Int, RoomInfo>()
        stream.reader().use { reader ->
            for (element in JsonParser.parseReader(reader).asJsonArray) {
                val obj = element.asJsonObject
                val info = RoomInfo(
                    name = obj["name"].asString,
                    type = obj["type"].asString,
                    shape = obj["shape"].asString,
                    secrets = obj["secrets"]?.asInt ?: 0,
                    crypts = obj["crypts"]?.asInt ?: 0,
                )
                for (core in obj["cores"].asJsonArray) result[core.asInt] = info
            }
        }
        SighteAddons.LOGGER.info("Loaded {} room cores", result.size)
        return result
    }
}
