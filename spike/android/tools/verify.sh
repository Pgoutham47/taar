#!/usr/bin/env bash
# Compiles and runs the golden and domain checks without an Android SDK.
# Everything under dsp/ and domain/ is pure Kotlin by design, precisely so this
# is possible.
#
# Two mistakes this script has already made, both worth not repeating:
#   - piping kotlinc through `|| true`, so a broken build silently ran the
#     previous jar and reported every check passing;
#   - piping it through grep under `set -o pipefail`, so grep finding nothing to
#     filter reported a failure when the compile had succeeded.
# The compiler's own exit status is the only thing that decides.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"

OUT="${TMPDIR:-/tmp}/taar-verify.jar"
LOG="${TMPDIR:-/tmp}/taar-verify.log"
rm -f "$OUT"

set +e
kotlinc \
  app/src/main/java/com/taar/dsp/*.kt \
  app/src/main/java/com/taar/domain/*.kt \
  app/src/test/java/com/taar/dsp/GoldenChecks.kt \
  app/src/test/java/com/taar/domain/DomainChecks.kt \
  tools/Verify.kt \
  -include-runtime -d "$OUT" >"$LOG" 2>&1
status=$?
set -e

grep -v '^warning:' "$LOG" || true

if [ "$status" -ne 0 ] || [ ! -f "$OUT" ]; then
  echo "COMPILATION FAILED (exit $status) — not running checks" >&2
  exit 1
fi

java -jar "$OUT" golden

# The Android layer cannot be compiled here, so this script alone is not enough.
echo
echo "NOTE: this covers dsp/ and domain/ only. sensor/, ui/ and data/ are compiled"
echo "      by Gradle -- run ./gradlew assembleDebug before trusting a change there."

