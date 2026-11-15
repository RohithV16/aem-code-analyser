#!/usr/bin/env bash
# Run the deliberate rule-probe fixture set and assert that every rule fires
# on a probe file. Exits non-zero if the analyser crashes, a probe is missing,
# or a rule only fires on non-probe code.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAR:-$ROOT/aem-analyser-cli/target/aem-analyser-cli.jar}"
PROBES="$ROOT/validation/probes"
TMP_BASE="${TMPDIR:-/tmp}"
TMP_BASE="${TMP_BASE%/}"
OUT="$(mktemp -d "$TMP_BASE/aem-rule-probes.XXXXXX")"

if [[ ! -f "$JAR" ]]; then
  echo "Missing analyser jar: $JAR" >&2
  echo "Build it first: mvn -q -DskipTests package" >&2
  exit 2
fi

echo "Running rule probes from $PROBES"
# Exit 0 = no findings, 1 = findings, 2 = hard failure. Findings are expected
# here because every probe is deliberately broken, so only 2 is fatal.
status=0
java -jar "$JAR" \
  --project-root "$PROBES" \
  --format SARIF \
  --output "$OUT" \
  --no-color --no-sonar --no-html > "$OUT/console.log" 2>&1 || status=$?
if [[ "$status" -gt 1 ]]; then
  echo "Analyser failed with exit code $status:" >&2
  cat "$OUT/console.log" >&2
  exit "$status"
fi

python3 "$ROOT/scripts/check_rule_probes.py" \
  "$OUT/aem-analyser.sarif" \
  "$PROBES/expected-rules.txt"

echo "Probe output: $OUT"
