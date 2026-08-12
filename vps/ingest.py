#!/usr/bin/env python3
"""Receives telemetry from the mod: debug sessions into the inbox, run reports into profiles.

    SIGHTE_TOKEN=<shared secret>  # required, same value as upload.properties in the mod
    SIGHTE_INBOX=/srv/sighte/inbox
    SIGHTE_PROFILES=/srv/sighte/profiles
    SIGHTE_PORT=8420
    SIGHTE_HOST=0.0.0.0           # 127.0.0.1 when a reverse proxy fronts this

    POST /ingest    body = session JSONL, X-Session-File: session-<millis>.jsonl
    POST /runs      body = one run report, X-Session-File: run-<millis>-<uuid>.json
    GET  /health    200, for checking the port is reachable at all

    Both POSTs need Authorization: Bearer <token>. X-Mod-Version is optional.
"""

import hmac
import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

TOKEN = os.environ["SIGHTE_TOKEN"]
INBOX = Path(os.environ.get("SIGHTE_INBOX", "/srv/sighte/inbox"))
PROFILES = Path(os.environ.get("SIGHTE_PROFILES", "/srv/sighte/profiles"))
PORT = int(os.environ.get("SIGHTE_PORT", "8420"))
# 127.0.0.1 once something else terminates TLS in front of this; see "Narrowing the exposure".
HOST = os.environ.get("SIGHTE_HOST", "0.0.0.0")

# A 20 000-event session is a few MB; this is the runaway guard, not a target.
MAX_BYTES = 64 * 1024 * 1024
# A run report is a few kB. It goes into a permanent file, so it gets the tighter cap.
MAX_RUN = 4 * 1024 * 1024

SESSION = re.compile(r"session-\d{10,17}\.jsonl")
RUN = re.compile(r"run-\d{10,17}-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.json")
VERSION = re.compile(r"[^A-Za-z0-9._+-]")


class Ingest(BaseHTTPRequestHandler):
    server_version = "sighte-ingest"

    def do_GET(self):
        self.reply(200 if self.path == "/health" else 404)

    def do_POST(self):
        if self.path not in ("/ingest", "/runs"):
            return self.reply(404)
        # Constant-time: the token is the only thing standing between this box and the internet.
        if not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {TOKEN}"):
            return self.reply(401)

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(411)
        if length <= 0 or length > MAX_BYTES:
            return self.reply(413)
        body = self.rfile.read(length)
        if len(body) != length:
            return self.reply(400)

        # The header becomes a filename in both cases, so nothing but the exact shape the mod
        # sends is accepted — the endpoint decides which shape that is.
        name = self.headers.get("X-Session-File", "")
        if self.path == "/ingest":
            return self.store_session(name, body)
        return self.store_run(name, body)

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

    def store_run(self, name, body):
        match = RUN.fullmatch(name)
        if not match:
            return self.reply(400)
        if len(body) > MAX_RUN:
            return self.reply(413)
        # Parsed and re-serialised rather than appended raw: this file is permanent and append-only,
        # so a torn upload must not be able to leave a broken line in it forever.
        try:
            report = json.loads(body)
        except ValueError:
            return self.reply(400)
        if not isinstance(report, dict):
            return self.reply(400)

        PROFILES.mkdir(parents=True, exist_ok=True)
        profile = PROFILES / f"{match.group(1)}.jsonl"
        with profile.open("a", encoding="utf-8") as out:
            out.write(json.dumps(report, separators=(",", ":"), ensure_ascii=False) + "\n")
        print(f"run {profile.name} floor={report.get('floor')} rooms={len(report.get('rooms', []))}", flush=True)
        self.reply(204)

    def reply(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    print(f"sighte-ingest on {HOST}:{PORT} -> {INBOX}, {PROFILES}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Ingest).serve_forever()
