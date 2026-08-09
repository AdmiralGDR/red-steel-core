#!/usr/bin/env bash
# Zero-warning gate: compile from clean and fail if the mod's own sources emit ANY
# javac warning. The one mandatory note about the framework-generated entrypoint is whitelisted, because
# it is not gated by any -Xlint category and concerns a framework file, not this mod's code.
set -euo pipefail
cd "$(dirname "$0")/.."

WHITELIST='created in the last round will not be subject to annotation processing'
LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT

./gradlew clean compileJava compileTestJava --rerun-tasks --console=plain 2>&1 | tee "$LOG" >/dev/null

# Any 'warning:' line that is NOT the whitelisted framework note is a violation.
if grep -n 'warning:' "$LOG" | grep -v "$WHITELIST" >/dev/null 2>&1; then
    echo "FAIL — mod sources produced warnings:" >&2
    grep -n 'warning:' "$LOG" | grep -v "$WHITELIST" >&2
    exit 1
fi
echo "check-warnings: clean (0 warnings from mod sources)"
