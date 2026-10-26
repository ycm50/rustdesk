#!/usr/bin/env bash
#
# fetch-ffmpeg.sh - fetch an open-source *prebuilt* FFmpeg (shared libraries)
# for the host ("controlled side") Rust build.
#
# FFmpeg is ONLY needed for the hwcodec feature (cargo ... --features hwcodec).
# The default host build does not link FFmpeg, so most builds never run this.
#
# Packages come from the BtbN/FFmpeg-Builds GitHub releases (GPL or LGPL, shared
# libraries) and are unpacked into:
#
#     host/.deps/ffmpeg/<platform>-<arch>/
#
# Supported: linux-x64, linux-arm64. Windows is handled by fetch-ffmpeg.ps1.
# There is no macOS prebuilt in that project; this script says so rather than
# pretending otherwise (use Homebrew/distro FFmpeg or build FFmpeg yourself).
#
# Environment (all optional):
#   FFMPEG_BUILD_TAG         release tag, default "latest"
#                            (pin e.g. autobuild-2026-10-26-14-25 for reproducible CI)
#   FFMPEG_SERIES            FFmpeg branch: "8.1", "n8.1", "master", ...
#                            default: newest stable branch present in the release
#   FFMPEG_ASSET             exact release asset file name (skips asset discovery)
#   FFMPEG_LICENSE           gpl (default) or lgpl
#   FFMPEG_DEST              output directory; default host/.deps/ffmpeg/<platform>-<arch>
#   FFMPEG_DEPS_ROOT         root for cache and outputs; default host/.deps
#   FFMPEG_CHECKSUM          expected sha256, overrides the release checksum file
#   FFMPEG_REQUIRE_CHECKSUM  1 = abort when no checksum is available (default: warn)
#   GITHUB_TOKEN             optional, raises the GitHub API rate limit
#
# Exit codes: 0 ok, 2 network/offline, 3 checksum, 64 usage, 65 unsupported platform.
#
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
host_dir=$(cd -- "$script_dir/.." && pwd)

info() { printf '[fetch-ffmpeg] %s\n' "$*"; }
warn() { printf '[fetch-ffmpeg] WARNING: %s\n' "$*" >&2; }
die() { __code=$1; shift; printf '[fetch-ffmpeg] ERROR: %s\n' "$*" >&2; exit "$__code"; }

usage() {
  cat <<'USAGE'
fetch-ffmpeg.sh - download a prebuilt FFmpeg for the host build

  Only needed for the hwcodec feature:
      cargo build --features hwcodec
  The default host build does not use FFmpeg at all.

Usage:
  host/scripts/fetch-ffmpeg.sh [options]

Options:
  -f, --force              re-download and re-extract even if the tree exists
      --tag <tag>          GitHub release tag (default: latest)
                           pin with e.g. autobuild-2026-10-26-14-25
      --series <branch>    FFmpeg branch: 8.1, n8.1, master ...
                           (default: newest stable branch in the release)
      --asset <file>       exact release asset name, skips discovery
      --lgpl               use the LGPL-shared package instead of GPL-shared
      --platform <p>       linux (host default on Linux)
      --arch <a>           x64 / arm64 (host default)
      --dest <dir>         output dir (default host/.deps/ffmpeg/<platform>-<arch>)
      --deps-root <dir>    cache+output root (default host/.deps)
      --dry-run            resolve and print the asset, download nothing
  -h, --help               this text

Output layout:
  host/.deps/ffmpeg/<platform>-<arch>/{bin,include,lib,...}
  host/.deps/ffmpeg/_cache/                    downloaded archives + checksums

Environment: FFMPEG_BUILD_TAG, FFMPEG_SERIES, FFMPEG_ASSET, FFMPEG_LICENSE,
FFMPEG_DEST, FFMPEG_DEPS_ROOT, FFMPEG_CHECKSUM, FFMPEG_REQUIRE_CHECKSUM,
GITHUB_TOKEN. See the comment block at the top of this file.

Exit codes: 0 ok, 2 network/offline, 3 checksum, 64 usage, 65 unsupported.
USAGE
}

have() { command -v "$1" >/dev/null 2>&1; }

