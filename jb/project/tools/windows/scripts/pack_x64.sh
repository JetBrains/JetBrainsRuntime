#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies the current version of OpenJDK e.g. 11_0_6
#   JDK_BUILD_NUMBER - specifies the number of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#                        jcef - the bundles with jcef
#                        empty - the bundles without jcef
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

  if [ -z "${bundle_type}" ]; then
    JBR_BUNDLE=jbr
  else
    JBR_BUNDLE=jbr_${bundle_type}
    rm -rf ${BASE_DIR}/jbr
    cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
  fi
  JBR_BASE_NAME=${JBR_BUNDLE}-${JBSDK_VERSION}

  JBR=$JBR_BASE_NAME-windows-x64-b$build_number
  echo Creating $JBR.tar.gz ...

  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR jbr || exit 1
}

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION
JBR_BASE_NAME=jbr-$JBSDK_VERSION

IMAGES_DIR=build/windows-x86_64-server-release/images
JSDK=$IMAGES_DIR/jdk
JBSDK=$JBRSDK_BASE_NAME-windows-x64-b$build_number
BASE_DIR=.

if [ "$bundle_type" == "jcef" ]; then
  JBRSDK_BUNDLE=jbrsdk
  echo Creating $JBSDK.tar.gz ...
  /usr/bin/tar -czf $JBSDK.tar.gz $JBRSDK_BUNDLE || exit 1
fi

pack_jbr $bundle_type

if [ "$bundle_type" == "jcef" ]; then
  JBRSDK_TEST=$JBRSDK_BASE_NAME-windows-test-x64-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || exit 1
fi