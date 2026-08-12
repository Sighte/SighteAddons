package sighteaddons

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SkullBlockEntity

/**
 * Counts secrets per room, and how many of them were provably yours.
 *
 * Two independent signals are combined:
 *
 * 1. Hypixel puts the **current room's** secret progress in the action bar (`6/10 Secrets`). That is
 *    a per-room number, not a per-player one — a rise only says *somebody* in that room found one.
 * 2. The client sees its **own** interactions. Right-clicking a chest, lever or one of the two secret
 *    skull types is visible locally, which teammates' interactions never are.
 *
 * A rise that coincides with your own interaction is yours. A rise without one belongs to somebody
 * else, regardless of who is standing where — which is stronger than guessing from presence alone,
 * and works even with the whole party digging through the same room.
 *
 * Secret coordinates are deliberately not needed for this; only the count from the action bar and
 * the room's expected total from the room database, which guards against attributing the number to
 * the wrong room.
 */
object SecretTracker {
    /** The action bar carries several stats, so this matches inside it rather than the whole string. */
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")

    /** Ticks the counter may lag behind your click before the two stop being considered related. */
    private const val OWN_WINDOW = 40

    /** Skull profile ids Hypixel uses for secret skulls, as identified by Odin and NoammAddons. */
    private val SECRET_SKULLS = setOf(
        "2865274b-3097-394e-8149-ec629c72d850", // wither essence
        "fed95410-aba1-39df-9b95-1d4f361eb66e", // redstone key
    )

    private var lastOwnInteraction = Int.MIN_VALUE

    fun init() {
        UseBlockCallback.EVENT.register { _, level, _, hit ->
            // Read-only observer: always PASS so the interaction itself is untouched.
            if (DungeonSession.calibrated) {
                val type = secretTypeAt(level, hit.blockPos)
                if (type != null) {
                    lastOwnInteraction = DungeonSession.runTicks
                    DebugLog.event("own_interaction", "type" to type, "pos" to hit.blockPos)
                }
            }
            InteractionResult.PASS
        }
    }

    fun reset() {
        lastOwnInteraction = Int.MIN_VALUE
    }

    /** Called for every action bar update while in a dungeon. */
    fun onActionBar(text: String, playerX: Double, playerZ: Double) {
        if (!DungeonSession.calibrated) return
        val match = SECRETS.find(text) ?: return
        val found = match.groupValues[1].toIntOrNull() ?: return
        val max = match.groupValues[2].toIntOrNull() ?: return

        val room = ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(playerX, playerZ)) ?: return
        val expected = room.info?.secrets

        // Only trust the number if the total it reports matches what the database says this room has.
        // Without that check a stale action bar gets attributed to whichever room you walked into next.
        if (expected == null || expected != max) {
            DebugLog.event(
                "secret_room_mismatch",
                "room" to room.label(), "barMax" to max, "expected" to (expected ?: -1), "barFound" to found,
            )
            return
        }
        if (found <= room.secretsFound) return

        val delta = found - room.secretsFound
        room.secretsFound = found

        val mine = DungeonSession.runTicks - lastOwnInteraction <= OWN_WINDOW
        if (mine) {
            room.ownSecrets++
            lastOwnInteraction = Int.MIN_VALUE // one click credits one secret, not a whole burst
        }
        DebugLog.event(
            "secret",
            "room" to room.label(), "found" to found, "max" to max, "delta" to delta,
            "mine" to mine, "ownTotal" to room.ownSecrets,
        )
    }

    /** Null when the block is not a secret. Mirrors the block set Odin and NoammAddons use. */
    private fun secretTypeAt(level: Level, pos: BlockPos): String? = when (level.getBlockState(pos).block) {
        Blocks.CHEST, Blocks.TRAPPED_CHEST -> "chest"
        Blocks.LEVER -> "lever"
        Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD -> {
            val id = (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile()?.id?.toString()
            if (id in SECRET_SKULLS) "skull" else null
        }
        else -> null
    }
}
