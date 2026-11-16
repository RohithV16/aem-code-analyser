#!/usr/bin/env bash
# Fails when docs/rules/ is not byte-identical to what --generate-rule-docs produces.
#
# The pages are generated from RuleRegistry, so a change to a rule's severity, description or
# remediation must not leave the published docs describing the old behaviour.
#
# Usage: scripts/check-rule-docs.sh [jar]
set -euo pipefail

JAR="${1:-aem-analyser-cli/target/aem-analyser-cli.jar}"
[ -f "$JAR" ] || { echo "jar not found: $JAR (run 'mvn -DskipTests package' first)" >&2; exit 2; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

java -jar "$JAR" --generate-rule-docs "$OUT" >/dev/null

if diff -ru docs/rules "$OUT"; then
  echo "rule docs are up to date ($(ls "$OUT" | wc -l | tr -d ' ') pages)"
else
  echo "rule docs are stale: run --generate-rule-docs docs/rules and commit the result" >&2
  exit 1
fi
