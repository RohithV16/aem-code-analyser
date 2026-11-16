#!/usr/bin/env bash
# Golden-run validation: a realistic three-module AEM reactor with the findings it must produce.
#
# This is the fixture that unit tests cannot express: injection through a wildcard import, a
# service reference declared on a shared base class, an OSGi PID composed from a project constant
# in another file, a factory PID with an amendment, and a clientlib used from a sibling module.
#
# Usage: scripts/run-reactor-validation.sh [jar] [--update]
set -euo pipefail

JAR="${1:-aem-analyser-cli/target/aem-analyser-cli.jar}"
ROOT="validation/reactor"
EXPECTED="$ROOT/expected-findings.txt"
UPDATE="${2:-}"

[ -f "$JAR" ] || { echo "jar not found: $JAR (run 'mvn -DskipTests package' first)" >&2; exit 2; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

java -jar "$JAR" -p "$ROOT" --no-html --no-sonar --no-color -f SARIF -o "$OUT" >/dev/null 2>&1 || true

python3 - "$OUT" "$EXPECTED" "$UPDATE" <<'PY'
import json, sys, pathlib

out_dir, expected_path, update = sys.argv[1], sys.argv[2], sys.argv[3] == "--update"
sarif = json.load(open(pathlib.Path(out_dir) / "aem-analyser.sarif"))
actual = sorted({
    r["ruleId"] + "\t" + r["locations"][0]["physicalLocation"]["artifactLocation"]["uri"]
    for r in sarif["runs"][0]["results"]
})

expected_file = pathlib.Path(expected_path)
expected = sorted(
    line.rstrip("\n") for line in expected_file.read_text().splitlines()
    if line.strip() and not line.startswith("#")
) if expected_file.exists() else []

if update:
    header = ("# Golden findings for this reactor: <RULE-ID> and the path relative to\n"
              "# validation/reactor. Regenerate deliberately: scripts/run-reactor-validation.sh <jar> --update\n")
    expected_file.write_text(header + "\n".join(actual) + "\n")
    print(f"updated {expected_path}: {len(actual)} findings")
    sys.exit(0)

missing = [row for row in expected if row not in actual]
unexpected = [row for row in actual if row not in expected]

for row in missing:
    print(f"  MISSING     {row.replace(chr(9), ' ')}")
for row in unexpected:
    print(f"  UNEXPECTED  {row.replace(chr(9), ' ')}")

if missing or unexpected:
    print(f"reactor validation FAILED: {len(missing)} missing, {len(unexpected)} unexpected "
          f"({len(actual)} findings, {len(expected)} expected)")
    sys.exit(1)

print(f"reactor validation PASSED: {len(actual)} findings match {expected_path}")
PY
