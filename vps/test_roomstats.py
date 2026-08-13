#!/usr/bin/env python3
"""python3 -m unittest discover vps

Nothing here touches the filesystem. `fold` takes lines and `store` takes what it produced, so the
part that decides what a room's averages are can be checked without a profiles directory — the same
reason the receiver's validator is a plain function.
"""

import json
import unittest

import roomstats


def room(**overrides):
    """One room entry as RunReport.kt writes it, schema 3.

    `enterTick` before `clearTick` and `secretsTick` after it, so the default visit times all three.
    Tests then take pieces away, which is the shape real data arrives in.
    """
    base = {
        "name": "Catwalk",
        "type": "ROOM",
        "shape": "1x2",
        "maxSecrets": 4,
        "crypts": 2,
        "segments": 2,
        "enterTick": 100,
        "clearTick": 400,
        "secretsTick": 500,
        "secretRunTicks": 60,
        "preCleared": False,
        "playerTicks": 315,
        "playersInRoom": 1,
        "ownTicks": 300,
        "secretsFound": 3,
        "ownSecrets": 2,
        "deaths": 1,
    }
    base.update(overrides)
    return base


def report(**overrides):
    """A run report as the receiver stored it: no `player`, install id in `uuid`."""
    base = {
        "v": 3,
        "ts": 1786530882102,
        "uuid": "0f5e4a1c-1111-2222-3333-444455556666",
        "floor": "M5",
        "runTicks": 8000,
        "partySize": 2,
        "roomsCleared": 7,
        "unattributed": 1.25,
        "deaths": 2,
        "modVersion": "0.6.0",
        "mcVersion": "26.1.2",
        "class": "Berserk",
        "classLevel": "VII",
        "classes": ["Berserk VII", "Mage L"],
        "rooms": [room()],
    }
    base.update(overrides)
    return base


def lines(*reports):
    return [json.dumps(one, separators=(",", ":")) for one in reports]


def fold(*reports):
    return roomstats.fold(lines(*reports))


def only(*reports, name="Catwalk"):
    """The one room the reports are about, folded."""
    rooms, _ = fold(*reports)
    return rooms[name].summary()


class Durations(unittest.TestCase):
    """The three times, and which absences silence which."""

    def test_the_canonical_visit_times_all_three(self):
        self.assertEqual(
            roomstats.durations(room()),
            {"clear": 300, "secretRun": 60, "afterClear": 100},
        )

    def test_a_pre_cleared_visit_times_nothing(self):
        # discover() stamps both ticks at first sight for these, so the differences would be 0 rather
        # than absent — the one case where the data looks measured and is not.
        times = roomstats.durations(room(preCleared=True, enterTick=700, clearTick=700, secretsTick=700))
        self.assertEqual(times, {"clear": None, "secretRun": None, "afterClear": None})

    def test_a_pre_cleared_visit_loses_its_secret_run_too(self):
        self.assertIsNone(roomstats.durations(room(preCleared=True))["secretRun"])

    def test_clear_needs_both_ends(self):
        self.assertIsNone(roomstats.durations(room(enterTick=None))["clear"])
        self.assertIsNone(roomstats.durations(room(clearTick=None))["clear"])
        # A v1 or v2 report has no anchor at all, and never will.
        without = room()
        del without["enterTick"]
        self.assertIsNone(roomstats.durations(without)["clear"])

    def test_after_clear_needs_both_ends(self):
        self.assertIsNone(roomstats.durations(room(clearTick=None))["afterClear"])
        self.assertIsNone(roomstats.durations(room(secretsTick=None))["afterClear"])

    def test_zero_is_a_time(self):
        # Real data has these: the room went green in the same tick the checkmark appeared.
        self.assertEqual(roomstats.durations(room(clearTick=400, secretsTick=400))["afterClear"], 0)

    def test_a_backwards_pair_is_refused_rather_than_clamped(self):
        # One of the two timestamps is not what it claims to be; a clamped 0 would average away the
        # only evidence of that.
        self.assertIsNone(roomstats.durations(room(enterTick=400, clearTick=100))["clear"])
        self.assertIsNone(roomstats.durations(room(clearTick=500, secretsTick=400))["afterClear"])

    def test_an_absent_secret_run_is_not_a_zero(self):
        self.assertIsNone(roomstats.durations(room(secretRunTicks=None))["secretRun"])

    def test_a_secret_run_survives_a_room_that_was_never_cleared(self):
        # The mod times the run off the secrets themselves, so it does not need the map's confirmation.
        self.assertEqual(roomstats.durations(room(clearTick=None))["secretRun"], 60)


