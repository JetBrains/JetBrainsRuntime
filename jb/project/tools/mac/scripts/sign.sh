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
      -exec sh -c '"$1" --timestamp -v -s "$2" --options=runtime --force --entitlements "$3" "$4" || exit 1' sh "$SIGN_UTILITY" "$JB_DEVELOPER_CERT" "$SCRIPT_DIR/entitlements.xml" {} \;
  fi
done

log "Signing jmod files"
JMODS_DIR="$APPLICATION_PATH/Contents/Home/jmods"
JMOD_EXE="$BOOT_JDK/bin/jmod"
if [ -d "$JMODS_DIR" ]; then
  log "processing jmods"  

  for jmod_file in "$JMODS_DIR"/*.jmod; do
    log "Processing $jmod_file"

    TMP_DIR="$JMODS_DIR/tmp"
    rm -rf "$TMP_DIR"
    mkdir "$TMP_DIR"

    log "Unzipping $jmod_file"
    $JMOD_EXE extract --dir "$TMP_DIR" "$jmod_file" >/dev/null

    log "Signing dylibs in $TMP_DIR"
    find "$TMP_DIR" \
      -type f \( -name "*.dylib" -o -name "*.so"-o -perm +111 -o -name jarsigner -o -name jnativescan -o -name jdeps -o -name jpackageapplauncher -o -name jspawnhelper -o -name jar -o -name javap -o -name jdeprscan -o -name jfr -o -name rmiregistry -o -name java -o -name jhsdb  -o -name jstatd  -o -name jstatd -o -name jpackage -o -name keytool -o -name jmod -o -name jlink -o -name jimage -o -name jstack -o -name jcmd -o -name jps -o -name jmap -o -name jstat -o -name jinfo -o -name jshell -o -name jwebserver -o -name javac -o -name serialver -o -name jrunscript -o -name jdb -o -name jconsole -o -name javadoc \) \
      -exec sh -c '"$1" --timestamp -v -s "$2" --options=runtime --force --entitlements "$3" "$4" || exit 1' sh "$SIGN_UTILITY" "$JB_DEVELOPER_CERT" "$SCRIPT_DIR/entitlements.xml" {} \;

    log "Removing $jmod_file"
    rm -f "$jmod_file"
    cmd="$JMOD_EXE create --class-path $TMP_DIR/classes"

    # Check each directory and add to the command if it exists
    [ -d "$TMP_DIR/bin" ] && cmd="$cmd --cmds $TMP_DIR/bin"
    [ -d "$TMP_DIR/conf" ] && cmd="$cmd --config $TMP_DIR/conf"
    [ -d "$TMP_DIR/lib" ] && cmd="$cmd --libs $TMP_DIR/lib"
    [ -d "$TMP_DIR/include" ] && cmd="$cmd --header-files $TMP_DIR/include"
    [ -d "$TMP_DIR/legal" ] && cmd="$cmd --legal-notices $TMP_DIR/legal"
    [ -d "$TMP_DIR/man" ] && cmd="$cmd --man-pages $TMP_DIR/man"

    log "Creating jmod file"
		log "$cmd"
    # Add the output file
    cmd="$cmd $jmod_file"

    # Execute the command
    eval $cmd

    log "Removing $TMP_DIR"
    rm -rf "$TMP_DIR"
  done

  log "Repack java.base.jmod with new hashes of modules"
  hash_modules=$($JMOD_EXE describe $JMODS_DIR/java.base.jmod | grep hashes | awk '{print $2}' | tr '\n' '|' | sed s/\|$//) || exit $?

  TMP_DIR="$JMODS_DIR/tmp"
  rm -rf "$TMP_DIR"
  mkdir "$TMP_DIR"

  jmod_file="$JMODS_DIR/java.base.jmod"
  log "Unzipping $jmod_file"
  $JMOD_EXE extract --dir "$TMP_DIR" "$jmod_file" >/dev/null

  log "Removing java.base.jmod"
  rm -f "$jmod_file"

  cmd="$JMOD_EXE create --class-path $TMP_DIR/classes --hash-modules \"$hash_modules\" --module-path $JMODS_DIR"

  # Check each directory and add to the command if it exists
  [ -d "$TMP_DIR/bin" ] && cmd="$cmd --cmds $TMP_DIR/bin"
  [ -d "$TMP_DIR/conf" ] && cmd="$cmd --config $TMP_DIR/conf"
  [ -d "$TMP_DIR/lib" ] && cmd="$cmd --libs $TMP_DIR/lib"
  [ -d "$TMP_DIR/include" ] && cmd="$cmd --header-files $TMP_DIR/include"
  [ -d "$TMP_DIR/legal" ] && cmd="$cmd --legal-notices $TMP_DIR/legal"
  [ -d "$TMP_DIR/man" ] && cmd="$cmd --man-pages $TMP_DIR/man"

  log "Creating jmod file"
	log "$cmd"
  # Add the output file
  cmd="$cmd $jmod_file"

  # Execute the command
  eval $cmd

  log "Removing $TMP_DIR"
  rm -rf "$TMP_DIR"
else
  echo "Directory '$JMODS_DIR' does not exist. Skipping signing of jmod files."
fi

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
      -exec sh -c '"$1" --timestamp --force -v -s "$2" --options=runtime --entitlements "$3" "$4" || exit 1' sh "$SIGN_UTILITY" "$JB_DEVELOPER_CERT" "$SCRIPT_DIR/entitlements.xml" {} \;

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
      -exec sh -c '"$1" --timestamp -v -s "$2" --options=runtime --force --entitlements "$3" "$4" || exit 1' sh "$SIGN_UTILITY" "$JB_DEVELOPER_CERT" "$SCRIPT_DIR/entitlements.xml" {} \;
  fi
done

log "Signing whole frameworks..."
# shellcheck disable=SC2043
if [ "$JB_SIGN" = true ]; then for f in \
  "Contents/Frameworks/cef_server.app/Contents/Frameworks" "Contents/Home/Frameworks" "Contents/Frameworks"; do
  if [ -d "$APPLICATION_PATH/$f" ]; then
    find "$APPLICATION_PATH/$f" \( -name '*.framework' -o -name '*.app' \) -maxdepth 1 | while read -r line
      do
        log "Signing '$line':"
        tar -pczf tmp-to-sign.tar.gz -C "$(dirname "$line")" "$(basename "$line")"
        "$SIGN_UTILITY" --timestamp \
            -v -s "$JB_DEVELOPER_CERT" --options=runtime \
            --force \
            --entitlements "$SCRIPT_DIR/entitlements.xml" tmp-to-sign.tar.gz || exit 1
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
  tar -pczf tmp-to-sign.tar.gz --exclude='man' -C "$(dirname "$APPLICATION_PATH")" "$(basename "$APPLICATION_PATH")"
  "$SIGN_UTILITY" --timestamp \
    -v -s "$JB_DEVELOPER_CERT" --options=runtime \
    --force \
    --entitlements "$SCRIPT_DIR/entitlements.xml" tmp-to-sign.tar.gz || exit 1
  rm -rf "$APPLICATION_PATH"
  tar -xzf tmp-to-sign.tar.gz --directory "$(dirname "$APPLICATION_PATH")"
  rm -f tmp-to-sign.tar.gz
else
  "$SIGN_UTILITY" --timestamp \
    -v -s "$JB_DEVELOPER_CERT" --options=runtime \
    --force \
    --entitlements "$SCRIPT_DIR/entitlements.xml" "$APPLICATION_PATH" || exit 1
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
