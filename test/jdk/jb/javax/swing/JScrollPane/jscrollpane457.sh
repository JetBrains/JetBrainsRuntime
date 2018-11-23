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
# @summary jscrollpane457.sh
# @run shell jscrollpane457.sh
# @key dtrace
# @requires os.family == "mac" | os.family == "linux"

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
${TESTJAVA}/bin/javac -d ${TESTCLASSES} JScrollPane457.java

echo "Running workload"
${TESTJAVA}/bin/java -cp ${TESTCLASSPATH} JScrollPane457 100 &
TEST_PID=$!

echo "Running ${DTRACE}"
DTRACE_OUTPUT=$(echo ${BUPWD} | sudo -S ${DTRACE} -q -p${TEST_PID} -s ${TESTSRCPATH}/jscrollpane457.d)
count=$(echo ${DTRACE_OUTPUT} | grep -c "OGLTR_DisableGlyphModeState")
echo ${DTRACE_OUTPUT}

case $count in
0) echo "PASSED: OGLTR_DisableGlyphModeState is NOT on top"
   ;;
*) echo ${DTRACE_OUTPUT}
   echo "FAILED: OGLTR_DisableGlyphModeState is on top"
   exit 1
   ;;
esac
exit 0
