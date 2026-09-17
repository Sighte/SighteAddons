package sighteaddons

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import sighteaddons.ui.Format

/**
 * Posting a solo run to Discord from the `/sa` solo tab, by hand and with an optional YouTube link.
 *
 * **The body names the run and carries the link, and that is all it carries.** Until 2026-09-17 this
 * sent the numbers — time, secrets, score, the record claim — and the receiver relayed them, so editing
 * the local `solo-<ts>.json` before pressing the button put any time in the channel (the user tried it).
 * Now the receiver posts from the copy it took at the run's end ([SoloFile], `POST /v1/solo_run`), and
 * this route, `POST /v1/solo_post`, only says which filed run and which video. Whatever the local file
 * says by then changes the tab and nothing else; `pb` is the box's own comparison.
 *
 * Two things follow, and the tab says both before the click: a run without a 300 mark was never filed
 * (only the 300s go to the box), and a run without [SoloRuns.Record.filedTs] — refused, unanswered, or
 * played before filing existed — cannot be posted at all.
 *
 * **This side still remembers the post.** [SoloRuns.markPosted] writes the time and the link into the
 * run's own file, which is what stops the tab from offering the same post twice and what puts the link
 * on the run's page afterwards; the receiver refuses a second post of the same run on its side too.
 *
 * One post at a time, process-wide: the state below is what the tab draws, and two in flight would
 * have one status line for two answers. The HTTP happens on [SoloClear.post]'s daemon thread; the
 * answer hops back to the client thread before it touches the list.
 */
object SoloPost {
    internal const val ROUTE = "/v1/solo_post"

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
        val refusal = when {
            record.to300 == null -> "no 300 reached"
            record.filedTs == null -> "not filed on the server"
            player == null -> "no player name"
            else -> null
        }
        if (refusal != null || player == null) {
            state = State.Failed(record.ts, refusal ?: "no player name")
            return
        }
        state = State.Posting(record.ts)
        val body = payload(record, player, video)
        DebugLog.event("solo_post", "ts" to record.ts, "floor" to record.floor, "video" to (video != null))
        SoloClear.post(body.toString(), ROUTE) { ok, reason ->
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
     * The body for one post: which run, and the link. **Three keys, and the receiver reads no other** —
     * a `time` or a `secrets` added here would be ignored on the box, which is the property the whole
     * change is for. Pure over the record.
     */
    internal fun payload(record: SoloRuns.Record, player: String, video: String?): JsonObject = JsonObject().apply {
        addProperty("player", player)
        addProperty("ts", record.ts)
        video?.let { addProperty("video", it) }
    }

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
