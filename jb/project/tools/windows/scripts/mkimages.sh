#!/bin/bash

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies the current version of OpenJDK e.g. 11_0_6
#   JDK_BUILD_NUMBER - specifies the number of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#
# jbrsdk-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
# jbr-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
# jbrlw-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
#
# $ ./java --version
# openjdk 11.0.6 2020-01-14
# OpenJDK Runtime Environment (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number})
# OpenJDK 64-Bit Server VM (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number}, mixed mode)
#

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3

function create_jbr {
  rm -rf $BASE_DIR/$JBR_BUNDLE

  if [ ! -z "$1" ]; then
    grep -v "jdk.compiler\|jdk.hotspot.agent" modules.list > modules_tmp.list
  else
    cat modules.list > modules_tmp.list
  fi

  $JSDK/bin/jlink \
    --module-path $JSDK/jmods --no-man-pages --compress=2 \
    --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output $BASE_DIR/$JBR_BUNDLE || exit $?
  cp -R jcef_win_x64/* $BASE_DIR/$JBR_BUNDLE/bin
  echo Modifying release info ...
  grep -v \"^JAVA_VERSION\" $JSDK/release | grep -v \"^MODULES\" >> $BASE_DIR/$JBR_BUNDLE/release
}

JBRSDK_BASE_NAME=jbrsdk-$JBSDK_VERSION
JBR_BASE_NAME=jbr-$JBSDK_VERSION
JBRLW_BASE_NAME=jbrlw-$JBSDK_VERSION
WORK_DIR=$(pwd)

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
JBR=$JBR_BASE_NAME-windows-x64-b$build_number
JBSDK=$JBRSDK_BASE_NAME-windows-x64-b$build_number

BASE_DIR=build/windows-x86_64-normal-server-release/images
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR/$JBRSDK_BUNDLE && rsync -a --exclude demo --exclude sample $JSDK/ $JBRSDK_BUNDLE || exit 1
cp -R jcef_win_x64/* $JBRSDK_BUNDLE/bin

JBR_BUNDLE=jbr
create_jbr

JBR_BUNDLE=jbrlw
create_jbr "lw"
