#!/usr/bin/env bash
# Regenerate the site's /releases/ pages from CHANGELOG.md.
#
# Usage:
#   scripts/build-releases-page.sh [docs-dir]            # rewrite docs/releases/
#   scripts/build-releases-page.sh [docs-dir] --check    # fail when committed pages are stale
#
# Runs before `jekyll build` in CI, and --check runs in the validation job so a
# changelog entry cannot ship without its page.
set -euo pipefail

exec python3 "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-releases-page.py" "$@"
