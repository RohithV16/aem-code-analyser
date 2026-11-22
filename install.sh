#!/bin/sh
# AEM Code Analyser installer.
#
# Downloads a released CLI jar, verifies it against the release's own SHA256SUMS.txt
# before anything is installed, and writes an `aem-analyser` launcher next to it.
#
#   sh install.sh                       # newest release, under ~/.local
#   sh install.sh --version 1.0.10      # pin a release
#   sh install.sh --prefix /usr/local   # install somewhere else (needs write access)
#   sh install.sh --from ./release-assets   # install offline from a directory holding
#                                           # aem-analyser-cli*.jar + SHA256SUMS.txt
#
# Nothing is installed if the checksum does not match; the downloaded bytes are
# discarded and the script exits non-zero.
set -eu

REPO="RohithV16/aem-code-analyser"
RELEASES="https://github.com/${REPO}/releases"
API="https://api.github.com/repos/${REPO}/releases/latest"

VERSION=""
PREFIX="${PREFIX:-${HOME}/.local}"
FROM=""
DRY_RUN=0

usage() {
  cat <<'EOF'
AEM Code Analyser installer

Usage:
  sh install.sh [options]

Options:
  --version <v>    Release to install (e.g. 1.0.10 or v1.0.10). Default: latest.
  --prefix <dir>   Install prefix. Default: $PREFIX, else ~/.local.
                   The jar goes to <prefix>/share/aem-analyser/<version>/ and the
                   launcher to <prefix>/bin/aem-analyser.
  --from <path>    Install from local release assets instead of GitHub: a directory
                   containing the CLI jar and SHA256SUMS.txt, or the jar itself with
                   SHA256SUMS.txt beside it.
  --dry-run        Resolve and verify the download, then print what would be written.
  -h, --help       This text.

The launcher honours JAVA_HOME at run time and needs Java 21 or newer.

Examples:
  sh install.sh
  sh install.sh --version 1.0.10 --prefix "$HOME/.local"
  curl -fsSLO https://raw.githubusercontent.com/RohithV16/aem-code-analyser/main/install.sh
  sh install.sh
EOF
}

die() {
  printf 'install.sh: %s\n' "$*" >&2
  exit 1
}

note() {
  printf '%s\n' "$*"
}

# Read one option value, supporting both `--opt value` and `--opt=value`.
need_value() {
  [ "$#" -ge 2 ] && [ -n "$2" ] || die "$1 needs a value (see --help)"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      need_value "$@"; VERSION="$2"; shift 2 ;;
    --version=*)
      VERSION="${1#*=}"; shift ;;
    --prefix)
      need_value "$@"; PREFIX="$2"; shift 2 ;;
    --prefix=*)
      PREFIX="${1#*=}"; shift ;;
    --from)
      need_value "$@"; FROM="$2"; shift 2 ;;
    --from=*)
      FROM="${1#*=}"; shift ;;
    --dry-run)
      DRY_RUN=1; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      die "unknown argument: $1 (see --help)" ;;
  esac
done

[ -n "$PREFIX" ] || die "--prefix cannot be empty"

fetch() {
  url="$1"
  out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$out" || die "download failed: $url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$out" "$url" || die "download failed: $url"
  else
    die "neither curl nor wget is available"
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  else
    die "no sha256 tool found (need sha256sum, shasum or openssl)"
  fi
}

java_bin() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    printf '%s' "${JAVA_HOME}/bin/java"
  else
    command -v java 2>/dev/null || true
  fi
}

JAVA="$(java_bin)"
[ -n "$JAVA" ] || die "java not found; install a Java 21 runtime or set JAVA_HOME"
JAVA_LINE="$("$JAVA" -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(printf '%s' "$JAVA_LINE" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
case "${JAVA_MAJOR:-0}" in
  ''|*[!0-9]*) die "could not determine the Java version from: $JAVA_LINE" ;;
esac
[ "$JAVA_MAJOR" -ge 21 ] || die "Java 21 or newer is required; found: $JAVA_LINE"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/aem-analyser-install.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

if [ -n "$FROM" ]; then
  [ -e "$FROM" ] || die "--from path does not exist: $FROM"
  if [ -d "$FROM" ]; then
    ASSET_DIR="$FROM"
  else
    ASSET_DIR="$(dirname "$FROM")"
  fi
  [ -f "${ASSET_DIR}/SHA256SUMS.txt" ] || die "no SHA256SUMS.txt in ${ASSET_DIR}"
  SUMS="${ASSET_DIR}/SHA256SUMS.txt"
  if [ -z "$VERSION" ]; then
    # Derive the version from the jar name, else fall back to the sums' own jar line.
    JAR_NAME="$(basename "$FROM" 2>/dev/null || true)"
    case "$JAR_NAME" in
      aem-analyser-cli-[0-9]*.jar)
        VERSION="${JAR_NAME#aem-analyser-cli-}"; VERSION="${VERSION%.jar}" ;;
      *) VERSION="$(awk '$2 ~ /aem-analyser-cli/ { print $2; exit }' "$SUMS" \
                    | sed -n 's/^aem-analyser-cli-\(.*\)\.jar$/\1/p')" ;;
    esac
    [ -n "$VERSION" ] || die "could not infer the version from --from; pass --version"
  fi
