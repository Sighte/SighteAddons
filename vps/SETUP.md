# Sighte Addons — VPS

Everything the server side needs, in one file: receiver, agent prompt, runner, setup. Paste the
blocks in order on a fresh Debian/Ubuntu box at `217.160.51.229`. Nothing here has to be in the
repository — the commands write every file themselves.

```
Minecraft ─┬─ POST /ingest ─▶ 217.160.51.229:8420 ─▶ /srv/sighte/inbox      (diagnostics)
           └─ POST /runs   ─▶                     ─▶ /srv/sighte/profiles   (permanent)
                                                             │
                                          cron every 30 min: run-agent.sh
                                                             │
                                              claude -p AGENT-PROMPT.md
                                                             │
                                    PR on github.com/Sighte/SighteAddons + report
```

| Path | What |
|---|---|
| `/srv/sighte/inbox/` | Debug sessions waiting to be analysed |
| `/srv/sighte/processed/` | Sessions the agent is done with |
| `/srv/sighte/reports/` | One markdown report per session |
| `/srv/sighte/profiles/<uuid>.jsonl` | **Permanent** per-player run history, append-only |
| `/srv/sighte/repo/` | Clone of the mod, where the agent works |

The two streams are deliberately separate. The inbox is diagnostic material with a short life; a
profile is the permanent record that room difficulty and clear scores get derived from later, and
nothing ever rewrites a line in it.

## 1. Packages

```bash
sudo apt update && sudo apt install -y git python3 jq gh openjdk-25-jdk
```

If the distro has no `openjdk-25-jdk`, take a JDK 25+ tarball (Temurin, Zulu) and put its `bin` on
`PATH` — Gradle 9.5.1 needs 25, `build.gradle` pins no toolchain.

Install Claude Code and sign in **once, interactively, as the `sighte` user** created below — a
headless run cannot do the browser flow for you.

## 2. User, directories, clone

```bash
sudo useradd -m -d /srv/sighte sighte && sudo -u sighte mkdir -p /srv/sighte/{inbox,processed,reports,profiles} && sudo -u sighte git clone https://github.com/Sighte/SighteAddons.git /srv/sighte/repo
```

```bash
sudo -u sighte gh auth login
```

```bash
sudo -u sighte gh auth setup-git
```

`gh auth setup-git` is not optional: without git credentials the agent's first `git push` fails and
every cron run dies at delivery.

## 3. Token

```bash
openssl rand -hex 24
```

Same value on both sides, never in git. Put it in the service environment:

```bash
sudo tee /etc/sighte-ingest.env >/dev/null <<'EOF'
SIGHTE_TOKEN=<the token from openssl>
SIGHTE_INBOX=/srv/sighte/inbox
SIGHTE_PROFILES=/srv/sighte/profiles
SIGHTE_PORT=8420
EOF
sudo chmod 600 /etc/sighte-ingest.env
```

## 4. The receiver

Stdlib only, stateless, a file drop rather than a service. `/ingest` takes a debug session and drops
it in the inbox; `/runs` takes one run report and appends it to that player's profile. Everything
else — scoring, evaluation, room ratings — happens later, over the files.

