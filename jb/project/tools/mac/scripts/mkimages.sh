#!/bin/bash

set -euo pipefail
set -x

# The following parameters must be specified:
#   build_number - specifies the number of JetBrainsRuntime build
#   bundle_type  - specifies bundle to be built;possible values:
#               <empty> or nomod - the release bundles without any additional modules (jcef)
#               jcef - the release bundles with jcef
#               fd - the fastdebug bundles which also include the jcef module
#
# This script makes test-image along with JDK images when bundle_type is set to "jcef".
# If the character 't' is added at the end of bundle_type then it also makes test-image along with JDK images.
#
# Environment variables:
#   JDK_BUILD_NUMBER - specifies update release of OpenJDK build or the value of --with-version-build argument
#               to configure
#               By default JDK_BUILD_NUMBER is set zero
#   JCEF_PATH - specifies the path to the directory with JCEF binaries.
#               By default JCEF binaries should be located in ./jcef_mac

source jb/project/tools/common/scripts/common.sh

JCEF_PATH=${JCEF_PATH:=./jcef_mac}
BOOT_JDK=${BOOT_JDK:=$(/usr/libexec/java_home -v 17)}

function do_configure {
  if [[ "${architecture}" == *aarch64* ]]; then
    sh configure \
      $WITH_DEBUG_LEVEL \
      --with-vendor-name="${VENDOR_NAME}" \
      --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
      --with-macosx-bundle-name-base=${VENDOR_VERSION_STRING} \
      --with-macosx-bundle-id-base="com.jetbrains.jbr" \
      --with-jvm-features=shenandoahgc \
      --with-version-pre= \
      --with-version-build="${JDK_BUILD_NUMBER}" \
      --with-version-opt=b"${build_number}" \
      --with-boot-jdk="$BOOT_JDK" \
      --disable-hotspot-gtest --disable-javac-server --disable-full-docs --disable-manpages \
      --enable-cds=no \
      --with-extra-cflags="-F$(pwd)/Frameworks" \
      --with-extra-cxxflags="-F$(pwd)/Frameworks" \
      --with-extra-ldflags="-F$(pwd)/Frameworks" \
      $STATIC_CONF_ARGS \
      $REPRODUCIBLE_BUILD_OPTS \
      $WITH_ZIPPED_NATIVE_DEBUG_SYMBOLS \
      || do_exit $?
  else
    sh configure \
      $WITH_DEBUG_LEVEL \
      --with-vendor-name="$VENDOR_NAME" \
      --with-vendor-version-string="$VENDOR_VERSION_STRING" \
      --with-macosx-bundle-name-base=${VENDOR_VERSION_STRING} \
      --with-macosx-bundle-id-base="com.jetbrains.jbr" \
      --with-jvm-features=shenandoahgc \
      --with-version-pre= \
      --with-version-build="$JDK_BUILD_NUMBER" \
      --with-version-opt=b"$build_number" \
      --with-boot-jdk="$BOOT_JDK" \
      --enable-cds=yes \
      $STATIC_CONF_ARGS \
      $REPRODUCIBLE_BUILD_OPTS \
      $WITH_ZIPPED_NATIVE_DEBUG_SYMBOLS \
      || do_exit $?
  fi
}

