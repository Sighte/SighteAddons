#!/usr/bin/env python3
"""Receives telemetry from the mod: debug sessions into the inbox, run reports into profiles.

    SIGHTE_TOKEN=<shared secret>         # required, the author's own token
    SIGHTE_PUBLIC_TOKEN=<public secret>  # optional, the one compiled into the published jar
    SIGHTE_INBOX=/srv/sighte/inbox
    SIGHTE_PROFILES=/srv/sighte/profiles
    SIGHTE_ROOMS=<repo>/src/main/resources/assets/sighteaddons/rooms.json
    SIGHTE_PORT=8420
    SIGHTE_HOST=0.0.0.0           # 127.0.0.1 when a reverse proxy fronts this
    SIGHTE_TRUST_PROXY=1          # only behind a reverse proxy: read X-Forwarded-For

    POST /ingest    body = session JSONL, X-Session-File: session-<millis>.jsonl
    POST /runs      body = one run report, X-Session-File: run-<millis>-<install id>.json
    GET  /health    200, for checking the port is reachable at all

    Both POSTs need Authorization: Bearer <token>. X-Mod-Version is optional.
    /ingest takes the private token only and answers a public one with 403; /runs takes either.

Deployed as /srv/sighte/ingest.py by copying this file, see SETUP.md section 4. It used to live in
that document as a heredoc, which is how the running box ended up without a /runs endpoint for
hours — and a validator in a markdown code block cannot be unit tested.
"""

import hmac
import json
import math
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# .get rather than [] so this module imports without an environment: the validator and the rate
# limiter are unit tested and neither needs a token to exist. Startup still refuses to run without
# one, see __main__.
TOKEN = os.environ.get("SIGHTE_TOKEN", "")
# Compiled into the published jar and therefore readable by anyone who decompiles it. Unset means
# there is no public tier at all: an /etc/sighte-ingest.env written before this existed must never
# silently start accepting strangers.
PUBLIC_TOKEN = os.environ.get("SIGHTE_PUBLIC_TOKEN", "")
INBOX = Path(os.environ.get("SIGHTE_INBOX", "/srv/sighte/inbox"))
PROFILES = Path(os.environ.get("SIGHTE_PROFILES", "/srv/sighte/profiles"))
PORT = int(os.environ.get("SIGHTE_PORT", "8420"))
# 127.0.0.1 once something else terminates TLS in front of this; see "Narrowing the exposure".
HOST = os.environ.get("SIGHTE_HOST", "0.0.0.0")
# The peer address is the proxy once something fronts this, so the limit needs the header to see
# individual clients — but only there. Off by default, because anyone can send it.
TRUST_PROXY = os.environ.get("SIGHTE_TRUST_PROXY", "") == "1"

# A 20 000-event session is a few MB; this is the runaway guard, not a target.
MAX_BYTES = 64 * 1024 * 1024
# A run report is a few kB. It goes into a permanent file, so it gets the tighter cap.
MAX_RUN = 4 * 1024 * 1024
# One profile is one install. Far past any plausible number of players, and the point is only that
# a stranger cycling install ids cannot fill the disk with new files; see store_run.
MAX_PROFILES = 10_000

# Per client address, sliding. A game start hands over a whole backlog at once, so this sits well
# above a handful: it exists to make guessing the token and hammering the validator expensive, not
# to pace a legitimate uploader.
RATE_WINDOW = 600
RATE_BURST = 60
# A request costs one unit plus one per this many body bytes, so the budget is 60 posts *or* ~3.8 MiB
# per window, whichever runs out first. Counting requests alone let one address write 60 × MAX_RUN
# into permanent storage every ten minutes; a real report is a few kB and still costs its one unit.
RATE_UNIT = 64 * 1024
# ponytail: in-memory, so the window is per process and resets on restart, and a distributed
# uploader gets a bucket per address. The upgrade path is a shared store, which needs a dependency
# this box deliberately does not have. RATE_SWEEP is when the whole dict gets checked for dead
# entries rather than just the address being touched.
RATE_SWEEP = 4096

