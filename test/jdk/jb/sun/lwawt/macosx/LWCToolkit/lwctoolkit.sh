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
# @summary lwctoolkit.sh
# @run shell lwctoolkit.sh
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

ITERATIONS=$1

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

if [ -z "${ITERATIONS}" ]; then
  echo "ITERATIONS undefined: set to 10"
  ITERATIONS=10
fi
echo "ITERATIONS=${ITERATIONS}"

cd ${TESTSRC}
${TESTJAVA}/bin/javac -d ${TESTCLASSES} LWCToolkit.java

echo "Running workload"
${TESTJAVA}/bin/java -XX:+ExtendedDTraceProbes -cp ${TESTCLASSES} LWCToolkit ${ITERATIONS} &
TEST_PID=$!

DTRACE_OUTPUT=$(echo ${BUPWD} | sudo -S ${DTRACE} -p${TEST_PID} -s ${TESTSRC}/lwctoolkit.d)
echo "=dtrace output==========================="
echo $DTRACE_OUTPUT
echo "========================================="

METHOD_LINE=$(echo ${DTRACE_OUTPUT} | grep "LWCToolkit" | grep " invokeAndWait")

if [ -z "${METHOD_LINE}" ]; then
  echo "LWCToolkit.invokeAndWait is not contained in dtrace's output"
  count=0
else
  count=$(echo ${METHOD_LINE} | awk {'print $3'})
fi

if [ "${count}" -lt "100" ]; then
    echo "Test PASSED"
    exit 0
else
    echo "the fix for JRE-501 reduced the frequency of the invocations LWCToolkit.invokeAndWait to 80"
    echo "(e.g. see results on the build 1.8.0_152-release-b1084)"
    echo "currently LWCToolkit.invokeAndWait was invoked ${count}-times"
    echo "Test FAILED"
    exit 1
fi











