package sighteaddons

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether Mayor Paul's EZPZ perk is on — ten bonus points on every run that neither the sidebar nor the
 * tab list ever shows, and the largest single reason a projection can sit under Hypixel's final number.
 *
 * Odin asks `api.hypixel.net/resources/skyblock/election` on floor entry (`WebUtils.hasBonusPaulScore`)
 * and this is that request: **no key, no player, nothing but a GET of a public document.** It is the
 * only thing this mod ever asks Hypixel for directly. The answer changes with the election, once every
 * few days, so it is asked for at most once an hour and read off [paul] in between — a request that
 * has not come back yet reads as "no Paul", which is the error in the direction the gate can live with.
 *
 * Odin also lets a player force the perk on or off (`togglePaul`). Not here: two people use this mod
 * and neither has asked, and a switch that overrides a fact is a switch somebody forgets.
 */
object Mayor {
    private const val URL = "https://api.hypixel.net/v2/resources/skyblock/election"
    private const val TTL_MS = 60L * 60L * 1000L

    @Volatile
    var paul = false
        private set

    @Volatile
    private var fetchedAt = 0L
    private val running = AtomicBoolean(false)

    /** Asks again if the answer is older than an hour. Off the client thread; never throws. */
    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - fetchedAt < TTL_MS) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                val request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    paul = parse(response.body())
                    fetchedAt = nowMs
                    DebugLog.event("mayor", "paul" to paul)
                } else {
                    SighteAddons.LOGGER.warn("Election lookup answered HTTP {}", response.statusCode())
                }
            } catch (e: Exception) {
                SighteAddons.LOGGER.warn("Election lookup failed; assuming no Paul", e)
            } finally {
                running.set(false)
            }
        }, "sighteaddons-mayor").apply { isDaemon = true }.start()
    }

    /** Odin's reading of the document: the mayor is Paul and one of his perks is EZPZ. Pure. */
    internal fun parse(body: String): Boolean = try {
        val mayor = JsonParser.parseString(body).asJsonObject.getAsJsonObject("mayor")
        mayor?.get("name")?.asString == "Paul" &&
            mayor.getAsJsonArray("perks")?.any { it.asJsonObject.get("name")?.asString == "EZPZ" } == true
    } catch (_: Exception) {
        false
    }
}
