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
# Environment variables:
#   MODULAR_SDK_PATH - specifies the path to the directory where imported modules are located.
#               By default imported modules should be located in ./modular-sdk
#   JCEF_PATH - specifies the path to the directory where JCEF binaries are located.
#               By default imported modules should be located in ./jcef_win_x64

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3

JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
WITH_IMPORT_MODULES="--with-import-modules=${MODULAR_SDK_PATH:=${WORK_DIR}/modular-sdk}"
JCEF_PATH=${JCEF_PATH:=./jcef_win_x64}

source jb/project/tools/common.sh

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WORK_DIR=$(pwd)
WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
RELEASE_NAME=windows-x86_64-normal-server-fastdebug
PATH="/usr/local/bin:/usr/bin:${PATH}"
./configure \
  --disable-warnings-as-errors \
  $WITH_DEBUG_LEVEL \
  --with-target-bits=64 \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  $WITH_IMPORT_MODULES \
  --with-toolchain-version=2015 \
  --with-boot-jdk=${BOOT_JDK} \
  --disable-ccache \
  --enable-cds=yes || exit 1

make LOG=info clean images CONF=$RELEASE_NAME || exit 1

JSDK=build/$RELEASE_NAME/images/jdk
JBSDK=${JBRSDK_BASE_NAME}-windows-x64-b${build_number}
BASE_DIR=build/$RELEASE_NAME/images
JBRSDK_BUNDLE=jbrsdk

rm -rf ${BASE_DIR}/${JBRSDK_BUNDLE} && rsync -a --exclude demo --exclude sample ${JSDK}/ ${JBRSDK_BUNDLE} || exit 1
cp -R ${JCEF_PATH}/* ${JBRSDK_BUNDLE}/bin
sed 's/JBR/JBRSDK/g' ${JSDK}/release > release
mv release ${JBRSDK_BUNDLE}/release

JBR_BUNDLE=jbr
JBR_BASE_NAME=jbr-$JBSDK_VERSION
rm -rf ${JBR_BUNDLE}

${JSDK}/bin/jlink \
  --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
  --add-modules $(xargs < modules.list | sed s/" "//g) --output ${JBR_BUNDLE} || exit $?
cp -R ${JCEF_PATH}/* ${JBR_BUNDLE}/bin
echo Modifying release info ...
cat ${JSDK}/release | tr -d '\r' | grep -v 'JAVA_VERSION' | grep -v 'MODULES' >> ${JBR_BUNDLE}/release
