#!/bin/bash

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3

bash -x jb/project/tools/mac/scripts/mkimages.sh $JBSDK_VERSION $JDK_BUILD_NUMBER $build_number "jcef"
bash -x jb/project/tools/mac/scripts/mkimages.sh $JBSDK_VERSION $JDK_BUILD_NUMBER $build_number "jfx"
