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
  --with-boot-jdk=`/usr/libexec/java_home -v 11` \
  --enable-cds=yes || exit $?
make clean CONF=macosx-x86_64-normal-server-fastdebug || exit $?
make images CONF=macosx-x86_64-normal-server-fastdebug || exit $?

JSDK=build/macosx-x86_64-normal-server-fastdebug/images/jdk-bundle
JBSDK=${JBRSDK_BASE_NAME}-osx-x64-fastdebug-b${build_number}

BASE_DIR=jre
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR
mkdir $BASE_DIR || exit $?
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
cp -a $JSDK/jdk-$JBSDK_VERSION_WITH_DOTS.jdk $BASE_DIR/$JBRSDK_BUNDLE || exit $?

echo Creating $JBSDK.tar.gz ...
cp -a jcef_mac/Frameworks $BASE_DIR/$JBRSDK_BUNDLE/Contents/
cp -a jcef_mac/Helpers    $BASE_DIR/$JBRSDK_BUNDLE/Contents

COPYFILE_DISABLE=1 \
  tar -pczf ${JBSDK}.tar.gz -C ${BASE_DIR} \
    --exclude='._*' --exclude='.DS_Store' --exclude='*~' \
    --exclude='Home/demo' --exclude='Home/man' --exclude='Home/sample' \
    ${JBRSDK_BUNDLE} || exit $?

JBR_BUNDLE=jbr
JRE_CONTENTS=$BASE_DIR/$JBR_BUNDLE/Contents
JRE_HOME=$JRE_CONTENTS/Home
      JBR_BASE_NAME=jbr-$JBSDK_VERSION

mkdir -p $JRE_CONTENTS

if [ -d "$JRE_HOME" ]; then
  rm -rf $JRE_HOME
fi

JBR=${JBR_BASE_NAME}-osx-x64-fastdebug-b${build_number}

$BASE_DIR/$JBRSDK_BUNDLE/Contents/Home/bin/jlink \
    --module-path $BASE_DIR/$JBRSDK_BUNDLE/Contents/Home/jmods --no-man-pages --compress=2 \
    --add-modules $(xargs < modules.list | sed s/" "//g) --output $JRE_HOME  || exit $?
grep -v "^JAVA_VERSION" $BASE_DIR/$JBRSDK_BUNDLE/Contents/Home/release | grep -v "^MODULES" >> $JRE_HOME/release
cp -R $BASE_DIR/$JBRSDK_BUNDLE/Contents/MacOS $JRE_CONTENTS
cp $BASE_DIR/$JBRSDK_BUNDLE/Contents/Info.plist $JRE_CONTENTS
cp -a jcef_mac/Frameworks ${JRE_CONTENTS} || exit $?
cp -a jcef_mac/Helpers    ${JRE_CONTENTS} || exit $?


echo Creating $JBR.tar.gz ...
COPYFILE_DISABLE=1 tar -pczf $JBR.tar.gz --exclude='*.dSYM' --exclude='man' -C $BASE_DIR $JBR_BUNDLE || exit $?
