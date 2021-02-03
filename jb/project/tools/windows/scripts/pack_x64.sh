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

source jb/project/tools/common.sh

function pack_jbr {

  JBR_BUNDLE=jbr_${bundle_type}

  case "${bundle_type}" in
  "jcef" | "dcevm" | "nomod" | "fd")
    JBR_BASE_NAME=jbr_${bundle_type}-${JBSDK_VERSION}
    ;;
  *)
    echo "***ERR*** bundle was not specified" && do_exit 1
    ;;
  esac

  JBR=$JBR_BASE_NAME-windows-x64-b$build_number
  echo Creating $JBR.tar.gz ...
  chmod -R ug+rwx,o+rx ${BASE_DIR}/${JBR_BUNDLE}
  cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR jbr || do_exit $?
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=windows-x86_64-normal-server-release
JBSDK=${JBRSDK_BASE_NAME}-windows-x64-b${build_number}
case "$bundle_type" in
  "fd")
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=windows-x86_64-normal-server-fastdebug
    JBSDK=${JBRSDK_BASE_NAME}-windows-x64-fastdebug-b${build_number}
    ;;
esac

IMAGES_DIR=build/$RELEASE_NAME/images
JSDK=$IMAGES_DIR/jdk
BASE_DIR=.

if [ "${bundle_type}" == "jcef" ] || [ "${bundle_type}" == "fd" ]; then
  JBRSDK_BUNDLE=jbrsdk
  echo Creating $JBSDK.tar.gz ...
  [ -f "$JBSDK.tar.gz" ] && rm "$JBSDK.tar.gz"
  /usr/bin/tar -czf $JBSDK.tar.gz $JBRSDK_BUNDLE || do_exit $?
fi

pack_jbr $bundle_type

if [ "$bundle_type" == "jcef" ]; then
  JBRSDK_TEST=$JBRSDK_BASE_NAME-windows-test-x64-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
fi