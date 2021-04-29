#!/bin/bash

APP_DIRECTORY=$1
JB_CERT=$2

if [[ -z "$APP_DIRECTORY" ]] || [[ -z "$JB_CERT" ]]; then
  echo "Usage: $0 AppDirectory CertificateID"
  exit 1
fi
if [[ ! -d "$APP_DIRECTORY" ]]; then
  echo "AppDirectory '$APP_DIRECTORY' does not exist or not a directory"
  exit 1
fi

function log() {
  echo "$(date '+[%H:%M:%S]') $*"
}

#immediately exit script with an error if a command fails
set -euo pipefail

# Cleanup files left from previous sign attempt (if any)
find "$APP_DIRECTORY" -name '*.cstemp' -exec rm '{}' \;

log "Signing libraries and executables..."
# -perm +111 searches for executables
for f in \
   "Contents/Home/bin" \
   "Contents/Home/lib"; do
  if [ -d "$APP_DIRECTORY/$f" ]; then
    find "$APP_DIRECTORY/$f" \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -perm +111 \) \
      -exec codesign --timestamp --force \
      -v -s "$JB_CERT" --options=runtime \
      --entitlements entitlements.xml {} \;
  fi
done

if [ -d "$APP_DIRECTORY/Contents/Frameworks" ]; then
  log "Signing frameworks..."
  for f in $APP_DIRECTORY/Contents/Frameworks/*; do
    find "$f" \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" \) \
      -exec codesign --timestamp --force \
      -v -s "$JB_CERT" \
      --entitlements entitlements.xml {} \;
    codesign --timestamp --force \
      -v -s "$JB_CERT" --options=runtime \
      --entitlements entitlements.xml "$f"
  done
fi

log "Signing libraries in jars in $PWD"

# todo: add set -euo pipefail; into the inner sh -c
# `-e` prevents `grep -q && printf` loginc
# with `-o pipefail` there's no input for 'while' loop
find "$APP_DIRECTORY" -name '*.jar' \
  -exec sh -c "set -u; unzip -l \"\$0\" | grep -q -e '\.dylib\$' -e '\.jnilib\$' -e '\.so\$' -e '^jattach\$' && printf \"\$0\0\" " {} \; |
  while IFS= read -r -d $'\0' file; do
    log "Processing libraries in $file"

    rm -rf jarfolder jar.jar
    mkdir jarfolder
    filename="${file##*/}"
    log "Filename: $filename"
    cp "$file" jarfolder && (cd jarfolder && jar xf "$filename" && rm "$filename")

    find jarfolder \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -name "jattach" \) \
      -exec codesign --timestamp --force \
      -v -s "$JB_CERT" --options=runtime \
      --entitlements entitlements.xml {} \;

    (cd jarfolder; zip -q -r -o ../jar.jar .)
    mv jar.jar "$file"
  done

rm -rf jarfolder jar.jar

log "Signing other files..."
for f in \
  "Contents/MacOS"; do
  if [ -d "$APP_DIRECTORY/$f" ]; then
    find "$APP_DIRECTORY/$f" \
      -type f \( -name "*.jnilib" -o -name "*.dylib" -o -name "*.so" -o -perm +111 \) \
      -exec codesign --timestamp --force \
      -v -s "$JB_CERT" --options=runtime \
      --entitlements entitlements.xml {} \;
  fi
done

#log "Signing executable..."
#codesign --timestamp \
#    -v -s "$JB_CERT" --options=runtime \
#    --force \
#    --entitlements entitlements.xml "$APP_DIRECTORY/Contents/MacOS/idea"

log "Signing whole app..."
codesign --timestamp \
  -v -s "$JB_CERT" --options=runtime \
  --force \
  --entitlements entitlements.xml "$APP_DIRECTORY"

log "Verifying java is not broken"
find "$APP_DIRECTORY" \
  -type f -name 'java' -perm +111 -exec {} -version \;
