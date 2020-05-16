#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies the current version of OpenJDK e.g. 11_0_6
#   JDK_BUILD_NUMBER - specifies the number of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to bu built; possible values:
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
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')

source jb/project/tools/common.sh

function create_jbr {

  if [ -z "${bundle_type}" ]; then
    JBR_BUNDLE=jbr
  else
    JBR_BUNDLE=jbr_${bundle_type}
  fi
  JBR_BASE_NAME=${JBR_BUNDLE}-${JBSDK_VERSION}
  cat modules.list > modules_tmp.list
  rm -rf ${BASE_DIR}/jbr
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}

  JBR=$JBR_BASE_NAME-linux-x64-b$build_number

  echo Running jlink....
  $JSDK/bin/jlink \
    --module-path $JSDK/jmods --no-man-pages --compress=2 \
    --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output $BASE_DIR/$JBR_BUNDLE

  if [ ! -z "${bundle_type}" ]; then
    cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
    cp -R jcef_linux_x64/* $BASE_DIR/$JBR_BUNDLE/lib || exit $?
  fi
  grep -v "^JAVA_VERSION" $JSDK/release | grep -v "^MODULES" >> $BASE_DIR/$JBR_BUNDLE/release

  echo Creating $JBR.tar.gz ...
  tar -pcf $JBR.tar -C $BASE_DIR jbr || exit $?
  gzip $JBR.tar || exit $?
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}
}

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION

git checkout -- modules.list
git checkout -- src/java.desktop/share/classes/module-info.java
[ -z "$bundle_type" ] && git apply -p0 < jb/project/tools/exclude_jcef_module.patch

sh configure \
  --disable-warnings-as-errors \
  --with-debug-level=release \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  --with-import-modules=./modular-sdk \
  --with-boot-jdk=${BOOT_JDK} \
  --enable-cds=yes || exit $?

make images CONF=linux-x86_64-server-release || exit $?

JSDK=build/linux-x86_64-server-release/images/jdk
JBSDK=$JBRSDK_BASE_NAME-linux-x64-b$build_number

echo Fixing permissions
chmod -R a+r $JSDK

BASE_DIR=build/linux-x86_64-server-release/images
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR/$JBRSDK_BUNDLE
cp -r $JSDK $BASE_DIR/$JBRSDK_BUNDLE || exit $?

if [[ "$bundle_type" == *jcef* ]]; then
  cp -R jcef_linux_x64/* $BASE_DIR/$JBRSDK_BUNDLE/lib || exit $?
fi
echo Creating $JBSDK.tar.gz ...
sed 's/JBR/JBRSDK/g' ${BASE_DIR}/${JBRSDK_BUNDLE}/release > release
mv release ${BASE_DIR}/${JBRSDK_BUNDLE}/release

tar -pcf $JBSDK.tar --exclude=*.debuginfo --exclude=demo --exclude=sample --exclude=man \
  -C $BASE_DIR $JBRSDK_BUNDLE || exit $?
gzip $JBSDK.tar || exit $?

create_jbr || exit $?

make test-image || exit $?

JBRSDK_TEST=$JBRSDK_BASE_NAME-linux-test-x64-b$build_number

echo Creating $JBSDK_TEST.tar.gz ...
tar -pcf $JBRSDK_TEST.tar -C $BASE_DIR --exclude='test/jdk/demos' test || exit $?
gzip $JBRSDK_TEST.tar || exit $?