function create_image_bundle {
  __bundle_name=$1
  __arch_name=$2
  __modules_path=$3
  __modules=$4

  fastdebug_infix=''

  tmp=.bundle.$$.tmp
  mkdir "$tmp" || do_exit $?

  [ "$bundle_type" == "fd" ] && [ "$__arch_name" == "$JBRSDK_BUNDLE" ] && __bundle_name=$__arch_name && fastdebug_infix="fastdebug-"
  JBR=${__bundle_name}-${JBSDK_VERSION}-osx-${architecture}-${fastdebug_infix:-}b${build_number}

  JRE_CONTENTS=$tmp/$__arch_name/Contents
  mkdir -p "$JRE_CONTENTS" || do_exit $?

  echo Running jlink...
  "$JSDK"/bin/jlink \
    --module-path "$__modules_path" --no-man-pages --compress=2 \
    --add-modules "$__modules" --output "$JRE_CONTENTS/Home" || do_exit $?

  grep -v "^JAVA_VERSION" "$JSDK"/release | grep -v "^MODULES" >> "$JRE_CONTENTS/Home/release"
  if [ "$__arch_name" == "$JBRSDK_BUNDLE" ]; then
    sed 's/JBR/JBRSDK/g' $JRE_CONTENTS/Home/release > release
    mv release $JRE_CONTENTS/Home/release
    cp $IMAGES_DIR/jdk-bundle/jdk-$JBSDK_VERSION.jdk/Contents/Home/lib/src.zip $JRE_CONTENTS/Home/lib
    copy_jmods "$__modules" "$__modules_path" "$JRE_CONTENTS"/Home/jmods
    zip_native_debug_symbols $IMAGES_DIR/jdk-bundle/jdk-$JBSDK_VERSION.jdk "${JBR}_diz"
  fi

  cp -R "$JSDK"/../MacOS "$JRE_CONTENTS"
  cp "$JSDK"/../Info.plist "$JRE_CONTENTS"

  [ -n "$bundle_type" ] && (cp -a $JCEF_PATH/Frameworks "$JRE_CONTENTS" || do_exit $?)

  echo Creating "$JBR".tar.gz ...
  # Normalize timestamp
  find "$tmp"/"$__arch_name" -print0 | xargs -0 touch -c -h -t "$TOUCH_TIME"

  (cd "$tmp" &&
      find "$__arch_name" -print0 | LC_ALL=C sort -z | \
      COPYFILE_DISABLE=1 tar $REPRODUCIBLE_TAR_OPTS  --no-recursion --null -T - \
                             -czf "$JBR".tar.gz --exclude='*.dSYM' --exclude='man') || do_exit $?
  mv "$tmp"/"$JBR".tar.gz  "$JBR".tar.gz
  rm -rf "$tmp"
}

WITH_DEBUG_LEVEL="--with-debug-level=release"
CONF_ARCHITECTURE=x86_64
if [[ "${architecture}" == *aarch64* ]]; then
  CONF_ARCHITECTURE=aarch64
fi
RELEASE_NAME=macosx-${CONF_ARCHITECTURE}-server-release

case "$bundle_type" in
  "jcef")
    do_reset_changes=1
    do_maketest=1
    ;;
  "nomod" | "")
    bundle_type=""
    ;;
  "fd")
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=macosx-${CONF_ARCHITECTURE}-server-fastdebug
    JBSDK=macosx-${architecture}-server-release
    ;;
esac

if [ -z "${INC_BUILD:-}" ]; then
  do_configure || do_exit $?
  make clean CONF=$RELEASE_NAME || do_exit $?
fi
make images CONF=$RELEASE_NAME || do_exit $?

IMAGES_DIR=build/$RELEASE_NAME/images

JSDK=$IMAGES_DIR/jdk-bundle/jdk-$JBSDK_VERSION.jdk/Contents/Home
JSDK_MODS_DIR=$IMAGES_DIR/jmods
JBRSDK_BUNDLE=jbrsdk

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ]; then
  git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
  update_jsdk_mods "$JSDK" "$JCEF_PATH"/jmods "$JSDK"/jmods "$JSDK_MODS_DIR" || do_exit $?
  cp $JCEF_PATH/jmods/* $JSDK_MODS_DIR # $JSDK/jmods is not changed

  jbr_name_postfix="_${bundle_type}"
else
  jbr_name_postfix=""
fi

# create runtime image bundle
modules=$(xargs < jb/project/tools/common/modules.list | sed s/" "//g) || do_exit $?
create_image_bundle "jbr${jbr_name_postfix}" "jbr" $JSDK_MODS_DIR "$modules" || do_exit $?

# create sdk image bundle
modules=$(cat "$JSDK"/release | grep MODULES | sed s/MODULES=//g | sed s/' '/','/g | sed s/\"//g | sed s/\\n//g) || do_exit $?
if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ] || [ "$bundle_type" == "$JBRSDK_BUNDLE" ]; then
  modules=${modules},$(get_mods_list "$JCEF_PATH"/jmods)
fi
create_image_bundle "$JBRSDK_BUNDLE${jbr_name_postfix}" "$JBRSDK_BUNDLE" "$JSDK_MODS_DIR" "$modules" || do_exit $?

if [ $do_maketest -eq 1 ]; then
    JBRSDK_TEST=${JBRSDK_BUNDLE}-${JBSDK_VERSION}-osx-test-${architecture}-b${build_number}
    echo Creating "$JBRSDK_TEST" ...
    [ $do_reset_changes -eq 1 ] && git checkout HEAD jb/project/tools/common/modules.list src/java.desktop/share/classes/module-info.java
    make test-image CONF=$RELEASE_NAME || do_exit $?
    [ -f "$JBRSDK_TEST.tar.gz" ] && rm "$JBRSDK_TEST.tar.gz"
    COPYFILE_DISABLE=1 tar -pczf "$JBRSDK_TEST".tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
fi

do_exit 0
