#!/bin/bash

# Downloads, verifies and unpacks the pinned prebuilt Skia for use with
# configure --with-skia. Idempotent: a complete, verified unpack is reused.
#
# Usage:
#   fetch_skia.sh [-t macos|linux|windows] [-a arm64|x64] [-c <conf>] [-o <out_root>] [-q]
#
#   -t  target OS      (default: host)
#   -a  target arch    (default: host; "aarch64" is accepted as alias for arm64)
#   -c  pin file       (default: jb/project/tools/common/skia_prebuilt.conf)
#   -o  output root    (default: <repo>/build/deps/skia, which is git-ignored)
#   -q  quiet: print only the resulting path
#
# Prints the unpacked directory on stdout, e.g. for:
#   bash configure --with-skia="$(jb/project/tools/common/scripts/fetch_skia.sh)"

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../../.." && pwd)"

target=""
arch=""
conf="${script_dir}/../skia_prebuilt.conf"
out_root="${repo_root}/build/deps/skia"
quiet=0

while getopts ":t:a:c:o:q" o; do
  case "${o}" in
    t) target="${OPTARG}" ;;
    a) arch="${OPTARG}" ;;
    c) conf="${OPTARG}" ;;
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
    Linux) echo linux ;;
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

function getConfProp() {
  # grep exits 1 on a missing key; callers validate emptiness themselves
  grep "^${1}=" "${conf}" | cut -d'=' -f2- | tr -d '\r' || true
}

[[ -f "${conf}" ]] || { echo "Pin file not found: ${conf}" >&2; exit 1; }

target="${target:-$(detect_target)}"
arch="${arch:-$(detect_arch)}"
[[ "${arch}" == "aarch64" ]] && arch=arm64

publisher_url="$(getConfProp SKIA_PUBLISHER_URL)"
tag="$(getConfProp SKIA_TAG)"
build_type="$(getConfProp SKIA_BUILD_TYPE)"
expected_sha="$(getConfProp "SKIA_SHA256_${target}_${arch}")"

if [[ -z "${publisher_url}" || -z "${tag}" || -z "${build_type}" ]]; then
  echo "Incomplete pin file: ${conf}" >&2; exit 1
fi
if [[ -z "${expected_sha}" ]]; then
  echo "No SKIA_SHA256_${target}_${arch} in ${conf}; this target is not pinned" >&2; exit 1
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
trap 'rm -f "${tmp_zip}"' EXIT

# On Cygwin the curl in PATH is usually the native Windows one, which cannot
# open a /cygdrive path (it fails on the first write); give it a Windows path.
curl_out="${tmp_zip}"
if command -v cygpath >/dev/null 2>&1; then
  curl_out="$(cygpath -w "${tmp_zip}")"
fi

log "Downloading ${url}"
curl -fsSL --retry 3 -o "${curl_out}" "${url}"

actual_sha="$(sha256_of "${tmp_zip}")"
if [[ "${actual_sha}" != "${expected_sha}" ]]; then
  echo "SHA-256 mismatch for ${asset}" >&2
  echo "  expected: ${expected_sha}" >&2
  echo "  actual:   ${actual_sha}" >&2
  exit 1
fi
log "SHA-256 verified"

rm -rf "${dest}"
mkdir -p "${dest}"
if command -v unzip >/dev/null 2>&1; then
  unzip -q "${tmp_zip}" -d "${dest}"
else
  python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "${tmp_zip}" "${dest}"
fi
echo "${expected_sha}" > "${marker}"
log "Unpacked to ${dest}"
echo "${dest}"