SESSION = re.compile(r"session-\d{10,17}\.jsonl")
RUN = re.compile(r"run-\d{10,17}-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.json")
VERSION = re.compile(r"[^A-Za-z0-9._+-]")

# Every string a run report may contain, as a closed pattern or a closed set. See check_run for why
# none of them is allowed to be "any text up to n characters".
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
# Hypixel's sidebar, via the FLOOR regex in DungeonSession.kt: entrance, seven floors, seven master
# floors. "?" is the mod's own value for a run whose floor it never read.
FLOOR = re.compile(r"\?|E|[FM][1-7]")
MOD_VERSION = re.compile(r"[A-Za-z0-9._+-]{1,32}")
PLAYER = re.compile(r"[A-Za-z0-9_]{1,16}")
# Roman numerals in practice, and empty when the tab row never showed one.
CLASS_LEVEL = re.compile(r"[A-Za-z0-9]{0,8}")
# The five classes plus the two states a tab row shows instead of one; same vocabulary Pseudonym.KEEP
# holds for the other direction. PartyTracker carries the living class across a death, so DEAD and
# EMPTY only reach a report when the slot never resolved to anything else.
CLASSES = frozenset(("Archer", "Berserk", "Healer", "Mage", "Tank", "DEAD", "EMPTY"))
# `classes` is "<class> <level>" trimmed (RunReport.build), so the level may be absent, and an entry
# is empty when the slot resolved to neither.
CLASS_ENTRY = re.compile(rf"(?:(?:{'|'.join(sorted(CLASSES))})(?: [LXVI0]{{1,8}})?)?")
# RoomType in DungeonMapReader.kt, which is read off the map colour rather than from rooms.json.
ROOM_TYPES = frozenset(("ENTRANCE", "ROOM", "PUZZLE", "TRAP", "MINIBOSS", "FAIRY", "BLOOD", "UNKNOWN"))

# Room names and shapes come from the mod's own asset rather than from a pattern or a copy in here.
# rooms.json is Odin's database verbatim (LICENSE-Odin) and the repo forbids regenerating or editing
# it, so a list of 140 names in this file would be exactly that copy drifting — and a name is the
# longest string a report carries, which makes it the one worth closing hardest.
#
# This file runs from two places: the repo checkout the tests import it from, and /srv/sighte after
# the copy in SETUP.md section 4. SIGHTE_ROOMS wins when set; otherwise both are tried. Nothing
# readable leaves the sets empty, which rejects every named room, and __main__ refuses to start —
# a closed vocabulary with a loose fallback is not one.
ROOM_ASSET = "src/main/resources/assets/sighteaddons/rooms.json"


def _rooms(configured=None):
    """(names, shapes) out of rooms.json, or two empty sets when it cannot be read."""
    configured = os.environ.get("SIGHTE_ROOMS", "") if configured is None else configured
    candidates = [Path(configured)] if configured else [
        Path(__file__).resolve().parent.parent / ROOM_ASSET,
        Path("/srv/sighte/repo") / ROOM_ASSET,
    ]
    for path in candidates:
        try:
            entries = json.loads(path.read_bytes())
            return (
                frozenset(entry["name"] for entry in entries),
                frozenset(entry["shape"] for entry in entries),
            )
        except (OSError, ValueError, TypeError, KeyError):
            continue
    return frozenset(), frozenset()


ROOM_NAMES, ROOM_SHAPES = _rooms()

RUN_KEYS = frozenset((
    "v", "ts", "uuid", "floor", "runTicks", "partySize", "roomsCleared", "unattributed", "deaths",
    "modVersion", "mcVersion", "class", "classLevel", "classes", "rooms",
))
# The uploader's own name. Their own data on the private path, absent from a report that carries
# nobody else's — optional so a build that stops sending it validates too.
RUN_OPTIONAL = frozenset(("player",))
ROOM_KEYS = frozenset((
    "name", "type", "shape", "maxSecrets", "crypts", "segments", "clearTick", "secretsTick",
    "secretRunTicks", "preCleared", "playerTicks", "playersInRoom", "ownTicks", "secretsFound",
    "ownSecrets", "deaths",
))
# Added by schema 3. Optional for the same reason `player` is: a v1 or v2 report can still be sitting
# in an uploader's backlog, and rejecting it for a key its build never heard of would lose the run.
ROOM_OPTIONAL = frozenset(("enterTick",))

