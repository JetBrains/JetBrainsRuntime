#!/bin/bash -x

# The following parameter must be specified:
#   build_number - specifies the number of JetBrainsRuntime build
#

source jb/project/tools/common/scripts/common.sh

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}

sh configure \
  --with-debug-level=release \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-jvm-features=shenandoahgc \
  --with-version-pre= \
  --with-version-build="${JDK_BUILD_NUMBER}" \
  --with-version-opt=b${build_number} \
  --with-boot-jdk=${BOOT_JDK} \
  --enable-cds=yes \
  $REPRODUCIBLE_BUILD_OPTS \
  || exit $?
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

# NB: --sort=name requires tar1.28
tar $REPRODUCIBLE_TAR_OPTS --sort=name -pcf $JBSDK.tar \
  --exclude=*.debuginfo --exclude=demo --exclude=sample --exclude=man \
  -C $BASE_DIR ${JBRSDK_BUNDLE} || exit $?
touch -c -d @$SOURCE_DATE_EPOCH $JBRSDK.tar
gzip $JBSDK.tar || exit $?

JBR_BUNDLE=jbr
JBR_BASE_NAME=jbr-$JBSDK_VERSION
rm -rf $BASE_DIR/$JBR_BUNDLE

JBR=$JBR_BASE_NAME-linux-aarch64-b$build_number
grep -v javafx jb/project/tools/common/modules.list | grep -v "jdk.internal.vm\|jdk.aot\|jcef" > modules.list.aarch64
echo Running jlink....
${JSDK}/bin/jlink \
  --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
  --add-modules $(xargs < modules.list.aarch64 | sed s/" "//g | sed s/',$'//g) \
  --output ${BASE_DIR}/${JBR_BUNDLE} || exit $?

echo Modifying release info ...
grep -v \"^JAVA_VERSION\" ${JSDK}/release | grep -v \"^MODULES\" >> ${BASE_DIR}/${JBR_BUNDLE}/release

echo Creating $JBR.tar.gz ...
tar $REPRODUCIBLE_TAR_OPTS --sort=name -pcf $JBR.tar -C $BASE_DIR ${JBR_BUNDLE} || exit $?
touch -c -d @$SOURCE_DATE_EPOCH $JBR.tar
gzip $JBR.tar || exit $?

JBRSDK_TEST=$JBRSDK_BASE_NAME-linux-test-aarch64-b$build_number
echo Creating $JBRSDK_TEST.tar.gz ...
tar -pcf $JBRSDK_TEST.tar -C $BASE_DIR --exclude='test/jdk/demos' test || exit $?
gzip $JBRSDK_TEST.tar || exit $?
