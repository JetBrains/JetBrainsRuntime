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
# @summary Regression test for JBR-3314
#          It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations
#          exceeds a limit.
#          Number of iterations can be specified via the environment variable RUN_NUMBER, by default it is set to 100.
# @run shell/timeout=300 HandleJSQueryTest3314.sh

RUNS_NUMBER=${RUN_NUMBER:-50}
echo "number of iterations: $RUNS_NUMBER"

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

curdir=$(pwd)
cd ${TESTSRC}
${TESTJAVA}/bin/javac -d ${TESTCLASSES} JBCefApp.java JBCefBrowser.java HandleJSQueryTest.java
cd $curdir

i=0
while [ "$i" -le "$RUNS_NUMBER" ]; do
    echo "iteration - $i"
    ${TESTJAVA}/bin/java -cp ${TESTCLASSES} HandleJSQueryTest
    exit_code=$?
    echo "exit_xode=$exit_code"
    if [ $exit_code -ne "0" ]; then
      [[ $exit_code -eq "134" ]] && echo "FAILED: Test crashed" && exit $exit_code
      echo "Test failed because of not a crash. Execution is being conituned"
    fi
    i=$(( i + 1 ))
done
echo "PASSED: Test did never crash during $RUNS_NUMBER iterations"
exit 0
