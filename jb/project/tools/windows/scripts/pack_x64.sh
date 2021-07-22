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
  __bundle_name=$1

  fastdebug_infix=""
  [ "$bundle_type" == "fd" ] && [ "$__bundle_name" == "jbrsdk" ] && fastdebug_infix="fastdebug-"

  if [ -z "${bundle_type}" ]; then
    JBR_BUNDLE=$__bundle_name
  else
    JBR_BUNDLE="${__bundle_name}_${bundle_type}"
    [ -d ${BASE_DIR}/$__bundle_name ] && rm -rf ${BASE_DIR}/$__bundle_name
    cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/$__bundle_name
  fi
  if [ "${bundle_type}" == "fd" ]; then
    JBR_BASE_NAME=${__bundle_name}-${JBSDK_VERSION}
  else
    JBR_BASE_NAME=${JBR_BUNDLE}-${JBSDK_VERSION}
  fi

  JBR=$JBR_BASE_NAME-windows-x64-${fastdebug_infix}b$build_number
  echo Creating $JBR.tar.gz ...

  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR $__bundle_name || do_exit $?
}

[ "$bundle_type" == "nomod" ] && bundle_type=""

JBRSDK_BUNDLE=jbrsdk
RELEASE_NAME=windows-x86_64-server-release
IMAGES_DIR=build/$RELEASE_NAME/images
JBSDK=$JBRSDK_BASE_NAME-windows-x64-b$build_number
BASE_DIR=.

pack_jbr jbr
pack_jbr jbrsdk

if [ -z "$bundle_type" ]; then
  JBRSDK_TEST=$JBRSDK_BASE_NAME-windows-test-x64-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
fi