#!/usr/bin/env bash
# Compiles and runs the golden checks without an Android SDK.
# Everything under dsp/ is pure Kotlin by design, precisely so this is possible.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"

OUT="${TMPDIR:-/tmp}/taar-verify.jar"
kotlinc \
  app/src/main/java/com/taar/dsp/*.kt \
  app/src/main/java/com/taar/domain/*.kt \
  app/src/test/java/com/taar/dsp/GoldenChecks.kt \
  app/src/test/java/com/taar/domain/DomainChecks.kt \
  tools/Verify.kt \
  -include-runtime -d "$OUT" 2>&1 | grep -v '^warning:' || true

java -jar "$OUT" golden
