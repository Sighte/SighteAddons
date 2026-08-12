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

    /**
     * The action bar still carries legacy `§` colour codes, and one sits directly in front of the
     * secret count: `§70/8 Secrets` is grey plus `0/8`, not seventy out of eight. `\d+` swallows the
     * code's digit, so every count came out prefixed with a 7 and each room's first reading looked
     * like a jump of seventy secrets. Stripped before matching rather than worked around inside
     * [SECRETS], because the same codes sit around every other number the bar carries.
     */
    private val FORMATTING = Regex("§.")

    /** Ticks the counter may lag behind your click before the two stop being considered related. */
    private const val OWN_WINDOW = 40

    /** A repeat of one click within this many ticks is the same interaction, not a second find. */
    private const val REPEAT_WINDOW = 5

    /** No click seen yet this run. Compared, never subtracted from — see [isOwn]. */
    private const val NO_INTERACTION = Int.MIN_VALUE

    /** Skull profile ids Hypixel uses for secret skulls, as identified by Odin and NoammAddons. */
    private val SECRET_SKULLS = setOf(
        "2865274b-3097-394e-8149-ec629c72d850", // wither essence
        "fed95410-aba1-39df-9b95-1d4f361eb66e", // redstone key
    )

    private var lastOwnInteraction = NO_INTERACTION

    /** Only suppresses duplicate log lines; unlike [lastOwnInteraction] it survives a credit. */
    private var lastLoggedPos: BlockPos? = null
    private var lastLoggedTick = NO_INTERACTION

    fun init() {
        UseBlockCallback.EVENT.register { _, level, _, hit ->
            // Read-only observer: always PASS so the interaction itself is untouched.
            if (DungeonSession.calibrated) {
                val type = secretTypeAt(level, hit.blockPos)
                if (type != null) {
                    val pos = hit.blockPos.immutable()
                    lastOwnInteraction = DungeonSession.runTicks
                    // One right click arrives more than once — main hand and off hand both fire —
                    // so the raw stream showed every chest two to four times over.
                    if (!isRepeat(pos)) {
                        lastLoggedPos = pos
                        lastLoggedTick = DungeonSession.runTicks
                        DebugLog.event("own_interaction", "type" to type, "pos" to pos)
                    }
                }
            }
            InteractionResult.PASS
        }
    }

    fun reset() {
        lastOwnInteraction = NO_INTERACTION
        lastLoggedPos = null
        lastLoggedTick = NO_INTERACTION
    }

    private fun isRepeat(pos: BlockPos) = pos == lastLoggedPos &&
        lastLoggedTick != NO_INTERACTION && DungeonSession.runTicks - lastLoggedTick <= REPEAT_WINDOW

    /** Called for every action bar update while in a dungeon. */
    fun onActionBar(text: String, playerX: Double, playerZ: Double) {
        if (!DungeonSession.calibrated) return
        val bar = parseSecrets(text) ?: return

        val room = ContributionTracker.roomAt(DungeonGrid.physicalRoomPos(playerX, playerZ)) ?: return
        val expected = room.info?.secrets

        // Only trust the number if the total it reports matches what the database says this room has.
        // Without that check a stale action bar gets attributed to whichever room you walked into next.
        if (expected == null || expected != bar.max) {
            DebugLog.event(
                "secret_room_mismatch",
                "room" to room.label(), "barMax" to bar.max, "expected" to (expected ?: -1),
                "barFound" to bar.found,
            )
            return
        }
        if (bar.found <= room.secretsFound) return

        val delta = bar.found - room.secretsFound
        room.secretsFound = bar.found

        val mine = isOwn(DungeonSession.runTicks, lastOwnInteraction)
        if (mine) {
            room.ownSecrets++
            lastOwnInteraction = NO_INTERACTION // one click credits one secret, not a whole burst
        }
        DebugLog.event(
            "secret",
            "room" to room.label(), "found" to bar.found, "max" to bar.max, "delta" to delta,
            "mine" to mine, "ownTotal" to room.ownSecrets,
        )
    }

    /** The room's secret counter as the action bar reports it. */
    internal data class BarSecrets(val found: Int, val max: Int)

    /** Null when the bar carries no secret counter at all, which is most of the time. */
    internal fun parseSecrets(text: String): BarSecrets? {
        val match = SECRETS.find(FORMATTING.replace(text, "")) ?: return null
        val found = match.groupValues[1].toIntOrNull() ?: return null
        val max = match.groupValues[2].toIntOrNull() ?: return null
        return BarSecrets(found, max)
    }

    /**
     * Whether a secret that just appeared can be credited to the local player.
     *
     * [NO_INTERACTION] is compared, never subtracted: `runTicks - Int.MIN_VALUE` overflows into a
     * large negative number, which passes the window check. That silently credited every secret
     * found *without* a preceding click — the first reading of every room, and every reading after
     * a credit had reset the timestamp back to the sentinel.
     */
    internal fun isOwn(runTicks: Int, lastInteraction: Int) =
        lastInteraction != NO_INTERACTION && runTicks - lastInteraction <= OWN_WINDOW

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