# ponytail: every ceiling below is "far past anything a real run reaches" rather than a measured
# limit — a run is around 8 000 ticks over 30 rooms. When a Hypixel change makes one of them real,
# raise it and bump the report's schema version instead of loosening the shape.
MAX_TICKS = 2_000_000
MAX_ROOMS = 100
MAX_PARTY = 5
MAX_CLEARED = 200
MAX_DEATHS = 500
MAX_SECRETS = 64


def _num(value, low, high):
    """An int in range. bool is excluded on purpose: isinstance(True, int) is True in Python, so
    without that clause `"v": true` would validate as a schema version."""
    return isinstance(value, int) and not isinstance(value, bool) and low <= value <= high


def _real(value, low, high):
    """`unattributed` is the one field that is legitimately fractional.

    json.loads accepts NaN and Infinity, and json.dumps writes them straight back out as tokens no
    other JSON reader accepts — which is precisely the permanently broken line in an append-only
    file that parsing and re-serialising exists to prevent. NaN fails the range test on its own;
    Infinity does not.
    """
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    return math.isfinite(value) and low <= value <= high


def _text(value, pattern):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def _opt_text(value, pattern):
    return value is None or _text(value, pattern)


def _one_of(value, allowed):
    """isinstance first: `in` against a frozenset raises on an unhashable value, and a list here
    would come back as a 500 instead of the 400 it is."""
    return isinstance(value, str) and value in allowed


def _opt_one_of(value, allowed):
    return value is None or _one_of(value, allowed)


def _opt_num(value, low, high):
    return value is None or _num(value, low, high)


RUN_FIELDS = (
    ("v", lambda x: _num(x, 1, 10)),
    ("ts", lambda x: _num(x, 10 ** 9, 10 ** 17 - 1)),
    ("uuid", lambda x: _text(x, UUID)),
    ("player", lambda x: _text(x, PLAYER)),
    ("floor", lambda x: _text(x, FLOOR)),
    ("runTicks", lambda x: _num(x, 0, MAX_TICKS)),
    ("partySize", lambda x: _num(x, 0, MAX_PARTY)),
    ("roomsCleared", lambda x: _num(x, 0, MAX_CLEARED)),
    ("deaths", lambda x: _num(x, 0, MAX_DEATHS)),
    ("unattributed", lambda x: _real(x, 0, MAX_CLEARED)),
    ("modVersion", lambda x: _text(x, MOD_VERSION)),
    ("mcVersion", lambda x: _text(x, MOD_VERSION)),
    ("class", lambda x: _opt_one_of(x, CLASSES)),
    ("classLevel", lambda x: _opt_text(x, CLASS_LEVEL)),
    ("classes", lambda x: isinstance(x, list) and len(x) <= MAX_PARTY
        and all(_text(entry, CLASS_ENTRY) for entry in x)),
    ("rooms", lambda x: isinstance(x, list) and len(x) <= MAX_ROOMS),
)