http_get() { # url outfile
  if have curl; then
    curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$2" "$1"
  elif have wget; then
    wget -q --tries=3 --timeout=60 -O "$2" "$1"
  else
    die 2 "neither curl nor wget is installed; cannot download $1"
  fi
}

http_head_ok() { # url
  if have curl; then
    curl -fsIL --retry 2 --connect-timeout 20 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null | grep -q '^200$'
  elif have wget; then
    wget -q --spider --tries=2 --timeout=30 "$1" 2>/dev/null
  else
    return 1
  fi
}

sha256_of() {
  if have sha256sum; then
    sha256sum "$1" | cut -d' ' -f1
  elif have shasum; then
    shasum -a 256 "$1" | cut -d' ' -f1
  elif have openssl; then
    openssl dgst -sha256 "$1" | sed 's/.*= //'
  else
    printf ''
  fi
}

series_of_name() {
  printf '%s\n' "$1" | sed -nE 's/.*-shared-([0-9]+\.[0-9]+)\.(tar\.xz|zip)$/\1/p'
}

# List asset names of the release. Runs in the current shell so that
# RELEASE_PUBLISHED survives (callers redirect stdout to a file).
api_names() {
  _tmp=$(mktemp "${TMPDIR:-/tmp}/fetch-ffmpeg-api.XXXXXX") || return 1
  _auth="X-Skip-Auth: 1"
  if [ -n "$GITHUB_TOKEN" ]; then
    _auth="Authorization: Bearer $GITHUB_TOKEN"
  fi
  if have curl; then
    if ! curl -fsSL --retry 2 --retry-delay 2 --connect-timeout 20 \
        -H "$_auth" \
        -H 'Accept: application/vnd.github+json' \
        -H 'User-Agent: rustdesk-fetch-ffmpeg' \
        -o "$_tmp" "$api_url"; then
      rm -f "$_tmp"
      return 1
    fi
  elif have wget; then
    if ! wget -q --tries=2 --timeout=60 -O "$_tmp" \
        --header="$_auth" \
        --header='Accept: application/vnd.github+json' \
        --header='User-Agent: rustdesk-fetch-ffmpeg' "$api_url"; then
      rm -f "$_tmp"
      return 1
    fi
  else
    return 1
  fi
  RELEASE_PUBLISHED=$(sed -n 's/.*"published_at"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$_tmp" | head -n 1)
  [ -n "$RELEASE_PUBLISHED" ] || RELEASE_PUBLISHED=unknown
  grep -oE '"name"[[:space:]]*:[[:space:]]*"[^"]+"' "$_tmp" \
    | sed -E 's/.*"([^"]+)"$/\1/' \
    | sort -u
  rm -f "$_tmp"
}

# Fallback asset names when the GitHub API is unreachable. Only the moving
# "latest" tag uses the "<series>-latest-..." layout; autobuild tags embed a
# git description that cannot be guessed, so those need --asset.
probe_candidates() {
  [ "$TAG" = latest ] || return 1
  if [ -n "$SERIES_NORM" ]; then
    set -- "$SERIES_NORM"
  else
    set -- 10.0 9.0 8.1 7.1 6.1 master
  fi
  for _s in "$@"; do
    if [ "$_s" = master ]; then
      _n="ffmpeg-master-$TAG-$PLATFORM_TOKEN-$LICENSE_KIND-shared.$ASSET_EXT"
    else
      _n="ffmpeg-n$_s-$TAG-$PLATFORM_TOKEN-$LICENSE_KIND-shared-$_s.$ASSET_EXT"
    fi
    if http_head_ok "$release_url/$_n"; then
      printf '%s\n' "$_n"
      return 0
    fi
  done
  return 1
}

relocate_pc() {
  for _pc in "$1"/lib/pkgconfig/*.pc "$1"/lib64/pkgconfig/*.pc "$1"/share/pkgconfig/*.pc; do
    [ -f "$_pc" ] || continue
    _old=$(sed -n 's/^prefix=//p' "$_pc" | head -n 1 | tr -d '\r')
    [ -n "$_old" ] || continue
    case $_old in
      *'${'*) continue ;;
    esac
    if [ -d "$_old" ]; then continue; fi
    sed "s|^prefix=.*|prefix=$1|" "$_pc" > "$_pc.reloc" && mv "$_pc.reloc" "$_pc"
    info "rewrote pkg-config prefix: $(basename "$_pc"): $_old -> $1"
  done
}

tree_ok() { [ -f "$DEST/include/libavcodec/avcodec.h" ] && [ -d "$DEST/lib" ]; }

# ---------------------------------------------------------------------------
# defaults and argument parsing
# ---------------------------------------------------------------------------
REPO=${FFMPEG_GITHUB_REPO:-BtbN/FFmpeg-Builds}
TAG=${FFMPEG_BUILD_TAG:-latest}
TAG_EXPLICIT=
if [ -n "${FFMPEG_BUILD_TAG:-}" ]; then TAG_EXPLICIT=1; fi
SERIES=${FFMPEG_SERIES:-}
SERIES_EXPLICIT=
if [ -n "${FFMPEG_SERIES:-}" ]; then SERIES_EXPLICIT=1; fi
ASSET_OVERRIDE=${FFMPEG_ASSET:-}
LICENSE_KIND=${FFMPEG_LICENSE:-gpl}
DEPS_ROOT=${FFMPEG_DEPS_ROOT:-$host_dir/.deps}
DEST_ARG=${FFMPEG_DEST:-}
REQUIRE_CHECKSUM=${FFMPEG_REQUIRE_CHECKSUM:-0}
EXPECTED_CHECKSUM=${FFMPEG_CHECKSUM:-}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
FORCE=0
PLATFORM_ARG=
ARCH_ARG=
DRY_RUN=0
SKIP_FETCH=0
RELEASE_PUBLISHED=unknown
ACTUAL=unknown
ASSET=

while [ $# -gt 0 ]; do
  case $1 in
    -f|--force) FORCE=1 ;;
    --tag)
      [ $# -ge 2 ] || die 64 "--tag needs a value"
      TAG=$2; TAG_EXPLICIT=1; shift ;;
    --series)
      [ $# -ge 2 ] || die 64 "--series needs a value"
      SERIES=$2; SERIES_EXPLICIT=1; shift ;;
    --asset)
      [ $# -ge 2 ] || die 64 "--asset needs a value"
      ASSET_OVERRIDE=$2; shift ;;
    --lgpl) LICENSE_KIND=lgpl ;;
    --gpl) LICENSE_KIND=gpl ;;
    --platform)
      [ $# -ge 2 ] || die 64 "--platform needs a value"
      PLATFORM_ARG=$2; shift ;;
    --arch)
      [ $# -ge 2 ] || die 64 "--arch needs a value"
      ARCH_ARG=$2; shift ;;
    --dest)
      [ $# -ge 2 ] || die 64 "--dest needs a value"
      DEST_ARG=$2; shift ;;
    --deps-root)
      [ $# -ge 2 ] || die 64 "--deps-root needs a value"
      DEPS_ROOT=$2; shift ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die 64 "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

case $LICENSE_KIND in
  gpl|lgpl) ;;
  *) die 64 "FFMPEG_LICENSE must be gpl or lgpl, got: $LICENSE_KIND" ;;
esac

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

detect_platform() {
  case $(uname -s 2>/dev/null || echo unknown) in
    Linux*) printf 'linux\n' ;;
    Darwin*) printf 'macos\n' ;;
    MINGW*|MSYS*|CYGWIN*) printf 'win\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

normalize_platform() {
  case $(lower "$1") in
    linux|linux64|gnu|ubuntu|debian|centos|fedora) printf 'linux\n' ;;
    win|windows|win32|win64) printf 'win\n' ;;
    macos|darwin|osx|mac) printf 'macos\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

detect_arch() {
  case $(uname -m 2>/dev/null || echo unknown) in
    x86_64|amd64) printf 'x64\n' ;;
    aarch64|arm64) printf 'arm64\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

normalize_arch() {
  case $(lower "$1") in
    x64|amd64|x86_64) printf 'x64\n' ;;
    arm64|aarch64) printf 'arm64\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

PLATFORM=
if [ -n "$PLATFORM_ARG" ]; then PLATFORM=$(normalize_platform "$PLATFORM_ARG"); fi
[ -n "$PLATFORM" ] || PLATFORM=$(detect_platform)
ARCH=
if [ -n "$ARCH_ARG" ]; then ARCH=$(normalize_arch "$ARCH_ARG"); fi
[ -n "$ARCH" ] || ARCH=$(detect_arch)

[ "$PLATFORM" != unknown ] || \
  die 65 "unsupported platform '${PLATFORM_ARG:-$(uname -s)}' (supported: linux, win, macos)"
[ "$ARCH" != unknown ] || \
  die 65 "unsupported architecture '${ARCH_ARG:-$(uname -m)}' (supported: x64, arm64)"

if [ "$PLATFORM" = macos ]; then
  die 65 "BtbN/FFmpeg-Builds publishes no macOS prebuilds, and this script has no macOS fallback.
  Use a package-manager FFmpeg instead:
      brew install ffmpeg pkg-config
      export PKG_CONFIG_PATH=\"\$(brew --prefix ffmpeg)/lib/pkgconfig\"
      export FFMPEG_DIR=\"\$(brew --prefix ffmpeg)\"
  or build FFmpeg yourself and point PKG_CONFIG_PATH/FFMPEG_DIR at it.
  (FFmpeg is only needed for --features hwcodec.)"
fi

case $PLATFORM-$ARCH in
  linux-x64)   PLATFORM_TOKEN=linux64;    ASSET_EXT=tar.xz ;;
  linux-arm64) PLATFORM_TOKEN=linuxarm64; ASSET_EXT=tar.xz ;;
  win-x64)     PLATFORM_TOKEN=win64;      ASSET_EXT=zip ;;
  win-arm64)   PLATFORM_TOKEN=winarm64;   ASSET_EXT=zip ;;
  *) die 65 "no prebuilt FFmpeg mapping for $PLATFORM-$ARCH" ;;
esac

DEST=$DEST_ARG
if [ -z "$DEST" ]; then DEST=$DEPS_ROOT/ffmpeg/$PLATFORM-$ARCH; fi
CACHE=$DEPS_ROOT/ffmpeg/_cache
marker=$DEST/.ffmpeg-build

SERIES_NORM=$(lower "$SERIES")
SERIES_NORM=${SERIES_NORM#n}

api_url="https://api.github.com/repos/$REPO/releases/tags/$TAG"
release_url="https://github.com/$REPO/releases/download/$TAG"
checksums_url="$release_url/checksums.sha256"

# ---------------------------------------------------------------------------
# already there?
# ---------------------------------------------------------------------------
if [ "$FORCE" -eq 0 ] && [ "$DRY_RUN" -eq 0 ] && tree_ok; then
  if [ -f "$marker" ]; then
    _old_asset=$(sed -n 's/^asset=//p' "$marker" | head -n 1)
    _old_tag=$(sed -n 's/^tag=//p' "$marker" | head -n 1)
    _refetch=0
    if [ -n "$ASSET_OVERRIDE" ] && [ -n "$_old_asset" ] && [ "$_old_asset" != "$ASSET_OVERRIDE" ]; then
      _refetch=1
    fi
    if [ -n "$TAG_EXPLICIT" ] && [ -n "$_old_tag" ] && [ "$_old_tag" != "$TAG" ]; then
      _refetch=1
    fi
    if [ -n "$SERIES_EXPLICIT" ] && [ -n "$SERIES_NORM" ] && [ -n "$_old_asset" ]; then
      case $_old_asset in
        *-shared-"$SERIES_NORM".*) ;;
        *) _refetch=1 ;;
      esac
    fi
    if [ "$_refetch" -eq 0 ]; then
      info "already present, nothing to do: $DEST"
      if [ -n "$_old_asset" ]; then ASSET=$_old_asset; fi
      if [ -n "$_old_tag" ]; then TAG=$_old_tag; fi
      SKIP_FETCH=1
    else
      info "different FFmpeg requested (tree has tag=$_old_tag asset=$_old_asset); re-fetching"
    fi
  else
    info "already present (unmanaged tree, no .ffmpeg-build marker): $DEST"
    SKIP_FETCH=1
  fi
fi

# ---------------------------------------------------------------------------
# resolve, download, verify, extract
# ---------------------------------------------------------------------------
if [ "$SKIP_FETCH" -eq 0 ]; then
  mkdir -p "$CACHE"

  if [ -n "$ASSET_OVERRIDE" ]; then
    ASSET=$ASSET_OVERRIDE
    info "using asset from --asset/FFMPEG_ASSET: $ASSET"
  else
    info "resolving FFmpeg asset for $PLATFORM-$ARCH ($LICENSE_KIND-shared, tag=$TAG)"
    if api_names > "$CACHE/release-$TAG.names" && [ -s "$CACHE/release-$TAG.names" ]; then
      candidates=$(cat "$CACHE/release-$TAG.names")
    else
      warn "GitHub API unreachable ($api_url); probing known asset names instead"
      if candidates=$(probe_candidates); then
        info "probe found: $candidates"
      else
        die 2 "cannot determine the FFmpeg asset name (GitHub API and name probing both failed).
  Check the network, or pass the asset name explicitly:
      --asset ffmpeg-n9.0-latest-$PLATFORM_TOKEN-$LICENSE_KIND-shared-9.0.$ASSET_EXT
      --tag <autobuild-tag>   # when pinning to an older release
  Offline? A previously downloaded archive in
      $CACHE
  is reused automatically, no network needed."
      fi
    fi

    _filtered=$(printf '%s\n' "$candidates" \
      | grep -E "^ffmpeg-.+-$PLATFORM_TOKEN-$LICENSE_KIND-shared(-[0-9]+\.[0-9]+)?\.(tar\.xz|zip)$" \
      || true)
    if [ -z "$_filtered" ]; then
      die 2 "release '$TAG' has no $PLATFORM_TOKEN/$LICENSE_KIND-shared asset for $PLATFORM-$ARCH.
  Inspect https://github.com/$REPO/releases/tag/$TAG
  then re-run with --tag/--series/--lgpl or --asset."
    fi

    _chosen=
    if [ -n "$SERIES_NORM" ]; then
      if [ "$SERIES_NORM" = master ]; then
        _chosen=$(printf '%s\n' "$_filtered" | grep -m1 -- '-master-' || true)
      else
        _chosen=$(printf '%s\n' "$_filtered" | while IFS= read -r _n; do
          [ -n "$_n" ] || continue
          if [ "$(series_of_name "$_n")" = "$SERIES_NORM" ]; then printf '%s\n' "$_n"; fi
        done | head -n 1)
      fi
      if [ -z "$_chosen" ]; then
        die 2 "release '$TAG' has no FFmpeg '$SERIES' asset for $PLATFORM-$ARCH.
  Matching assets in this release:
$(printf '%s\n' "$_filtered" | sed 's/^/    /')"
      fi
    else
      _best_series=$(printf '%s\n' "$_filtered" | while IFS= read -r _n; do
        if [ -n "$_n" ]; then series_of_name "$_n"; fi
      done | sort -t. -k1,1n -k2,2n | tail -n 1)
      if [ -n "$_best_series" ]; then
        _chosen=$(printf '%s\n' "$_filtered" | while IFS= read -r _n; do
          [ -n "$_n" ] || continue
          if [ "$(series_of_name "$_n")" = "$_best_series" ]; then printf '%s\n' "$_n"; fi
        done | head -n 1)
      else
        _chosen=$(printf '%s\n' "$_filtered" | grep -m1 -- '-master-' || true)
        [ -n "$_chosen" ] || _chosen=$(printf '%s\n' "$_filtered" | head -n 1)
        warn "release '$TAG' has no stable-branch asset for $PLATFORM-$ARCH; using '$_chosen'"
      fi
    fi
    ASSET=$_chosen
    info "selected asset: $ASSET"
  fi

  URL=$release_url/$ASSET
  CHECKSUMS=$CACHE/checksums.$TAG.sha256
  ARCHIVE=$CACHE/$ASSET

  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[fetch-ffmpeg] dry run\n  asset  : %s\n  url    : %s\n  dest   : %s\n  archive: %s\n' \
      "$ASSET" "$URL" "$DEST" "$ARCHIVE"
    exit 0
  fi

  mkdir -p "$DEST"

  EXPECTED=$EXPECTED_CHECKSUM
  if [ -z "$EXPECTED" ]; then
    if [ ! -s "$CHECKSUMS" ]; then
      if http_get "$checksums_url" "$CHECKSUMS.tmp" 2>/dev/null; then
        mv "$CHECKSUMS.tmp" "$CHECKSUMS"
      else
        rm -f "$CHECKSUMS.tmp"
        warn "could not download checksums.sha256 for release '$TAG'"
      fi
    fi
    if [ -s "$CHECKSUMS" ]; then
      EXPECTED=$(awk -v f="$ASSET" '{ n=$2; sub(/^\*/, "", n); if (n == f) { print $1; exit } }' "$CHECKSUMS")
      [ -n "$EXPECTED" ] || warn "checksums.sha256 for '$TAG' does not list '$ASSET'"
    fi
  else
    info "using checksum from FFMPEG_CHECKSUM"
  fi

  if [ -z "$EXPECTED" ]; then
    if [ "$REQUIRE_CHECKSUM" = 1 ]; then
      die 3 "no SHA256 available for '$ASSET' and FFMPEG_REQUIRE_CHECKSUM=1.
  Pin it: FFMPEG_CHECKSUM=<sha256> $0 --tag $TAG --asset $ASSET"
    fi
    warn "=================================================================="
    warn "UNVERIFIED DOWNLOAD: no SHA256 known for '$ASSET'."
    warn "The release checksum file is missing or does not list that asset."
    warn "Set FFMPEG_CHECKSUM=<sha256>, or FFMPEG_REQUIRE_CHECKSUM=1 to fail."
    warn "=================================================================="
  fi

  if [ "$FORCE" -eq 1 ] || [ ! -s "$ARCHIVE" ]; then
    info "downloading $URL"
    if ! http_get "$URL" "$ARCHIVE.tmp"; then
      rm -f "$ARCHIVE.tmp"
      die 2 "download failed: $URL
  Offline or blocked? Put the archive at
      $ARCHIVE
  and re-run: it is verified and extracted without touching the network.
  FFmpeg is only required for --features hwcodec."
    fi
    mv "$ARCHIVE.tmp" "$ARCHIVE"
  else
    info "using cached archive: $ARCHIVE"
  fi

  ACTUAL=unknown
  _hash=$(sha256_of "$ARCHIVE")
  if [ -n "$_hash" ]; then
    ACTUAL=$_hash
    if [ -n "$EXPECTED" ]; then
      if [ "$ACTUAL" != "$EXPECTED" ]; then
        rm -f "$ARCHIVE"
        die 3 "SHA256 mismatch for '$ASSET'
  expected: $EXPECTED
  actual  : $ACTUAL
  The archive was deleted; re-run to download it again."
      fi
      info "sha256 ok: $ACTUAL"
    fi
  else
    warn "no sha256sum/shasum/openssl available; cannot hash '$ARCHIVE'"
  fi

  info "extracting $ASSET -> $DEST"
  EXTRACT_DIR=$(mktemp -d "$CACHE/extract.XXXXXX") || die 2 "cannot create extraction dir"
  cleanup() { rm -rf "$EXTRACT_DIR"; }
  trap cleanup EXIT INT TERM
  case $ARCHIVE in
    *.tar.xz|*.tar.gz|*.tgz)
      tar -xf "$ARCHIVE" -C "$EXTRACT_DIR" || die 2 "tar failed to extract $ARCHIVE" ;;
    *.zip)
      if have unzip; then
        unzip -q "$ARCHIVE" -d "$EXTRACT_DIR" || die 2 "unzip failed to extract $ARCHIVE"
      else
        die 2 "the '$ASSET' package is a zip but 'unzip' is not installed"
      fi ;;
    *) die 2 "unsupported archive type: $ARCHIVE" ;;
  esac

  shopt -s nullglob dotglob
  set -- "$EXTRACT_DIR"/*
  shopt -u nullglob dotglob
  if [ $# -eq 1 ] && [ -d "$1" ]; then
    _src=$1
  else
    _src=$EXTRACT_DIR
  fi

  # Reaching this point means $DEST was absent or created by this script
  # (unmanaged trees are skipped earlier), so stale files are removed first.
  find "$DEST" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
  mkdir -p "$DEST"
  cp -a "$_src/." "$DEST/" || die 2 "failed to copy extracted files into $DEST"
  cleanup
  trap - EXIT INT TERM

  if [ ! -f "$DEST/include/libavcodec/avcodec.h" ]; then
    die 2 "unexpected archive layout: $DEST/include/libavcodec/avcodec.h is missing"
  fi

  relocate_pc "$DEST"

  {
    printf 'asset=%s\n' "$ASSET"
    printf 'tag=%s\n' "$TAG"
    printf 'repo=%s\n' "$REPO"
    printf 'sha256=%s\n' "$ACTUAL"
    printf 'release_published_at=%s\n' "$RELEASE_PUBLISHED"
    printf 'fetched_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'platform=%s\n' "$PLATFORM"
    printf 'arch=%s\n' "$ARCH"
    printf 'license=%s\n' "$LICENSE_KIND"
  } > "$marker"
fi

# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------
PC_DIR=
for _d in "$DEST/lib/pkgconfig" "$DEST/lib64/pkgconfig" "$DEST/share/pkgconfig"; do
  if [ -d "$_d" ]; then PC_DIR=$_d; break; fi
done

printf '\n[fetch-ffmpeg] FFmpeg ready\n'
printf '  asset      : %s\n' "$ASSET"
printf '  tag        : %s\n' "$TAG"
printf '  tree       : %s\n' "$DEST"
if [ -n "$PC_DIR" ]; then
  printf '  pkg-config : %s\n' "$PC_DIR"
else
  printf '  pkg-config : (none in this package; link with -L%s/lib -lavcodec ...)\n' "$DEST"
fi
printf '\n  Only the hwcodec build needs these variables:\n'
if [ -n "$PC_DIR" ]; then
  printf '    export PKG_CONFIG_PATH="%s%s"\n' "$PC_DIR" '${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}'
  printf '    export FFMPEG_PKG_CONFIG_PATH="%s/libavcodec.pc"\n' "$PC_DIR"
  printf '    export FFMPEG_DIR="%s"\n' "$DEST"
  printf '  PowerShell:\n'
  printf '    $env:PKG_CONFIG_PATH = "%s"\n' "$PC_DIR"
  printf '    $env:FFMPEG_PKG_CONFIG_PATH = "%s/libavcodec.pc"\n' "$PC_DIR"
  printf '    $env:FFMPEG_DIR = "%s"\n' "$DEST"
else
  printf '    export FFMPEG_DIR="%s"\n' "$DEST"
  printf '    # no .pc files: pass -L%s/lib and -lavcodec/-lavutil/-lavformat manually\n' "$DEST"
fi

if have pkg-config && [ -n "$PC_DIR" ]; then
  _declared=$(sed -n 's/^Version:[[:space:]]*//p' "$PC_DIR/libavcodec.pc" 2>/dev/null | head -n 1 | tr -d '\r')
  _got=$(PKG_CONFIG_PATH="$PC_DIR" pkg-config --modversion libavcodec 2>/dev/null || true)
  if [ -n "$_got" ] && [ "$_got" = "$_declared" ]; then
    printf '\n  check: pkg-config --exists libavcodec -> ok (version %s)\n' "$_got"
  else
    printf '\n  check: pkg-config --exists libavcodec -> FAILED\n'
    printf '    pkg-config reported "%s" but %s/libavcodec.pc declares "%s"\n' \
      "${_got:-<none>}" "$PC_DIR" "${_declared:-<none>}"
    printf '    (another libavcodec.pc may shadow it in the default pkg-config search path)\n'
  fi
elif [ -n "$PC_DIR" ]; then
  printf '\n  check: pkg-config not found on PATH; skipped\n'
fi
