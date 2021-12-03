#!/bin/bash -x

APPLICATION_PATH=$1
APP_NAME=$2
BUNDLE_ID=$3
JB_DEVELOPER_CERT=$4
JB_INSTALLER_CERT=$5

if [[ -z "$APPLICATION_PATH" ]] || [[ -z "$JB_DEVELOPER_CERT" ]]; then
  echo "Usage: $0 AppDirectory CertificateID"
  exit 1
fi
if [[ ! -d "$APPLICATION_PATH" ]]; then
  echo "AppDirectory '$APPLICATION_PATH' does not exist or not a directory"
  exit 1
fi

function log() {
  echo "$(date '+[%H:%M:%S]') $*"
}

#immediately exit script with an error if a command fails
set -euo pipefail

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
      -exec codesign --timestamp \
      -v -s "$JB_DEVELOPER_CERT" --options=runtime --force \
      --entitlements entitlements.xml {} \;
  fi
done

log "Signing libraries in jars in $PWD"

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
      -exec codesign --timestamp \
      --force \
      -v -s "$JB_DEVELOPER_CERT" --options=runtime \
      --entitlements entitlements.xml {} \;

    (cd jarfolder; zip -q -r -o -0 ../jar.jar .)
    mv jar.jar "$file"
  done

rm -rf jarfolder jar.jar

log "Signing other files..."
for f in \
  "Contents/Home/bin"; do
  if [ -d "$APPLICATION_PATH/$f" ]; then
    find "$APPLICATION_PATH/$f" \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -name "*.tbd" -o -perm +111 \) \
      -exec codesign --timestamp \
      -v -s "$JB_DEVELOPER_CERT" --options=runtime --force \
      --entitlements entitlements.xml {} \;
  fi
done

#log "Signing executable..."
#codesign --timestamp \
#    -v -s "$JB_DEVELOPER_CERT" --options=runtime \
#    --force \
#    --entitlements entitlements.xml "$APPLICATION_PATH/Contents/MacOS/idea"

log "Signing whole app..."
codesign --timestamp \
  -v -s "$JB_DEVELOPER_CERT" --options=runtime \
  --force \
  --entitlements entitlements.xml "$APPLICATION_PATH"

BUILD_NAME=$(echo $APPLICATION_PATH | awk -F"/" '{ print $2 }')

log "Creating $APP_NAME.pkg..."
rm -rf "$APP_NAME.pkg"
pkgbuild --identifier $BUNDLE_ID --sign "$JB_INSTALLER_CERT" --root $APPLICATION_PATH \
    --install-location /Library/Java/JavaVirtualMachines/${BUILD_NAME} ${APP_NAME}.pkg

#log "Signing whole app..."
#codesign --timestamp \
#  -v -s "$JB_DEVELOPER_CERT" --options=runtime \
#  --force \
#  --entitlements entitlements.xml $APP_NAME.pkg

log "Verifying java is not broken"
find "$APPLICATION_PATH" \
  -type f -name 'java' -perm +111 -exec {} -version \;
