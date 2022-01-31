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

min_parameters_count=2
source jb/project/tools/common/scripts/common.sh

[ "$bundle_type" == "jcef" ] && do_maketest=1

function pack_jbr {
  __bundle_name=$1
  __arch_name=$2

  [ "$bundle_type" == "fd" ] && [ "$__arch_name" == "$JBRSDK_BUNDLE" ] && __bundle_name=$__arch_name && fastdebug_infix="fastdebug-"
  JBR=${__bundle_name}-${JBSDK_VERSION}-windows-x64-${fastdebug_infix}b${build_number}

  echo Creating $JBR.tar.gz ...

  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR $__arch_name || do_exit $?
}

[ "$bundle_type" == "nomod" ] && bundle_type=""

JBRSDK_BUNDLE=jbrsdk
RELEASE_NAME=windows-x86_64-server-release
IMAGES_DIR=build/$RELEASE_NAME/images
BASE_DIR=.

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "dcevm" ] || [ "$bundle_type" == "fd" ]; then
  jbr_name_postfix="_${bundle_type}"
fi

pack_jbr jbr${jbr_name_postfix} jbr
pack_jbr jbrsdk${jbr_name_postfix} jbrsdk

if [ $do_maketest -eq 1 ]; then
  JBRSDK_TEST=$JBRSDK_BUNDLE-$JBSDK_VERSION-windows-test-x64-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $IMAGES_DIR --exclude='test/jdk/demos' test || do_exit $?
fi