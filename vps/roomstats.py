#!/usr/bin/env python3
"""Per-room averages out of the run reports in profiles/.

    SIGHTE_PROFILES=/srv/sighte/profiles
    SIGHTE_ROOMSTATS=/srv/sighte/roomstats.json

    python3 roomstats.py

Clearing and secret hunting are averaged **apart**, and that separation is the whole point. A room can
be a long fight guarding two trivial chests, or a thirty-second clear wrapped around a secret nobody
can find. One combined number calls those two rooms equally hard, which is the flat weighting this is
meant to replace.

Three times per visit, each with its own sample count, because the fields they come from are absent
independently of one another:

    clear       enterTick -> clearTick      how long the room took to clear
    secretRun   secretRunTicks              first secret to last, measured by the mod
    afterClear  clearTick -> secretsTick    checkmark to green, the secrets left after the fight

`clear` needs schema 3, so runs uploaded before `enterTick` existed contribute nothing to it and never
will. `secretRun` is the semantically exact secret time but the mod discards it in every ambiguous
case (single-secret rooms, joined half-finished, counter jumping straight to full), so it is sparse.
`afterClear` covers far more visits and measures less — only the part of the hunt after the clear.
They are never mixed into one mean.

**`clear` is an upper bound, not a duration.** The mod stamps `enterTick` the first time it sees *any*
party member in the room, with no minimum stay, so a split party where somebody passes through at tick
100 and the others clear it at tick 3000 reports 145 s for a 1x1. The bound only ever runs long, and
nothing here can tell the two cases apart. Ranking rooms against each other survives that; using
`avgSeconds` as a difficulty weight does not, until the mod anchors on a minimum stay (see the
`ponytail:` note on `enteredAtTick` in `ContributionTracker.kt`, which costs a schema bump).

Recomputed in full on every run rather than updated incrementally. `profiles/` is append-only, so a
full fold is always right, needs no state of its own, and cannot leave a wrong number behind that
nobody can trace. The receiver is not involved: it appends run reports and nothing else.
"""

import json
import os
import sys
import time
from pathlib import Path

PROFILES = Path(os.environ.get("SIGHTE_PROFILES", "/srv/sighte/profiles"))
ROOMSTATS = Path(os.environ.get("SIGHTE_ROOMSTATS", "/srv/sighte/roomstats.json"))

# 20 ticks to the second, the same conversion history.jsonl writes alongside its tick counts.
TICKS_PER_SECOND = 20.0

METRICS = ("clear", "secretRun", "afterClear")


def _span(start, end):
    """`end - start`, or None when either end is missing or the pair runs backwards.

    Backwards is not a small anomaly to be clamped away: it means one of the two timestamps is not
    what this thinks it is, and averaging a clamped zero would hide that behind a plausible number.
    """
    if start is None or end is None or end < start:
        return None
    return end - start


def durations(room):
    """The three times one room visit can contribute. None where this visit cannot say."""
    if room.get("preCleared"):
        # discover() stamps clearedAtTick and secretsAtTick at first sight for a room that was
        # already checkmarked, so every difference here would come out 0 — a number, and a lie.
        # Nobody cleared it during this run, so the visit times nothing at all.
        return dict.fromkeys(METRICS)
    return {
        "clear": _span(room.get("enterTick"), room.get("clearTick")),
        # Already a duration, and timed from the secrets themselves rather than from the map.
        "secretRun": room.get("secretRunTicks"),
        "afterClear": _span(room.get("clearTick"), room.get("secretsTick")),
    }


class Times:
    """One metric of one room: the running mean, and how many visits are behind it.

    The count is per metric rather than per room. A visit routinely times the clear and says nothing
    about the secrets, so a single `visits` number next to three averages would claim evidence that
    is not there.
    """

    __slots__ = ("n", "total", "low", "high")

    def __init__(self):
        self.n = 0
        self.total = 0
        self.low = None
        self.high = None

    def add(self, ticks):
        self.n += 1
        self.total += ticks
        self.low = ticks if self.low is None else min(self.low, ticks)
        self.high = ticks if self.high is None else max(self.high, ticks)

    def summary(self):
        if not self.n:
            return {"n": 0, "avgTicks": None, "avgSeconds": None, "minTicks": None, "maxTicks": None}
        average = self.total / self.n
        return {
            "n": self.n,
            "avgTicks": round(average, 1),
            "avgSeconds": round(average / TICKS_PER_SECOND, 2),
            "minTicks": self.low,
            "maxTicks": self.high,
        }


