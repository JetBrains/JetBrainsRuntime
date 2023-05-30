#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null && pwd)"

source "$SCRIPT_DIR/jetsign-common.sh" || exit 1

function isMacOsBinary() {
  file "$1" | grep -q 'Mach-O'
}

function isSigned() {
  codesign --verify "$1" >/dev/null 2>&1 && ! grep -q Signature=adhoc < <(codesign --display --verbose "$1" 2>&1)
}

# last argument is a path to be signed
pathToBeSigned="$(pwd)/${*: -1}"
jetSignArgs=("${@:1:$#-1}")
if [[ ! -f "$pathToBeSigned" ]]; then
  echo "$pathToBeSigned is missing or not a file"
  exit 1
elif isSigned "$pathToBeSigned" && ! isForced "${jetSignArgs[@]}" ; then
  echo "Already signed: $pathToBeSigned"
elif [[ "$JETSIGN_CLIENT" == "null" ]]; then
  echo "JetSign client is missing, cannot proceed with signing"
  exit 1
elif ! isMacOsBinary "$pathToBeSigned" && [[ "$pathToBeSigned" != *.sit ]] && [[ "$pathToBeSigned" != *.tar.gz ]]; then
  echo "$pathToBeSigned won't be signed, assumed not to be a macOS executable"
else
  if isMacOsBinary "$pathToBeSigned" && ! isSigned "$pathToBeSigned" ; then
    echo "Unsigned macOS binary: $pathToBeSigned"
  fi
  workDir=$(dirname "$pathToBeSigned")
  pathSigned="$workDir/signed/${pathToBeSigned##*/}"
  jetSignExtensions=$(jetSignExtensions "${jetSignArgs[@]}")
  contentType=$(jetSignContentType "$pathToBeSigned")
  (
    cd "$workDir" || exit 1
    "$JETSIGN_CLIENT" -log-format text -denoted-content-type "$contentType" -extensions "$jetSignExtensions" "$pathToBeSigned"
    # SRE-1223 (Codesign removes execute bits in executable files) workaround
    chmod "$(stat -f %A "$pathToBeSigned")" "$pathSigned"
    if isMacOsBinary "$pathSigned"; then
      isSigned "$pathSigned"
    fi
    rm "$pathToBeSigned"
    mv "$pathSigned" "$pathToBeSigned"
    rm -rf "$workDir/signed"
  )
fi
