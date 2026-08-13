#!/usr/bin/env bash
# The baseline check every session starts with. CLAUDE.md is the loop this feeds: a FAILING baseline
# is the active task, ahead of any feature.
#
# There is no install step. Gradle resolves everything on the first build, which is also why that
# build is slow rather than broken — see the note below before deciding a hung command is a failure.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

REQUIRED_JAVA_MAJOR=25
SMOKE_CMD=(./gradlew test)
VERIFY_CMD=(./gradlew build)
START_CMD=(./gradlew runClient)

echo "==> Working directory: $PWD"

echo "==> Environment check"
if ! command -v java >/dev/null 2>&1; then
	echo "    ERROR: java not found on PATH."
	exit 1
fi
# Minecraft 26.1.2 needs bytecode 25, and build.gradle sets that with --release rather than a pinned
# toolchain, so any JDK from 25 up builds. Gradle prefers JAVA_HOME over PATH, so a warning here can
# be about a different JDK than the one that will run the build.
JAVA_LINE="$(java -version 2>&1 | head -1)"
JAVA_MAJOR="$(printf '%s' "$JAVA_LINE" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
if [ -z "$JAVA_MAJOR" ]; then
	echo "    WARNING: could not read a version out of: $JAVA_LINE"
elif [ "$JAVA_MAJOR" -lt "$REQUIRED_JAVA_MAJOR" ]; then
	echo "    WARNING: java $JAVA_MAJOR found, $REQUIRED_JAVA_MAJOR+ expected ($JAVA_LINE)."
	echo "             If Gradle 9.5.1 refuses your JDK as a daemon JVM, use 25."
else
	echo "    ${JAVA_LINE} OK"
fi
[ -n "${JAVA_HOME:-}" ] && echo "    JAVA_HOME=$JAVA_HOME (Gradle uses this, not PATH)"

# Not seconds. The first run downloads Loom, the Minecraft jar and the Mojang mappings, which is
# minutes on a cold Gradle cache and near-instant afterwards. A run that looks stuck on the first
# invocation is almost always still downloading.
echo "==> Running baseline smoke check (first run is slow: cold Gradle cache)"
if "${SMOKE_CMD[@]}"; then
	BASELINE_STATUS="PASSING"
else
	BASELINE_STATUS="FAILING"
fi

echo "==> Startup command"
printf '   '
printf ' %q' "${START_CMD[@]}"
printf '\n'
echo "    Loom's dev client has no valid session, so it cannot reach Hypixel. Anything that needs a"
echo "    real dungeon run stays unverified here — that is what the telemetry log is for."
echo "==> Full verification command"
printf '   '
printf ' %q' "${VERIFY_CMD[@]}"
printf '\n'
echo "    build also refreshes dist/sighteaddons-<version>.jar. If that file changes, the committed"
echo "    jar was stale — see the release gate in CLAUDE.md."

echo "==> BASELINE: $BASELINE_STATUS"
if [ "$BASELINE_STATUS" = "FAILING" ]; then
	echo "    Per CLAUDE.md: repairing the baseline is the active task now."
fi
[ "$BASELINE_STATUS" = "PASSING" ]
