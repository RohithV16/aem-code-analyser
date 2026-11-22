#!/usr/bin/env bash
# Fails when rule-ids.lock no longer matches the RuleId enum.
#
# The lock makes a rule-id removal or status change a reviewed diff. This wrapper runs the same test
# the build runs, so it can be used before committing without remembering the Surefire selector.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mvn -B -ntp -o -q -pl aem-analyser-core -am \
  -Dtest=RuleIdCompatibilityTest -DfailIfNoTests=false test