class Room:
    """Everything the reports say about one room, keyed by its name.

    The name is the key because it is the only cross-run identity the mod has — `RoomDatabase` cannot
    use position, and rooms.json holds 140 unique names with no duplicates. Floors are counted but not
    split on, exactly as RoomHistory collapses them client-side: every run in profiles/ so far has
    `floor` as "?", so per-floor buckets would today be one bucket wearing a label. The count is here
    so splitting them later reads the same file instead of migrating it.
    """

    def __init__(self, name):
        self.name = name
        self.visits = 0
        self.type = None
        self.shape = None
        self.floors = {}
        self.times = {metric: Times() for metric in METRICS}

    def add(self, room, floor):
        self.visits += 1
        self.floors[floor] = self.floors.get(floor, 0) + 1
        # Last non-null wins. Both are null until the chunk streams in, and neither changes for a room
        # that Hypixel has not rebuilt, so the newest report that knew them is the best answer.
        # `type` is the map-colour RoomType, never rooms.json's type — the two vocabularies overlap
        # in shape only, and reading one as the other would be a silent mistake.
        if room.get("type") is not None:
            self.type = room["type"]
        if room.get("shape") is not None:
            self.shape = room["shape"]
        for metric, ticks in durations(room).items():
            if ticks is not None:
                self.times[metric].add(ticks)

    def summary(self):
        return {
            "name": self.name,
            "type": self.type,
            "shape": self.shape,
            "visits": self.visits,
            "floors": dict(sorted(self.floors.items())),
            **{metric: self.times[metric].summary() for metric in METRICS},
        }


def fold(lines):
    """`(rooms, tally)` out of raw profile lines, in the order they were written.

    Counts what it cannot use instead of dropping it, the same way RoomHistory counts malformed
    history lines: a store that silently shrinks is one nobody can audit against the files it came
    from. `sum(room.visits) + unnamed == visits` holds afterwards, which is what makes the counters
    worth printing.
    """
    rooms = {}
    tally = {"runs": 0, "visits": 0, "unnamed": 0, "preCleared": 0, "malformed": 0}
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            report = json.loads(line)
        except ValueError:
            tally["malformed"] += 1
            continue
        entries = report.get("rooms") if isinstance(report, dict) else None
        if not isinstance(entries, list):
            tally["malformed"] += 1
            continue
        tally["runs"] += 1
        # v1, v2 and v3 all fold: the schema number changed what a line means about its uploader, not
        # what a room entry means. An unknown `v` is not a reason to skip a run either — every field
        # read here is looked up rather than assumed.
        floor = report.get("floor") or "?"
        for entry in entries:
            if not isinstance(entry, dict):
                tally["malformed"] += 1
                continue
            tally["visits"] += 1
            if entry.get("preCleared"):
                tally["preCleared"] += 1
            name = entry.get("name")
            if name is None:
                # The chunk never streamed in, so the visit happened but belongs to no known room.
                # How much of a run stays unnamed is a finding in its own right.
                tally["unnamed"] += 1
                continue
            rooms.setdefault(name, Room(name)).add(entry, floor)
    return rooms, tally


def store(rooms, tally, generated_ts):
    """The document, sorted by name so two folds over the same profiles are byte-identical."""
    return {
        "v": 1,
        "generatedTs": generated_ts,
        **tally,
        "rooms": [room.summary() for room in sorted(rooms.values(), key=lambda room: room.name)],
    }


def _lines(paths):
    for path in paths:
        with path.open(encoding="utf-8") as handle:
            yield from handle


if __name__ == "__main__":
    # Refusing beats writing an empty store: a wrong SIGHTE_PROFILES would otherwise overwrite a good
    # file with "no rooms anywhere", which reads exactly like a fresh box. An empty directory that
    # does exist is a fresh box, and that folds to an empty store as it should.
    if not PROFILES.is_dir():
        sys.exit(f"no profiles directory at {PROFILES}: set SIGHTE_PROFILES")

    profiles = sorted(PROFILES.glob("*.jsonl"))
    rooms, tally = fold(_lines(profiles))
    document = store(rooms, tally, int(time.time() * 1000))

    tmp = ROOMSTATS.with_suffix(".part")
    tmp.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(ROOMSTATS)

    print(
        f"{len(profiles)} profiles, {tally['runs']} runs, {tally['visits']} visits, "
        f"{len(rooms)} rooms -> {ROOMSTATS}",
        flush=True,
    )
    # Per metric, because "61 rooms" says nothing about how many of them any one average covers.
    for metric in METRICS:
        rated = sum(1 for room in rooms.values() if room.times[metric].n)
        visits = sum(room.times[metric].n for room in rooms.values())
        print(f"  {metric}: {rated} rooms, {visits} visits", flush=True)
    for label in ("unnamed", "preCleared", "malformed"):
        if tally[label]:
            print(f"  {tally[label]} {label}", flush=True)
