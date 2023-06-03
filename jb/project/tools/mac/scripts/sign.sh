#!/bin/bash

#immediately exit script with an error if a command fails
set -euo pipefail
[[ "${SCRIPT_VERBOSE:-}" == "1" ]] && set -x

if [[ $# -lt 5  ]]; then
  echo "Usage: $0 AppDirectory AppName BundleId CertificateID InstallerCertificateID"
  exit 1
fi

APPLICATION_PATH=$1
PKG_NAME=$2
BUNDLE_ID=$3
JB_DEVELOPER_CERT=$4
JB_INSTALLER_CERT=$5

SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null && pwd)"

# Use JetBrains sign utility if it's available
if [[ "${JETSIGN_CLIENT:=}" == "null" ]] || [[ "$JETSIGN_CLIENT" == "" ]]; then
  JB_SIGN=false
  SIGN_UTILITY="codesign"
  PRODUCTSIGN_UTILITY="productsign"
else
  JB_SIGN=true
  SIGN_UTILITY="$SCRIPT_DIR/codesign.sh"
  PRODUCTSIGN_UTILITY="$SCRIPT_DIR/productsign.sh"
fi

if [[ ! -d "$APPLICATION_PATH" ]]; then
  echo "AppDirectory '$APPLICATION_PATH' does not exist or not a directory"
  exit 1
fi

function log() {
  echo "$(date '+[%H:%M:%S]') $*"
}

# Cleanup files left from previous sign attempt (if any)
find "$APPLICATION_PATH" -name '*.cstemp' -exec rm '{}' \;

log "Signing libraries and executables..."
# -perm +111 searches for executables
for f in \
  "Contents/Home/lib" "Contents/MacOS" \
  "Contents/Home/Frameworks" \
  "Contents/Frameworks"; do
  if [ -d "$APPLICATION_PATH/$f" ]; then
    find "$APPLICATION_PATH/$f" \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -name "*.tbd" -o -name "*.node" -o -perm +111 \) \
      -exec "$SIGN_UTILITY" --timestamp \
      -v -s "$JB_DEVELOPER_CERT" --options=runtime --force \
      --entitlements "$SCRIPT_DIR/entitlements.xml" {} \;
  fi
done

log "Signing libraries in jars in $APPLICATION_PATH"

# todo: add set -euo pipefail; into the inner sh -c
# `-e` prevents `grep -q && printf` loginc
# with `-o pipefail` there's no input for 'while' loop
find "$APPLICATION_PATH" -name '*.jar' \
  -exec sh -c "set -u; unzip -l \"\$0\" | grep -q -e '\.dylib\$' -e '\.jnilib\$' -e '\.so\$' -e '\.tbd\$' -e '^jattach\$' && printf \"\$0\0\" " {} \; |
  while IFS= read -r -d $'\0' file; do
    log "Processing libraries in $file"

    rm -rf jarfolder jar.jar
    mkdir jarfolder
    filename="${file##*/}"
    log "Filename: $filename"
    cp "$file" jarfolder && (cd jarfolder && jar xf "$filename" && rm "$filename")

    find jarfolder \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -name "*.tbd" -o -name "jattach" \) \
      -exec "$SIGN_UTILITY" --timestamp \
      --force \
      -v -s "$JB_DEVELOPER_CERT" --options=runtime \
      --entitlements "$SCRIPT_DIR/entitlements.xml" {} \;

    (cd jarfolder; zip -q -r -o -0 ../jar.jar .)
    mv jar.jar "$file"
  done

rm -rf jarfolder jar.jar

log "Signing other files..."
# shellcheck disable=SC2043
for f in \
  "Contents/Home/bin"; do
  if [ -d "$APPLICATION_PATH/$f" ]; then
    find "$APPLICATION_PATH/$f" \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -name "*.tbd" -o -perm +111 \) \
      -exec "$SIGN_UTILITY" --timestamp \
      -v -s "$JB_DEVELOPER_CERT" --options=runtime --force \
      --entitlements "$SCRIPT_DIR/entitlements.xml" {} \;
  fi
done

log "Signing whole frameworks..."
# shellcheck disable=SC2043
if [ "$JB_SIGN" = true ]; then for f in \
  "Contents/Home/Frameworks" "Contents/Frameworks"; do
  if [ -d "$APPLICATION_PATH/$f" ]; then
    find "$APPLICATION_PATH/$f" \( -name '*.framework' -o -name '*.app' \) -maxdepth 1 | while read -r line
      do
        log "Signing '$line':"
        tar -pczf tmp-to-sign.tar.gz -C "$(dirname "$line")" "$(basename "$line")"
        "$SIGN_UTILITY" --timestamp \
            -v -s "$JB_DEVELOPER_CERT" --options=runtime \
            --force \
            --entitlements "$SCRIPT_DIR/entitlements.xml" tmp-to-sign.tar.gz
        rm -rf "$line"
        tar -xzf tmp-to-sign.tar.gz --directory "$(dirname "$line")"
        rm -f tmp-to-sign.tar.gz
      done
  fi
done; fi

log "Checking framework signatures..."
for f in \
  "Contents/Home/Frameworks" "Contents/Frameworks"; do
  if [ -d "$APPLICATION_PATH/$f" ]; then
    find "$APPLICATION_PATH/$f" -name '*.framework'  -maxdepth 1 | while read -r line
      do
        log "Checking '$line':"
        codesign --verify --deep --strict --verbose=4 "$line"
      done
  fi
done

log "Signing whole app..."
if [ "$JB_SIGN" = true ]; then
  tar -pczvf tmp-to-sign.tar.gz --exclude='man' -C "$(dirname "$APPLICATION_PATH")" "$(basename "$APPLICATION_PATH")"
  "$SIGN_UTILITY" --timestamp \
    -v -s "$JB_DEVELOPER_CERT" --options=runtime \
    --force \
    --entitlements "$SCRIPT_DIR/entitlements.xml" tmp-to-sign.tar.gz
  rm -rf "$APPLICATION_PATH"
  tar -xzvf tmp-to-sign.tar.gz --directory "$(dirname "$APPLICATION_PATH")"
  rm -f tmp-to-sign.tar.gz
else
  "$SIGN_UTILITY" --timestamp \
    -v -s "$JB_DEVELOPER_CERT" --options=runtime \
    --force \
    --entitlements "$SCRIPT_DIR/entitlements.xml" "$APPLICATION_PATH"
fi

BUILD_NAME="$(basename "$APPLICATION_PATH")"

log "Creating $PKG_NAME..."
rm -rf "$PKG_NAME"

mkdir -p unsigned
pkgbuild --identifier $BUNDLE_ID --root $APPLICATION_PATH \
    --install-location /Library/Java/JavaVirtualMachines/${BUILD_NAME} unsigned/${PKG_NAME}
log "Signing $PKG_NAME..."
"$PRODUCTSIGN_UTILITY" --timestamp --sign "$JB_INSTALLER_CERT" unsigned/${PKG_NAME} ${PKG_NAME}

log "Verifying java is not broken"
find "$APPLICATION_PATH" \
  -type f -name 'java' -perm +111 -exec {} -version \;
