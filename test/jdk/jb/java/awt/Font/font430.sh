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
# @summary font430.sh checks the 'physical' font object, created for 'Segoe UI' font and font-fallback-enabled one
#                     are not identical
# @run shell font430.sh

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
${TESTJAVA}/bin/javac -d ${TESTCLASSES} Font430.java

${TESTJAVA}/bin/java -cp ${TESTCLASSES} Font430 t1.bmp true
${TESTJAVA}/bin/java -cp ${TESTCLASSES} Font430 t2.bmp false

cmp t1.bmp t2.bmp
exit_code=$?

case $exit_code in
0) echo "PASSED"
   ;;
*) echo "FAILED: $exit_code"
   mv t[1..2].bmp ${TESTCLASSES}
   exit 1
   ;;
esac
exit 0
