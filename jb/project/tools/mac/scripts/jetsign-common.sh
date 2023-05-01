#!/bin/bash
set -euo pipefail

function isForced() {
  for arg in "$@"; do
    if [[ "$arg" == --force ]]; then
      return 0
    fi
  done
  return 1
}

function jetSignExtensions() {
  args=("$@")
  ((lastElementIndex=${#args[@]}-1))
  for index in "${!args[@]}"; do
    arg=${args[$index]}
    case "$arg" in
    --sign | -s)
      echo -n 'mac_codesign_identity='
      continue
      ;;
    --entitlements)
      echo -n 'mac_codesign_entitlements='
      continue
      ;;
    --options=runtime)
      echo -n 'mac_codesign_options=runtime'
      ;;
    --force)
      echo -n 'mac_codesign_force=true'
      ;;
    --timestamp | --verbose | -v)
      continue
      ;;
    *)
      echo -n "$arg"
      ;;
    esac
    if [[ $index != "$lastElementIndex" ]]; then
      echo -n ","
    fi
  done
}

# See jetbrains.sign.util.FileUtil.contentType
function jetSignContentType() {
  case "${1##*/}" in
  *.sit)
    echo -n 'application/x-mac-app-zip'
    ;;
  *.tar.gz)
    echo -n 'application/x-mac-app-targz'
    ;;
  *.pkg)
    echo -n 'application/x-mac-pkg'
    ;;
  *)
    echo -n 'application/x-mac-app-bin'
    ;;
  esac
}

