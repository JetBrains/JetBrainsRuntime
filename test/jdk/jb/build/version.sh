#!/bin/sh

#
# Copyright 2000-2021 JetBrains s.r.o.
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
# @summary Regression test for JBR-3887.
#          The test checks <code>java $arg -version</code> do not cause crashes
# @run shell version.sh

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA undefined: testing cancelled"
  exit 1
fi

args="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:0"

echo "check> ${TESTJAVA}/bin/java $args -version"
"${TESTJAVA}"/bin/java $args -version
exit_status=$?
echo "exit status: ${exit_status}"
if [ $exit_status != 0 ]; then
    echo "\nTest failed"
    exit $exit_status
fi
