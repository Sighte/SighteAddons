#!/usr/bin/env bash
#
# Read-out of the one real dungeon run this repository holds evidence of.
#
# Every number this prints is asserted, so the script is the evidence rather than a description of
# it: if somebody edits session-excerpt.jsonl, or if a claim in feature_list.json is transcribed
# from here by hand and drifts, this fails and names which number moved.
#
# Run it from anywhere:  bash docs/evidence/session-1786719912927/readout.sh
#
set -u
cd "$(dirname "$0")" || exit 1

F=session-excerpt.jsonl
S=player_room-sample.jsonl
fail=0

check() { # label expected actual
  if [ "$2" = "$3" ]; then
    printf 'ok    %-56s %s\n' "$1" "$3"
  else
    printf 'FAIL  %-56s expected %s, got %s\n' "$1" "$2" "$3"
    fail=1
  fi
}

count() { grep -c "$1" "$F" || true; }

echo "== the file =="
check "excerpt lines"                        143 "$(grep -c . "$F")"
check "player_room lines (excluded on purpose)" 0 "$(count '"e":"player_room"')"
check "player_room sample lines"               3 "$(grep -c . "$S")"

echo
echo "== calibration and room naming  (ingame-001) =="
check "calibrated events"                      1 "$(count '"e":"calibrated"')"
check "run_start events"                       1 "$(count '"e":"run_start"')"
check "floor is M7"                            1 "$(count '"e":"calibrated","floor":"M7"')"
check "room_identified events"                36 "$(count '"e":"room_identified"')"
check "  ... naming Withermancer"              3 "$(count '"e":"room_identified".*"name":"Withermancer"')"
check "  ... naming Cathedral"                 4 "$(count '"e":"room_identified".*"name":"Cathedral"')"
check "  ... naming Teleport Maze"             1 "$(count '"e":"room_identified".*"name":"Teleport Maze"')"

echo
echo "== MIN_TICKS: stay.ticks counts SIGHTINGS  (clear-001 note 1) =="
# A room is anchored on the 20th sighting (MIN_TICKS = 20) and the anchor is the stay's START, so
# on a per-tick decoration stream the room_anchored event lands exactly 19 ticks after `at`.
check "room_anchored events"                  10 "$(count '"e":"room_anchored"')"
check "  ... stamped exactly 19 ticks after the stay began" 9 \
  "$(awk -F'"at":' '/"e":"room_anchored"/ {split($2,a,",");split($0,b,"\"t\":");split(b[2],c,",");
       if (c[1]-a[1]==19) n++} END {print n+0}' "$F")"
check "  ... stamped 24 (New Trap, 5 sightings missed)"     1 \
  "$(awk -F'"at":' '/"e":"room_anchored"/ {split($2,a,",");split($0,b,"\"t\":");split(b[2],c,",");
       if (c[1]-a[1]==24) n++} END {print n+0}' "$F")"
check "  ... any other delta"                  0 \
  "$(awk -F'"at":' '/"e":"room_anchored"/ {split($2,a,",");split($0,b,"\"t\":");split(b[2],c,",");
       d=c[1]-a[1]; if (d!=19 && d!=24) n++} END {print n+0}' "$F")"

echo
echo "== anchorOnClear frequency  (clear-001 note 3) =="
check "cleared events"                        10 "$(count '"e":"cleared"')"
check "  ... anchored by the fallback"         1 "$(count '"anchoredOnClear":true')"
check "  ... anchored by a qualifying stay"    9 "$(count '"anchoredOnClear":false')"
check "the fallback room is Duncan"            1 "$(count '"room":"Duncan".*"anchoredOnClear":true')"
check "  ... cleared 6 ticks after entry"      1 "$(count '"e":"cleared","room":"Duncan".*"enterTick":2990')"

echo
echo "== checkmark reading  (ingame-001) =="
# DungeonMapReader: WHITE = 34 (cleared), GREEN = 30 (cleared, all secrets found). The two rooms
# the database gives 0 secrets read GREEN; the eight that still held secrets at the clear read WHITE.
check "cleared GREEN, 30 = all secrets found"   2 "$(count '"e":"cleared".*"checkmark":30')"
check "cleared WHITE, 34 = secrets still out"   8 "$(count '"e":"cleared".*"checkmark":34')"
check "  ... and GREEN is exactly Default+Hall" 2 \
  "$(grep -cE '"e":"cleared","room":"(Default|Hall)".*"checkmark":30' "$F")"
# ... and those two are exactly the CLEARED rooms the database gives 0 secrets. (Several rooms that
# were never cleared also hold 0 — Entrance, Fairy, Ice Fill, Teleport Maze — so this is a statement
# about the ten clears, not about the 36 identified rooms.)
check "  ... Default and Hall hold 0 secrets"  2 \
  "$(grep -cE '"e":"room_identified".*"name":"(Default|Hall)","type":"[A-Z]+","shape":"1x1","secrets":0' "$F")"
check "  ... and no other CLEARED room holds 0" 8 \
  "$(grep -oE '"e":"cleared","room":"[^"]+"' "$F" | sed 's/.*room":"//;s/"//' \
     | while read -r r; do grep -m1 -E "\"room_identified\".*\"name\":\"$r\"" "$F"; done \
     | grep -cvE '"secrets":0')"

echo
echo "== the party heuristic was NOT exercised  (party-001) =="
check "roster_skew events"                     0 "$(count '"e":"roster_skew"')"
check "distinct players in the player_room sample" 1 \
  "$(grep -o '"player":"[^"]*"' "$S" | sort -u | grep -c .)"
check "distinct decoIndex in the player_room sample" 1 \
  "$(grep -o '"decoIndex":[0-9]*' "$S" | sort -u | grep -c .)"

echo
echo "== the run report was never written  (runloss-001) =="
# SighteAddons.kt writes the report on ClientPlayConnectionEvents.JOIN and on the end-of-run
# headline. The user quit the game straight from the dungeon, so neither fired.
check "run_end events"                         0 "$(count '"e":"run_end"')"

echo
echo "== ClearPoints weights, under clearpoints-001's DELETED formula =="
# These are NOT the weights HEAD produces. The build that played this run was a debug build of
# 72e0825; clearpoints-002 (0d81667) replaced the formula. See README.md in this directory.
check "award events"                           9 "$(count '"e":"award"')"
check "unattributed events"                    1 "$(count '"e":"unattributed"')"
check "sum of award points"              "26.25" \
  "$(awk -F'"points":' '/"e":"award"/ {split($2,a,",");s+=a[1]} END {printf "%.2f", s}' "$F")"
check "  Hall,      the cheapest"         "1.0" "$(awk -F'"points":' '/"e":"award","room":"Hall"/ {split($2,a,",");print a[1]}' "$F")"
check "  Cathedral, the dearest"          "4.5" "$(awk -F'"points":' '/"e":"award","room":"Cathedral"/ {split($2,a,",");print a[1]}' "$F")"
check "  Pipes,     1x4 with 7 secrets"  "4.25" "$(awk -F'"points":' '/"e":"award","room":"Pipes"/ {split($2,a,",");print a[1]}' "$F")"
check "  Duncan,    via the fallback split" "1.25" \
  "$(awk -F'"points":' '/"e":"unattributed","room":"Duncan"/ {split($2,a,",");print a[1]}' "$F")"

echo
if [ "$fail" -eq 0 ]; then
  echo "==> READOUT: OK"
else
  echo "==> READOUT: FAILED — a number in the evidence moved. Do not transcribe past this."
fi
exit "$fail"
