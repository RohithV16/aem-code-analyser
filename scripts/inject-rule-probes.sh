#!/usr/bin/env bash
# Inject the rule-probe module into a temporary copy of a target AEM repo, run
# the analyser, and verify every rule fires on a probe file.
#
# The original repository is never modified: the target is rsynced into a temp
# directory, the probes are copied in beside the real modules, and the same
# expected-rules list is asserted against the combined result.
#
# Usage: inject-rule-probes.sh <target-aem-repo>
#   KEEP=1  retain the temp copy for inspection
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAR:-$ROOT/aem-analyser-cli/target/aem-analyser-cli.jar}"
PROBES="$ROOT/validation/probes"
TARGET="${1:-}"
KEEP="${KEEP:-0}"
TMP_BASE="${TMPDIR:-/tmp}"
TMP_BASE="${TMP_BASE%/}"

if [[ -z "$TARGET" || ! -d "$TARGET" ]]; then
  echo "usage: $0 <target-aem-repo>   (KEEP=1 to retain the temp copy)" >&2
  exit 2
fi
if [[ ! -f "$JAR" ]]; then
  echo "Missing analyser jar: $JAR" >&2
  echo "Build it first: mvn -q -DskipTests package" >&2
  exit 2
fi

WORK="$(mktemp -d "$TMP_BASE/aem-probe-target.XXXXXX")"
COPY="$WORK/repo"
OUT="$WORK/out"
mkdir -p "$OUT"

cleanup() {
  if [[ "$KEEP" == "1" ]]; then
    echo "KEEP=1: retained at $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

echo "Copying target repo to $COPY (originals untouched) ..."
rsync -a --exclude '.git' --exclude 'target' --exclude 'node_modules' "$TARGET/" "$COPY/"
cp -R "$PROBES" "$COPY/analyser-probes"

python3 - "$COPY/pom.xml" <<'PY'
import re
import sys
import pathlib

pom = pathlib.Path(sys.argv[1])
text = pom.read_text()
if "<module>analyser-probes</module>" in text:
    raise SystemExit(0)
if "<modules>" in text:
    text = text.replace("<modules>", "<modules>\n    <module>analyser-probes</module>", 1)
else:
    text = re.sub(r"</project>", "  <modules>\n    <module>analyser-probes</module>\n  </modules>\n</project>", text, count=1)
pom.write_text(text)
print("Added analyser-probes module to", pom)
PY

echo "Running analyser against the injected copy ..."
# Exit 0 = no findings, 1 = findings, 2 = hard failure. The real repo may carry
# its own findings and the probes always do, so only 2 is fatal.
status=0
java -jar "$JAR" \
  --project-root "$COPY" \
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
  "$PROBES/expected-rules.txt" \
  --allow-parse-errors
