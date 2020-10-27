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

source jb/project/tools/common/scripts/common.sh

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}

[ -z "$bundle_type" ] && (git apply -p0 < jb/project/tools/patches/exclude_jcef_module.patch || exit $?)

sh configure \
  --disable-warnings-as-errors \
  --with-debug-level=release \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  --with-boot-jdk=${BOOT_JDK} \
  --enable-cds=yes || exit $?
make clean CONF=linux-aarch64-server-release || exit $?
make images CONF=linux-aarch64-server-release test-image || exit $?

JBSDK=${JBRSDK_BASE_NAME}-linux-aarch64-b${build_number}
BASE_DIR=build/linux-aarch64-server-release/images
JSDK=${BASE_DIR}/jdk
JBRSDK_BUNDLE=jbrsdk

echo Fixing permissions
chmod -R a+r $JSDK

rm -rf $BASE_DIR/$JBRSDK_BUNDLE
cp -r $JSDK $BASE_DIR/$JBRSDK_BUNDLE || exit $?

echo Creating $JBSDK.tar.gz ...
sed 's/JBR/JBRSDK/g' ${BASE_DIR}/${JBRSDK_BUNDLE}/release > release
mv release ${BASE_DIR}/${JBRSDK_BUNDLE}/release

tar -pcf $JBSDK.tar \
  --exclude=*.debuginfo --exclude=demo --exclude=sample --exclude=man \
  -C $BASE_DIR ${JBRSDK_BUNDLE} || exit $?
gzip $JBSDK.tar || exit $?

JBR_BUNDLE=jbr
JBR_BASE_NAME=jbr-$JBSDK_VERSION
rm -rf $BASE_DIR/$JBR_BUNDLE

JBR=$JBR_BASE_NAME-linux-aarch64-b$build_number
grep -v javafx modules.list | grep -v "jdk.internal.vm\|jdk.aot\|jcef" > modules.list.aarch64
echo Running jlink....
${JSDK}/bin/jlink \
  --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
  --add-modules $(xargs < modules.list.aarch64 | sed s/" "//g | sed s/,$//g) \
  --output ${BASE_DIR}/${JBR_BUNDLE} || exit $?

echo Modifying release info ...
grep -v \"^JAVA_VERSION\" ${JSDK}/release | grep -v \"^MODULES\" >> ${BASE_DIR}/${JBR_BUNDLE}/release

echo Creating $JBR.tar.gz ...
tar -pcf $JBR.tar -C $BASE_DIR ${JBR_BUNDLE} || exit $?
gzip $JBR.tar || exit $?

JBRSDK_TEST=$JBRSDK_BASE_NAME-linux-test-aarch64-b$build_number
echo Creating $JBRSDK_TEST.tar.gz ...
tar -pcf $JBRSDK_TEST.tar -C $BASE_DIR --exclude='test/jdk/demos' test || exit $?
gzip $JBRSDK_TEST.tar || exit $?