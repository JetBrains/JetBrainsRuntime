#!/bin/bash

#
# Copyright 2000-2020 JetBrains s.r.o.
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
# @summary Regression test for JBR-2041: Touchscreen devices support
# @requires (jdk.version.major >= 11) & (os.family == "linux")
# @modules java.desktop/sun.awt.event
# @build TouchRobot LinuxTouchRobot LinuxTouchScreenDevice TouchScreenEventsTest
# @run shell TouchScreenEventsTestLinux.sh

# password for sudo
PASSWORD=${BUPWD}

if [ "${PASSWORD}" = "" ]
then
  echo "Error: Root password is empty"; exit 1
fi

echo "Allow current user write to /dev/uinput:"
echo "> sudo chown `whoami` /dev/uinput"
echo ${PASSWORD} | sudo -S chown `whoami` /dev/uinput
if [ $? != 0 ] ; then
  echo "Error: Cannot change owner of /dev/uinput"; exit 1
fi


echo "Launching TouchScreenEventsTest.java:"
echo "> $TESTJAVA/bin/java $TESTVMOPTS -cp $TESTCLASSES TouchScreenEventsTest"
$TESTJAVA/bin/java $TESTVMOPTS -cp $TESTCLASSES TouchScreenEventsTest
result=$?
echo "Test run result=$result"

echo "Restore permissions on /dev/uinput:"
echo "> sudo chown root /dev/uinput"
echo ${PASSWORD} | sudo -S chown root /dev/uinput
if [ $? != 0 ] ; then
  echo "Error: Cannot restore permissions on /dev/uinput"
fi

exit $result
