#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies major version of OpenJDK e.g. 11_0_6 (instead of dots '.' underbars "_" are used)
#   JDK_BUILD_NUMBER - specifies udate release of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to be built; possible values:
#                        <empty> or nomod - the bundles without any additional modules (jcef)
#                        jcef - the bundles with jcef
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

source jb/project/tools/common/scripts/common.sh

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4

function pack_jbr {

  if [ -z "${bundle_type}" ]; then
    JBR_BUNDLE=jbr
  else
    JBR_BUNDLE=jbr_${bundle_type}
    [ -d ${BASE_DIR}/jbr ] && rm -rf ${BASE_DIR}/jbr
    cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
  fi
  JBR_BASE_NAME=${JBR_BUNDLE}-${JBSDK_VERSION}

  JBR=$JBR_BASE_NAME-windows-x64-b$build_number
  echo Creating $JBR.tar.gz ...

  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR jbr || do_exit $?
}

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION
JBR_BASE_NAME=jbr-$JBSDK_VERSION
RELEASE_NAME=windows-x86_64-server-release
JBSDK=${JBRSDK_BASE_NAME}-osx-x64-b${build_number}
case "$bundle_type" in
  "nomod" | "")
    bundle_type=""
    ;;
  "fd")
    RELEASE_NAME=macosx-x86_64-server-fastdebug
    JBSDK=${JBRSDK_BASE_NAME}-osx-x64-fastdebug-b${build_number}
    ;;
esac

IMAGES_DIR=build/$RELEASE_NAME/images
JSDK=$IMAGES_DIR/jdk
JBSDK=$JBRSDK_BASE_NAME-windows-x64-b$build_number
BASE_DIR=.

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ]; then
  JBRSDK_BUNDLE=jbrsdk
  echo Creating $JBSDK.tar.gz ...
  [ -f "$JBSDK.tar.gz" ] && rm "$JBSDK.tar.gz"
  /usr/bin/tar -czf $JBSDK.tar.gz $JBRSDK_BUNDLE || do_exit $?
fi

pack_jbr $bundle_type

if [ -z "$bundle_type" ]; then
  JBRSDK_TEST=$JBRSDK_BASE_NAME-windows-test-x64-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
fi