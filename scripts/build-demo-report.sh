#!/usr/bin/env bash
# Regenerates the public sample report committed at docs/demo/index.html.
#
# The report is the real thing: the CLI is run against validation/reactor, a fixture that
# deliberately trips several rules, and the HTML it writes is copied into the site. Keeping it
# reproducible means the demo cannot drift from what the tool actually produces.
#
# Usage: scripts/build-demo-report.sh [jar]
set -euo pipefail

JAR="${1:-aem-analyser-cli/target/aem-analyser-cli.jar}"
[ -f "$JAR" ] || { echo "jar not found: $JAR (run 'mvn -DskipTests package' first)" >&2; exit 2; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# The fixture has findings, so the gate exits 1 by design; the report is still written.
java -jar "$JAR" validation/reactor --format HTML -o "$OUT" >/dev/null || true

mkdir -p docs/demo
cp "$OUT/aem-analyser-report.html" docs/demo/index.html
echo "Wrote docs/demo/index.html"
