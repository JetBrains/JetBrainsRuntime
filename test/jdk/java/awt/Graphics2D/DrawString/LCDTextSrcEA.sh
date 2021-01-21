#!/bin/bash

#
# Copyright 2000-2021 JetBrains s.r.o.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# @test
# @key headful
# @bug 6996867
# @summary Render as LCD Text in SrcEa composite mode
# @requires (os.family == "mac")
# @comment sh file is used to fix JBR-1388 for macOS versions >= 10.14

if [ -z "${TESTSRC}" ]; then
  echo "TESTSRC is undefined: set to ."
  TESTSRC=.
fi

if [ -z "${TESTCLASSES}" ]; then
  echo "TESTCLASSES is undefined: set to ."
  TESTCLASSES=.
fi

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA is undefined: testing cancelled"
  exit 1
fi

cd ${TESTSRC}
${TESTJAVA}/bin/javac -d ${TESTCLASSES} LCDTextSrcEa.java

echo "reading current CGFontRenderingFontSmoothingDisabled value"
oldKeyValue=$(defaults read -g CGFontRenderingFontSmoothingDisabled)

# should not affect the test run on macOS versions < 10.14
defaults write -g CGFontRenderingFontSmoothingDisabled -bool NO
${TESTJAVA}/bin/java -cp ${TESTCLASSES} LCDTextSrcEa
exit_code=$?
if [ -z "$oldKeyValue" ]; then
  defaults delete -g CGFontRenderingFontSmoothingDisabled
elif [ "$oldKeyValue" -eq 1 ]; then
  defaults write -g CGFontRenderingFontSmoothingDisabled -bool YES
fi

case $exit_code in
0) echo "PASSED"
   ;;
*) echo "FAILED"
   exit 1
   ;;
esac
exit 0
