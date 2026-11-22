#!/usr/bin/env bash
# Fails when docs/issues/ is not byte-identical to what --generate-rule-docs produces.
#
# The page is generated from RuleRegistry and RuleFixCatalog, so a change to a rule's severity,
# description, remediation or fix example must not leave the published reference describing the
# old behaviour — the dashboard links every finding to an anchor on that page.
#
# Usage: scripts/check-rule-docs.sh [jar]
set -euo pipefail

JAR="${1:-aem-analyser-cli/target/aem-analyser-cli.jar}"
[ -f "$JAR" ] || { echo "jar not found: $JAR (run 'mvn -DskipTests package' first)" >&2; exit 2; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

java -jar "$JAR" --generate-rule-docs "$OUT" >/dev/null

if diff -ru docs/issues "$OUT/issues"; then
  echo "issue reference is up to date ($(grep -c 'class="rule-card"' docs/issues/index.html) rules,"
  echo "  $(find docs/issues -mindepth 2 -name index.html | wc -l | tr -d ' ') per-rule pages)"
else
  echo "issue reference is stale: run --generate-rule-docs docs and commit the result" >&2
  exit 1
fi

# The generated pages only earn their URLs if the site publishes and describes them; this asserts the
# page set, the titles, the JSON-LD and the sitemap URL set derived from the same source tree.
if command -v python3 >/dev/null 2>&1; then
  python3 "$(dirname "${BASH_SOURCE[0]}")/check-site-seo.py" docs
  python3 "$(dirname "${BASH_SOURCE[0]}")/generate-rule-evidence.py" --check
fi
