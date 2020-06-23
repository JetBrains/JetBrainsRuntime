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
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')

source jb/project/tools/common.sh

function create_jbr {

  case "$1" in
  "${bundle_type}_lw")
    grep -v "jdk.compiler\|jdk.hotspot.agent" modules.list > modules_tmp.list
    ;;
  "jfx" | "jcef" | "jfx_jcef" | "dcevm" | "nomod")
    cat modules.list > modules_tmp.list
    ;;
  *)
    echo "***ERR*** bundle was not specified" && exit 1
    ;;
  esac
  rm -rf ${JBR_BUNDLE}

  ${JSDK}/bin/jlink \
    --module-path ${JSDK}/jmods --no-man-pages --compress=2 \
    --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output ${JBR_BUNDLE} || exit $?
  if [[ "${bundle_type}" == *jcef* ]] || [[ "${bundle_type}" == *dcevm* ]]
  then
    cp -R jcef_win_x64/* ${JBR_BUNDLE}/bin
  fi
  echo Modifying release info ...
  cat ${JSDK}/release | tr -d '\r' | grep -v 'JAVA_VERSION' | grep -v 'MODULES' >> ${JBR_BUNDLE}/release
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WORK_DIR=$(pwd)

WITH_DEBUG_LEVEL="--with-debug-level=release"
WITH_IMPORT_MODULES="--with-import-modules=${WORK_DIR}/modular-sdk"
RELEASE_NAME=windows-x86_64-normal-server-release
git checkout -- modules.list src/java.desktop/share/classes/module-info.java
case "$bundle_type" in
  "jfx")
    echo "Excluding jcef modules"
    git apply -p0 < jb/project/tools/patches/exclude_jcef_module.patch
    ;;
  "jcef")
    echo "Excluding jfx modules"
    git apply -p0 < jb/project/tools/patches/exclude_jfx_module.patch
    ;;
  "dcevm")
    echo "Adding dcevm patches"
    git am jb/project/tools/patches/dcevm/*.patch
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug --with-external-symbols-in-bundles:public"
    RELEASE_NAME=windows-x86_64-normal-server-fastdebug
    ;;
  "nomod")
    git apply -p0 < jb/project/tools/patches/exclude_jcef_module.patch
    git apply -p0 < jb/project/tools/patches/exclude_jfx_module.patch
    WITH_IMPORT_MODULES=""
    ;;
esac

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

if [ "$bundle_type" == "jfx_jcef" ]; then
  make LOG=info clean images CONF=$RELEASE_NAME test-image || exit 1
else
  make LOG=info clean images CONF=$RELEASE_NAME || exit 1
fi

JSDK=build/$RELEASE_NAME/images/jdk
if [[ "$bundle_type" == *jcef* ]]; then
  JBSDK=${JBRSDK_BASE_NAME}-windows-x64-b${build_number}
fi
BASE_DIR=build/$RELEASE_NAME/images
JBRSDK_BUNDLE=jbrsdk

rm -rf ${BASE_DIR}/${JBRSDK_BUNDLE} && rsync -a --exclude demo --exclude sample ${JSDK}/ ${JBRSDK_BUNDLE} || exit 1
cp -R jcef_win_x64/* ${JBRSDK_BUNDLE}/bin
sed 's/JBR/JBRSDK/g' ${JSDK}/release > release
mv release ${JBRSDK_BUNDLE}/release

JBR_BUNDLE=jbr_${bundle_type}
create_jbr ${bundle_type}
