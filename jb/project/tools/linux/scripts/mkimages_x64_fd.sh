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

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}

sh configure \
  --disable-warnings-as-errors \
  --with-debug-level=fastdebug \
  --with-version-build=$JDK_BUILD_NUMBER \
  --with-version-pre= \
  --with-version-opt=b$build_number \
  --with-import-modules=./modular-sdk \
  --enable-cds=yes || exit $?
make clean CONF=linux-x86_64-normal-server-fastdebug || exit $?
make images CONF=linux-x86_64-normal-server-fastdebug || exit $?

JBSDK=${JBRSDK_BASE_NAME}-linux-x64-fastdebug-b${build_number}
BASE_DIR=build/linux-x86_64-normal-server-fastdebug/images
JSDK=${BASE_DIR}/jdk
JBRSDK_BUNDLE=jbrsdk

echo Fixing permissions
chmod -R a+r $JSDK

rm -rf $BASE_DIR/$JBRSDK_BUNDLE
cp -r $JSDK $BASE_DIR/$JBRSDK_BUNDLE || exit $?
cp -R jcef_linux_x64/* $BASE_DIR/$JBRSDK_BUNDLE/lib || exit $?

echo Creating $JBSDK.tar.gz ...
tar -pcf $JBSDK.tar \
  --exclude=*.debuginfo --exclude=demo --exclude=sample --exclude=man \
  -C $BASE_DIR ${JBRSDK_BUNDLE} || exit $?
gzip $JBSDK.tar || exit $?

JBR_BUNDLE=jbr
JBR_BASE_NAME=jbr-$JBSDK_VERSION
rm -rf $BASE_DIR/$JBR_BUNDLE

JBR=$JBR_BASE_NAME-linux-x64-fastdebug-b$build_number
echo Running jlink....
${JSDK}/bin/jlink \
  --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
  --add-modules $(xargs < modules.list | sed s/" "//g | sed s/,$//g) \
  --output ${BASE_DIR}/${JBR_BUNDLE} || exit $?
cp -R jcef_linux_x64/* $BASE_DIR/$JBR_BUNDLE/lib || exit $?

echo Modifying release info ...
grep -v \"^JAVA_VERSION\" ${JSDK}/release | grep -v \"^MODULES\" >> ${BASE_DIR}/${JBR_BUNDLE}/release

echo Creating $JBR.tar.gz ...
tar -czf $JBR.tar -C $BASE_DIR ${JBR_BUNDLE} || exit $?
gzip $JBR.tar || exit $?
