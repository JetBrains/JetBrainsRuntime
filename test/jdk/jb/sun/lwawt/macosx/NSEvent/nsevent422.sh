#!/usr/bin/env bash

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
