#!/bin/bash

#
# Copyright 2000-2023 JetBrains s.r.o.
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