class Averages(unittest.TestCase):
    def test_two_visits_average(self):
        summary = only(report(rooms=[room(enterTick=0, clearTick=100)]),
                       report(rooms=[room(enterTick=0, clearTick=300)]))
        self.assertEqual(summary["clear"]["n"], 2)
        self.assertEqual(summary["clear"]["avgTicks"], 200.0)
        self.assertEqual(summary["clear"]["minTicks"], 100)
        self.assertEqual(summary["clear"]["maxTicks"], 300)

    def test_seconds_are_the_ticks_over_twenty(self):
        summary = only(report(rooms=[room(enterTick=0, clearTick=310)]))
        self.assertEqual(summary["clear"]["avgTicks"], 310.0)
        self.assertEqual(summary["clear"]["avgSeconds"], 15.5)

    def test_the_three_counts_are_independent(self):
        """The point of the whole file: one room, three metrics, three different sample sizes.

        A single `visits` number next to three averages would claim evidence none of them has.
        """
        summary = only(
            # Times all three.
            report(rooms=[room()]),
            # Pre-schema-3: no anchor, so no clear time, but the secrets still time.
            report(rooms=[room(secretRunTicks=90, enterTick=None)]),
            # Secrets left behind: no secretsTick, so nothing after the clear.
            report(rooms=[room(secretsTick=None, secretRunTicks=None)]),
        )
        self.assertEqual(summary["visits"], 3)
        self.assertEqual(summary["clear"]["n"], 2)
        self.assertEqual(summary["secretRun"]["n"], 2)
        self.assertEqual(summary["afterClear"]["n"], 2)
        # ...and they are not the same two visits.
        self.assertEqual(summary["secretRun"]["avgTicks"], 75.0)
        self.assertEqual(summary["afterClear"]["avgTicks"], 100.0)

    def test_a_metric_with_no_samples_reports_null_rather_than_zero(self):
        summary = only(report(rooms=[room(secretRunTicks=None)]))
        self.assertEqual(
            summary["secretRun"],
            {"n": 0, "avgTicks": None, "avgSeconds": None, "minTicks": None, "maxTicks": None},
        )

    def test_clear_and_secrets_are_never_mixed(self):
        # A slow fight around trivial secrets: the two averages have to be able to disagree.
        summary = only(report(rooms=[room(enterTick=0, clearTick=2000, secretsTick=2010, secretRunTicks=8)]))
        self.assertEqual(summary["clear"]["avgTicks"], 2000.0)
        self.assertEqual(summary["secretRun"]["avgTicks"], 8.0)
        self.assertEqual(summary["afterClear"]["avgTicks"], 10.0)


class RoomIdentity(unittest.TestCase):
    def test_the_same_name_across_runs_is_one_room(self):
        rooms, _ = fold(report(), report())
        self.assertEqual(list(rooms), ["Catwalk"])
        self.assertEqual(rooms["Catwalk"].visits, 2)

    def test_different_names_stay_apart(self):
        rooms, _ = fold(report(rooms=[room(), room(name="Museum")]))
        self.assertEqual(sorted(rooms), ["Catwalk", "Museum"])

    def test_an_unnamed_visit_is_counted_not_dropped(self):
        rooms, tally = fold(report(rooms=[room(), room(name=None)]))
        self.assertEqual(tally["unnamed"], 1)
        self.assertEqual(tally["visits"], 2)
        self.assertNotIn(None, rooms)
        # The invariant that makes the counters worth printing.
        self.assertEqual(sum(room.visits for room in rooms.values()) + tally["unnamed"], tally["visits"])

    def test_floors_are_counted_but_not_split_on(self):
        rooms, _ = fold(report(floor="M5"), report(floor="F7"), report(floor="M5"))
        self.assertEqual(list(rooms), ["Catwalk"])
        self.assertEqual(rooms["Catwalk"].summary()["floors"], {"F7": 1, "M5": 2})

    def test_a_missing_floor_reads_as_unknown(self):
        rooms, _ = fold(report(floor=None))
        self.assertEqual(rooms["Catwalk"].summary()["floors"], {"?": 1})

    def test_type_and_shape_come_from_the_last_report_that_knew_them(self):
        # Both are null until the chunk streams in, so a later null must not erase what was learned.
        summary = only(report(rooms=[room(shape="1x2")]), report(rooms=[room(shape=None)]))
        self.assertEqual(summary["shape"], "1x2")
        self.assertEqual(summary["type"], "ROOM")

    def test_a_pre_cleared_visit_still_counts_as_a_visit(self):
        # It timed nothing, but the party was there — visits and floors are not claims about timing.
        summary = only(report(rooms=[room(preCleared=True)]))
        self.assertEqual(summary["visits"], 1)
        self.assertEqual(summary["clear"]["n"], 0)


class Tally(unittest.TestCase):
    def test_runs_and_visits(self):
        _, tally = fold(report(rooms=[room(), room(name="Museum")]), report())
        self.assertEqual(tally["runs"], 2)
        self.assertEqual(tally["visits"], 3)

    def test_pre_cleared_visits_are_counted(self):
        _, tally = fold(report(rooms=[room(preCleared=True), room(name="Museum")]))
        self.assertEqual(tally["preCleared"], 1)

    def test_a_torn_line_is_counted_and_the_rest_still_folds(self):
        # profiles/ is append-only and never rewritten, so one bad line must not cost every good one.
        rooms, tally = roomstats.fold(lines(report()) + ['{"v":3,"rooms":['] + lines(report()))
        self.assertEqual(tally["malformed"], 1)
        self.assertEqual(tally["runs"], 2)
        self.assertEqual(rooms["Catwalk"].visits, 2)

    def test_blank_lines_are_not_malformed(self):
        _, tally = roomstats.fold(["", "  ", *lines(report())])
        self.assertEqual(tally["malformed"], 0)
        self.assertEqual(tally["runs"], 1)

    def test_a_line_that_is_not_a_report_is_malformed(self):
        _, tally = roomstats.fold(lines([], "text", 7, {"v": 3}, {"v": 3, "rooms": "Catwalk"}))
        self.assertEqual(tally["malformed"], 5)
        self.assertEqual(tally["runs"], 0)

    def test_a_room_entry_that_is_not_an_object_is_malformed(self):
        rooms, tally = fold(report(rooms=[room(), "Catwalk"]))
        self.assertEqual(tally["malformed"], 1)
        self.assertEqual(tally["runs"], 1)
        self.assertEqual(rooms["Catwalk"].visits, 1)


