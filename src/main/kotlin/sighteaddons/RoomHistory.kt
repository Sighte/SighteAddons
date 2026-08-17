package sighteaddons

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Permanent, append-only history of the local player's room times.
 *
 * Every completed room is appended as one line and **nothing is ever overwritten or removed** — a
 * personal best is simply the minimum over the history, rebuilt in memory at startup. That keeps the
 * full progression available for later analysis instead of collapsing it to a single number, and it
 * removes any chance of the record file and the history disagreeing.
 *
 * Only the local player is recorded. Teammates' times are announced in chat but not stored: in a
 * party-finder group they are strangers, so storing them would grow the file without any use.
 *
 * Two metrics, one file, told apart by the line's `kind`. A [CLEAR] is *time spent in the room*
 * until it turned cleared — not run-relative time, because that is what a player can influence and
 * it stays comparable across runs and floors. A [SECRETS] line is the room's secret run: first
 * secret to last, timed from the secrets themselves rather than from your arrival.
 *
 * **A line is written only when the work behind it was yours** — [ownClear] and [ownSecretRun] are
 * the two gates, and they gate *whether a line is written*, never what the number in it means. That
 * distinction is load-bearing: the file is append-only and every line already in it is still read,
 * so a `clear` has to keep meaning the local player's total ticks in the room and a `secretrun` has
 * to keep meaning [TrackedRoom.secretRunTicks], for old and new lines alike. Changing either
 * measurement would make lines of one kind quietly incomparable with each other — which is the exact
 * failure the `secrets` -> [SECRETS] rename was invented to avoid. Adding a gate cannot: a line that
 * is not written is not a line that says something different.
 *
 * The consequence, accepted deliberately by the user on 2026-08-15: **records already in the file
 * stay records.** The bogus bests written before the gates existed are not reinterpreted, rewritten
 * or versioned away, so a room whose record was set by walking into somebody else's clear will
 * simply never show a PB again. Rooms are cheap and there are many; a file that rewrites its own
 * history is not.
 */
object RoomHistory {
    private val FILE = FabricLoader.getInstance().configDir.resolve("sighteaddons/history.jsonl")

    /** Time spent in the room until it turned cleared. */
    const val CLEAR = "clear"

    /**
     * The secret run: the room's first secret to its last.
     *
     * Deliberately not the old `secrets` kind, whose ticks were how long you had been in the room
     * when it turned green. Reusing that name for a shorter measurement would let every new run beat
     * every old entry and announce it as a personal best. The old lines stay in the file — nothing
     * here is ever rewritten — they are simply no longer read.
     */
    const val SECRETS = "secretrun"

    /**
     * The blood room, from the door opening to the Watcher's pass line — Odin's `Blood Clear` split.
     *
     * **Its own kind rather than a redefined [CLEAR], and that is the rule rather than a preference.**
     * Every `clear` line already in the file means the local player's ticks in a room; a blood room
     * that started writing a different span under the same name would make those lines incomparable
     * with each other, which is precisely the failure the `secrets` -> [SECRETS] rename exists to
     * prevent. A new kind cannot do that: nothing reads it yet, so it has no history to contradict.
     *
     * The blood room writes no [CLEAR] line at all any more — see [onRoomCleared]. That is a gate on
     * *whether* a line is written, which is the change this file's contract does allow.
     *
     * See [BloodClear] for what the span is and why it has no ownership gate.
     */
    const val BLOOD = "bloodclear"

    /** The kinds still read back. A line of any other kind stays in the file — see [SECRETS]. */
    private val KINDS = setOf(CLEAR, SECRETS, BLOOD)

    /** "<room>|<kind>" -> the record and how it was reached. Derived from [FILE] at startup. */
    private val best = HashMap<String, Record>()

    /**
     * "<room>|<kind>" -> every attempt, in the order the file has them. The record is the minimum of
     * these; keeping the rest is what lets the `/sa` detail line draw a room's progression and its
     * best time per floor without widening the file format — all of it has been written since the
     * first version and was simply dropped on the way in.
     *
     * ponytail: whole history in memory. ~40 bytes per line, so a heavy account's 20k lines cost
     * under a megabyte. Cap it or read the file on demand if it ever reaches six figures.
     */
    private val log = HashMap<String, MutableList<Attempt>>()

