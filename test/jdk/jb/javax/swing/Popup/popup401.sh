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
