#!/bin/bash -x

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3

jb/project/tools/windows/scripts/mkimages.sh $JBSDK_VERSION $JDK_BUILD_NUMBER $build_number "jcef" || exit $?
jb/project/tools/windows/scripts/mkimages.sh $JBSDK_VERSION $JDK_BUILD_NUMBER $build_number "jfx" || exit $?