    private val newThisRun = mutableListOf<String>()

    private var writer: BufferedWriter? = null
    private var loaded = false
    private var entries = 0

    /**
     * One room's record: the fewest ticks ever recorded, how many times it was completed at all, and
     * when that last happened. Runs and timestamp are what the `/sa` records table shows next to the
     * time — a record without them says nothing about whether it is one lucky run or a routine.
     */
    data class Record(val ticks: Int, val runs: Int, val lastTs: Long) {
        fun plus(ticks: Int, ts: Long) = Record(minOf(this.ticks, ticks), runs + 1, maxOf(lastTs, ts))
    }

    /**
     * One completed room, as the file has it. [pb] is whether it beat the record *at the time it was
     * written* — a fact about that run, which is exactly what the progression wants to mark.
     * [floor] is "?" for a line written before the floor was known.
     */
    data class Attempt(val ticks: Int, val ts: Long, val floor: String, val pb: Boolean)

    /** [fold]'s result. Malformed lines are counted rather than dropped silently. */
    internal class Records(
        val byKey: Map<String, Record>,
        val attempts: Map<String, List<Attempt>>,
        val malformed: Int,
    ) {
        /** Every valid line raises exactly one key's run count, so the total needs no own counter. */
        val entries get() = byKey.values.sumOf { it.runs }
    }

    fun startRun() {
        ensureLoaded()
        newThisRun.clear()
    }

    fun newBestsThisRun(): List<String> = newThisRun

    /** For the `/sa` records table. Keyed "<room>|<kind>", floors collapsed. */
    fun records(): Map<String, Record> {
        ensureLoaded()
        return best
    }

    /**
     * The rooms the records table has a line for.
     *
     * Filtered by kind rather than taken from every key: a room whose only history line is a retired
     * kind — `secrets`, from before it became [SECRETS] — has no record in either column the table
     * draws, and would otherwise get a row of nothing but dashes and a run count of 0.
     */
    internal fun roomsWithRecords(keys: Set<String>): List<String> =
        keys.filter { it.substringAfter('|') in KINDS }.map { it.substringBefore('|') }.distinct()

    /** Every recorded attempt at one room and kind, oldest first. For the `/sa` detail line. */
    fun attempts(room: String, kind: String): List<Attempt> {
        ensureLoaded()
        return log["$room|$kind"] ?: emptyList()
    }

    /** Lines in the history file, i.e. completed rooms — not records. */
    fun entryCount(): Int {
        ensureLoaded()
        return entries
    }

