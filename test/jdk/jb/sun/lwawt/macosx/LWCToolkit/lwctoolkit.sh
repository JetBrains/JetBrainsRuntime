#!/bin/bash

#
# Copyright 2000-2017 JetBrains s.r.o.
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











