#!/bin/bash

#immediately exit script with an error if a command fails
set -euo pipefail
set -x

export COPY_EXTENDED_ATTRIBUTES_DISABLE=true
export COPYFILE_DISABLE=true

INPUT_FILE=$1
EXPLODED=$2.exploded
BACKUP_JMODS=$2.backup
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
rm -rf "$BACKUP_JMODS"
mkdir "$BACKUP_JMODS"

log "Unzipping $INPUT_FILE to $EXPLODED ..."
tar -xzvf "$INPUT_FILE" --directory $EXPLODED
BUILD_NAME="$(ls "$EXPLODED")"
#sed -i '' s/BNDL/APPL/ $EXPLODED/$BUILD_NAME/Contents/Info.plist
rm -f $EXPLODED/$BUILD_NAME/Contents/CodeResources
rm "$INPUT_FILE"
if test -d $EXPLODED/$BUILD_NAME/Contents/Home/jmods; then
  mv $EXPLODED/$BUILD_NAME/Contents/Home/jmods $BACKUP_JMODS
fi

log "$INPUT_FILE extracted and removed"

APP_NAME=$(basename "$INPUT_FILE" | awk -F".tar" '{ print $1 }')
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
  "$SCRIPT_DIR/sign.sh" "$APPLICATION_PATH" "$APP_NAME" "$BUNDLE_ID" "$CODESIGN_STRING" "$JB_INSTALLER_CERT"
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
  "$SCRIPT_DIR/notarize.sh" "$APP_NAME.pkg"
  log "Stapling..."
  xcrun stapler staple "$APPLICATION_PATH" ||:
  xcrun stapler staple "$APP_NAME.pkg" ||:
else
  log "Notarization disabled"
  log "Stapling disabled"
fi

log "Zipping $BUILD_NAME to $INPUT_FILE ..."
(
  #cd "$EXPLODED"
  #ditto -c -k --sequesterRsrc --keepParent "$BUILD_NAME" "../$INPUT_FILE"
  if test -d $BACKUP_JMODS/jmods; then
    mv $BACKUP_JMODS/jmods $APPLICATION_PATH/Contents/Home
  fi
  mv $APPLICATION_PATH $EXPLODED/$BUILD_NAME

  tar -pczvf $INPUT_FILE --exclude='man' -C $EXPLODED $BUILD_NAME
  log "Finished zipping"
)
rm -rf "$EXPLODED"
log "Done"