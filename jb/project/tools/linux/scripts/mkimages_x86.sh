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

source jb/project/tools/common/scripts/common.sh

function do_configure {
  linux32 bash configure \
    $WITH_DEBUG_LEVEL \
    --with-vendor-name="$VENDOR_NAME" \
    --with-vendor-version-string="$VENDOR_VERSION_STRING" \
    --with-jvm-features=shenandoahgc \
    --with-version-pre= \
    --with-version-build="$JDK_BUILD_NUMBER" \
    --with-version-opt=b"$build_number" \
    --with-boot-jdk="$BOOT_JDK" \
    $STATIC_CONF_ARGS \
    --enable-cds=yes \
    $DISABLE_WARNINGS_AS_ERRORS \
    $REPRODUCIBLE_BUILD_OPTS \
    $WITH_ZIPPED_NATIVE_DEBUG_SYMBOLS \
    || do_exit $?
}

function is_musl {
  libc=$(ldd /bin/ls | grep 'musl' | head -1 | cut -d ' ' -f1)
  if [ -z $libc ]; then
    # This is not Musl, return 1 == false
    return 1
  fi
  return 0
}

function create_image_bundle {
  __bundle_name=$1
  __arch_name=$2
  __modules_path=$3
  __modules=$4

  libc_type_suffix=''
  fastdebug_infix=''
  __cds_opt=''

  if is_musl; then libc_type_suffix='musl-' ; fi
  if [ "$__arch_name" == "$JBRSDK_BUNDLE" ]; then __cds_opt="--generate-cds-archive"; fi

  [ "$bundle_type" == "fd" ] && [ "$__arch_name" == "$JBRSDK_BUNDLE" ] && __bundle_name=$__arch_name && fastdebug_infix="fastdebug-"
  JBR=${__bundle_name}-${JBSDK_VERSION}-linux-${libc_type_suffix}x86-${fastdebug_infix}b${build_number}
  __root_dir=${__bundle_name}-${JBSDK_VERSION}-linux-${libc_type_suffix}x86-${fastdebug_infix:-}b${build_number}

  echo Running jlink....
  [ -d "$IMAGES_DIR"/"$__root_dir" ] && rm -rf "${IMAGES_DIR:?}"/"$__root_dir"
  $JSDK/bin/jlink \
    --module-path "$__modules_path" --no-man-pages --compress=2 \
    $__cds_opt --add-modules "$__modules" --output "$IMAGES_DIR"/"$__root_dir"

  grep -v "^JAVA_VERSION" "$JSDK"/release | grep -v "^MODULES" >> "$IMAGES_DIR"/"$__root_dir"/release
  if [ "$__arch_name" == "$JBRSDK_BUNDLE" ]; then
    sed 's/JBR/JBRSDK/g' "$IMAGES_DIR"/"$__root_dir"/release > release
    mv release "$IMAGES_DIR"/"$__root_dir"/release
    cp $IMAGES_DIR/jdk/lib/src.zip "$IMAGES_DIR"/"$__root_dir"/lib
    copy_jmods "$__modules" "$__modules_path" "$IMAGES_DIR"/"$__root_dir"/jmods
    zip_native_debug_symbols $IMAGES_DIR/jdk "${JBR}_diz"
  fi

  # jmod does not preserve file permissions (JDK-8173610)
  [ -f "$IMAGES_DIR"/"$__root_dir"/lib/jcef_helper ] && chmod a+x "$IMAGES_DIR"/"$__root_dir"/lib/jcef_helper

  echo Creating "$JBR".tar.gz ...

  (cd "$IMAGES_DIR" &&
    find "$__root_dir" -print0 | LC_ALL=C sort -z | \
    tar $REPRODUCIBLE_TAR_OPTS \
      --no-recursion --null -T - -cf "$JBR".tar) || do_exit $?
  mv "$IMAGES_DIR"/"$JBR".tar ./"$JBR".tar
  [ -f "$JBR".tar.gz ] && rm "$JBR.tar.gz"
  touch -c -d "@$SOURCE_DATE_EPOCH" "$JBR".tar
  gzip "$JBR".tar || do_exit $?
  rm -rf "${IMAGES_DIR:?}"/"$__root_dir"
}

WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=linux-x86-server-release

case "$bundle_type" in
  "jcef")
    echo "not implemented" && do_exit 1
    ;;
  "nomod" | "")
    bundle_type=""
    ;;
  "fd")
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=linux-x86-server-fastdebug
    ;;
esac

if [ -z "${INC_BUILD:-}" ]; then
  do_configure || do_exit $?
  make clean CONF=$RELEASE_NAME || do_exit $?
fi
make images CONF=$RELEASE_NAME || do_exit $?

IMAGES_DIR=build/$RELEASE_NAME/images
JSDK=$IMAGES_DIR/jdk
JSDK_MODS_DIR=$IMAGES_DIR/jmods
JBRSDK_BUNDLE=jbrsdk

echo Fixing permissions
chmod -R a+r $JSDK

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ]; then
  jbr_name_postfix="_${bundle_type}"
else
  jbr_name_postfix=""
fi

# create runtime image bundle
modules=$(grep -v "jdk.internal.vm" jb/project/tools/common/modules.list | xargs | sed s/" "//g) || do_exit $?
create_image_bundle "jbr${jbr_name_postfix}" "jbr" $JSDK_MODS_DIR "$modules" || do_exit $?

# create sdk image bundle
modules=$(cat $JSDK/release | grep MODULES | sed s/MODULES=//g | sed s/' '/','/g | sed s/\"//g | sed s/\\n//g) || do_exit $?
create_image_bundle "$JBRSDK_BUNDLE${jbr_name_postfix}" $JBRSDK_BUNDLE $JSDK_MODS_DIR "$modules" || do_exit $?

if [ $do_maketest -eq 1 ]; then
    JBRSDK_TEST=${JBRSDK_BUNDLE}-${JBSDK_VERSION}-linux-${libc_type_suffix}test-x86-b${build_number}
    echo Creating "$JBRSDK_TEST" ...
    [ $do_reset_changes -eq 1 ] && git checkout HEAD jb/project/tools/common/modules.list src/java.desktop/share/classes/module-info.java
    make test-image jbr-api CONF=$RELEASE_NAME JBR_API_JBR_VERSION=TEST || do_exit $?
    cp "build/${RELEASE_NAME}/jbr-api/jbr-api.jar" "${IMAGES_DIR}/test"
    tar -pcf "$JBRSDK_TEST".tar -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
    [ -f "$JBRSDK_TEST.tar.gz" ] && rm "$JBRSDK_TEST.tar.gz"
    gzip "$JBRSDK_TEST".tar || do_exit $?
fi

do_exit 0