````bash
sudo -u sighte tee /srv/sighte/ingest.py >/dev/null <<'PY'
#!/usr/bin/env python3
"""Receives telemetry from the mod: debug sessions into the inbox, run reports into profiles.

    SIGHTE_TOKEN=<shared secret>  # required, same value as upload.properties in the mod
    SIGHTE_INBOX=/srv/sighte/inbox
    SIGHTE_PROFILES=/srv/sighte/profiles
    SIGHTE_PORT=8420

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
    print(f"sighte-ingest on :{PORT} -> {INBOX}, {PROFILES}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Ingest).serve_forever()
PY
````

```bash
sudo tee /etc/systemd/system/sighte-ingest.service >/dev/null <<'EOF'
[Unit]
Description=Sighte Addons telemetry ingest
After=network.target

[Service]
User=sighte
EnvironmentFile=/etc/sighte-ingest.env
ExecStart=/usr/bin/python3 /srv/sighte/ingest.py
Restart=always

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now sighte-ingest
```

Open the port **twice**: on the machine and in the IONOS cloud firewall in the panel. The panel rule
is the one that is easy to forget, and without it the port stays closed no matter what `ufw` says.

```bash
sudo ufw allow 8420/tcp
```

## 5. The agent prompt

This is what `claude -p` receives. Everything the agent is allowed to assume about the mod is in
here; if a rule is missing from this file, the agent does not know it.

````bash
sudo -u sighte tee /srv/sighte/AGENT-PROMPT.md >/dev/null <<'PROMPT'
# Sighte Addons — telemetry agent

You maintain the Fabric mod in `/srv/sighte/repo` from the telemetry of real Hypixel runs. The mod's
logic — calibration, decoration→player mapping, checkmark reading, room core hashing — has never been
verified against real Hypixel data by any other means. **The JSONL sessions are the only ground truth
that exists.** Your job is to read them, find where the mod's assumptions do not match Hypixel, fix
that, and hand the fix over as a pull request.

## Layout

| Path | What |
|---|---|
| `/srv/sighte/inbox/session-<millis>-<modversion>.jsonl` | Sessions waiting to be analysed |
| `/srv/sighte/processed/` | Sessions you are done with |
| `/srv/sighte/reports/` | One `<session>.md` per session, written by you |
| `/srv/sighte/profiles/<uuid>.jsonl` | Permanent per-player run history. Read-only for you. |
| `/srv/sighte/repo/` | Clone of `github.com/Sighte/SighteAddons`, branch `main` |

## The loop

Work the inbox oldest file first, one session at a time. If the inbox is empty, stop and say so —
do not invent work.

1. `cd /srv/sighte/repo && git checkout main && git pull --ff-only`
2. **Survey the session before reading it.** A session holds up to 20 000 events; never read the
   whole file into context. Start with
   `jq -r .e <session> | sort | uniq -c | sort -rn` and pull individual event types with
   `jq -c 'select(.e=="room_unmatched")' <session> | head`.
3. Work the playbook below. Write down what the log *shows*, separately from what you *suspect*.
4. Fix the root cause, smallest diff that holds. One concern per pull request; if the session shows
   three unrelated problems, that is three branches.
5. `./gradlew build test` — must pass before you push. The build also refreshes
   `dist/sighteaddons-<version>.jar`; that file changing in your diff is expected, commit it. If you
   raised `mod_version`, the old jar is deleted and the new one added — commit both sides.
6. `gh pr list` first — sessions repeat symptoms, and an open PR already covering the finding gets
   referenced in the report, not opened a second time. Otherwise branch
   `agent/<session-millis>-<short-slug>`, commit, push, open a PR against `main` with `gh`.
   Body: what the log showed, what you changed, what still needs an in-game check.
7. Write `/srv/sighte/reports/<session>.md`, then `mv` the session to `/srv/sighte/processed/`.

A session that shows nothing wrong is a good outcome. Write the report, move the file, open no PR.

## Playbook

Each entry is "what the log says" → "what to look at". Confirm against the source before acting;
the mapping below is where to start, not a diagnosis.

| Signal in the log | Where the fault would be |
|---|---|
| `calibration_waiting` repeats, `calibrated` never arrives | `DungeonSession` — the entrance/Mort anchors never both resolved. Check `mort_found` and what the floor detection saw. |
| `calibrated` arrives with an offset that puts rooms off-grid | `DungeonGrid` / `DungeonSession` calibration maths. Cross-check a `player_room` cell against the player's real coordinates in the same event. |
| `tab_slot` rows present but no roster | `PartyTracker` regex vs. the raw row in the event — Hypixel changed the tab format. |
| `player_room` maps two players to one decoration, or drops members | The decoration-order heuristic in `PartyTracker` (see its `ponytail:` comment). The upgrade path — the player-slot digit in the decoration map key — is already noted there. |
| `room_unmatched` with a full block column | Decide *which* of the two it is before touching anything: (a) the column was hashed from the wrong blocks → `RoomDatabase.coreAt` drifted from Odin's algorithm, fix the algorithm; (b) the column is right but the hash is absent from `rooms.json` → **report it, do not edit the data.** |
| `unattributed` large relative to `roomsCleared` in `run_end` | Same decoration→player mapping as above; the gap is the built-in diagnostic for it. |
| `cleared` / `all_secrets` missing for rooms that clearly were done | `DungeonMapReader` checkmark scan — map colour IDs or the "centre pixel equals room colour" rule. |
| Secret counts never rise, or rise in the wrong room | `SecretTracker` — the action-bar guard requires the reported total to match the room's database count; if the room is unnamed the guard blocks everything, which is correct behaviour, not a bug. |
| No `run_end` although the run finished | The `RUN_END` regex in `SighteAddons.kt` vs. Hypixel's actual headline. |
| No `death` events although the run clearly had deaths | Death detection reads the tab class flipping to `DEAD`, which assumes a dead player's row still matches the `TAB` regex. Check what `tab_slot` logged for that slot: if the row stopped parsing, `parsed` is null and the flip is missed. Deaths in the boss phase are never seen by design — `PartyTracker` stops updating there. |
| `truncated` record | The session hit 20 000 events. Everything after it is missing — say so in the report before drawing conclusions from absence. |

## Hard constraints

- **You cannot run Minecraft.** Loom's dev client has no valid session and cannot reach Hypixel.
  `./gradlew runClient` proves nothing about Hypixel behaviour — never claim a fix is verified
  in-game. Compilation, unit tests and the telemetry are what you have.
- **Never regenerate or hand-edit `assets/sighteaddons/rooms.json`.** It is Odin's database verbatim
  (BSD-3, see `LICENSE-Odin`), and the core hashes only match if the algorithm matches exactly. A
  missing room is a finding for the report, not an edit.
- **Mojang mappings**, not Yarn: `MapItemSavedData`, `Minecraft`, `DataComponents`. There is no
  `mappings` line in `build.gradle` on purpose.
- **No new dependencies**, no Minecraft/Fabric/Kotlin version bumps, no reformatting of code you did
  not otherwise touch.
- **Never push to `main`, never force-push, never rewrite history.** PRs only.
- Never commit tokens or `upload.properties`. Nothing under `/srv/sighte/inbox`, `processed` or
  `profiles` belongs in the repo — telemetry contains player names.
- Do not delete telemetry. Moving a session to `processed/` is the only thing you do to those files.
- **`/srv/sighte/profiles/` is permanent append-only data written by the receiver.** Read it if a
  finding needs history across runs; never edit, rewrite, deduplicate or delete a line in there. A
  run report that looks wrong is a bug in the mod that wrote it, and the fix is in the mod.

## House style

The repo is written by a lazy senior developer and reads that way — keep it that way.

- Smallest change that fixes the root cause. No abstraction with one caller, no config for a value
  that never changes, no defensive scaffolding for a case the log does not show.
- Comments say *why*, never *what*. A deliberate shortcut with a known ceiling gets a `ponytail:`
  comment naming the ceiling and the upgrade path — there are existing ones to copy the tone from.
- Non-trivial logic leaves one runnable check behind: a JUnit test next to the existing ones in
  `src/test/kotlin/sighteaddons/`. No frameworks beyond what is already there.
- If a change alters documented behaviour, update `README.md` in the same PR.

## Report format

Write the report as markdown with these sections: a headline `session-<millis> (mod <version>)`, the
event histogram and run totals, then **Confirmed by the log** (what the events actually show, each
with the event that shows it), **Suspected** (inference, and what would confirm it), **Not visible in
this session** (what you could not check, and why), **Delivered** (PR number and one line, or "no
change"), **For the human** (anything needing an in-game decision or a look at Hypixel).

## When you are unsure

Say so in the report and leave the code alone. A wrong fix to attribution logic is worse than an open
question: it silently produces plausible numbers, and the next session cannot tell you it broke. If a
fix is right in principle but unverifiable from the log alone, open the PR as a **draft** and write
that into both the PR body and the report.
PROMPT
````

## 6. The runner

```bash
sudo -u sighte tee /srv/sighte/run-agent.sh >/dev/null <<'EOF'
#!/usr/bin/env bash
# One agent run over whatever is in the inbox. Safe to call from cron: exits immediately when there
# is nothing to do, and never runs twice at the same time.
set -euo pipefail

INBOX=${SIGHTE_INBOX:-/srv/sighte/inbox}
HOME_DIR=${SIGHTE_HOME:-/srv/sighte}

compgen -G "$INBOX/*.jsonl" >/dev/null || exit 0

exec 9>"$HOME_DIR/.agent.lock"
# A second cron tick while the first is still building would fight over the working tree.
flock -n 9 || exit 0

cd "$HOME_DIR/repo"
git checkout main
git pull --ff-only

# The agent runs unattended, so it cannot answer permission prompts. What keeps this contained is
# the prompt's hard rules plus the delivery path: pull requests only, never a push to main.
exec claude -p "$(cat "$HOME_DIR/AGENT-PROMPT.md")" \
  --model opus \
  --dangerously-skip-permissions
EOF
sudo -u sighte chmod +x /srv/sighte/run-agent.sh
```

## 7. The mod side

On the gaming machine, in the Minecraft instance the mod runs in — the mod never writes this file
and it is never committed, so the token stays on the machine that plays:

```properties
# <prism instance>/.minecraft/config/sighteaddons/upload.properties
url=http://217.160.51.229:8420
token=<the same token>
```

Base URL, no path — the mod appends `/ingest` and `/runs` itself.

No file, no upload. Debug sessions and run reports both go up at the *next* game start, not during
a run, and move to an `uploaded/` folder next to themselves once the server has them. A run played
tonight therefore lands in the profile the next time the game starts.

Plain HTTP, as chosen: the token keeps strangers out of the inbox, but the logs travel readable and
they contain party member names. Caddy in front with a real hostname is the fix if that matters.

## 8. Run it

```bash
sudo -u sighte /srv/sighte/run-agent.sh
```

Once it behaves, let it run itself — every 30 minutes is plenty, the inbox only grows when somebody
plays, and the script exits immediately when it is empty:

```bash
sudo -u sighte crontab -l 2>/dev/null | { cat; echo '*/30 * * * * /srv/sighte/run-agent.sh >> /srv/sighte/agent.log 2>&1'; } | sudo -u sighte crontab -
```

## Checking it works

| Check | Command |
|---|---|
| Receiver up | `curl -s -o /dev/null -w '%{http_code}' http://217.160.51.229:8420/health` → 200 |
| Auth rejects | `curl -s -o /dev/null -w '%{http_code}' -X POST http://217.160.51.229:8420/ingest` → 401 |
| Something arrived | `ls -l /srv/sighte/inbox /srv/sighte/profiles` |
| Runs per profile | `wc -l /srv/sighte/profiles/*.jsonl` |
| Receiver log | `journalctl -u sighte-ingest -f` |
| Agent log | `tail -f /srv/sighte/agent.log`, reports in `/srv/sighte/reports` |
