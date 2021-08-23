#!/bin/sh
#
# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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
#   @test
#   @bug        8269223
#   @summary Verifies that -Xcheck:jni issues no warnings from freetypeScaler.c
#   @compile FreeTypeScalerJNICheck.java
#   @run shell/timeout=300 FreeTypeScalerJNICheck.sh
#
OS=`uname`

# pick up the compiled class files.
if [ -z "${TESTCLASSES}" ]; then
  CP="."
else
  CP="${TESTCLASSES}"
fi

if [ -z "${TESTJAVA}" ] ; then
   JAVACMD=java
else
   JAVACMD=$TESTJAVA/bin/java
fi

$JAVACMD ${TESTVMOPTS} \
    -cp "${CP}" -Xcheck:jni FreeTypeScalerJNICheck| grep "WARNING"  > "${CP}"/log.txt

# any messages logged may indicate a failure.
if [ -s "${CP}"/log.txt ]; then
    echo "Test failed - warnings found"
    cat "${CP}"/log.txt
    exit 1
fi

echo "Test passed"
exit 0
