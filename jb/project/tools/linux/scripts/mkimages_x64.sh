#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies major version of OpenJDK e.g. 11_0_6 (instead of dots '.' underbars "_" are used)
#   JDK_BUILD_NUMBER - specifies update release of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to be built; possible values:
#                        <empty> or nomod - the release bundles without any additional modules (jcef)
#                        jcef - the release bundles with jcef
#                        fd - the fastdebug bundles which also include the jcef module
#
# jbrsdk-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
# jbr-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
#
# $ ./java --version
# openjdk 11.0.6 2020-01-14
# OpenJDK Runtime Environment (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number})
# OpenJDK 64-Bit Server VM (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number}, mixed mode)
#
# Environment variables:
#   JCEF_PATH - specifies the path to the directory with JCEF binaries.
#               By default JCEF binaries should be located in ./jcef_linux_x64

while getopts ":i?" o; do
    case "${o}" in
        i)
            i="incremental build"
            INC_BUILD=1
            ;;
    esac
done
shift $((OPTIND-1))

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
JCEF_PATH=${JCEF_PATH:=./jcef_linux_x64}

source jb/project/tools/common/scripts/common.sh

function do_configure {
  sh configure \
    $WITH_DEBUG_LEVEL \
    --with-vendor-name="$VENDOR_NAME" \
    --with-vendor-version-string="$VENDOR_VERSION_STRING" \
    --with-jvm-features=shenandoahgc \
    --with-version-pre= \
    --with-version-build="$JDK_BUILD_NUMBER" \
    --with-version-opt=b"$build_number" \
    --with-boot-jdk="$BOOT_JDK" \
    --enable-cds=yes \
    $REPRODUCIBLE_BUILD_OPTS \
    || do_exit $?
}

function create_image_bundle {
  __bundle_name=$1
  __arch_name=$2
  __modules_path=$3
  __modules=$4

  [ "$bundle_type" == "fd" ] && [ "$__arch_name" == "$JBRSDK_BUNDLE" ] && __bundle_name=$__arch_name && fastdebug_infix="fastdebug-"
  JBR=${__bundle_name}-${JBSDK_VERSION}-linux-x64-${fastdebug_infix}b${build_number}

  echo Running jlink....
  [ -d "$IMAGES_DIR"/"$__arch_name" ] && rm -rf "${IMAGES_DIR:?}"/"$__arch_name"
  $JSDK/bin/jlink \
    --module-path "$__modules_path" --no-man-pages --compress=2 \
    --add-modules "$__modules" --output "$IMAGES_DIR"/"$__arch_name"

  grep -v "^JAVA_VERSION" "$JSDK"/release | grep -v "^MODULES" >> "$IMAGES_DIR"/"$__arch_name"/release
  if [ "$__arch_name" == "$JBRSDK_BUNDLE" ]; then
    sed 's/JBR/JBRSDK/g' "$IMAGES_DIR"/"$__arch_name"/release > release
    mv release "$IMAGES_DIR"/"$__arch_name"/release
    copy_jmods "$__modules" "$__modules_path" "$IMAGES_DIR"/"$__arch_name"/jmods
  fi

  # jmod does not preserve file permissions (JDK-8173610)
  [ -f "$IMAGES_DIR"/"$__arch_name"/lib/jcef_helper ] && chmod a+x "$IMAGES_DIR"/"$__arch_name"/lib/jcef_helper

  echo Creating "$JBR".tar.gz ...

  (cd "$IMAGES_DIR" &&
    find "$__arch_name" -print0 | LC_ALL=C sort -z | \
    tar $REPRODUCIBLE_TAR_OPTS \
      --no-recursion --null -T - -cf "$JBR".tar) || do_exit $?
  mv "$IMAGES_DIR"/"$JBR".tar ./"$JBR".tar
  [ -f "$JBR".tar.gz ] && rm "$JBR.tar.gz"
  touch -c -d "@$SOURCE_DATE_EPOCH" "$JBR".tar
  gzip "$JBR".tar || do_exit $?
  rm -rf "${IMAGES_DIR:?}"/"$__arch_name"
}

WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=linux-x86_64-server-release

case "$bundle_type" in
  "jcef")
    do_reset_changes=0
    ;;
  "dcevm")
    HEAD_REVISION=$(git rev-parse HEAD)
    git am jb/project/tools/patches/dcevm/*.patch || do_exit $?
    do_reset_dcevm=0
    do_reset_changes=0
    ;;
  "nomod" | "")
    bundle_type=""
    ;;
  "fd")
    do_reset_changes=0
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=linux-x86_64-server-fastdebug
    ;;
esac

if [ -z "$INC_BUILD" ]; then
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

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "dcevm" ] || [ "$bundle_type" == "fd" ]; then
  git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
  update_jsdk_mods $JSDK $JCEF_PATH/jmods $JSDK/jmods $JSDK_MODS_DIR || do_exit $?
  cp $JCEF_PATH/jmods/* $JSDK_MODS_DIR # $JSDK/jmods is not changed

  jbr_name_postfix="_${bundle_type}"
  [ "$bundle_type" != "fd" ] && jbrsdk_name_postfix="_${bundle_type}"
fi

# create runtime image bundle
modules=$(xargs < modules.list | sed s/" "//g) || do_exit $?
create_image_bundle "jbr${jbr_name_postfix}" "jbr" $JSDK_MODS_DIR "$modules" || do_exit $?

# create sdk image bundle
modules=$(cat $JSDK/release | grep MODULES | sed s/MODULES=//g | sed s/' '/','/g | sed s/\"//g | sed s/\\n//g) || do_exit $?
if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "dcevm" ] || [ "$bundle_type" == "fd" ] || [ "$bundle_type" == "$JBRSDK_BUNDLE" ]; then
  modules=${modules},$(get_mods_list "$JCEF_PATH"/jmods)
fi
create_image_bundle "$JBRSDK_BUNDLE${jbr_name_postfix}" $JBRSDK_BUNDLE $JSDK_MODS_DIR "$modules" || do_exit $?

if [ -z "$bundle_type" ]; then
    JBRSDK_TEST=${JBRSDK_BUNDLE}-${JBSDK_VERSION}-linux-test-x64-b${build_number}
    echo Creating "$JBRSDK_TEST" ...
    make test-image CONF=$RELEASE_NAME || do_exit $?
    tar -pcf "$JBRSDK_TEST".tar -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
    [ -f "$JBRSDK_TEST.tar.gz" ] && rm "$JBRSDK_TEST.tar.gz"
    gzip "$JBRSDK_TEST".tar || do_exit $?
fi

do_exit 0
