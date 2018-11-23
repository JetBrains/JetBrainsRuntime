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
# @summary popup401.sh checks memory leaks
# @run shell popup401.sh
# @key dtrace

#export PATH="$PATH:/usr/sbin"
#echo $PATH
#echo "dtrace located at `which dtrace`"

OS=`uname -s`
case "$OS" in
  Linux | Darwin)
    echo "Detected OS $OS"
    ;;
  * )
    echo "PASSED: The test is valid for MacOSX, Linux"
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
${TESTJAVA}/bin/javac -d ${TESTCLASSES} Popup401.java

echo "Running ${DTRACE}"
echo ${BUPWD} | sudo -S ${DTRACE} -Z -q -s ${TESTSRCPATH}/popup401.d -c "${TESTJAVA}/bin/java -cp ${TESTCLASSPATH} Popup401 10"
exit_code=$?

case $exit_code in
0) echo "PASSED: mem leaks not found"
   ;;
9) echo "FAILED: mem leaks detected"
   exit 1
   ;;
*) echo "FAILED: undefined error"
   exit 1
   ;;
esac
exit 0