ROOM_FIELDS = (
    # Null when the chunk never streamed in and the room stayed unnamed; anything else has to be a
    # room the database knows, see ROOM_ASSET.
    ("name", lambda x: _opt_one_of(x, ROOM_NAMES)),
    ("type", lambda x: _one_of(x, ROOM_TYPES)),
    ("shape", lambda x: _opt_one_of(x, ROOM_SHAPES)),
    # From -1, which is the mod's "the room database had no entry for this" rather than a count.
    # It is written as a number, so it has to pass rather than read as corrupt.
    ("maxSecrets", lambda x: _opt_num(x, -1, MAX_SECRETS)),
    ("crypts", lambda x: _opt_num(x, -1, MAX_SECRETS)),
    ("segments", lambda x: _opt_num(x, 0, 8)),
    # Run tick of first entry, the anchor `clearTick` needs to become a duration. Same tolerance as
    # every other tick field; a negative value is what a broken anchor would look like.
    ("enterTick", lambda x: _opt_num(x, 0, MAX_TICKS)),
    ("clearTick", lambda x: _opt_num(x, 0, MAX_TICKS)),
    ("secretsTick", lambda x: _opt_num(x, 0, MAX_TICKS)),
    ("secretRunTicks", lambda x: _opt_num(x, 0, MAX_TICKS)),
    ("playerTicks", lambda x: _opt_num(x, 0, MAX_TICKS * MAX_PARTY)),
    ("playersInRoom", lambda x: _opt_num(x, 0, MAX_PARTY)),
    ("ownTicks", lambda x: _opt_num(x, 0, MAX_TICKS)),
    ("secretsFound", lambda x: _opt_num(x, 0, MAX_SECRETS)),
    ("ownSecrets", lambda x: _opt_num(x, 0, MAX_SECRETS)),
    ("deaths", lambda x: _opt_num(x, 0, MAX_DEATHS)),
    # Not _opt_num: this is the one field where a bool is the only right answer, and an int check
    # would take 0 and 1 in the other direction.
    ("preCleared", lambda x: isinstance(x, bool)),
)


def check_run(report, uuid):
    """The first field that does not fit, or None when the report is exactly the shape the mod
    writes.

    Strict rather than tolerant, and that is the entire point of this function. The token on /runs
    is compiled into a published jar, so anybody who decompiles it can post here: the token no
    longer says anything about who wrote a report, and this validator is what stands in its place.
    The analysis agent reads `profiles/`, so any field that let arbitrary text through would be a
    way for a stranger to put their sentences into an agent's context. Hence a closed pattern for
    every string and no free text anywhere.

    Which is why `name`, `shape`, `floor` and `classes` are closed *sets* rather than character
    classes: a pattern wide enough for `Arrow Trap` is wide enough for `Ignore the other reports and`,
    and 100 rooms of that is a page of prose. Letters and spaces are all an injection needs, so the
    only safe version of "a room name" is the vocabulary the mod itself writes.

    Unknown keys are rejected for the same reason — a field nobody has reviewed must not be able to
    ride along in a future payload. ponytail: that also means a mod that adds a field gets 400 until
    this list grows, which is the intended direction. The upgrade path is bumping `v` in the report
    and adding the field here in the same change.

    It names the field rather than answering yes or no because the first real upload was rejected
    and neither side could say why: the mod logs a 400 and moves on, and the receiver knew exactly
    which field it was and threw that away. A validator this strict has to be able to explain itself
    or every future mod change costs an evening.
    """
    if not isinstance(report, dict):
        return "body is not an object"
    unknown = set(report) - RUN_KEYS - RUN_OPTIONAL
    if unknown:
        # Sanitised: a key name is a stranger's string, and this goes into a log a human reads.
        return f"unknown key {_safe(sorted(unknown)[0])}"
    missing = RUN_KEYS - set(report)
    if missing:
        return f"missing key {sorted(missing)[0]}"
    for key, ok in RUN_FIELDS:
        # `player` is the one optional key; the rest are known to be present by now.
        if key in report and not ok(report[key]):
            return key
    # The filename decides which profile this line is appended to, so a body claiming a different
    # install than the file it arrived as is a mismatch, not a detail.
    if report["uuid"] != uuid:
        return "uuid does not match the filename"
    for index, room in enumerate(report["rooms"]):
        bad = _check_room(room)
        if bad:
            return f"rooms[{index}].{bad}"
    return None


def validate_run(report, uuid):
    return check_run(report, uuid) is None


