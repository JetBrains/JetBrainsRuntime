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
