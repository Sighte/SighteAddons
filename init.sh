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
# Deliberately not `build`. `build` is `finalizedBy copyToDist`, and `copyToDist dependsOn cleanDist`,
# which deletes dist/sighteaddons-*.jar and writes the current tree's build in its place. That is
# exactly what the release gate in CLAUDE.md wants — it is how a stale committed jar is detected — and
# exactly wrong as the command every session is told to run: with an unreleased fix on a branch, it
# swaps the released artifact for a different build wearing the same version number, and nothing says
# so. `assemble check` compiles and tests the same code and leaves dist/ alone.
VERIFY_CMD=(./gradlew assemble check)
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
echo "    none. ${START_CMD[*]} is OFF THE TABLE — it opens a Minecraft window on the machine the"
echo "    user is playing on. Its dev client has no valid session and cannot reach Hypixel anyway, so"
echo "    anything needing a real dungeon run stays unverified here; that is what the telemetry log"
echo "    is for. See CLAUDE.md."
echo "==> Full verification command"
printf '   '
printf ' %q' "${VERIFY_CMD[@]}"
printf '\n'
echo "    assemble check, not build: build would delete and rewrite dist/sighteaddons-<version>.jar,"
echo "    replacing the released artifact with the current tree under the same version number."
echo "    ./gradlew build belongs to the release gate in CLAUDE.md, where refreshing that jar is the"
echo "    point — there a change means the committed jar was stale. Here it means you lost it."

echo "==> BASELINE: $BASELINE_STATUS"
if [ "$BASELINE_STATUS" = "FAILING" ]; then
	echo "    Per CLAUDE.md: repairing the baseline is the active task now."
fi
[ "$BASELINE_STATUS" = "PASSING" ]
