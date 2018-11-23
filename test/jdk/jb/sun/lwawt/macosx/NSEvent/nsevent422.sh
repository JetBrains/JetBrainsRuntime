#!/usr/bin/env bash

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
# nsevent422.sh checks NSEvent objects are collected by GC
# @run shell nsevent422.sh
# @requires os.family == "mac"

#
# The test launches NSEvent Java app, which generates series of mouse movements and checks the number of NSEvent
# instances via jmap
# When the number of NSEvent instances on the current iteration becomes less this number then on the previous one,
# the test stops checking and exits with PASSED status. Otherwise it fails.
#
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
${TESTJAVA}/bin/javac -d ${TESTCLASSES} NSEvent422.java

${TESTJAVA}/bin/java -Xmx8M -cp ${TESTCLASSES} NSEvent422 1000 &
pid=$!
sleep 5
old_instances=0
passed=0
while [ ! -z $pid ]
do
    histo=`${TESTJAVA}/bin/jmap -histo $pid | grep "sun.lwawt.macosx.NSEvent"`
    echo ${histo}
    new_instances=`echo ${histo} | awk {'print $2'}`
    if [ -z "${new_instances}" ]; then
        break
    fi

    if [ "${new_instances}" -lt "${old_instances}" ]; then
        passed="1"
        break
    fi
    old_instances=${new_instances}
    sleep 1

done

kill -9 $pid

if [ "${passed}" -eq "1" ]; then
    echo "Test PASSED"
    exit 0
else
    echo "Test FAILED"
    exit 1
fi
