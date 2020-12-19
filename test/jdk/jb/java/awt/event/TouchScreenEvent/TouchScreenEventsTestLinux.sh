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
# @summary Regression test for JBR-2041: Touchscreen devices support
# @requires (jdk.version.major >= 11) & (os.family == "linux")
# @build TouchRobot LinuxTouchRobot LinuxTouchScreenDevice TouchScreenEventsTest
# @run shell TouchScreenEventsTestLinux.sh

# password for sudo
PASSWORD=${BUPWD}

echo "Allow current user write to /dev/uinput:"
echo "> sudo chown `whoami` /dev/uinput"
echo ${PASSWORD} | sudo -S chown `whoami` /dev/uinput
echo "result=$?"

echo "Launching TouchScreenEventsTest.java:"
echo "> $TESTJAVA/bin/java $TESTVMOPTS -cp $TESTCLASSES TouchScreenEventsTest"
$TESTJAVA/bin/java $TESTVMOPTS -cp $TESTCLASSES TouchScreenEventsTest
result=$?
echo "result=$result"

echo "Restore permissions for /dev/uinput:"
echo "> sudo chown root /dev/uinput"
echo ${PASSWORD} | sudo -S chown root /dev/uinput
echo "result=$?"

exit $result
