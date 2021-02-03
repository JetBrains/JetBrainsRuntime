#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies major version of OpenJDK e.g. 11_0_6 (instead of dots '.' underbars "_" are used)
#   JDK_BUILD_NUMBER - specifies update release of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to be built; possible values:
#                        jcef - the release bundles with jcef
#                        dcevm - the release bundles with dcevm patches
#                        nomod - the release bundles without any additional modules (jcef)
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
#   MODULAR_SDK_PATH - specifies the path to the directory where imported modules are located.
#               By default imported modules should be located in ./modular-sdk
#   JCEF_PATH - specifies the path to the directory with JCEF binaries.
#               By default JCEF binaries should be located in ./jcef_win_x64

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
WORK_DIR=$(pwd)
WITH_IMPORT_MODULES="--with-import-modules=${MODULAR_SDK_PATH:=${WORK_DIR}/modular-sdk}"
JCEF_PATH=${JCEF_PATH:=${WORK_DIR}/jcef_win_x64}
TOOLCHAIN_VERSION=${TOOLCHAIN_VERSION:=2015}

source jb/project/tools/common.sh

function create_jbr {

  JBR_BUNDLE=jbr_${bundle_type}

  case "${bundle_type}" in
  "jcef" | "dcevm" | "nomod" | "fd")
    JBR_BASE_NAME=jbr_${bundle_type}-${JBSDK_VERSION}
    ;;
  *)
    echo "***ERR*** bundle was not specified" && do_exit 1
    ;;
  esac
  cat modules.list > modules_tmp.list
  rm -rf ${JBR_BUNDLE}

  echo Running jlink....
  ${JSDK}/bin/jlink \
    --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
    --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output ${JBR_BUNDLE} || do_exit $?
  if [[ "${bundle_type}" == *jcef* ]] || [[ "${bundle_type}" == *dcevm* ]] || [[ "${bundle_type}" == fd ]]
  then
    rsync -av ${JCEF_PATH}/ ${JBR_BUNDLE}/bin --exclude="modular-sdk" || do_exit $?
  fi
  echo Modifying release info ...
  cat ${JSDK}/release | tr -d '\r' | grep -v 'JAVA_VERSION' | grep -v 'MODULES' >> ${JBR_BUNDLE}/release
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=windows-x86_64-normal-server-release
JBSDK=${JBRSDK_BASE_NAME}-windows-x64-b${build_number}
case "$bundle_type" in
  "jcef")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    ;;
  "dcevm")
    HEAD_REVISION=$(git rev-parse HEAD)
    git am jb/project/tools/patches/dcevm/*.patch || do_exit $?
    do_reset_dcevm=1
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    ;;
  "nomod")
    WITH_IMPORT_MODULES=""
    ;;
  "fd")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=windows-x86_64-normal-server-fastdebug
    JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}-fastdebug
    JBSDK=${JBRSDK_BASE_NAME}-windows-x64-fastdebug-b${build_number}
    ;;
  *)
    echo "***ERR*** bundle was not specified" && do_exit 1
    ;;
esac

sh ./configure \
  --disable-warnings-as-errors \
  $WITH_DEBUG_LEVEL \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-jvm-features=shenandoahgc \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  $WITH_IMPORT_MODULES \
  --with-toolchain-version=${TOOLCHAIN_VERSION} \
  --with-boot-jdk=${BOOT_JDK} \
  --disable-ccache \
  --enable-cds=yes || do_exit $?

if [ "${bundle_type}" == "jcef" ]; then
  make LOG=info clean images test-image CONF=$RELEASE_NAME || do_exit $?
else
  make LOG=info clean images CONF=$RELEASE_NAME || do_exit $?
fi

JSDK=build/$RELEASE_NAME/images/jdk
BASE_DIR=build/$RELEASE_NAME/images
JBRSDK_BUNDLE=jbrsdk

rm -rf ${BASE_DIR}/${JBRSDK_BUNDLE} && rsync -a --exclude demo --exclude sample ${JSDK}/ ${JBRSDK_BUNDLE} || do_exit $?
if [[ "${bundle_type}" == *jcef* ]] || [[ "${bundle_type}" == *dcevm* ]] || [[ "${bundle_type}" == fd ]]
then
  rsync -av ${JCEF_PATH}/ ${JBRSDK_BUNDLE}/bin --exclude='modular-sdk' || do_exit $?
  sed 's/JBR/JBRSDK/g' ${JSDK}/release > release
  mv release ${JBRSDK_BUNDLE}/release
fi

create_jbr || do_exit $?

do_exit 0