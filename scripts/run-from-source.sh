#!/usr/bin/env bash
# Run the analyser against a project straight from a local clone - no release download, no Maven
# plugin, no registry configuration.
#
#   scripts/run-from-source.sh            # analyse the current directory
#   scripts/run-from-source.sh ../my-aem  # analyse another project
#   scripts/run-from-source.sh . -f SARIF # extra CLI flags are passed through
#
# The jar is rebuilt only when it is missing or older than the sources, so repeated runs are cheap.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$PWD"
if [ "$#" -gt 0 ] && [ "${1#-}" = "$1" ]; then
  # First argument is a path; anything starting with '-' is a CLI flag for the analyser.
  TARGET="$1"
  shift
fi

JAR="$ROOT/aem-analyser-cli/target/aem-analyser-cli.jar"
STALE=""
if [ -f "$JAR" ]; then
  STALE="$(find "$ROOT/aem-analyser-core/src" "$ROOT/aem-analyser-cli/src" \
              -name '*.java' -newer "$JAR" -print -quit 2>/dev/null || true)"
fi

if [ ! -f "$JAR" ] || [ -n "$STALE" ]; then
  echo "Building the CLI from $ROOT ..." >&2
  ( cd "$ROOT" && mvn -B -ntp -q -DskipTests package )
fi

exec java -jar "$JAR" "$TARGET" "$@"
