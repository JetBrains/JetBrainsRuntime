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
#               By default JCEF binaries should be located in ./jcef_win_x64

source jb/project/tools/common/scripts/common.sh

WORK_DIR=$(pwd)
JCEF_PATH=${JCEF_PATH:=$WORK_DIR/jcef_win_x64}
NVDA_PATH=${NVDA_PATH:=$WORK_DIR/nvda_controllerClient}

function do_configure {
  sh ./configure \
    $WITH_DEBUG_LEVEL \
    --with-vendor-name="$VENDOR_NAME" \
    --with-vendor-version-string="$VENDOR_VERSION_STRING" \
    --with-jvm-features=shenandoahgc \
    --with-version-pre= \
    --with-version-build=$JDK_BUILD_NUMBER \
    --with-version-opt=b${build_number} \
    --with-toolchain-version=$TOOLCHAIN_VERSION \
    --with-boot-jdk=$BOOT_JDK \
    --with-nvdacontrollerclient=$NVDA_PATH \
    --disable-ccache \
    --enable-cds=yes \
    $DISABLE_WARNINGS_AS_ERRORS \
    $STATIC_CONF_ARGS \
    $REPRODUCIBLE_BUILD_OPTS \
    || do_exit $?
}

function create_image_bundle {
  __bundle_name=$1
  __arch_name=$2
  __modules_path=$3
  __modules=$4

  fastdebug_infix=''
  __cds_opt=''
  __cds_opt="--generate-cds-archive"

  [ "$bundle_type" == "fd" ] && [ "$__arch_name" == "$JBRSDK_BUNDLE" ] && __bundle_name=$__arch_name && fastdebug_infix="fastdebug-"
  __root_dir=${__bundle_name}-${JBSDK_VERSION}-windows-x64-${fastdebug_infix}b${build_number}

  echo Running jlink ...
  ${JSDK}/bin/jlink \
    --module-path $__modules_path --no-man-pages --compress=2 \
    $__cds_opt --add-modules $__modules --output $__root_dir || do_exit $?

  grep -v "^JAVA_VERSION" "$JSDK"/release | grep -v "^MODULES" >> $__root_dir/release
  if [ "$__arch_name" == "$JBRSDK_BUNDLE" ]; then
    sed 's/JBR/JBRSDK/g' $__root_dir/release > release
    mv release $__root_dir/release
    cp $IMAGES_DIR/jdk/lib/src.zip $__root_dir/lib
    for dir in $(ls -d $IMAGES_DIR/jdk/*); do
      rsync -amv --include="*/" --include="*.pdb" --exclude="*" $dir $__root_dir
    done
    copy_jmods "$__modules" "$__modules_path" "$__root_dir"/jmods
  fi
}

WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=windows-x86_64-server-release

case "$bundle_type" in
  "jcef")
    do_reset_changes=0
    do_maketest=1
    ;;
  "nomod" | "")
    bundle_type=""
    ;;
  "fd")
    do_reset_changes=0
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=windows-x86_64-server-fastdebug
    ;;
esac

if [ -z "${INC_BUILD:-}" ]; then
  do_configure || do_exit $?
  if [ $do_maketest -eq 1 ]; then
    make LOG=info CONF=$RELEASE_NAME clean || do_exit $?
    make LOG=info CONF=$RELEASE_NAME images test-image JBR_API_JBR_VERSION=TEST || do_exit $?
  else
    make LOG=info CONF=$RELEASE_NAME clean || do_exit $?
    make LOG=info CONF=$RELEASE_NAME images || do_exit $?
  fi
else
  if [ $do_maketest -eq 1 ]; then
    make LOG=info CONF=$RELEASE_NAME images test-image JBR_API_JBR_VERSION=TEST || do_exit $?
  else
    make LOG=info CONF=$RELEASE_NAME images || do_exit $?
  fi
fi

IMAGES_DIR=build/$RELEASE_NAME/images
JSDK=$IMAGES_DIR/jdk
JSDK_MODS_DIR=$IMAGES_DIR/jmods
JBRSDK_BUNDLE=jbrsdk

where cygpath
if [ $? -eq 0 ]; then
  JCEF_PATH="$(cygpath -w $JCEF_PATH | sed 's/\\/\//g')"
fi

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ]; then
  if [ "$bundle_type" == "jcef" ]; then
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    update_jsdk_mods "$JSDK" "$JCEF_PATH"/jmods "$JSDK"/jmods "$JSDK_MODS_DIR" || do_exit $?
    cp $JCEF_PATH/jmods/* ${JSDK_MODS_DIR} # $JSDK/jmods is not unchanged
    cat $JCEF_PATH/jcef.version >> $JSDK/release
  fi
  jbr_name_postfix="_${bundle_type}"
else
  jbr_name_postfix=""
fi

# create runtime image bundle
modules=$(xargs < jb/project/tools/common/modules.list | sed s/" "//g) || do_exit $?
modules+=",jdk.crypto.mscapi"
create_image_bundle "jbr${jbr_name_postfix}" "jbr" $JSDK_MODS_DIR "$modules" || do_exit $?

# create sdk image bundle
modules=$(cat ${JSDK}/release | grep MODULES | sed s/MODULES=//g | sed s/' '/','/g | sed s/\"//g | sed s/\\r//g | sed s/\\n//g)
if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "$JBRSDK_BUNDLE" ]; then
  modules=${modules},$(get_mods_list "$JCEF_PATH"/jmods)
fi
create_image_bundle "$JBRSDK_BUNDLE${jbr_name_postfix}" "$JBRSDK_BUNDLE" "$JSDK_MODS_DIR" "$modules" || do_exit $?

do_exit 0
