#!/bin/bash

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
bundle_type=$4

function create_jbr {

  case "$1" in
  "${bundle_type}_lw")
    JBR_BASE_NAME=jbr_${bundle_type}_lw-${JBSDK_VERSION}
    grep -v "jdk.compiler\|jdk.hotspot.agent" modules.list > modules_tmp.list
    ;;
  "jfx")
    JBR_BASE_NAME=jbr_${bundle_type}-${JBSDK_VERSION}
    cat modules.list > modules_tmp.list
    ;;
  "jcef")
    JBR_BASE_NAME=jbr-${JBSDK_VERSION}
    cat modules.list > modules_tmp.list
    ;;
  *)
    echo "***ERR*** bundle was not specified" && exit $?
    ;;
  esac
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}

  JRE_CONTENTS=${BASE_DIR}/${JBR_BUNDLE}/Contents
  JRE_HOME=${JRE_CONTENTS}/Home
  if [ -d "${JRE_CONTENTS}" ]; then
    rm -rf ${JRE_CONTENTS}
  fi
  mkdir -p ${JRE_CONTENTS}

  JBR=${JBR_BASE_NAME}-osx-x64-b${build_number}

  ${BASE_DIR}/$JBRSDK_BUNDLE/Contents/Home/bin/jlink \
      --module-path ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/jmods --no-man-pages --compress=2 \
      --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output ${JRE_HOME}  || exit $?
  grep -v "^JAVA_VERSION" ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release | grep -v "^MODULES" >> ${JRE_HOME}/release
  cp -R ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/MacOS ${JRE_CONTENTS}
  cp ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Info.plist ${JRE_CONTENTS}

  if [ "${bundle_type}" == "jcef" ]; then
    rm -rf ${JRE_CONTENTS}/Frameworks || exit $?
    rm -rf ${JRE_CONTENTS}/Helpers    || exit $?
    cp -a jcef_mac/Frameworks ${JRE_CONTENTS} || exit $?
    cp -a jcef_mac/Helpers    ${JRE_CONTENTS} || exit $?
  fi

  echo Creating ${JBR}.tar.gz ...
  rm -rf ${BASE_DIR}/jbr
  cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
  COPYFILE_DISABLE=1 tar -pczf ${JBR}.tar.gz --exclude='*.dSYM' --exclude='man' -C ${BASE_DIR} jbr || exit $?
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}

if [ "${bundle_type}" == "jfx" ]; then
  git apply -p0 < jb/project/tools/exclude_jcef_module.patch
fi

sh configure \
  --disable-warnings-as-errors \
  --with-debug-level=release \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  --with-import-modules=./modular-sdk \
  --with-boot-jdk=`/usr/libexec/java_home -v 11` \
  --enable-cds=yes || exit $?

make images CONF=macosx-x86_64-normal-server-release || exit $?

JSDK=build/macosx-x86_64-normal-server-release/images/jdk-bundle
JBSDK=${JBRSDK_BASE_NAME}-osx-x64-b${build_number}

BASE_DIR=jre
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR
mkdir $BASE_DIR || exit $?
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
cp -a build/macosx-x86_64-normal-server-release/images/jdk-bundle/jdk-$JBSDK_VERSION_WITH_DOTS.jdk \
  $BASE_DIR/$JBRSDK_BUNDLE || exit $?

if [ "$bundle_type" == "jcef" ]; then
  echo Creating $JBSDK.tar.gz ...
  cp -a jcef_mac/Frameworks $BASE_DIR/$JBRSDK_BUNDLE/Contents/
  cp -a jcef_mac/Helpers    $BASE_DIR/$JBRSDK_BUNDLE/Contents/

  COPYFILE_DISABLE=1 tar -pczf $JBSDK.tar.gz -C $BASE_DIR \
    --exclude='._*' --exclude='.DS_Store' --exclude='*~' \
    --exclude='Home/demo' --exclude='Home/man' --exclude='Home/sample' \
    $JBRSDK_BUNDLE || exit $?
fi

JBR_BUNDLE=jbr_${bundle_type}
create_jbr $bundle_type

JBR_BUNDLE=jbr_${bundle_type}_lw
create_jbr "${bundle_type}_lw"

if [ "$bundle_type" == "jcef" ]; then
  make test-image || exit $?

  JBRSDK_TEST=$JBRSDK_BASE_NAME-osx-test-x64-b$build_number

  echo Creating $JBSDK_TEST.tar.gz ...
  COPYFILE_DISABLE=1 tar -pczf $JBRSDK_TEST.tar.gz -C build/macosx-x86_64-normal-server-release/images \
    --exclude='test/jdk/demos' test || exit $?
fi