#!/bin/bash

#
# Copyright 2000-2022 JetBrains s.r.o.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

# The test checks all required JBR artifacts were built and published

dirname=$1
jbsdk_version=$2
build_number=$3


read -r -d '' allArtifacts << EndOFArtifactsList
jbr-${jbsdk_version}-linux-aarch64-b${build_number}.tar.gz
jbr-${jbsdk_version}-linux-musl-aarch64-b${build_number}.tar.gz
jbr-${jbsdk_version}-linux-musl-x64-b${build_number}.tar.gz
jbr-${jbsdk_version}-linux-x64-b${build_number}.tar.gz
jbr-${jbsdk_version}-osx-aarch64-b${build_number}.tar.gz
jbr-${jbsdk_version}-osx-x64-b${build_number}.tar.gz
jbr-${jbsdk_version}-windows-x64-b${build_number}.tar.gz
jbr-${jbsdk_version}-windows-x86-b${build_number}.tar.gz

jbr_fd-${jbsdk_version}-linux-aarch64-b${build_number}.tar.gz
jbr_fd-${jbsdk_version}-linux-x64-b${build_number}.tar.gz
jbr_fd-${jbsdk_version}-osx-aarch64-b${build_number}.tar.gz
jbr_fd-${jbsdk_version}-osx-x64-b${build_number}.tar.gz
jbr_fd-${jbsdk_version}-windows-x64-b${build_number}.tar.gz

jbr_jcef-${jbsdk_version}-linux-aarch64-b${build_number}.tar.gz
jbr_jcef-${jbsdk_version}-linux-x64-b${build_number}.tar.gz
jbr_jcef-${jbsdk_version}-osx-aarch64-b${build_number}.tar.gz
jbr_jcef-${jbsdk_version}-osx-aarch64-b${build_number}.pkg
jbr_jcef-${jbsdk_version}-osx-x64-b${build_number}.tar.gz
jbr_jcef-${jbsdk_version}-osx-x64-b${build_number}.pkg
jbr_jcef-${jbsdk_version}-windows-x64-b${build_number}.tar.gz

jbr-${jbsdk_version}-osx-aarch64-b${build_number}.pkg
jbr-${jbsdk_version}-osx-x64-b${build_number}.pkg
jbrsdk-${jbsdk_version}-osx-aarch64-b${build_number}.pkg
jbrsdk-${jbsdk_version}-osx-x64-b${build_number}.pkg

jbrsdk-${jbsdk_version}-linux-aarch64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-musl-aarch64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-musl-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-osx-aarch64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-osx-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-windows-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-windows-x86-b${build_number}.tar.gz

jbrsdk-${jbsdk_version}-linux-test-aarch64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-musl-test-aarch64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-musl-test-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-test-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-osx-test-aarch64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-osx-test-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-windows-test-x64-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-windows-test-x86-b${build_number}.tar.gz

jbrsdk-${jbsdk_version}-linux-aarch64-fastdebug-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-linux-x64-fastdebug-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-osx-aarch64-fastdebug-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-osx-x64-fastdebug-b${build_number}.tar.gz
jbrsdk-${jbsdk_version}-windows-x64-fastdebug-b${build_number}.tar.gz

jbrsdk-${jbsdk_version}-linux-aarch64-fastdebug-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-linux-musl-aarch64-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-linux-musl-x64-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-linux-x64-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-linux-x64-fastdebug-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-osx-aarch64-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-osx-aarch64-fastdebug-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-osx-x64-b${build_number}_diz.tar.gz
jbrsdk-${jbsdk_version}-osx-x64-fastdebug-b${build_number}_diz.tar.gz

jbrsdk_jcef-${jbsdk_version}-linux-aarch64-b${build_number}.tar.gz
jbrsdk_jcef-${jbsdk_version}-linux-x64-b${build_number}.tar.gz
jbrsdk_jcef-${jbsdk_version}-osx-aarch64-b${build_number}.pkg
jbrsdk_jcef-${jbsdk_version}-osx-aarch64-b${build_number}.tar.gz
jbrsdk_jcef-${jbsdk_version}-osx-x64-b${build_number}.pkg
jbrsdk_jcef-${jbsdk_version}-osx-x64-b${build_number}.tar.gz
jbrsdk_jcef-${jbsdk_version}-windows-x64-b${build_number}.tar.gz

jbrsdk_jcef-${jbsdk_version}-linux-x64-b${build_number}_diz.tar.gz
jbrsdk_jcef-${jbsdk_version}-osx-aarch64-b${build_number}_diz.tar.gz
jbrsdk_jcef-${jbsdk_version}-osx-x64-b${build_number}_diz.tar.gz

EndOFArtifactsList

testname="JBRArtifacts"
count=$(echo $allArtifacts | wc -w)
n=0
echo \#\#teamcity[testStarted name=\'$testname\']
echo "Non existing artifacts:"
for artifact in $allArtifacts; do
  isFound=$(ls $dirname | grep -c $artifact)
  if [ $isFound -eq 0 ]; then
    n=$((n+1))
    echo -e "\t$artifact"
  fi
done

echo "Extra artifacts:"
for relpath in $(ls $dirname); do
  filename=$(basename $relpath)
  isFound=$(echo $allArtifacts | grep -c $filename)
  if [ $isFound -eq 0 ]; then
    n=$((n+1))
    echo -e "\t$filename"
  fi
done
if [ $n -eq 0 ]; then
  echo \#\#teamcity[testFinished name=\'$testname\']
else
  echo \#\#teamcity[testFailed name=\'$testname\' message=\'Some artifacts cannot be found\']
fi

exit $n