else
  if [ -z "$VERSION" ]; then
    RESPONSE="${TMP}/latest.json"
    fetch "$API" "$RESPONSE"
    VERSION="$(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RESPONSE" | head -n 1)"
    [ -n "$VERSION" ] || die "could not read tag_name from $API"
  fi
  VERSION="${VERSION#v}"
  fetch "${RELEASES}/download/v${VERSION}/SHA256SUMS.txt" "${TMP}/SHA256SUMS.txt" \
    || die "release v${VERSION} has no SHA256SUMS.txt"
  SUMS="${TMP}/SHA256SUMS.txt"
fi

VERSION="${VERSION#v}"
printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$' \
  || die "not a release version: ${VERSION}"

# 1.0.11+ publishes a versioned CLI asset; earlier releases only carry the stable
# name, so prefer the versioned one and fall back for those.
ASSET=""
for candidate in "aem-analyser-cli-${VERSION}.jar" "aem-analyser-cli.jar"; do
  if awk -v name="$candidate" '$2 == name || $2 == "*" name { found = 1 } END { exit !found }' "$SUMS"; then
    ASSET="$candidate"
    break
  fi
done
[ -n "$ASSET" ] || die "SHA256SUMS.txt names no CLI jar for ${VERSION}"

if [ -n "$FROM" ]; then
  if [ -f "$FROM" ] && [ "$(basename "$FROM")" != "$ASSET" ]; then
    # The caller pointed at one jar, but the sums use a different name (usually the
    # versioned asset). Use the file they gave as long as the digest matches.
    JAR="$FROM"
  else
    [ -f "${ASSET_DIR}/${ASSET}" ] || die "${ASSET_DIR}/${ASSET} is missing"
    JAR="${ASSET_DIR}/${ASSET}"
  fi
else
  JAR="${TMP}/${ASSET}"
  note "Downloading ${REPO} ${VERSION} …"
  fetch "${RELEASES}/download/v${VERSION}/${ASSET}" "$JAR"
fi

EXPECTED="$(awk -v name="$ASSET" '$2 == name || $2 == "*" name { print $1; exit }' "$SUMS")"
[ -n "$EXPECTED" ] || die "SHA256SUMS.txt has no checksum line for ${ASSET}"
ACTUAL="$(sha256_of "$JAR")"
if [ "$EXPECTED" != "$ACTUAL" ]; then
  die "checksum mismatch for ${ASSET}
  expected ${EXPECTED}
  got      ${ACTUAL}
Nothing was installed."
fi
note "Verified SHA-256 ${ACTUAL} against SHA256SUMS.txt"

DEST="${PREFIX}/share/aem-analyser/${VERSION}"
LAUNCHER="${PREFIX}/bin/aem-analyser"

if [ "$DRY_RUN" -eq 1 ]; then
  note "Dry run — would install:"
  note "  jar      ${DEST}/aem-analyser.jar"
  note "  launcher ${LAUNCHER}"
  note "Re-run without --dry-run to install."
  exit 0
fi

mkdir -p "$DEST" "${PREFIX}/bin"
cp "$JAR" "${DEST}/.aem-analyser.jar.tmp"
chmod 0644 "${DEST}/.aem-analyser.jar.tmp"
mv -f "${DEST}/.aem-analyser.jar.tmp" "${DEST}/aem-analyser.jar"

cat > "${TMP}/aem-analyser" <<EOF
#!/bin/sh
# Generated by install.sh — AEM Code Analyser ${VERSION}
JAR="${DEST}/aem-analyser.jar"
if [ -n "\${JAVA_HOME:-}" ] && [ -x "\${JAVA_HOME}/bin/java" ]; then
  JAVA="\${JAVA_HOME}/bin/java"
else
  JAVA=java
fi
exec "\$JAVA" -jar "\$JAR" "\$@"
EOF
chmod 0755 "${TMP}/aem-analyser"
mv -f "${TMP}/aem-analyser" "$LAUNCHER"

note "Installed AEM Code Analyser ${VERSION}"
note "  jar      ${DEST}/aem-analyser.jar"
note "  launcher ${LAUNCHER}"

case ":${PATH}:" in
  *":${PREFIX}/bin:"*) note "Run: aem-analyser --version" ;;
  *)
    note ""
    note "${PREFIX}/bin is not on PATH. Add it:"
    note "  export PATH=\"${PREFIX}/bin:\$PATH\""
    note "Then: aem-analyser --version"
    ;;
esac
