#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies the current version of OpenJDK e.g. 11_0_6
#   JDK_BUILD_NUMBER - specifies the number of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to bu built; possible values:
#                        jcef - the bundles 1) jbr with jcef+javafx, 2) jbrsdk and 3) test will be created
#                        jfx  - the bundle 1) jbr with javafx only will be created
#
# jbrsdk-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
# jbr-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
#
# $ ./java --version
# openjdk 11.0.6 2020-01-14
# OpenJDK Runtime Environment (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number})
# OpenJDK 64-Bit Server VM (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number}, mixed mode)
#

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4

function pack_jbr {

  case "$1" in
  "${bundle_type}_lw")
    JBR_BASE_NAME=jbr_${bundle_type}_lw-${JBSDK_VERSION}
    ;;
  "jfx")
    JBR_BASE_NAME=jbr_${bundle_type}-${JBSDK_VERSION}
    ;;
  "jcef")
    JBR_BASE_NAME=jbr-${JBSDK_VERSION}
    ;;
  *)
    echo "***ERR*** bundle was not specified" && exit $?
    ;;
  esac

  JBR=$JBR_BASE_NAME-windows-x64-b$build_number
  echo Creating $JBR.tar.gz ...
  rm -rf ${BASE_DIR}/jbr
  cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr

  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR jbr || exit 1
}

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION
JBR_BASE_NAME=jbr-$JBSDK_VERSION

IMAGES_DIR=build/windows-x86_64-normal-server-release/images
JSDK=$IMAGES_DIR/jdk
JBSDK=$JBRSDK_BASE_NAME-windows-x64-b$build_number
BASE_DIR=.

if [ "$bundle_type" == "jcef" ]; then
  JBRSDK_BUNDLE=jbrsdk
  echo Creating $JBSDK.tar.gz ...
  /usr/bin/tar -czf $JBSDK.tar.gz $JBRSDK_BUNDLE || exit 1
fi

JBR_BUNDLE=jbr_${bundle_type}
pack_jbr $bundle_type
JBR_BUNDLE=jbr_${bundle_type}_lw
pack_jbr ${bundle_type}_lw

if [ "$bundle_type" == "jcef" ]; then
  JBRSDK_TEST=$JBRSDK_BASE_NAME-windows-test-x64-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || exit 1
fi