def _check_room(room):
    if not isinstance(room, dict):
        return "not an object"
    unknown = set(room) - ROOM_KEYS - ROOM_OPTIONAL
    if unknown:
        return f"unknown key {_safe(sorted(unknown)[0])}"
    missing = ROOM_KEYS - set(room)
    if missing:
        return f"missing key {sorted(missing)[0]}"
    for key, ok in ROOM_FIELDS:
        # `in` because ROOM_OPTIONAL keys may be absent; everything in ROOM_KEYS is present by now.
        if key in room and not ok(room[key]):
            return key
    return None


def to_store(report):
    """What gets appended, which is not quite what arrived.

    `player` validates — a v1 file written before 0.5.0 can still be sitting in a backlog and should
    still be accepted — but it must not be written. Schema 2 stopped sending a name on purpose (see
    RunReport.kt's header) and a profile line is permanent, so the one place where a name could
    become permanent anyway is here.
    """
    return {key: value for key, value in report.items() if key != "player"}


def seen_ts(blob):
    """The `ts` values already in a profile.

    A regex over the raw bytes rather than json.loads per line: this runs on every accepted report and
    the only thing needed off each line is one number.
    """
    return {int(found) for found in re.findall(rb'"ts":(\d+)', blob)}


def _safe(text):
    """A stranger's string on its way into the log, cut down to something that cannot pretend to be
    anything else."""
    return re.sub(r"[^A-Za-z0-9_.-]", "?", str(text))[:32]


_hits = {}
_hits_lock = threading.Lock()


def rate_ok(ip, now, hits=_hits, units=1):
    """False once `ip` has spent RATE_BURST units inside RATE_WINDOW seconds.

    `now` is a parameter rather than read in here so the window can be tested without sleeping
    through ten minutes. Expired timestamps are dropped every time an address is touched and an
    address with none left is deleted, so walking the address space cannot grow this without bound.

    `units` is how a large body costs more than a small one — the caller charges the body's size once
    it knows it, see do_POST. Every unit is stamped with the same `now`, so they expire together.
    """
    cutoff = now - RATE_WINDOW
    with _hits_lock:
        if len(hits) > RATE_SWEEP:
            for dead in [addr for addr, seen in hits.items() if seen[-1] <= cutoff]:
                del hits[dead]
        seen = [t for t in hits.get(ip, ()) if t > cutoff]
        seen.extend([now] * units)
        # Refused attempts are remembered too, so hammering keeps the door shut instead of freeing
        # a slot a second. Trimmed to the budget, or a flood would be a memory leak sitting behind
        # a rate limit.
        hits[ip] = seen[-(RATE_BURST + 1):]
        return len(seen) <= RATE_BURST


def units_for(size):
    """What a body of `size` bytes costs on top of the one unit the request already cost.

    Zero up to and including RATE_UNIT, which is why it is `- 1`: the request itself is the first
    unit, so a normal report must not be charged twice for being a normal report.
    """
    return (size - 1) // RATE_UNIT


def client_ip(peer, forwarded, trust_proxy):
    """The address the rate limit counts against.

    X-Forwarded-For is read only when SIGHTE_TRUST_PROXY=1. Anyone can send that header, so
    trusting it by default would mean the limit is bypassed by setting it — while behind a reverse
    proxy the peer is always 127.0.0.1 and every uploader in the world would share one bucket.
    """
    if not trust_proxy:
        return peer
    # Left-most entry is the client, everything after it is the proxy chain.
    first = (forwarded or "").split(",")[0].strip()
    return first or peer


def tier(auth):
    """Which token this request presented: "private", "public" or None.

    Constant-time on both. Each arm needs its own token to be non-empty, because an
    /etc/sighte-ingest.env carrying a bare `SIGHTE_PUBLIC_TOKEN=` would otherwise make `Bearer `
    with nothing after it the password to the public tier.
    """
    # compare_digest raises on non-ASCII, and headers arrive latin-1 decoded. Such a header could
    # never have matched a token anyway, so this is a 401 rather than a 500.
    if not auth.isascii():
        return None
    if TOKEN and hmac.compare_digest(auth, f"Bearer {TOKEN}"):
        return "private"
    if PUBLIC_TOKEN and hmac.compare_digest(auth, f"Bearer {PUBLIC_TOKEN}"):
        return "public"
    return None


