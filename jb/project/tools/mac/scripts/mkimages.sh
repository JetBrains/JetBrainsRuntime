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
#               By default JCEF binaries should be located in ./jcef_mac

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
WITH_IMPORT_MODULES="--with-import-modules=${MODULAR_SDK_PATH:=./modular-sdk}"
JCEF_PATH=${JCEF_PATH:=./jcef_mac}
MAJOR_JBSDK_VERSION=$(echo $JBSDK_VERSION_WITH_DOTS | awk -F "." '{print $1}')

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

  JRE_CONTENTS=${BASE_DIR}/${JBR_BUNDLE}/Contents
  JRE_HOME=${JRE_CONTENTS}/Home
  if [ -d "${JRE_CONTENTS}" ]; then
    rm -rf ${JRE_CONTENTS}
  fi
  mkdir -p ${JRE_CONTENTS}

  JBR=${JBR_BASE_NAME}-osx-x64-b${build_number}

  echo Running jlink....
  ${BASE_DIR}/$JBRSDK_BUNDLE/Contents/Home/bin/jlink \
      --module-path ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/jmods --no-man-pages --compress=2 \
      --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output ${JRE_HOME}  || do_exit $?
  grep -v "^JAVA_VERSION" ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release | grep -v "^MODULES" >> ${JRE_HOME}/release
  cp -R ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/MacOS ${JRE_CONTENTS}
  cp ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Info.plist ${JRE_CONTENTS}

  rm -rf ${JRE_CONTENTS}/Frameworks || do_exit $?
  [ ! -z "${bundle_type}" ] && (cp -a ${JCEF_PATH}/Frameworks ${JRE_CONTENTS} || do_exit $?)

  echo Creating ${JBR}.tar.gz ...
  [ ! -z "${bundle_type}" ] && cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
  COPYFILE_DISABLE=1 tar -pczf ${JBR}.tar.gz --exclude='*.dSYM' --exclude='man' -C ${BASE_DIR} jbr || do_exit $?
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WITH_DEBUG_LEVEL="--with-debug-level=release"
RELEASE_NAME=macosx-x86_64-server-release
JBSDK=${JBRSDK_BASE_NAME}-osx-x64-b${build_number}
case "$bundle_type" in
  "jcef")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    ;;
  "nomod" | "")
    bundle_type=""
    WITH_IMPORT_MODULES=""
    ;;
  "fd")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    RELEASE_NAME=macosx-x86_64-server-fastdebug
    JBSDK=${JBRSDK_BASE_NAME}-osx-x64-fastdebug-b${build_number}
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
  --with-boot-jdk=`/usr/libexec/java_home -v 14` \
  --enable-cds=yes || do_exit $?

make clean CONF=$RELEASE_NAME || do_exit $?
make images CONF=$RELEASE_NAME || do_exit $?

JSDK=build/$RELEASE_NAME/images/jdk-bundle

BASE_DIR=jre
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR
mkdir $BASE_DIR || do_exit $?
cp -a $JSDK/jdk-$MAJOR_JBSDK_VERSION.jdk $BASE_DIR/$JBRSDK_BUNDLE || do_exit $?

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "fd" ]; then
  cp -a ${JCEF_PATH}/Frameworks $BASE_DIR/$JBRSDK_BUNDLE/Contents/
  echo Creating $JBSDK.tar.gz ...
  sed 's/JBR/JBRSDK/g' ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release > release
  mv release ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release
  [ -f "$JBSDK.tar.gz" ] && rm "$JBSDK.tar.gz"
  COPYFILE_DISABLE=1 tar -pczf $JBSDK.tar.gz -C $BASE_DIR \
    --exclude='._*' --exclude='.DS_Store' --exclude='*~' \
    --exclude='Home/demo' --exclude='Home/man' --exclude='Home/sample' \
    $JBRSDK_BUNDLE || do_exit $?
fi

create_jbr || do_exit $?

if [ "$bundle_type" == "jcef" ]; then
  make test-image CONF=$RELEASE_NAME || do_exit $?

  JBRSDK_TEST=$JBRSDK_BASE_NAME-osx-test-x64-b$build_number

  echo Creating $JBRSDK_TEST.tar.gz ...
  [ -f "$JBRSDK_TEST.tar.gz" ] && rm "$JBRSDK_TEST.tar.gz"
  COPYFILE_DISABLE=1 tar -pczf $JBRSDK_TEST.tar.gz -C build/${RELEASE_NAME}/images \
    --exclude='test/jdk/demos' test || do_exit $?
fi

do_exit 0