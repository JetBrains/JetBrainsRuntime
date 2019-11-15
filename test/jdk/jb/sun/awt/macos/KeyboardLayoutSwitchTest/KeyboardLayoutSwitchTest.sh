#!/bin/bash

#
# Copyright 2000-2019 JetBrains s.r.o.
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
# @summary KeyboardLayoutSwitchTest.sh checks memory leaks
# @run shell KeyboardLayoutSwitchTest.sh

OS=`uname -s`
case "$OS" in
  Darwin)
    echo "Detected OS $OS"
    ;;
  * )
    echo "PASSED: The test is valid for MacOSX"
    exit 0;
    ;;
esac

if [ -z "${TESTSRC}" ]; then
  echo "TESTSRC undefined: set to ."
  TESTSRC=.
fi

if [ -z "${TESTCLASSES}" ]; then
  echo "TESTCLASSES undefined: set to ."
  TESTCLASSES=.
fi

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA undefined: testing cancelled"
  exit 1
fi

cd ${TESTSRC}
//${TESTJAVA}/bin/javac -d ${TESTCLASSES} AppleSymbolicHotKeysReader.java
${TESTJAVA}/bin/javac -d ${TESTCLASSES} KeyboardLayoutSwitchTest.java

echo "reading current hot keys"
oldKeyValue=$(defaults read "com.apple.symbolichotkeys" | grep -A10  " 60 =" | sed '$d' | sed '1d' | tr -d '\r\n' | tr -s ' ' | sed s/standard/\'standard\'/)

defaults write com.apple.symbolichotkeys AppleSymbolicHotKeys -dict-add 60 "{ enabled = 1; value = { parameters = ( 65535, 48, 262144 ); type = 'standard'; };}"
${TESTJAVA}/bin/java -cp ${TESTCLASSES} KeyboardLayoutSwitchTest
exit_code=$?
defaults write com.apple.symbolichotkeys AppleSymbolicHotKeys -dict-add 60 "$oldKeyValue"

case $exit_code in
0) echo "PASSED"
   ;;
*) echo "FAILED"
   exit 1
   ;;
esac
exit 0



