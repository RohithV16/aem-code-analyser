#!/usr/bin/env bash
# Proves the Gradle plugin the way a Gradle user experiences it: the task runs once, is UP-TO-DATE
# on an unchanged tree, replays FROM-CACHE after a clean, re-runs on a content change, and fails the
# build at the configured severity.
#
# Needs a Gradle 8.10+ installation: set GRADLE_HOME, have `gradle` on PATH, or let this script
# download the distribution into .gradle-dist/ (skipped with a clear message when offline).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$ROOT/validation/gradle-reactor"
PLUGIN="$ROOT/aem-analyser-gradle-plugin"
GRADLE_VERSION="${GRADLE_VERSION:-8.10}"

fail() { printf 'GRADLE PROOF FAILED: %s\n' "$1" >&2; exit 1; }

resolve_gradle() {
  if [[ -n "${GRADLE_HOME:-}" && -x "$GRADLE_HOME/bin/gradle" ]]; then
    echo "$GRADLE_HOME/bin/gradle"; return
  fi
  if command -v gradle >/dev/null 2>&1; then command -v gradle; return; fi
  local dist="$ROOT/.gradle-dist/gradle-$GRADLE_VERSION"
  if [[ ! -x "$dist/bin/gradle" ]]; then
    mkdir -p "$ROOT/.gradle-dist"
    local zip="$ROOT/.gradle-dist/gradle-$GRADLE_VERSION-bin.zip"
    if [[ ! -s "$zip" ]]; then
      curl -sSL --max-time 300 -o "$zip" \
        "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
        || { echo "SKIP: gradle is not installed and could not be downloaded" >&2; exit 0; }
    fi
    unzip -q -o "$zip" -d "$ROOT/.gradle-dist"
  fi
  [[ -x "$dist/bin/gradle" ]] || { echo "SKIP: gradle distribution missing" >&2; exit 0; }
  echo "$dist/bin/gradle"
}

GRADLE="$(resolve_gradle)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo '')}"

# The plugin compiles against the core API from the local Maven repository, as a consumer would.
# -am installs the aggregator POM too: core's POM declares it as its parent, and Gradle cannot parse
# a POM whose parent is missing, which only shows up on a machine without a warm ~/.m2.
( cd "$ROOT" && mvn -B -ntp -q -DskipTests install -pl aem-analyser-core -am )

( cd "$PLUGIN" && "$GRADLE" -q build ) || fail "the plugin does not build"

run() { ( cd "$FIXTURE" && "$GRADLE" aemAnalyserCheck "$@" 2>&1 ); }

cd "$FIXTURE" && rm -rf build .gradle

out="$(run -PaemFailOn=NONE)"
grep -q "AEM Code Analyser:" <<<"$out" || fail "no analysis summary: $out"
grep -q "3 findings (1 error, 2 warning, 0 info)" <<<"$out" \
  || fail "the fixture must yield 1 error and 2 warnings: $out"
printf 'RUN       %s\n' "$(grep -m1 'AEM Code Analyser:' <<<"$out")"

out="$(run -PaemFailOn=NONE)"
grep -q "Task :aemAnalyserCheck UP-TO-DATE" <<<"$out" || fail "an unchanged tree was re-analysed: $out"
printf 'UP-TO-DATE ok\n'

printf '\n// proof: a content change must invalidate the task\n' \
  >> "$FIXTURE/src/main/java/com/example/fixture/BetaServlet.java"
out="$(run -PaemFailOn=NONE)"
grep -q "Task :aemAnalyserCheck UP-TO-DATE" <<<"$out" && fail "a content change did not invalidate the task"
printf 'INVALIDATED ok\n'

# The store run must start from a clean output directory: a build/ left behind by the uncached run
# above makes Gradle treat the outputs as untracked and it stores nothing.
rm -rf "$FIXTURE/build"
out="$(run -PaemFailOn=NONE --build-cache)"
grep -q "FROM-CACHE" <<<"$out" || printf 'STORE      %s\n' "$(grep -m1 'AEM Code Analyser:' <<<"$out")"
rm -rf "$FIXTURE/build"
out="$(run -PaemFailOn=NONE --build-cache)"
grep -q "Task :aemAnalyserCheck FROM-CACHE" <<<"$out" || fail "the build cache did not replay: $out"
printf 'FROM-CACHE ok\n'

set +e
out="$(run -PaemFailOn=ERROR)"
status=$?
set -e
[[ $status -ne 0 ]] || fail "failOn=ERROR did not fail the build on 1 error finding"
grep -q "1 finding(s) at or above ERROR" <<<"$out" || fail "the gate message is missing: $out"
printf 'GATE       ok (exit %s)\n' "$status"

[[ -s "$FIXTURE/build/aem-analyser/aem-analyser.sarif" ]] || fail "no SARIF report"
[[ -s "$FIXTURE/build/aem-analyser/aem-analyser.txt" ]] || fail "no text report"
printf 'REPORTS    ok (sarif + text)\n'

printf 'Gradle plugin proof passed.\n'
