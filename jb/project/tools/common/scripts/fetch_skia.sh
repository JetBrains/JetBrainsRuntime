#!/bin/bash

# Downloads, verifies and unpacks the pinned prebuilt Skia for use with
# configure --with-skia. Idempotent: a complete, verified unpack is reused.
#
# Usage:
#   fetch_skia.sh [-t macos|linux|windows] [-a arm64|x64] [-c <conf>] [-o <out_root>] [-q]
#
#   -t  target OS      (default: host; windows under WSL, as configure assumes there)
#   -a  target arch    (default: host; "aarch64" is accepted as alias for arm64)
#       Only a target the pin file has a SKIA_SHA256_<os>_<arch> digest for can be
#       fetched; skia_prebuilt.conf pins windows x64 only.
#   -c  pin file       (default: jb/project/tools/common/skia_prebuilt.conf)
#   -o  output root    (default: <repo>/build/deps/skia, which is git-ignored)
#   -q  quiet: print only the resulting path
#
# Prints the unpacked directory on stdout, e.g. for:
#   bash configure --with-skia="$(jb/project/tools/common/scripts/fetch_skia.sh)"

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../../.." && pwd)"

# curl: a transient failure of the GitHub release endpoint should not fail a build
curl_retries=3

target=""
arch=""
pin_file="${script_dir}/../skia_prebuilt.conf"
out_root="${repo_root}/build/deps/skia"
quiet=0

while getopts ":t:a:c:o:q" option; do
  case "${option}" in
    t) target="${OPTARG}" ;;
    a) arch="${OPTARG}" ;;
    c) pin_file="${OPTARG}" ;;
    o) out_root="${OPTARG}" ;;
    q) quiet=1 ;;
    *) echo "Unknown option: -${OPTARG}" >&2; exit 2 ;;
  esac
done
shift $((OPTIND-1))

function log() {
  [[ "${quiet}" -eq 1 ]] || echo "$@" >&2
}

function detect_target() {
  case "$(uname -s)" in
    Darwin) echo macos ;;
    # WSL builds a Windows JDK, as configure assumes there (make/autoconf/build-aux/config.guess)
    Linux) if uname -r | grep -qi microsoft; then echo windows; else echo linux; fi ;;
    MINGW*|MSYS*|CYGWIN*) echo windows ;;
    *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
  esac
}

function detect_arch() {
  case "$(uname -m)" in
    arm64|aarch64) echo arm64 ;;
    x86_64|amd64) echo x64 ;;
    *) echo "Unsupported host arch: $(uname -m)" >&2; exit 1 ;;
  esac
}

function sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
  fi
}

# the value of a key in the pin file (empty when absent: grep exits 1 then, and the callers
# validate emptiness themselves); a CRLF checkout must not leak a \r into it
function pin_value() {
  grep "^${1}=" "${pin_file}" | cut -d'=' -f2- | tr -d '\r' || true
}

[[ -f "${pin_file}" ]] || { echo "Pin file not found: ${pin_file}" >&2; exit 1; }

target="${target:-$(detect_target)}"
arch="${arch:-$(detect_arch)}"
[[ "${arch}" == "aarch64" ]] && arch=arm64

publisher_url="$(pin_value SKIA_PUBLISHER_URL)"
tag="$(pin_value SKIA_TAG)"
build_type="$(pin_value SKIA_BUILD_TYPE)"
expected_sha="$(pin_value "SKIA_SHA256_${target}_${arch}")"

if [[ -z "${publisher_url}" || -z "${tag}" || -z "${build_type}" ]]; then
  echo "Incomplete pin file: ${pin_file}" >&2; exit 1
fi
if [[ -z "${expected_sha}" ]]; then
  echo "No SKIA_SHA256_${target}_${arch} in ${pin_file}; this target is not pinned" >&2; exit 1
fi

asset="Skia-${tag}-${target}-${build_type}-${arch}.zip"
url="${publisher_url}/${tag}/${asset}"
dest="${out_root}/${tag}-${target}-${build_type}-${arch}"
marker="${dest}/.fetch_skia.complete"

if [[ -f "${marker}" ]] && [[ "$(cat "${marker}")" == "${expected_sha}" ]]; then
  log "Skia ${tag} (${target}-${arch}) already present: ${dest}"
  echo "${dest}"
  exit 0
fi

mkdir -p "${out_root}"
tmp_zip="$(mktemp "${out_root}/${asset}.XXXXXX")"
# unpacked and marked beside the destination, then moved into place: a concurrent run sharing the root
# never sees a half-unpacked tree, and an interrupted one leaves nothing behind (the trap removes the
# staging directory; after the move it no longer exists)
staging="${dest}.staging.$$"
trap 'rm -rf "${tmp_zip}" "${staging}"' EXIT

# On Cygwin the curl in PATH is usually the native Windows one, which cannot
# open a /cygdrive path (it fails on the first write); give it a Windows path.
curl_out="${tmp_zip}"
if command -v cygpath >/dev/null 2>&1; then
  curl_out="$(cygpath -w "${tmp_zip}")"
fi

log "Downloading ${url}"
curl -fsSL --retry "${curl_retries}" -o "${curl_out}" "${url}"

actual_sha="$(sha256_of "${tmp_zip}")"
if [[ "${actual_sha}" != "${expected_sha}" ]]; then
  echo "SHA-256 mismatch for ${asset}" >&2
  echo "  expected: ${expected_sha}" >&2
  echo "  actual:   ${actual_sha}" >&2
  exit 1
fi
log "SHA-256 verified"

rm -rf "${staging}"
mkdir -p "${staging}"
if command -v unzip >/dev/null 2>&1; then
  unzip -q "${tmp_zip}" -d "${staging}"
else
  python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "${tmp_zip}" "${staging}"
fi
echo "${expected_sha}" > "${staging}/$(basename "${marker}")"
rm -rf "${dest}"
mv "${staging}" "${dest}"
log "Unpacked to ${dest}"
echo "${dest}"
