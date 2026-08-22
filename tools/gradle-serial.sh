#!/usr/bin/env bash
# Serialises Gradle invocations for this checkout.
#
# Gradle keeps per-project incremental-compilation state under app/build. Two builds running
# against the same checkout at once can corrupt it ("Storage corrupted ... source-to-classes.tab"),
# which shows up later as a cascade of bogus "unresolved reference" errors. During development
# several tools may want to build at the same time, so every build goes through this lock.
#
# Usage:  tools/gradle-serial.sh :app:compileDebugKotlin [more gradle args...]
set -uo pipefail

LOCK_DIR="${ROADGUARD_BUILD_LOCK:-/tmp/roadguard-build.lock}"
WAIT_SECONDS="${ROADGUARD_BUILD_LOCK_WAIT:-1800}"
: "${ANDROID_HOME:=/home/user/android-sdk}"
export ANDROID_HOME

waited=0
until mkdir "$LOCK_DIR" 2>/dev/null; do
  if [ "$waited" -ge "$WAIT_SECONDS" ]; then
    echo "gradle-serial: timed out after ${WAIT_SECONDS}s waiting for $LOCK_DIR" >&2
    exit 75
  fi
  # A lock older than the wait budget is stale (a killed build); take it over.
  if [ -d "$LOCK_DIR" ] && [ -n "$(find "$LOCK_DIR" -maxdepth 0 -mmin +30 2>/dev/null)" ]; then
    echo "gradle-serial: removing a stale lock" >&2
    rm -rf "$LOCK_DIR"
    continue
  fi
  sleep 5
  waited=$((waited + 5))
done
trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM

cd "$(dirname "$0")/.."
./gradlew "$@"
status=$?

# One automatic recovery from a corrupted incremental cache, which is the failure mode this lock
# exists to prevent but which a previously-killed build can still leave behind.
if [ "$status" -ne 0 ]; then
  if ./gradlew "$@" 2>&1 | grep -q 'Storage corrupted\|Incremental compilation failed'; then
    echo "gradle-serial: clearing the Kotlin incremental cache and retrying once" >&2
    rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc
    ./gradlew "$@"
    status=$?
  fi
fi
exit "$status"
