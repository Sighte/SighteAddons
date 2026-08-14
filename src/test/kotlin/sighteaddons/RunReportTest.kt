package sighteaddons

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The report is the permanent record every later evaluation reads, so its shape is a contract:
 * once runs are on the server in schema 1, nothing can go back and re-derive a field that was
 * never written.
 */
class RunReportTest {
    private fun room(name: String, ticks: Map<String, Int>) =
        TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0), Pos(32, 0))).apply {
            this.name = name
            info = RoomInfo(name, "normal", "1x2", secrets = 4, crypts = 2)
            ticks.forEach { (player, t) -> this.ticks[player] = t }
            // The anchor is not assignable any more — schema 5 stamps it from a qualifying stay — so
            // the fixture earns it the way a real room does: Sighte present from tick 120 for the
            // minimum stay. Same 120 the assertions below have always expected.
            repeat(ContributionTracker.MIN_TICKS) { onPresence("Sighte", 120 + it) }
            clearedAtTick = 400
            secretsFound = 3
            ownSecrets = 2
            deaths = 1
        }

    private fun report(named: Boolean = false, complete: Boolean = true, floor: String = "M5") = RunReport.build(
        named = named,
        ts = 1786530882102,
        installId = "0f5e4a1c-1111-2222-3333-444455556666",
        player = "Sighte",
        floor = floor,
        complete = complete,
        runTicks = 8000,
        roster = listOf(DungeonPlayer("Sighte", "Berserk", "VII"), DungeonPlayer("Stranger", "Mage", "L")),
        rooms = listOf(room("Catwalk", mapOf("Sighte" to 300, "Stranger" to 15))),
        roomsCleared = 7,
        unattributed = 1.25,
        deaths = 2,
        modVersion = "0.3.0",
        mcVersion = "26.1.2",
    )

    @Test
    fun `run context survives`() {
        val json = report()
        assertEquals(5, json["v"].asInt)
        assertEquals("M5", json["floor"].asString)
        assertEquals(2, json["partySize"].asInt)
        assertEquals(1.25, json["unattributed"].asDouble)
        assertEquals("Berserk", json["class"].asString)
        assertEquals("VII", json["classLevel"].asString)
    }

    @Test
    fun `nobody appears by name, not even the uploader`() {
        val json = report()
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })
        // The stranger never belonged in here. The uploader's own name went with 0.5.0: the mod
        // ships to everyone now, and the install id is what the server files a run under.
        assertFalse(json.toString().contains("Stranger"))
        assertFalse(json.toString().contains("Sighte"))
        assertFalse(json.has("player"))
    }

    /** Everything the server knows about a player hangs off this one string. */
    @Test
    fun `the run is keyed by the install id`() {
        assertEquals("0f5e4a1c-1111-2222-3333-444455556666", report()["uuid"].asString)
    }

    /**
     * Opting in adds the uploader's own name and nothing else. The switch is in `/sa` → debug and it
     * exists for the leaderboards: a row needs a label, and that label is the player's to give.
     */
    @Test
    fun `opting in names the uploader and still nobody else`() {
        val json = report(named = true)
        assertEquals("Sighte", json["player"].asString)
        // The id stays the identity — the name annotates it rather than replacing it, so a profile
        // survives the switch going back off.
        assertEquals("0f5e4a1c-1111-2222-3333-444455556666", json["uuid"].asString)
        // The teammate is not the uploader's to hand over, and no setting of theirs may change that.
        assertFalse(json.toString().contains("Stranger"))
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })

        // The receiver takes the key's presence as the consent, so "not named" has to mean absent
        // rather than null — see check_run in the receiver's ingest.py, where `player` is the one optional key.
        assertFalse(report(named = false).has("player"))
    }

    /** A player who is dead when the headline prints must not reach the record as class "DEAD". */
    @Test
    fun `a player dead at the end still reports their class`() {
        val json = RunReport.build(
            ts = 1786530882102,
            installId = "0f5e4a1c-1111-2222-3333-444455556666",
            player = "Sighte",
            floor = "M5",
            complete = true,
            runTicks = 8000,
            roster = listOf(
                DungeonPlayer("Sighte", "Berserk", "VII"),
                DungeonPlayer("Stranger", "DEAD", "", livingClass = "Mage", livingLevel = "L"),
            ),
            rooms = listOf(room("Catwalk", mapOf("Sighte" to 300))),
            roomsCleared = 7,
            unattributed = 0.0,
            deaths = 2,
            modVersion = "0.3.0",
            mcVersion = "26.1.2",
        )
        assertEquals(listOf("Berserk VII", "Mage L"), json["classes"].asJsonArray.map { it.asString })
    }

    /**
     * A floor that was walked out of still reports its rooms — that is the point of the field — and it
     * has to say so, because `runTicks`, `roomsCleared` and `deaths` then cover the part that was
     * played. The room entries are byte-identical either way: a clear time is a fact about the room,
     * not about how the run ended.
     */
    @Test
    fun `an abandoned run reports its rooms and admits it was abandoned`() {
        val left = report(complete = false)
        assertFalse(left["complete"].asBoolean)
        assertTrue(report()["complete"].asBoolean)
        assertEquals(
            report()["rooms"].toString(),
            left["rooms"].toString(),
        )
    }

    @Test
    fun `room carries party effort, not just own time`() {
        val room = report()["rooms"].asJsonArray[0].asJsonObject
        // 300 + 15: everything counts towards effort...
        assertEquals(315, room["playerTicks"].asInt)
        // ...but 15 ticks is walking through, not being in the room.
        assertEquals(1, room["playersInRoom"].asInt)
        assertEquals(300, room["ownTicks"].asInt)
        assertEquals(2, room["segments"].asInt)
        assertEquals(4, room["maxSecrets"].asInt)
        assertEquals(2, room["crypts"].asInt)
        assertEquals(1, room["deaths"].asInt)
        assertTrue(room["secretsTick"].isJsonNull)
    }

    /**
     * Both ticks are run timestamps. Only together are they a clear duration, which is the whole
     * reason `enterTick` exists — the server subtracts them, so a report carrying one without the
     * other contributes nothing.
     */
    @Test
    fun `room carries the clear anchor next to the checkmark`() {
        val room = report()["rooms"].asJsonArray[0].asJsonObject
        assertEquals(120, room["enterTick"].asInt)
        assertEquals(400, room["clearTick"].asInt)
    }

    /**
     * The one thing about this pair that no other check can catch. `enterTick` changed meaning
     * without changing shape, so a build shipping the stay anchor under `v: 4` is accepted by the
     * receiver, logs nothing, and folds a stay-anchored span into the sighting-anchored average it
     * was created to replace — `roomstats.py` routes on `v` alone (`STAY_ANCHOR_SCHEMA = 5`).
     * `profiles/` is append-only, so that average could never be cleaned afterwards.
     *
     * Written as a floor rather than as `== 5` on purpose: a later bump is fine and must not fail
     * here, going back below 5 while the anchor is a stay is the thing that must never compile green.
     */
    @Test
    fun `the stay anchor only ships under a schema that says so`() {
        val json = report()
        val room = json["rooms"].asJsonArray[0].asJsonObject
        // The fixture's anchor came from onPresence, i.e. from a stay reaching MIN_TICKS.
        assertEquals(120, room["enterTick"].asInt)
        assertTrue(
            json["v"].asInt >= 5,
            "enterTick is stay-anchored, so v must be at least roomstats.py's STAY_ANCHOR_SCHEMA (5)",
        )
    }

    @Test
    fun `a room nobody was ever seen in reports a null anchor`() {
        val untouched = TrackedRoom(RoomType.ROOM, setOf(Pos(0, 0)), setOf(Pos(0, 0)))
        val json = RunReport.build(
            ts = 1786530882102,
            installId = "0f5e4a1c-1111-2222-3333-444455556666",
            player = "Sighte",
            floor = "M5",
            complete = true,
            runTicks = 8000,
            roster = listOf(DungeonPlayer("Sighte", "Berserk", "VII")),
            rooms = listOf(untouched),
            roomsCleared = 0,
            unattributed = 0.0,
            deaths = 0,
            modVersion = "0.3.0",
            mcVersion = "26.1.2",
        )
        assertTrue(json["rooms"].asJsonArray[0].asJsonObject["enterTick"].isJsonNull)
    }

    // --- unattributed: the one legitimately fractional field ---

    /** [build] with everything but the field under test held at its ordinary value. */
    private fun unattributed(value: Double) = RunReport.build(
        ts = 1786530882102,
        installId = installId,
        player = "Sighte",
        floor = "M5",
        complete = true,
        runTicks = 8000,
        roster = listOf(DungeonPlayer("Sighte", "Berserk", "VII")),
        rooms = listOf(room("Catwalk", mapOf("Sighte" to 300))),
        roomsCleared = 30,
        unattributed = value,
        deaths = 0,
        modVersion = "0.3.0",
        mcVersion = "26.1.2",
    )["unattributed"]

    /**
     * The defect this exists for, with the value a real uploaded report actually carried. The
     * receiver appends it to `profiles/`, which is permanent and append-only, so a residue that gets
     * through is wrong forever — and `3.552713678800501e-15` is not a number anybody reading a
     * profile can act on.
     */
    @Test
    fun `the split residue is not reported as a fraction of a room`() {
        assertEquals(0.0, unattributed(3.552713678800501e-15).asDouble)
        // The serialised form too: this is what is posted, and a stored line is read as text long
        // after this code is gone.
        assertEquals("0.0", unattributed(3.552713678800501e-15).toString())
    }

    /** What [ContributionTracker.award] does to a run of [rooms] rooms shared three ways. */
    private fun drift(rooms: Int): Double {
        val credited = HashMap<String, Double>()
        repeat(rooms) {
            DungeonGrid.splitPoints(mapOf("A" to 100, "B" to 100, "C" to 100), 1.0, ContributionTracker.MIN_TICKS)
                .forEach { (name, points) -> credited.merge(name, points, Double::plus) }
        }
        return rooms - credited.values.sum()
    }

    /**
     * The residue is not hypothetical and not a constant somebody typed into a test. This is how it
     * is made: one point per room, split by ticks, accumulated over a floor. Three thirds do not add
     * back up to the point they came from, and sixteen rooms of that reproduce the exact value the
     * live report carried.
     */
    @Test
    fun `the residue this removes is the one the point split actually produces`() {
        assertEquals(3.552713678800501e-15, drift(16))
        assertEquals(0.0, unattributed(drift(16)).asDouble)
    }

    /**
     * Why the old one-sided clamp could never have been enough, stated as the property rather than
     * as a claim in a comment: the same arithmetic drifts either way depending only on how many
     * rooms the floor had. `coerceAtLeast(0.0)` covered the 36-room case and shipped the 16-room one.
     */
    @Test
    fun `the residue drifts in both directions, which is what the clamp alone missed`() {
        assertTrue(drift(16) > 0.0, "expected a positive residue, got ${drift(16)}")
        assertTrue(drift(36) < 0.0, "expected a negative residue, got ${drift(36)}")
        assertEquals(0.0, unattributed(drift(16)).asDouble)
        assertEquals(0.0, unattributed(drift(36)).asDouble)
    }

    /**
     * The other half of the same residue, which is what the original clamp was written for and what
     * it was never tested against. A negative is not merely untidy here: the receiver validates this
     * field as `_real(x, 0, MAX_CLEARED)`, so it is a 400, and TelemetryUpload never retries.
     */
    @Test
    fun `a negative never reaches the report`() {
        assertEquals(0.0, unattributed(-3.552713678800501e-15).asDouble)
        // Not only the residue-sized ones: whatever produced it, a negative count of rooms is not a
        // thing the report may claim.
        assertEquals(0.0, unattributed(-0.5).asDouble)
        assertEquals(0.0, unattributed(-7.0).asDouble)
    }

    /**
     * Only the noise goes. A room that cleared with nobody ever seen in it is a real unattributed
     * point, and the gap between this field and `roomsCleared` is the receiver's own diagnostic for
     * a broken decoration mapping — zeroing the field would delete the signal with the noise.
     */
    @Test
    fun `a genuinely unattributed room still counts`() {
        assertEquals(1.0, unattributed(1.0).asDouble)
        assertEquals(3.25, unattributed(3.25).asDouble)
        // Rounded to the two decimals every path that shows this number already truncates to, so the
        // stored figure and the one the player was shown are the same figure.
        assertEquals(0.33, unattributed(1.0 / 3.0).asDouble)
        // Small, but a whole percent of a room is a real gap and not float noise.
        assertEquals(0.01, unattributed(0.01).asDouble)
    }

    // --- restamp: the queue between "written" and "sent" ---

    private val installId = "0f5e4a1c-1111-2222-3333-444455556666"

    /** A queued report, written the way [RunReport.write] names it. */
    private fun queued(dir: Path, ts: Long, named: Boolean = false): Path {
        Files.createDirectories(dir)
        return dir.resolve("run-$ts-$installId.json").also {
            Files.writeString(it, report(named).toString())
        }
    }

    private fun read(file: Path) = JsonParser.parseString(Files.readString(file)).asJsonObject

    /**
     * The gap this closes: a report is written at run end and only handed over at the next game
     * start, so consent given in between has to reach what is still waiting.
     */
    @Test
    fun `switching on names the reports still waiting to be sent`(@TempDir dir: Path) {
        val first = queued(dir, 1786530000000)
        val second = queued(dir, 1786531000000)

        assertEquals(2, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(first)["player"].asString)
        assertEquals("Sighte", read(second)["player"].asString)
        // Nothing else about the run may move: it is the same run, only now attributed.
        assertEquals(installId, read(first)["uuid"].asString)
        assertEquals(1, read(first)["rooms"].asJsonArray.size())
        // Against a freshly built report rather than a literal: restamping must not touch the schema
        // version, and saying so this way survives the next bump instead of failing on it.
        assertEquals(report()["v"].asInt, read(first)["v"].asInt)
        assertEquals(1.25, read(first)["unattributed"].asDouble)
    }

    @Test
    fun `switching off removes the field rather than nulling it`(@TempDir dir: Path) {
        val file = queued(dir, 1786530000000, named = true)

        assertEquals(1, RunReport.restamp(dir, null))
        // Absent, not null. The receiver reads the key's presence as the consent, so a null would be
        // a claim about a name instead of the silence that was asked for.
        assertFalse(read(file).has("player"))
        // The exact key, not the substring: every room legitimately carries `playerTicks`.
        assertFalse(Files.readString(file).contains("\"player\":"))
    }

    @Test
    fun `restamping is idempotent and survives being toggled`(@TempDir dir: Path) {
        val file = queued(dir, 1786530000000)

        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        // Already correct, so nothing is rewritten — the count is changes, not files seen.
        assertEquals(0, RunReport.restamp(dir, "Sighte"))
        assertEquals(1, RunReport.restamp(dir, null))
        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(file)["player"].asString)
        // One entry, not one per toggle.
        assertEquals(1, Regex("\"player\":").findAll(Files.readString(file)).count())
    }

    /** The promise the switch makes: what has left the machine stays as it left. */
    @Test
    fun `already uploaded and rejected reports are out of reach`(@TempDir dir: Path) {
        val pending = queued(dir, 1786530000000)
        // Same valid report name, one directory down — the boundary is the directory, so a matching
        // name must not be enough to get edited.
        val sent = queued(dir.resolve("uploaded"), 1786520000000)
        val rejected = queued(dir.resolve("rejected"), 1786510000000)

        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(pending)["player"].asString)
        assertFalse(read(sent).has("player"))
        assertFalse(read(rejected).has("player"))
    }

    @Test
    fun `an unreadable or foreign file is skipped, not fatal`(@TempDir dir: Path) {
        val good = queued(dir, 1786530000000)
        Files.writeString(dir.resolve("run-1786531000000-$installId.json"), "{not json")
        // Not a report name: a stray file in this directory is none of our business.
        Files.writeString(dir.resolve("notes.txt"), "{}")
        Files.writeString(dir.resolve("run-nope.json"), "{}")

        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        assertEquals("Sighte", read(good)["player"].asString)
        assertEquals("{not json", Files.readString(dir.resolve("run-1786531000000-$installId.json")))
        assertEquals("{}", Files.readString(dir.resolve("notes.txt")))
        // No leftovers from the temporary file the replace goes through.
        val names = Files.list(dir).use { paths -> paths.toList() }.map { it.fileName.toString() }
        assertTrue(names.none { it.endsWith(".part") }, "left a .part file behind: $names")
    }

    @Test
    fun `a missing queue is not an error`(@TempDir dir: Path) {
        assertEquals(0, RunReport.restamp(dir.resolve("never-created"), "Sighte"))
        assertEquals(0, RunReport.restamp(dir, "Sighte")) // exists but empty
    }

    // --- runloss-001: a run quit straight from the dungeon still reaches the queue ---
    //
    // What this half of the class pins is the three things that had to become true before
    // `ClientPlayConnectionEvents.DISCONNECT` could be a call site for `write`, none of which the
    // wiring itself can demonstrate here (no dev client can reach Hypixel, and the event cannot be
    // raised in a unit test):
    //
    //   1. the report can be built without asking the client who the player is,
    //   2. it lands as a whole file or not at all, because the writer may be a process on its way
    //      out through `System.exit(0)`,
    //   3. it lands exactly once, across two call sites that can both fire for one run.
    //
    // The wiring is measured instead of tested — see the disassembly cited on [RunReport.uploader]
    // — and named as unverified in `feature_list.json`.

    @org.junit.jupiter.api.BeforeEach
    fun clearTheReportedFlag() {
        // The guard is process-wide state on an `object`, and the suite runs sequentially.
        RunReport.reset()
    }

    /**
     * The identity question the `ponytail:` note at the old `SighteAddons.kt:53-57` said made this
     * "not a one-liner": the DISCONNECT handler races `Minecraft.player` being nulled, on a thread
     * that is not the one nulling it. The answer is to stop asking the client — the name captured
     * during the run wins, and it is also the name every room's tick map is keyed by.
     */
    @Test
    fun `the report is keyed by the name captured during the run, not by the client`() {
        assertEquals("Sighte", RunReport.uploader(live = "Sighte", captured = "Sighte"))
        // The case the feature exists for: the client has already let go of its player.
        assertEquals("Sighte", RunReport.uploader(live = null, captured = "Sighte"))
        // Captured wins outright rather than merely filling in. A live player who is not the one the
        // run was tracked under would key the report to rows that are not in it.
        assertEquals("Sighte", RunReport.uploader(live = "Somebody", captured = "Sighte"))
        // Nothing was ever captured: a report before PartyTracker has run at all.
        assertEquals("Sighte", RunReport.uploader(live = "Sighte", captured = null))
    }

    /** Neither source: there is no report to write, and `write` returns before building one. */
    @Test
    fun `with no name from either source there is nobody to report`() {
        assertEquals(null, RunReport.uploader(live = null, captured = null))
    }

    /**
     * The expensive failure this path can produce is not a missing report, it is half of one. The
     * DISCONNECT write runs a few statements before `System.exit(0)`; a truncated `run-*.json` still
     * matches [TelemetryUpload.RUN], is posted at the next launch, fails the receiver's `check_run`
     * with a 400 and is filed under `rejected/` permanently. So the file appears whole or not at all.
     */
    @Test
    fun `a report appears whole or not at all`(@TempDir dir: Path) {
        val name = "run-1786530000000-$installId.json"
        assertTrue(RunReport.queue(dir, name, report().toString()))

        val file = dir.resolve(name)
        assertEquals(report().toString(), Files.readString(file))
        // The temporary file is gone, and while it existed its name was outside the uploader's
        // pattern — that is what keeps a torn write off the wire rather than off the disk.
        val names = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertTrue(names.none { it.endsWith(".part") }, "left a .part file behind: $names")
        assertTrue(TelemetryUpload.RUN.matches(name))
        assertFalse(TelemetryUpload.RUN.matches("$name.part"))
    }

    /**
     * The other half of the same mechanism, and the one that can be observed from here: a torn
     * temporary left by an earlier attempt is consumed by the next successful write rather than
     * accumulating in the queue directory. That the target is only ever reached by a move is a
     * property of the code and not of any end state a test can inspect — this is the assertion that
     * fails if the move is replaced by writing the report straight to its final name.
     */
    @Test
    fun `a torn temporary from an earlier attempt does not survive the next write`(@TempDir dir: Path) {
        val name = "run-1786530000000-$installId.json"
        // What a crash between "write the temporary" and "move it" leaves behind.
        Files.writeString(dir.resolve("$name.part"), report().toString().take(120))

        assertTrue(RunReport.queue(dir, name, report().toString()))

        assertEquals(report().toString(), Files.readString(dir.resolve(name)))
        val names = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(listOf(name), names, "the queue directory must hold the report and nothing else")
    }

    /** The queue directory does not exist yet on a first run, and creating it is part of the write. */
    @Test
    fun `the queue directory is created on the way`(@TempDir dir: Path) {
        val runs = dir.resolve("sighteaddons/runs")
        val name = "run-1786530000000-$installId.json"

        assertTrue(RunReport.queue(runs, name, report().toString()))
        assertEquals(report().toString(), Files.readString(runs.resolve(name)))
    }

    /**
     * The pair `summaryPrinted` never covered, and the reason the guard moved into [RunReport].
     * Dropping to the title screen from inside a floor disconnects — the report is written there —
     * and joining any server afterwards logs in again, reaching the JOIN site with nothing having
     * reset in between. Both calls carry the same run; only one file may exist.
     */
    @Test
    fun `a run reported on the way out is not reported again on the way back in`(@TempDir dir: Path) {
        val onDisconnect = "run-1786530000000-$installId.json"
        val onJoin = "run-1786530009999-$installId.json"

        assertTrue(RunReport.queue(dir, onDisconnect, report(complete = false).toString()))
        assertFalse(RunReport.queue(dir, onJoin, report(complete = false).toString()))

        val names = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(listOf(onDisconnect), names)
    }

    /** The guard is per run, not per process: the next dungeon is reported normally. */
    @Test
    fun `the next run may be reported again`(@TempDir dir: Path) {
        val first = "run-1786530000000-$installId.json"
        val second = "run-1786540000000-$installId.json"

        assertTrue(RunReport.queue(dir, first, report().toString()))
        assertFalse(RunReport.queue(dir, second, report().toString()))
        // What DungeonSession.reset() does at the end of every run.
        RunReport.reset()
        assertTrue(RunReport.queue(dir, second, report().toString()))
        assertEquals(2, Files.list(dir).use { it.toList() }.size)
    }

    /**
     * A write that failed is not a run that was reported. The claim goes back, so the later call
     * site still gets its turn — losing the run twice over would be the one outcome worse than the
     * defect this feature fixes.
     */
    @Test
    fun `a failed write leaves the next call site its chance`(@TempDir dir: Path) {
        // A regular file where the queue directory should be: createDirectories fails, so nothing is
        // written and nothing is claimed.
        val blocked = dir.resolve("runs")
        Files.writeString(blocked, "not a directory")
        val name = "run-1786530000000-$installId.json"

        assertFalse(RunReport.queue(blocked, name, report().toString()))
        // Same run, a directory that works this time.
        val runs = dir.resolve("elsewhere")
        assertTrue(RunReport.queue(runs, name, report().toString()))
        assertEquals(report().toString(), Files.readString(runs.resolve(name)))
    }

    /**
     * The queue is where [restamp] and [TelemetryUpload] meet what was just written, so what
     * [publish] produces has to be a report by both of their definitions — one pattern, one contract.
     */
    @Test
    fun `what the disconnect path writes is a report the queue recognises`(@TempDir dir: Path) {
        val name = "run-1786530000000-$installId.json"
        assertTrue(RunReport.queue(dir, name, report(complete = false).toString()))

        // restamp walks the same directory with the same pattern and must find it.
        assertEquals(1, RunReport.restamp(dir, "Sighte"))
        val stored = read(dir.resolve(name))
        assertEquals("Sighte", stored["player"].asString)
        // Abandoned, and still saying so after being restamped: this is the field that stops the
        // receiver reading a quit run as a whole one.
        assertFalse(stored["complete"].asBoolean)
        assertEquals(1, stored["rooms"].asJsonArray.size())
    }

    /**
     * Writes [DungeonSession.floor] the way `DungeonSessionTest` does and for the same reason: the
     * setter is private and the only writer needs a live client. Cleared again by the caller — the
     * field is process-wide state on an `object` and the suite runs sequentially.
     */
    private fun setFloor(value: String?) {
        val field = DungeonSession::class.java.getDeclaredField("floor")
        field.isAccessible = true
        field.set(DungeonSession, value)
    }

    /**
     * **The read all three write paths share.** [RunReport.write] cannot be called from this suite —
     * it needs a live `Minecraft` — so this is the closest reachable point to the line that filed 20
     * of the 22 reports on the box under `?`. What it asserts is that the value on
     * [DungeonSession] reaches the report unchanged: the headline path had it all along, and since
     * `floorloss-001` the `JOIN` and `DISCONNECT` paths see the same thing, because the floor is no
     * longer cleared on the way out.
     */
    @Test
    fun `a report is filed under the floor the session remembers`() {
        try {
            setFloor("M7")
            assertEquals("M7", RunReport.reportedFloor())
            // And it reaches the file under the key the receiver requires — `floor` is in
            // ingest.py's RUN_KEYS and has been since schema 1, so this changes the value and not
            // the shape.
            assertEquals("M7", report(floor = RunReport.reportedFloor())["floor"].asString)
        } finally {
            setFloor(null)
        }
    }

    /**
     * `?` survives, and it now means what it says. A run whose floor was never seen has no honest
     * answer, and inventing one would put a fabricated floor into an append-only store — so the
     * fallback stays. What changed is that it is no longer the ordinary outcome.
     */
    @Test
    fun `a floor that was never seen is still a question mark`() {
        setFloor(null)
        assertEquals("?", RunReport.reportedFloor())
    }
}