    /**
     * A room turned cleared.
     *
     * Announces who did it and how long they were in the room. Credit goes to whoever spent the most
     * time there — with several players present, one readable line beats one line per player. The
     * announcement is informational and is not a record: it names whoever cleared the room whether
     * or not that was you, and [Config.ownPbsOnly] is what governs how much of it you see.
     *
     * The **record** is a separate decision and a much stricter one — see [ownClear].
     */
    fun onRoomCleared(room: TrackedRoom) {
        // The blood room is measured and announced by [BloodClear] instead, on Odin's definition, and
        // it speaks for itself — so this returns rather than adding a second line about the same room
        // with a different number in it. Nothing else is lost by leaving early: the points for the
        // room are awarded in ContributionTracker, not here.
        if (room.type == RoomType.BLOOD) return

        val eligible = room.ticks.filterValues { it >= ContributionTracker.MIN_TICKS }
        val (topPlayer, topTicks) = eligible.maxByOrNull { it.value } ?: return

        val self = Minecraft.getInstance().player?.name?.string
        val ownTicks = self?.let { room.ticks[it] } ?: 0
        val mine = ownClear(room, self, topPlayer)
        // Appended first and unconditionally *given the gate*: the chat settings below hide the
        // message, never the record. A silenced chat that also stopped writing history would lose
        // runs for good. The gate is not a chat setting — it is the question of whether there is a
        // record here at all.
        val pb = if (mine) record(room, CLEAR, ownTicks) else null

        // Same gate the history uses, so the popup shows exactly the rooms that were just recorded —
        // and it is independent of the chat settings, being a different channel with its own switch.
        // ClearPopup's own KDoc promises the popup can never show a room the records will not have,
        // and that promise is kept here rather than in ClearPopup: this is where the answer is known.
        val name = room.name
        if (name != null && mine) {
            ClearPopup.show(name, secrets = false, ownTicks, pb != null)
        }

        if (!Config.roomMessages) return
        if (Config.ownPbsOnly && pb == null) return

        val line = Component.literal(topPlayer).withStyle(ChatFormatting.WHITE)
            .append(Component.literal(" cleared ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(room.label()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(DungeonGrid.formatTicks(topTicks)).withStyle(ChatFormatting.AQUA))

        if (eligible.size > 1) {
            line.append(Component.literal(" (+${eligible.size - 1})").withStyle(ChatFormatting.DARK_GRAY))
        }
        pb?.let { line.append(it) }
        announce(line)
    }

    /**
     * The room's secret run finished: first secret to last, from [TrackedRoom.onSecret].
     *
     * The *measurement* belongs to the room, not to one player — the clock runs from the first
     * secret to the last no matter whose hands took them, so the line names the room instead of
     * crediting somebody this client cannot identify. The **record** does not follow it: a time the
     * party set is not a time you set, and [ownSecretRun] is what decides.
     *
     * **On the default settings the announcement stays either way** and carries both counts, so a
     * run somebody else did most of is still shown — it is simply not filed. **With
     * [Config.ownPbsOnly] on it does not**, and that is a real narrowing rather than an oversight:
     * a refused run has a null `pb`, so `ownPbsOnly` now suppresses a line it used to print whenever
     * the party's run beat the stored record. `ownPbsOnly` defaults to `false`, so most installs see
     * no change, and on the installs that turn it on the new behaviour is arguably what the switch
     * always meant. Stated rather than left for a reader to discover, because the unqualified
     * version of this sentence read as coverage.
     */
    fun onSecretRun(room: TrackedRoom) {
        val name = room.name ?: return
        val ticks = room.secretRunTicks ?: return

        val mine = ownSecretRun(room)
        val pb = if (mine) record(room, SECRETS, ticks) else null
        if (mine) ClearPopup.show(name, secrets = true, ticks, pb != null)

        if (!Config.roomMessages) return
        if (Config.ownPbsOnly && pb == null) return

        announce(
            Component.literal(name).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(" secrets in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(DungeonGrid.formatTicks(ticks)).withStyle(ChatFormatting.AQUA))
                .append(
                    Component.literal(" (${room.secretsFound}, ${room.ownSecrets} yours)")
                        .withStyle(ChatFormatting.DARK_GRAY),
                )
                .apply { pb?.let { append(it) } },
        )
    }

    /**
     * The blood room finished, timed by [BloodClear].
     *
     * Announced like [onSecretRun] rather than like [onRoomCleared]: the line names the room and not
     * a player, because the measurement belongs to the fight and the party did it together. Both chat
     * switches still apply — this is a room message, and a player who turned those off did not ask
     * for one more.
     */
    fun onBloodCleared(room: TrackedRoom, ticks: Int) {
        val pb = record(room, BLOOD, ticks)
        val name = room.name ?: return
        ClearPopup.show(name, secrets = false, ticks, pb != null)

        if (!Config.roomMessages) return
        if (Config.ownPbsOnly && pb == null) return

        announce(
            Component.literal(name).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(" cleared in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(DungeonGrid.formatTicks(ticks)).withStyle(ChatFormatting.AQUA))
                .apply { pb?.let { append(it) } },
        )
    }

    /**
     * Whether the clear of [room] is the local player's to record. Both halves are required, which
     * is the user's decision of 2026-08-15 taken at its strict end rather than either half alone.
     *
     * 1. **You were the member with the most ticks in the room** — the same [topPlayer] the
     *    announcement already credits. Being present is not the same as having done the room, and
     *    the clear line has always named one person for exactly that reason; the record now agrees
     *    with the line instead of quietly disagreeing with it.
     * 2. **You were there from the start and had not left** — [TrackedRoom.presentFromStart] against
     *    [TrackedRoom.clearedAtTick]. Without this, arriving as the checkmark lands writes a ~1.5 s
     *    time that beats every honest record for that room, permanently, because the number recorded
     *    is your ticks in the room and one second of them is all the old bar asked for. Reported by
     *    the user, 2026-08-15: *"wenn ich 'verspätet' in einen Raum komme der bereits von jemand
     *    anderem gecleart wird"*.
     *
     * Neither implies the other. You can be top of a room you walked into late — everyone else left
     * before you arrived — and you can be there from the first tick and be third by time.
     *
     * **The [ContributionTracker.MIN_TICKS] floor is unreachable through the only caller there is,
     * and it stays, and the reason is precise rather than "totality" in the abstract.**
     * [onRoomCleared] filters `eligible` to members at or above the floor *before* it computes
     * [topPlayer], so `self == topPlayer` already implies the floor there — which is exactly why no
     * fixture built from that path can exercise it, and why deleting the line passed all 211 tests
     * on 2026-08-15 while this paragraph claimed the predicate was total. That claim was the same
     * species of defect this whole feature removes: a guard nothing holds, described as if something
     * did. The line is kept because this is `internal` and directly callable, and the shape that
     * reaches it is real — a room cleared inside a second, anchored by
     * [TrackedRoom.anchorOnClear], where a member can satisfy [TrackedRoom.presentFromStart] on six
     * ticks of presence. Without the floor that room records a 0.3 s clear, which is defect C by
     * another route. It is now exercised by exactly that fixture ('the presence floor is the one
     * thing wrong with a room that cleared in six ticks'), so the claim is held by a test and not by
     * this sentence.
     *
     * A null [self] is a `false`: the local player is not resolvable, so there is nobody to record
     * for. A null [TrackedRoom.clearedAtTick] is a `false` too — this runs from the clear path where
     * it has just been stamped, so a null means the room did not arrive here the way it is supposed
     * to, and guessing is the one thing an append-only file cannot afford.
     *
     * Each condition is on its own line on purpose: the sweep in `build/recordprobe.py` deletes them
     * one at a time, and a compound condition is a condition that cannot be probed alone.
     *
     * **A tie is unchanged and still arbitrary.** [topPlayer] is `maxByOrNull` over a `HashMap`, so
     * two members on exactly the same tick count resolve in hash order. That is pre-existing
     * behaviour of the announcement, it is not made worse here, and pinning it would be a second
     * decision the user has not been asked for. Recorded rather than silently inherited.
     */
    internal fun ownClear(room: TrackedRoom, self: String?, topPlayer: String): Boolean {
        if (self == null) return false
        if (self != topPlayer) return false
        if ((room.ticks[self] ?: 0) < ContributionTracker.MIN_TICKS) return false
        val at = room.clearedAtTick ?: return false
        return room.presentFromStart(self, at)
    }

    /**
     * Whether the finished secret run of [room] is the local player's to record: **every secret in
     * it was yours**. The user's decision of 2026-08-15, and they were told what it costs before
     * making it.
     *
     * The old code recorded the run unconditionally and announced it as a PB, so a room where a
     * teammate took eight of ten secrets filed the party's time as a personal best — reported by the
     * user as *"wenn ich in einen Raum komme wo jemand secrets macht, und ich nur 1 oder 2 mache
     * bekomme ich trotzdem die PB gutgeschrieben"*. [TrackedRoom.ownSecrets] was sitting one field
     * away from the call and was never consulted.
     *
     * **WHAT THIS COSTS, MEASURED. Roughly nine records in ten, solo included — not "party records
     * become rare", which is what this paragraph said until 2026-08-15 and which was wrong about
     * both the size and the cause.** Replayed over the fifteen real session logs on this machine
     * with `python build/ownsecrets.py`: of **87** completed secret runs (`secret_run_done`, which
     * carries exactly the two numbers this compares), **12 survive this gate — 13.8%**. Split by
     * roster it is **2 of 23** on single-member sessions and **10 of 64** on party sessions, so a
     * solo floor is hit as hard as a party one. On the committed floor
     * (`docs/evidence/session-1786719912927/`) four of five runs go: `Atlas 4/6`, `New Trap 2/3`,
     * `Slime 2/5`, `Pipes 5/7`, and only `Chains 2/2` stays.
     *
     * **So the cause is not shared work, it is attribution.** On a solo floor every secret *was* the
     * local player's by construction, and one of those logs records `Big Red Flag 0/2` — a room
     * where the client credited itself with none of them. [TrackedRoom.ownSecrets] counts an own
     * click inside [SecretTracker.OWN_WINDOW] plus the wither-essence chat line and nothing else, so
     * a secret picked up by walking onto it, or by a click the 40-tick window missed, reads as
     * somebody else's and sinks the whole run. That gap is real work and is recorded as
     * `ownsecrets-001`; it is deliberately **not** fixed here, and this gate is deliberately not
     * softened to compensate for it — the user was shown these numbers on 2026-08-15, was offered
     * the majority rule `ownSecrets * 2 >= secretsFound`, and reaffirmed the strict rule.
     *
     * The direction remains the argument for it: the failure mode of the loose gate is a permanent
     * wrong record, and the failure mode of the strict one is a missing one. What changed is that
     * the price is now a number rather than an adjective.
     *
     * The `> 0` guard is not decoration. `0 == 0` would be vacuously true, and this must never be
     * the answer for a room where nothing was ever counted.
     */
    internal fun ownSecretRun(room: TrackedRoom): Boolean =
        room.secretsFound > 0 && room.ownSecrets == room.secretsFound

    /**
     * Appends one line for the local player and returns the chat suffix if it beat the record.
     * [ticks] is whatever [kind] measures: time in the room for a clear, the secret run for secrets.
     *
     * **Never widened, never redefined.** Both callers gate on ownership before reaching here and
     * neither changes what it passes: a clear is still the local player's total ticks in the room, a
     * secret run is still [TrackedRoom.secretRunTicks]. The file is append-only and old lines are
     * still folded, so the meaning of a number inside one `kind` has to hold for every line the file
     * has ever had.
     */
    private fun record(room: TrackedRoom, kind: String, ticks: Int): Component? {
        val roomName = room.name ?: return null
        ensureLoaded()

        val key = "$roomName|$kind"
        val previous = best[key]?.ticks
        val improved = previous == null || ticks < previous
        val ts = System.currentTimeMillis()

        append(room, roomName, kind, ticks, improved, ts)
        // plus() keeps the minimum, so one path covers both a new record and an ordinary run.
        best[key] = best[key]?.plus(ticks, ts) ?: Record(ticks, 1, ts)
        // Mirrors the line just written, so the room you finished a moment ago is already in its
        // progression without re-reading the file.
        log.getOrPut(key) { mutableListOf() }.add(Attempt(ticks, ts, DungeonSession.floor ?: "?", improved))
        if (!improved) return null

        newThisRun.add("$roomName $kind ${DungeonGrid.formatTicks(ticks)}")
        val improvement = if (previous == null) "first record" else "was ${DungeonGrid.formatTicks(previous)}"
        return Component.literal("  PB").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" ($improvement)").withStyle(ChatFormatting.DARK_GRAY))
    }

    /** Chat breakdown at the end of a run. */
    fun printSummary() {
        if (!Config.runSummary) return
        val points = ContributionTracker.pointsByPlayer()
        // No self-identification of its own any more: the tag [Chat.say] puts on the front says who
        // is speaking, and a gold "Sighte" in front of that said it twice.
        announce(
            Component.literal(
                "${DungeonSession.floor ?: "?"} — ${DungeonGrid.formatTicks(DungeonSession.runTicks)}, " +
                    "${ContributionTracker.roomsCleared} rooms",
            ).withStyle(ChatFormatting.GRAY),
        )

        val rooms = ContributionTracker.visitedRooms()
        val self = Minecraft.getInstance().player?.name?.string
        PartyTracker.roster()
            .map { it.name to (points[it.name] ?: 0.0) }
            .sortedByDescending { it.second }
            .forEach { (name, earned) ->
                val contributed = rooms.count { (it.ticks[name] ?: 0) >= ContributionTracker.MIN_TICKS }
                // Only the local player's secrets are provable. Hypixel reports secrets per room and
                // only for the room you are standing in, so a teammate's count would be a guess —
                // they get a dash instead, the same way the history refuses an estimated middle number.
                val secrets = if (name == self) rooms.sumOf { it.ownSecrets } else null
                // The floor's true party-wide total, read out of the tab list rather than summed out
                // of the rooms this client happened to be inside. Only ever shown next to the local
                // player's provable count, because it is a fact about the floor and not about them.
                val ofTotal = if (name == self) DungeonTab.secretsFound else null
                announce(
                    Component.literal("  %5.2f".format(Locale.ROOT, earned)).withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("  $name ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(breakdown(contributed, secrets, ofTotal)).withStyle(ChatFormatting.DARK_GRAY)),
                )
            }

        // The true per-player counts, if a key is configured. Deliberately not waited for: this runs
        // off the run-end chat line and a network call here would hold up the whole summary. It
        // arrives as its own line a moment later, or not at all.
        //
        // `estimated` is taken here rather than inside the callback, so the comparison is against
        // what this run measured rather than against whatever the tracker holds by the time the
        // answer lands — which, for a player who starts the next floor quickly, is a different run.
        // `announce` schedules onto the client thread itself, so the callback is safe where it is.
        val estimated = rooms.sumOf { it.ownSecrets }
        // Captured here for the same reason `estimated` is: by the time the answer lands the player
        // may be on the next floor, and DungeonTab will have been reset by it.
        val floorTracked = DungeonTab.secretsFound
        val roster = PartyTracker.rosterIds()
        SecretApi.settle(roster) { counts ->
            announce(secretLine(counts))
            // The live tracker, graded against the one source that knows. `ownsecrets-001` has never
            // had a number for this; every run with a key now writes one.
            val audit = SecretAudit.of(estimated, counts, self, floorTracked, roster.size)
            DebugLog.event(
                "secret_audit",
                "verdict" to audit.verdict.name.lowercase(),
                "tracked" to audit.tracked, "actual" to audit.actual, "delta" to audit.delta,
                "floorTracked" to audit.floorTracked, "floorActual" to audit.floorActual,
                "answered" to audit.answered, "asked" to audit.asked,
            )
            announce(auditLine(audit))
        }

        // Was `> 0.01` against an inline subtraction — the same guard against the same split residue
        // that the run report clamps, written twice and agreeing by coincidence. The figure is now a
        // count of rooms rather than a subtraction of a score from a count, so there is no residue
        // left to guard against and "worth printing" is exactly "not zero". `%.2f` stays: it is what
        // the run report ships and what this line has always read as, and the field is a real number
        // to everything downstream even where this side of it produces whole ones.
        //
        // Rooms, deliberately, not points — a room nobody was in is one unattributed room whatever
        // it was worth. See ContributionTracker.unattributed.
        val unattributed = ContributionTracker.unattributed()
        if (unattributed > 0.0) {
            announce(
                Component.literal("  %.2f rooms unattributed".format(Locale.ROOT, unattributed))
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
        }
        // Secrets now ride on the player lines above, so this one carries the records alone rather
        // than repeating your own count next to them.
        announce(
            Component.literal(
                if (newThisRun.isEmpty()) "  no new records" else "  ${newThisRun.size} new records",
            ).withStyle(if (newThisRun.isEmpty()) ChatFormatting.DARK_GRAY else ChatFormatting.GOLD),
        )
    }

    /**
     * The per-player breakdown behind the points: rooms they were in long enough to earn from, and
     * the secrets they found. [secrets] is null for a teammate, whose secrets this client cannot see.
     *
     * [ofTotal] is the floor's **true** party-wide secret count from [DungeonTab], and it is the
     * whole of `secretcount-001`. [secrets] alone was printed under the bare label "secrets" and read
     * as "the secrets you found", which it is not: it is the secrets this client could *prove* were
     * yours, and it can only prove one in a room it was standing in when the counter moved. Measured
     * on a real 19-room M7 floor, five rooms ever produced a reading at all, so the label was wrong
     * by more than a factor of two before attribution was even considered.
     *
     * `10 of 29` fixes the label rather than the number, and both halves stay true statements:
     * 29 secrets were found on this floor, 10 of them are provably yours. **The other 19 are not
     * claimed for anybody** — a secret this client could not attribute is unattributed, not a
     * teammate's, and no arrangement of these two numbers may be read as saying otherwise.
     *
     * [secrets] keeps its meaning exactly. It is the same number [ownSecretRun] gates records on, and
     * this is a display change: nothing here feeds a record, a report field or a history line.
     *
     * A null [ofTotal] falls back to the old rendering rather than printing `10 of ?`. The tab rows
     * have never been observed on a real floor from this repository, so the fallback is the path that
     * has to stay correct.
     */
    /**
     * The follow-up line carrying Hypixel's own per-player counts, highest first.
     *
     * Kept apart from the per-player rows above rather than folded into them, and the reason is
     * honesty about provenance: those rows are what this client measured, this line is what Hypixel
     * says. A reader can tell them apart, and when they disagree the disagreement is visible instead
     * of resolved silently in favour of whichever arrived last.
     *
     * This line carries the counts alone. The comparison against what this client tracked used to
     * ride on it as a `(counted N)` after the local player's number; it is now [auditLine], because a
     * verdict squeezed into a list of names is a number the reader has to do the subtraction for.
     */
    internal fun secretLine(counts: Map<String, Int>): MutableComponent {
        val line = Component.literal("  secrets ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal("(Hypixel) ").withStyle(ChatFormatting.DARK_GRAY))
        counts.entries.sortedByDescending { it.value }.forEachIndexed { index, (name, found) ->
            if (index > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
            line.append(Component.literal("$name ").withStyle(ChatFormatting.WHITE))
            line.append(Component.literal("$found").withStyle(ChatFormatting.AQUA))
        }
        return line
    }

    /**
     * The live tracker, graded against Hypixel. See [SecretAudit] for what the two directions mean.
     *
     * Over-counting is the only outcome drawn in the colour the summary uses for records, because it
     * is the only one that is a defect rather than a reading: it says a teammate's secret was written
     * onto your screen and into your points. Missing some is the designed direction and is stated as
     * plainly as any other number.
     */
    internal fun auditLine(audit: SecretAudit.Result): MutableComponent {
        val line = Component.literal("  tracker ").withStyle(ChatFormatting.GOLD)
        when (audit.verdict) {
            SecretAudit.Verdict.UNKNOWN ->
                line.append(Component.literal("no reading for you").withStyle(ChatFormatting.DARK_GRAY))

            SecretAudit.Verdict.EXACT -> {
                line.append(Component.literal("${audit.tracked} of ${audit.actual}").withStyle(ChatFormatting.AQUA))
                line.append(Component.literal(" yours, exact").withStyle(ChatFormatting.GRAY))
            }

            SecretAudit.Verdict.MISSED -> {
                line.append(Component.literal("${audit.tracked} of ${audit.actual}").withStyle(ChatFormatting.AQUA))
                line.append(Component.literal(" yours, ").withStyle(ChatFormatting.GRAY))
                line.append(
                    Component.literal("${-(audit.delta ?: 0)} missed").withStyle(ChatFormatting.DARK_GRAY),
                )
            }

            SecretAudit.Verdict.OVER -> {
                line.append(Component.literal("${audit.tracked} of ${audit.actual}").withStyle(ChatFormatting.AQUA))
                line.append(Component.literal(" yours, ").withStyle(ChatFormatting.GRAY))
                line.append(
                    Component.literal("${audit.delta} too many").withStyle(ChatFormatting.GOLD),
                )
            }
        }
        // Only when it is short. A complete reading is the normal case and saying so every run is
        // noise; an incomplete one is why the floor totals below it cannot be compared.
        if (!audit.complete) {
            line.append(
                Component.literal("  (${audit.answered} of ${audit.asked} players read)")
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
        }
        return line
    }

    internal fun breakdown(rooms: Int, secrets: Int?, ofTotal: Int? = null) = "($rooms rooms · " +
        when {
            secrets == null -> "–"
            ofTotal == null -> "$secrets"
            else -> "$secrets of $ofTotal"
        } + " secrets)"

    private fun append(room: TrackedRoom, roomName: String, kind: String, ticks: Int, pb: Boolean, ts: Long) {
        val obj = JsonObject()
        obj.addProperty("ts", ts)
        obj.addProperty("floor", DungeonSession.floor ?: "?")
        obj.addProperty("room", roomName)
        obj.addProperty("kind", kind)
        obj.addProperty("ticks", ticks)
        // Redundant with ticks, but this file is meant to be readable without doing the maths.
        obj.addProperty("seconds", ticks / 20.0)
        // Two separate numbers on purpose: the room total is party-wide, ownSecrets are the ones that
        // coincided with your own interaction. No estimated third number in between.
        obj.addProperty("secretsInRoom", room.secretsFound)
        obj.addProperty("ownSecrets", room.ownSecrets)
        obj.addProperty("maxSecrets", room.info?.secrets ?: -1)
        // Which room scores were in force when this line was written — the receiver's `generatedTs`,
        // or 0 for the seed values. Room points are not stored here, but they are derived from these
        // scores, and once the fetch layer exists (`scores-fetch-001`) the scores move on their
        // own between launches. A permanent, append-only file is the only place a past run's
        // weighting can still be identified afterwards. Additive: `fold` reads by key, so this is
        // invisible to every line already written and to every reader of them.
        obj.addProperty("scoresTs", RoomStats.scores.generatedTs)
        obj.addProperty("pb", pb)
        try {
            val out = writer ?: open() ?: return
            out.write(obj.toString())
            out.newLine()
            out.flush() // per line, so a crash never costs more than the room in progress
            entries++
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Failed to append room history to {}", FILE, e)
            writer = null
        }
    }

    private fun open(): BufferedWriter? {
        return try {
            Files.createDirectories(FILE.parent)
            Files.newBufferedWriter(FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND).also { writer = it }
        } catch (e: Exception) {
            SighteAddons.LOGGER.error("Could not open room history {}", FILE, e)
            null
        }
    }

    /**
     * The file is the source of truth; a record is just its minimum per room and kind. Kept pure and
     * separate from the file handling so it can be tested without a game directory.
     */
    internal fun fold(lines: Sequence<String>): Records {
        val out = HashMap<String, Record>()
        val attempts = HashMap<String, MutableList<Attempt>>()
        var malformed = 0
        for (line in lines) {
            if (line.isBlank()) continue
            try {
                val obj = JsonParser.parseString(line).asJsonObject
                val key = "${obj["room"].asString}|${obj["kind"].asString}"
                val ticks = obj["ticks"].asInt
                val ts = obj["ts"].asLong
                out[key] = out[key]?.plus(ticks, ts) ?: Record(ticks, 1, ts)
                // Optional: lines written before these fields existed are still valid history.
                attempts.getOrPut(key) { mutableListOf() }.add(
                    Attempt(ticks, ts, obj["floor"]?.asString ?: "?", obj["pb"]?.asBoolean ?: false),
                )
            } catch (_: Exception) {
                malformed++
            }
        }
        return Records(out, attempts, malformed)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.exists(FILE)) return
        try {
            val records = Files.newBufferedReader(FILE).useLines { fold(it) }
            best.putAll(records.byKey)
            records.attempts.forEach { (key, list) -> log[key] = list.toMutableList() }
            entries = records.entries
            SighteAddons.LOGGER.info("Room history: {} entries, {} records{}", entries, best.size,
                if (records.malformed > 0) " (${records.malformed} unreadable lines skipped)" else "")
        } catch (e: Exception) {
            // Never let a broken history cost the run. Nothing is truncated — we only append.
            SighteAddons.LOGGER.error("Could not read room history {}", FILE, e)
        }
    }

    /**
     * Every line this file puts on screen. [Chat.say] is what puts the tag on it and what schedules
     * it onto the client thread — see there for why both are one function.
     */
    private fun announce(text: MutableComponent) = Chat.say(text)
}
