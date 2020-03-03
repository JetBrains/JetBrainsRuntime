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
  rm -rf $BASE_DIR/$JBR_BUNDLE

  case "$1" in
  'lw')
    JBR_BASE_NAME=jbr_${bundle_type}_lw-$JBSDK_VERSION
    grep -v "jdk.compiler\|jdk.hotspot.agent" modules.list > modules_tmp.list
    ;;
  'jfx')
    JBR_BASE_NAME=jbr-$JBSDK_VERSION
    cat modules.list > modules_tmp.list
    ;;
  'jcef')
    JBR_BASE_NAME=jbr_${bundle_type}-$JBSDK_VERSION
    cat modules.list > modules_tmp.list
    ;;
  *)
    echo "***ERR*** bundle was not specified" && exit $?
    ;;
  esac

  JBR=$JBR_BASE_NAME-windows-x64-b$build_number

  $JSDK/bin/jlink \
    --module-path $JSDK/jmods --no-man-pages --compress=2 \
    --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output $BASE_DIR/$JBR_BUNDLE || exit $?
  cp -R jcef_win_x64/* $BASE_DIR/$JBR_BUNDLE/bin
  echo Modifying release info ...
  grep -v \"^JAVA_VERSION\" $JSDK/release | grep -v \"^MODULES\" >> $BASE_DIR/$JBR_BUNDLE/release
}

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION
WORK_DIR=$(pwd)

if [ "$bundle_type" == "jfx" ]; then
  patch -p0 < jb/project/tools/exclude_jcef_module.patch
fi

PATH="/usr/local/bin:/usr/bin:$PATH"
./configure \
  --disable-warnings-as-errors \
  --disable-debug-symbols \
  --with-target-bits=64 \
  --with-version-pre= \
  --with-version-build=$JDK_BUILD_NUMBER  \
  --with-version-opt=b$build_number \
  --with-import-modules=$WORK_DIR/modular-sdk \
  --with-toolchain-version=2015 \
  --with-boot-jdk=$BOOT_JDK \
  --disable-ccache \
  --enable-cds=yes || exit 1
make clean CONF=windows-x86_64-normal-server-release || exit 1
make images CONF=windows-x86_64-normal-server-release || exit 1
make -d test-image || exit 1

JSDK=build/windows-x86_64-normal-server-release/images/jdk
JBSDK=$JBRSDK_BASE_NAME-windows-x64-b$build_number

BASE_DIR=build/windows-x86_64-normal-server-release/images
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR/$JBRSDK_BUNDLE && rsync -a --exclude demo --exclude sample $JSDK/ $JBRSDK_BUNDLE || exit 1
cp -R jcef_win_x64/* $JBRSDK_BUNDLE/bin

JBR_BUNDLE=jbr
create_jbr $bundle_type
create_jbr "lw"
