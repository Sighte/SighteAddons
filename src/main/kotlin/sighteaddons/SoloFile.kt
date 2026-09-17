package sighteaddons

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import sighteaddons.ui.Format

/**
 * Files a solo run that reached 300 with the receiver, on the tick [SoloRecorder] writes it locally.
 *
 * **Why the box holds a copy at all.** On 2026-09-17 the user edited one of the tab's `solo-<ts>.json`
 * files, pressed *post*, and Discord showed the edited numbers — the old route relayed whatever the client
 * sent. A signature here would not help: the key would sit in this jar. What this jar cannot forge is the
 * receiver's clock. So the run goes to the box now, within minutes of its `ts` (`POST /v1/solo_run`), the
 * box keeps that copy, and a later post from the tab ([SoloPost]) only names it. Edit the local file all
 * you like afterwards; the channel gets what the box took.
 *
 * **Only the 300s.** The user's line, so the box does not fill with every attempt. A record without a 300
 * mark returns here at once and stays what it was — the tab's own history, not postable.
 *
 * **One attempt, no queue, no retry.** The receiver refuses a filing more than ten minutes after the run,
 * so a retry at the next launch would be refused anyway. A box that is down at the wrong moment costs the
 * post of that one run — the chat line says so, and the tab says so on the run's page.
 *
 * What leaves the machine is what the post used to carry: the player's name, floor, the time at the
 * crossing, counts, the scorer's breakdown and the drawn map. **No `pb` and no `video`** — the record is
 * the box's own comparison now, and the link is added at posting time. The receiver refuses either key at
 * filing rather than ignoring it, so this side cannot drift into making claims without noticing.
 */
object SoloFile {
    internal const val ROUTE = "/v1/solo_run"

    /** Right after the record is on disk and on the list. Client thread; the HTTP happens off it. */
    fun file(record: SoloRuns.Record) {
        val mark = record.to300 ?: return
        val player = record.player ?: Minecraft.getInstance().player?.name?.string
        if (player == null) {
            DebugLog.event("solo_file_skipped", "ts" to record.ts, "why" to "no player name")
            return
        }
        val body = payload(record, player, mark)
        // The floor as a picture, base64 in the body. A drawing that fails is a filing without one.
        val png = try {
            SoloMapImage.render(record)
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not draw the map for the filing", e)
            null
        }
        png?.let { body.addProperty("map_png", java.util.Base64.getEncoder().encodeToString(it)) }
        DebugLog.event(
            "solo_file",
            "ts" to record.ts, "floor" to record.floor, "to300" to mark.tick, "mapBytes" to (png?.size ?: 0),
        )
        SoloClear.post(body.toString(), ROUTE) { ok, reason ->
            Minecraft.getInstance().execute {
                if (ok) {
                    SoloRuns.markFiled(record, System.currentTimeMillis())
                    DebugLog.event("solo_filed", "ts" to record.ts)
                } else {
                    DebugLog.event("solo_file_failed", "ts" to record.ts, "why" to reason)
                    // Said once, because the consequence is permanent: this run will not be postable.
                    Chat.say(
                        Chat.label("solo run not filed")
                            .append(Chat.meta(Chat.FIELD + reason))
                            .append(Chat.meta(Chat.FIELD + "kept here, cannot be posted")),
                    )
                }
            }
        }
    }

    /**
     * The filing body. Pure over the record, like [SoloClear.payload] is over the run.
     *
     * The time is Hypixel's clock at the crossing when it was read, our ticks otherwise. The score is the
     * projection's final breakdown, which is what crossed the line; Hypixel's `Team Score:` only exists for
     * a run that did the boss. `ts` and `to300` are what the receiver files the run under and checks its
     * clock against.
     */
    internal fun payload(record: SoloRuns.Record, player: String, mark: SoloRuns.Mark): JsonObject =
        SoloClear.payload(
            player = player,
            floor = record.floor,
            time = mark.clock ?: DungeonGrid.formatTicks(mark.tick),
            secrets = record.secrets,
            deaths = record.deaths,
            score = record.score.final?.total ?: record.score.projectedHigh.takeIf { it > 0 },
            prince = record.score.prince,
            pb = null,
            mimic = record.score.mimic,
            crypts = record.score.crypts,
            video = null,
            clearScore = record.clearScore?.let { Format.points(Format.hundredths(it)) },
        ).apply {
            addProperty("v", 1)
            addProperty("ts", record.ts)
            add("to300", JsonObject().apply {
                addProperty("tick", mark.tick)
                mark.clock?.let { addProperty("clock", it) }
                addProperty("ms", mark.ms)
            })
        }
}
