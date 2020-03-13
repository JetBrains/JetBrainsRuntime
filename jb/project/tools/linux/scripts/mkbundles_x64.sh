#!/bin/bash -x

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
script_dir=jb/project/tools/linux/scripts
${script_dir}/mkimages_x64.sh $JBSDK_VERSION $JDK_BUILD_NUMBER $build_number "jcef"
${script_dir}/mkimages_x64.sh $JBSDK_VERSION $JDK_BUILD_NUMBER $build_number "jfx"
