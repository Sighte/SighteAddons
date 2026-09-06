package sighteaddons

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft

/**
 * Posting a filed solo run to Discord from the `/sa` solo tab, by hand and with an optional YouTube link.
 *
 * The automatic announcement in [SoloClear] fires once, at the crossing, or not at all. This is the
 * second way in: the same `/v1/solo_clear` route, the same [SoloClear.payload], built from the record
 * instead of from the live run — so a run that was filed but never announced (gate off, receiver down,
 * played before the switch was on) can still be posted, and a video of it attached once it is uploaded.
 *
 * **The receiver keeps nothing and this side remembers the post.** [SoloRuns.markPosted] writes the
 * time and the link into the run's own file, which is what stops the tab from offering the same post
 * twice and what puts the link on the run's page afterwards.
 *
 * One post at a time, process-wide: the state below is what the tab draws, and two in flight would
 * have one status line for two answers. The HTTP happens on [SoloClear.post]'s daemon thread; the
 * answer hops back to the client thread before it touches the list.
 */
object SoloPost {

    sealed class State {
        object Idle : State()
        class Posting(val ts: Long) : State()
        class Failed(val ts: Long, val reason: String) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** Sends [record] with [video] (already validated by [videoLink]). No-op while another post is in flight. */
    fun send(record: SoloRuns.Record, video: String?) {
        if (state is State.Posting) return
        val player = record.player ?: Minecraft.getInstance().player?.name?.string
        val mark = record.headline
        if (player == null || mark == null) {
            state = State.Failed(record.ts, if (mark == null) "no 270 reached" else "no player name")
            return
        }
        state = State.Posting(record.ts)
        val pb = mark.threshold == 300 && SoloRuns.bestTo300(record.floor) == mark.tick
        val body = payload(record, player, mark, pb, video)
        // The floor as a picture, base64 in the body. A drawing that fails is a post without one.
        val png = try {
            SoloMapImage.render(record)
        } catch (e: Exception) {
            SighteAddons.LOGGER.warn("Could not draw the map for the post", e)
            null
        }
        png?.let { body.addProperty("map_png", java.util.Base64.getEncoder().encodeToString(it)) }
        DebugLog.event(
            "solo_post",
            "ts" to record.ts, "floor" to record.floor, "video" to (video != null), "pb" to pb,
            "mapBytes" to (png?.size ?: 0),
        )
        SoloClear.post(body.toString()) { ok, reason ->
            Minecraft.getInstance().execute {
                if (ok) {
                    SoloRuns.markPosted(record, video, System.currentTimeMillis())
                    state = State.Idle
                } else {
                    state = State.Failed(record.ts, reason)
                }
            }
        }
    }

    /** Forgets a failure, so the button reads as a button again. */
    fun dismiss() {
        if (state is State.Failed) state = State.Idle
    }

    /**
     * The body for one filed run. Pure over the record, like [SoloClear.payload] is over the run.
     *
     * The time is Hypixel's clock at the crossing when it was read, our ticks otherwise — the same
     * preference the automatic path announces by. The score is the projection's final breakdown, which is
     * what crossed the line; Hypixel's `Team Score:` only exists for a run that did the boss.
     */
    internal fun payload(record: SoloRuns.Record, player: String, mark: SoloRuns.Mark, pb: Boolean, video: String?): JsonObject =
        SoloClear.payload(
            player = player,
            floor = record.floor,
            time = mark.clock ?: DungeonGrid.formatTicks(mark.tick),
            secrets = record.secrets,
            deaths = record.deaths,
            score = record.score.final?.total ?: record.score.projectedHigh.takeIf { it > 0 },
            prince = record.score.prince,
            pb = pb,
            mimic = record.score.mimic,
            crypts = record.score.crypts,
            video = video,
        )

    /**
     * A typed link as the one that is sent, or null when it is not a YouTube link.
     *
     * Only YouTube, only `https`: the channel is read by people, and a box that relays whatever a client
     * types into it is a box relaying anything. A bare `youtu.be/…` or `www.youtube.com/…` gets its scheme
     * added rather than refused — that is how a link is pasted from an address bar half the time.
     */
    internal fun videoLink(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val https = withScheme.replaceFirst("http://", "https://")
        return https.takeIf { VIDEO.matches(it) }
    }

    /** `youtube.com`, `www.`/`m.youtube.com` and `youtu.be`, with a path, and nothing that could break the message. */
    internal val VIDEO = Regex("""^https://(?:(?:www\.|m\.)?youtube\.com|youtu\.be)/[A-Za-z0-9_\-?=&%./+]+$""")
}
