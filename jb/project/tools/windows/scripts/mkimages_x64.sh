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
#               By default JCEF binaries should be located in ./jcef_win_x64

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
WORK_DIR=$(pwd)
JCEF_PATH=${JCEF_PATH:=$WORK_DIR/jcef_win_x64}

source jb/project/tools/common/scripts/common.sh

function create_image_bundle {
  __bundle_name=$1
  __modules_path=$2
  __modules=$3

  [ -d $__bundle_name ] && rm -rf $__bundle_name

  echo Running jlink ...
  ${JSDK}/bin/jlink \
    --module-path $__modules_path --no-man-pages --compress=2 \
    --add-modules $__modules --output $__bundle_name || do_exit $?

  grep -v "^JAVA_VERSION" "$JSDK"/release | grep -v "^MODULES" >> $__bundle_name/release
  if [ "$__bundle_name" == "$JBRSDK_BUNDLE" ]; then
    sed 's/JBR/JBRSDK/g' $__bundle_name/release > release
    mv release $__bundle_name/release
  fi
}

WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=windows-x86_64-server-release

case "$bundle_type" in
  "jcef")
    do_reset_changes=1
    ;;
  "dcevm")
    HEAD_REVISION=$(git rev-parse HEAD)
    git am jb/project/tools/patches/dcevm/*.patch || do_exit $?
    do_reset_dcevm=1
    do_reset_changes=1
    ;;
  "nomod" | "")
    bundle_type=""
    ;;
  "fd")
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=windows-x86_64-server-fastdebug
    ;;
esac

sh ./configure \
  --disable-warnings-as-errors \
  $WITH_DEBUG_LEVEL \
  --with-vendor-name="$VENDOR_NAME" \
  --with-vendor-version-string="$VENDOR_VERSION_STRING" \
  --with-version-pre= \
  --with-version-build=$JDK_BUILD_NUMBER \
  --with-version-opt=b${build_number} \
  --with-toolchain-version=$TOOLCHAIN_VERSION \
  --with-boot-jdk=$BOOT_JDK \
  --disable-ccache \
  --enable-cds=yes || do_exit $?

if [ -z "$bundle_type" ]; then
  make LOG=info CONF=$RELEASE_NAME clean images test-image || do_exit $?
else
  make LOG=info CONF=$RELEASE_NAME clean images || do_exit $?
fi

IMAGES_DIR=build/$RELEASE_NAME/images
JSDK=$IMAGES_DIR/jdk
JSDK_MODS_DIR=$IMAGES_DIR/jmods
JBRSDK_BUNDLE=jbrsdk

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "dcevm" ] || [ "$bundle_type" == "fd" ]; then
  git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
  update_jsdk_mods "$JSDK" "$JCEF_PATH"/jmods "$JSDK"/jmods "$JSDK_MODS_DIR" || do_exit $?
  cp $JCEF_PATH/jmods/* ${JSDK_MODS_DIR} # $JSDK/jmods is not unchanged

  jbr_name_postfix="_${bundle_type}"
fi

# create runtime image bundle
modules=$(xargs < modules.list | sed s/" "//g) || do_exit $?
create_image_bundle "jbr${jbr_name_postfix}" $JSDK_MODS_DIR "$modules" || do_exit $?

# create sdk image bundle
modules=$(cat ${JSDK}/release | grep MODULES | sed s/MODULES=//g | sed s/' '/,/g | sed s/\"//g | sed s/\\r//g | sed s/\\n//g)
if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "dcevm" ] || [ "$bundle_type" == "fd" ] || [ "$bundle_type" == "$JBRSDK_BUNDLE" ]; then
  modules=${modules},$(get_mods_list "$JCEF_PATH"/jmods)
fi
create_image_bundle "$JBRSDK_BUNDLE" "$JSDK_MODS_DIR" "$modules" || do_exit $?

do_exit 0