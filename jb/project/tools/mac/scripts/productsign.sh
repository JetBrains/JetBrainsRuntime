#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null && pwd)"

source "$SCRIPT_DIR/jetsign-common.sh" || exit 1

function isSigned() {
  pkgutil --check-signature "$1" && grep -q "signed by a developer certificate" < <(pkgutil --check-signature "$1" 2>&1)
}

# second last argument is a path to be signed
pathToBeSigned="$(pwd)/${*:(-2):1}"
# last argument is a path to signed file
pathOut="$(pwd)/${*:(-1)}"
jetSignArgs=("${@:1:$#-2}")
if [[ ! -f "$pathToBeSigned" ]]; then
  echo "$pathToBeSigned is missing or not a file"
  exit 1
elif isSigned "$pathToBeSigned" && ! isForced "${jetSignArgs[@]}" ; then
  echo "Already signed: $pathToBeSigned"
elif [[ "$JETSIGN_CLIENT" == "null" ]]; then
  echo "JetSign client is missing, cannot proceed with signing"
  exit 1
elif [[ "$pathToBeSigned" != *.pkg ]]; then
  echo "$pathToBeSigned won't be signed, assumed not to be a macOS package"
else
  if ! isSigned "$pathToBeSigned" ; then
    echo "Unsigned macOS package: $pathToBeSigned"
  fi
  workDir=$(dirname "$pathToBeSigned")
  pathSigned="$workDir/signed/${pathToBeSigned##*/}"
  jetSignExtensions=$(jetSignExtensions "${jetSignArgs[@]}")
  contentType=$(jetSignContentType "$pathToBeSigned")
  (
    cd "$workDir" || exit 1
    "$JETSIGN_CLIENT" -log-format text -denoted-content-type "$contentType" -extensions "$jetSignExtensions" "$pathToBeSigned"
    isSigned "$pathSigned"
    rm -f "$pathOut"
    mv "$pathSigned" "$pathOut"
    rm -rf "$workDir/signed"
  )
fi
