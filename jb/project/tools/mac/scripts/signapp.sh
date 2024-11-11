#!/bin/bash

#immediately exit script with an error if a command fails
set -euo pipefail
[[ "${SCRIPT_VERBOSE:-}" == "1" ]] && set -x

export COPY_EXTENDED_ATTRIBUTES_DISABLE=true
export COPYFILE_DISABLE=true

INPUT_FILE=$1
EXPLODED=$2.exploded
USERNAME=$3
PASSWORD=$4
CODESIGN_STRING=$5
JB_INSTALLER_CERT=$6
NOTARIZE=$7
BUNDLE_ID=$8

SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null && pwd)"

function log() {
  echo "$(date '+[%H:%M:%S]') $*"
}

log "Deleting $EXPLODED ..."
if test -d "$EXPLODED"; then
  find "$EXPLODED" -mindepth 1 -maxdepth 1 -exec chmod -R u+wx '{}' \;
fi
rm -rf "$EXPLODED"
mkdir "$EXPLODED"

log "Unzipping $INPUT_FILE to $EXPLODED ..."
tar -xzvf "$INPUT_FILE" --directory $EXPLODED
BUILD_NAME="$(ls "$EXPLODED")"
#sed -i '' s/BNDL/APPL/ $EXPLODED/$BUILD_NAME/Contents/Info.plist
rm -f $EXPLODED/$BUILD_NAME/Contents/CodeResources
rm "$INPUT_FILE"

log "$INPUT_FILE extracted and removed"

APP_NAME=$(basename "$INPUT_FILE" | awk -F".tar" '{ print $1 }')
PKG_NAME="$APP_NAME.pkg"
APPLICATION_PATH=$EXPLODED/$(ls $EXPLODED)

find "$APPLICATION_PATH/Contents/Home/bin" \
  -maxdepth 1 -type f -name '*.jnilib' -print0 |
  while IFS= read -r -d $'\0' file; do
    if [ -f "$file" ]; then
      log "Linking $file"
      b="$(basename "$file" .jnilib)"
      ln -sf "$b.jnilib" "$(dirname "$file")/$b.dylib"
    fi
  done

find "$APPLICATION_PATH/Contents/" \
  -maxdepth 1 -type f -name '*.txt' -print0 |
  while IFS= read -r -d $'\0' file; do
    if [ -f "$file" ]; then
      log "Moving $file"
      mv "$file" "$APPLICATION_PATH/Contents/Resources"
    fi
  done

non_plist=$(find "$APPLICATION_PATH/Contents/" -maxdepth 1 -type f -and -not -name 'Info.plist' | wc -l)
if [[ $non_plist -gt 0 ]]; then
  log "Only Info.plist file is allowed in Contents directory but found $non_plist file(s):"
  log "$(find "$APPLICATION_PATH/Contents/" -maxdepth 1 -type f -and -not -name 'Info.plist')"
  exit 1
fi

if [[ "${JETSIGN_CLIENT:=}" == "null" ]] || [[ "$JETSIGN_CLIENT" == "" ]]; then
  log "Unlocking keychain..."
  # Make sure *.p12 is imported into local KeyChain
  security unlock-keychain -p "$PASSWORD" "/Users/$USERNAME/Library/Keychains/login.keychain"
fi

attempt=1
limit=3
set +e
while [[ $attempt -le $limit ]]; do
  log "Signing (attempt $attempt) $APPLICATION_PATH ..."
  "$SCRIPT_DIR/sign.sh" "$APPLICATION_PATH" "$PKG_NAME" "$BUNDLE_ID" "$CODESIGN_STRING" "$JB_INSTALLER_CERT"
  ec=$?
  if [[ $ec -ne 0 ]]; then
    ((attempt += 1))
    if [ $attempt -eq $limit ]; then
      set -e
    fi
    log "Signing failed, wait for 30 sec and try to sign again"
    sleep 30
  else
    log "Signing done"
    codesign -v "$APPLICATION_PATH" -vvvvv
    log "Check sign done"
    spctl -a -v $APPLICATION_PATH
    ((attempt += limit))
  fi
done

set -e

if [ "$NOTARIZE" = "yes" ]; then
  log "Notarizing..."
  "$SCRIPT_DIR/notarize.sh" "$PKG_NAME"

  log "Stapling..."
  appStaplerOutput=$(xcrun stapler staple "$APPLICATION_PATH")
  if [ $? -ne 0 ]; then
    log "Stapling application failed"
    echo "$appStaplerOutput"
    exit 1
  else
    echo "$appStaplerOutput"
  fi

  log "Stapling package..."
  pkgStaplerOutput=$(xcrun stapler staple "$PKG_NAME")
  if [ $? -ne 0 ]; then
    log "Stapling package failed"
    echo "$pkgStaplerOutput"
    exit 1
  else
    echo "$pkgStaplerOutput"
  fi

  # Verify stapling
  log "Verifying stapling..."
  if ! stapler validate "$APPLICATION_PATH"; then
    log "Stapling verification failed for application"
    exit 1
  fi
  if ! stapler validate "$PKG_NAME"; then
    log "Stapling verification failed for package"
    exit 1
  fi
else
  log "Notarization disabled"
  log "Stapling disabled"
fi

log "Zipping $BUILD_NAME to $INPUT_FILE ..."
(
  if [[ "$APPLICATION_PATH" != "$EXPLODED/$BUILD_NAME" ]]; then
    mv $APPLICATION_PATH $EXPLODED/$BUILD_NAME
  else
    echo "No move, source == destination: $APPLICATION_PATH"
  fi

  tar -pczvf $INPUT_FILE --exclude='man' -C $EXPLODED $BUILD_NAME
  log "Finished zipping"
)
rm -rf "$EXPLODED"
log "Done"
