#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies major version of OpenJDK e.g. 11_0_6 (instead of dots '.' underbars "_" are used)
#   JDK_BUILD_NUMBER - specifies update release of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to be built; possible values:
#                        <empty> or nomod - the release bundles without any additional modules (jcef)
#                        jcef - the release bundles with jcef
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
# Environment variables:
#   MODULAR_SDK_PATH - specifies the path to the directory where imported modules are located.
#               By default imported modules should be located in ./modular-sdk
#   JCEF_PATH - specifies the path to the directory with JCEF binaries.
#               By default JCEF binaries should be located in ./jcef_linux_x64

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
WITH_IMPORT_MODULES="--with-import-modules=${MODULAR_SDK_PATH:=./modular-sdk}"
JCEF_PATH=${JCEF_PATH:=./jcef_linux_x64}

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
    cp -R ${JCEF_PATH}/* $BASE_DIR/$JBR_BUNDLE/lib || do_exit $?
  fi
  grep -v "^JAVA_VERSION" $JSDK/release | grep -v "^MODULES" >> $BASE_DIR/$JBR_BUNDLE/release

  echo Creating $JBR.tar.gz ...
  tar -pcf $JBR.tar -C $BASE_DIR jbr || do_exit $?
  [ -f "$JBR.tar.gz" ] && rm "$JBR.tar.gz"
  gzip $JBR.tar || do_exit $?
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=linux-x86_64-server-release
JBSDK=${JBRSDK_BASE_NAME}-linux-x64-b${build_number}
case "$bundle_type" in
  "jcef")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch
    do_reset_changes=1
    ;;
  "nomod" | "")
    bundle_type=""
    WITH_IMPORT_MODULES=""
    ;;
  "fd")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=linux-x86_64-server-fastdebug
    JBSDK=${JBRSDK_BASE_NAME}-linux-x64-fastdebug-b${build_number}
    ;;
esac

sh configure \
  --disable-warnings-as-errors \
  $WITH_DEBUG_LEVEL \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  $WITH_IMPORT_MODULES \
  --with-boot-jdk=${BOOT_JDK} \
  --enable-cds=yes || do_exit $?

make clean CONF=$RELEASE_NAME || exit $?
make images CONF=$RELEASE_NAME || do_exit $?

JSDK=build/$RELEASE_NAME/images/jdk

echo Fixing permissions
chmod -R a+r $JSDK

BASE_DIR=build/$RELEASE_NAME/images
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR/$JBRSDK_BUNDLE
cp -r $JSDK $BASE_DIR/$JBRSDK_BUNDLE || do_exit $?

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ]; then
  cp -R ${JCEF_PATH}/* $BASE_DIR/$JBRSDK_BUNDLE/lib || do_exit $?
  echo Creating $JBSDK.tar.gz ...
  sed 's/JBR/JBRSDK/g' ${BASE_DIR}/${JBRSDK_BUNDLE}/release > release
  mv release ${BASE_DIR}/${JBRSDK_BUNDLE}/release

  tar -pcf $JBSDK.tar --exclude=*.debuginfo --exclude=demo --exclude=sample --exclude=man \
    -C $BASE_DIR $JBRSDK_BUNDLE || do_exit $?
  [ -f "$JBSDK.tar.gz" ] && rm "$JBSDK.tar.gz"
  gzip $JBSDK.tar || do_exit $?
fi

create_jbr || do_exit $?

if [ "$bundle_type" == "jcef" ]; then
  make test-image CONF=$RELEASE_NAME || do_exit $?

  JBRSDK_TEST=$JBRSDK_BASE_NAME-linux-test-x64-b$build_number

  echo Creating $JBSDK_TEST.tar.gz ...
  tar -pcf $JBRSDK_TEST.tar -C $BASE_DIR --exclude='test/jdk/demos' test || do_exit $?
  [ -f "$JBRSDK_TEST.tar.gz" ] && rm "$JBRSDK_TEST.tar.gz"
  gzip $JBRSDK_TEST.tar || do_exit $?
fi

do_exit 0