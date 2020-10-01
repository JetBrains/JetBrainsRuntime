#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies the current version of OpenJDK e.g. 11_0_6
#   JDK_BUILD_NUMBER - specifies the number of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
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

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION
JBR_BASE_NAME=jbr-$JBSDK_VERSION

IMAGES_DIR=build/windows-x86-server-release/images
JSDK=$IMAGES_DIR/jdk
JBSDK=$JBRSDK_BASE_NAME-windows-x86-b$build_number
BASE_DIR=.

JBRSDK_BUNDLE=jbrsdk
echo Creating $JBSDK.tar.gz ...
/usr/bin/tar -czf $JBSDK.tar.gz $JBRSDK_BUNDLE || exit 1

JBR_BUNDLE=jbr
JBR_BASE_NAME=jbr-${JBSDK_VERSION}

JBR=$JBR_BASE_NAME-windows-x86-b$build_number
echo Creating $JBR.tar.gz ...
/usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR ${JBR_BUNDLE} || exit 1

JBRSDK_TEST=$JBRSDK_BASE_NAME-windows-test-x86-b$build_number
echo Creating $JBRSDK_TEST.tar.gz ...
/usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || exit 1
