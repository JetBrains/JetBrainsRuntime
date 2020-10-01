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

JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')

source jb/project/tools/common.sh

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WORK_DIR=$(pwd)

[ -z "$bundle_type" ] && (git apply -p0 < jb/project/tools/patches/exclude_jcef_module.patch || exit $?)

PATH="/usr/local/bin:/usr/bin:${PATH}"
./configure \
  --disable-warnings-as-errors \
  --with-target-bits=32 \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  --with-toolchain-version=${TOOLCHAIN_VERSION} \
  --with-boot-jdk=${BOOT_JDK} \
  --disable-ccache \
  --enable-cds=yes || exit 1
make clean CONF=windows-x86-server-release || exit 1
make LOG=info images CONF=windows-x86-server-release test-image || exit 1

JBSDK=${JBRSDK_BASE_NAME}-windows-x86-b${build_number}
BASE_DIR=build/windows-x86-server-release/images
JSDK=${BASE_DIR}/jdk
JBRSDK_BUNDLE=jbrsdk

rm -rf ${BASE_DIR}/${JBRSDK_BUNDLE} && rsync -a --exclude demo --exclude sample ${JSDK}/ ${JBRSDK_BUNDLE} || exit 1
sed 's/JBR/JBRSDK/g' ${JSDK}/release > release
mv release ${JBRSDK_BUNDLE}/release

JBR_BUNDLE=jbr
rm -rf ${JBR_BUNDLE}
grep -v javafx modules.list | grep -v "jdk.internal.vm\|jdk.aot\|jcef" > modules.list.x86
${JSDK}/bin/jlink \
  --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
  --add-modules $(xargs < modules.list.x86 | sed s/" "//g) --output ${JBR_BUNDLE} || exit $?

echo Modifying release info ...
#grep -v \"^JAVA_VERSION\" ${JSDK}/release | grep -v \"^MODULES\" >> ${JBR_BUNDLE}/release