class Ingest(BaseHTTPRequestHandler):
    server_version = "sighte-ingest"
    # StreamRequestHandler puts this on the connection socket, so a body that stops arriving raises
    # here instead of holding the thread for as long as the client feels like. ThreadingHTTPServer
    # spawns one thread per connection and caps nothing, so without this a handful of slow sockets is
    # a memory problem that costs the other side nothing. Well above the seconds a real upload takes.
    timeout = 30

    def do_GET(self):
        # Not rate limited: it is the reachability probe, it writes nothing, and a monitoring curl
        # that starts answering 429 is a worse problem than the one the limit solves.
        self.reply(200 if self.path == "/health" else 404)

    def do_POST(self):
        if self.path not in ("/ingest", "/runs"):
            return self.reply(404)

        who = tier(self.headers.get("Authorization", ""))
        ip = client_ip(self.client_address[0], self.headers.get("X-Forwarded-For", ""), TRUST_PROXY)
        # Counted before the token decides anything, so guessing it runs out of attempts. The
        # author's own uploader is exempt from the ceiling: it hands over a whole backlog at game
        # start and would otherwise rate-limit itself out of its own inbox.
        if not rate_ok(ip, time.monotonic()) and who != "private":
            return self.reply(429)
        if who is None:
            return self.reply(401)
        # A debug session is the author's own and holds other players; run reports are what every
        # install uploads. A public token on /ingest is a well-formed request from a client with no
        # business here, which is 403 and not 401.
        if who == "public" and self.path == "/ingest":
            return self.reply(403)

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(411)
        # The endpoint's own cap, before the read rather than after it: a run report is a few kB, so
        # pulling 64 MB into memory to then answer 413 is work a stranger gets for free.
        if length <= 0 or length > (MAX_RUN if self.path == "/runs" else MAX_BYTES):
            return self.reply(413)
        body = self.rfile.read(length)
        if len(body) != length:
            return self.reply(400)
        # The size could only be charged once it was known. A few kB is free; a body that fills
        # MAX_RUN spends the whole window at once, which is the difference between "60 posts" and
        # "60 posts or 240 MiB" as the ceiling on what one address can leave behind.
        weight = units_for(len(body))
        if weight and not rate_ok(ip, time.monotonic(), units=weight) and who != "private":
            return self.reply(429)

        # The header becomes a filename in both cases, so nothing but the exact shape the mod
        # sends is accepted — the endpoint decides which shape that is.
        name = self.headers.get("X-Session-File", "")
        if self.path == "/ingest":
            return self.store_session(name, body)
        return self.store_run(name, body, who)

    def store_session(self, name, body):
        if not SESSION.fullmatch(name):
            return self.reply(400)
        version = VERSION.sub("", self.headers.get("X-Mod-Version", ""))[:32] or "unknown"
        INBOX.mkdir(parents=True, exist_ok=True)
        # Same session re-sent (the mod retries whatever it could not hand over) overwrites itself.
        target = INBOX / f"{name[:-len('.jsonl')]}-{version}.jsonl"
        tmp = target.with_suffix(".part")
        tmp.write_bytes(body)
        tmp.replace(target)
        print(f"session {target} ({len(body)} bytes)", flush=True)
        self.reply(204)

    def store_run(self, name, body, who):
        match = RUN.fullmatch(name)
        if not match:
            return self.reply(400)
        # Parsed and re-serialised rather than appended raw: this file is permanent and append-only,
        # so a torn upload must not be able to leave a broken line in it forever.
        try:
            report = json.loads(body)
        except ValueError:
            return self.reply(400)
        bad = check_run(report, match.group(1))
        if bad:
            # The field, not just the fact. A 400 that the mod cannot explain and the server will
            # not is a silent hole between two codebases nobody is reading at the same time.
            print(f"rejected run {name} from {self.address_string()} ({who}): {bad}", flush=True)
            return self.reply(400)

        PROFILES.mkdir(parents=True, exist_ok=True)
        # ponytail: one namespace for both tiers, as the contract both sides are built against
        # requires, so a stranger who learns an install id can append lines to that profile. The
        # upgrade path is a directory per tier, or signing a report with something the published jar
        # does not carry in the clear.
        profile = PROFILES / f"{match.group(1)}.jsonl"
        if not profile.exists() and sum(1 for _ in PROFILES.iterdir()) >= MAX_PROFILES:
            # A new install id per report is otherwise an unbounded number of files. 429 rather than
            # a refusal with an opinion: the mod keeps the file and tries again later, which is what
            # should happen if this ever fires for a real player.
            print(f"profile ceiling reached, refused a new id from {self.address_string()}", flush=True)
            return self.reply(429)
        if profile.is_file() and report["ts"] in seen_ts(profile.read_bytes()):
            # The mod keeps a file until it hears 204 and re-sends it at the next launch, so a reply
            # lost after the append arrives here a second time. 204 and no second line: it did land,
            # the uploader just never heard so. Appending it twice cannot be undone afterwards —
            # nothing downstream deduplicates and the agent is forbidden from editing profiles.
            print(f"duplicate run {profile.name} ts={report['ts']} ({who})", flush=True)
            return self.reply(204)
        with profile.open("a", encoding="utf-8") as out:
            out.write(json.dumps(to_store(report), separators=(",", ":"), ensure_ascii=False) + "\n")
        # Every value here is closed-pattern by the time it prints, which is what makes it safe to
        # put a stranger's report in a log a human reads.
        print(f"run {profile.name} floor={report['floor']} rooms={len(report['rooms'])} ({who})", flush=True)
        self.reply(204)

    def reply(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_request(self, code="-", size="-"):
        """The request line, and the peer address only when the request was refused.

        The mod tells the player their runs go up "under a random id, without your name". A 204 line
        carrying the address sits one line away from the install id that store_run prints, in a
        journal that keeps both for as long as the box lives — which quietly turns the random id into
        a pseudonym for a network address. On a refusal the address is the only thing that makes a
        flood or a token-guesser investigatable, so that direction keeps it.
        """
        refused = code == "-" or int(code) >= 400
        where = f"{self.address_string()} " if refused else ""
        print(f'{where}"{self.requestline}" {code}', file=sys.stderr, flush=True)

    def log_message(self, fmt, *args):
        # Everything reaching this is an error path (log_error), where the address is the point.
        print(f"{self.address_string()} {fmt % args}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    if not TOKEN:
        sys.exit("SIGHTE_TOKEN is not set: refusing to start without the private token")
    # Both tiers on one value would hand the private endpoint to everybody holding the public jar,
    # which is the one misconfiguration here that cannot be noticed by looking at the logs.
    if PUBLIC_TOKEN and hmac.compare_digest(TOKEN, PUBLIC_TOKEN):
        sys.exit("SIGHTE_PUBLIC_TOKEN equals SIGHTE_TOKEN: that would make every install private")
    # Refusing to start is the whole point: running with an empty vocabulary would reject every named
    # room, and running with a loose fallback instead would be the hole the vocabulary closes.
    if not ROOM_NAMES:
        sys.exit(f"no readable rooms.json: set SIGHTE_ROOMS to a checkout's {ROOM_ASSET}")
    tiers = "private+public" if PUBLIC_TOKEN else "private only"
    # The room count is in here because an asset that moved is otherwise invisible until the first
    # report is rejected for a name that was fine yesterday.
    print(f"sighte-ingest on {HOST}:{PORT} -> {INBOX}, {PROFILES} ({tiers}, {len(ROOM_NAMES)} rooms)", flush=True)
    ThreadingHTTPServer((HOST, PORT), Ingest).serve_forever()