class AbandonedRuns(unittest.TestCase):
    """A floor the party walked out of, which from v4 is reported like any other.

    The rooms they did clear are the entire reason those reports exist, so they have to fold exactly as
    a finished run's do. What must stay separable is the run level: `runTicks` and `roomsCleared` cover
    the part that was played, and this store counts such runs rather than averaging anything over them.
    """

    def test_a_cleared_room_counts_even_though_the_run_did_not_finish(self):
        summary = only(report(v=4, complete=False))
        self.assertEqual(summary["clear"]["n"], 1)
        self.assertEqual(summary["clear"]["avgTicks"], 300)
        self.assertEqual(summary["afterClear"]["n"], 1)

    def test_a_room_left_uncleared_times_nothing_and_still_counts_as_a_visit(self):
        # Walking out mid-room is the ordinary case: no checkmark, so no clear to time.
        summary = only(report(v=4, complete=False, rooms=[room(clearTick=None, secretsTick=None)]))
        self.assertEqual(summary["visits"], 1)
        self.assertEqual(summary["clear"]["n"], 0)
        self.assertEqual(summary["afterClear"]["n"], 0)

    def test_incomplete_runs_are_counted_apart_without_being_dropped(self):
        rooms, tally = fold(report(v=4, complete=True), report(v=4, complete=False))
        self.assertEqual(tally["runs"], 2)
        self.assertEqual(tally["incomplete"], 1)
        self.assertEqual(rooms["Catwalk"].visits, 2)

    def test_an_older_report_without_the_field_is_not_counted_as_incomplete(self):
        # Up to v3 a report only existed for a finished run, so absent has to read as complete.
        _, tally = fold(report(v=3))
        self.assertEqual(tally["incomplete"], 0)


class MixedSchemas(unittest.TestCase):
    """v1, v2 and v3 in one profile, which is what a real file looks like after an upgrade."""

    def v1(self):
        return report(v=1, player="Sighte", rooms=[self.without_anchor()])

    def without_anchor(self):
        entry = room()
        del entry["enterTick"]
        return entry

    def test_all_three_versions_fold(self):
        rooms, tally = fold(self.v1(), report(v=2, rooms=[self.without_anchor()]), report(v=3))
        self.assertEqual(tally["runs"], 3)
        self.assertEqual(rooms["Catwalk"].visits, 3)

    def test_only_the_v3_run_contributes_a_clear_time(self):
        summary = only(self.v1(), report(v=2, rooms=[self.without_anchor()]), report(v=3))
        self.assertEqual(summary["clear"]["n"], 1)
        # The older runs are not lost — they still time the secrets.
        self.assertEqual(summary["secretRun"]["n"], 3)
        self.assertEqual(summary["afterClear"]["n"], 3)

    def test_an_unknown_schema_still_folds(self):
        # Every field is looked up rather than assumed, so a future v is not a reason to skip a run.
        _, tally = fold(report(v=99))
        self.assertEqual(tally["runs"], 1)
        self.assertEqual(tally["malformed"], 0)


class Document(unittest.TestCase):
    def build(self, *reports):
        rooms, tally = fold(*reports)
        return roomstats.store(rooms, tally, 1786578151226)

    def test_rooms_are_sorted_by_name(self):
        # So two folds over the same profiles differ in nothing but generatedTs.
        document = self.build(report(rooms=[room(name="Museum"), room(), room(name="Blood")]))
        self.assertEqual([entry["name"] for entry in document["rooms"]], ["Blood", "Catwalk", "Museum"])

    def test_the_counters_ride_along_at_the_top(self):
        document = self.build(report())
        self.assertEqual(document["v"], 1)
        self.assertEqual(document["generatedTs"], 1786578151226)
        self.assertEqual(document["runs"], 1)
        self.assertEqual(document["visits"], 1)

    def test_the_document_is_json(self):
        # It is written to a file another program reads; NaN or a set would only show up there.
        json.dumps(self.build(report()), allow_nan=False)

    def test_an_empty_profiles_directory_is_an_empty_store(self):
        document = roomstats.store(*roomstats.fold([]), 1786578151226)
        self.assertEqual(document["rooms"], [])
        self.assertEqual(document["runs"], 0)


if __name__ == "__main__":
    unittest.main()
