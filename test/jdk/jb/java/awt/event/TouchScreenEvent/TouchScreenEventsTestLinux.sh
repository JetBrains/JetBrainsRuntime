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
# @build TouchRobot LinuxTouchRobot LinuxTouchScreenDevice TouchScreenEventsTest
# @run shell TouchScreenEventsTestLinux.sh

# password for sudo
PASSWORD=${BUPWD}

echo "Preparing env for the test:"

echo "> sudo groupadd uinput"
echo ${PASSWORD} | sudo -S groupadd uinput
echo "result=$?"

echo "> sudo usermod -a -G uinput `whoami`"
echo ${PASSWORD} | sudo -S usermod -a -G uinput `whoami`
echo "result=$?"

echo "> sudo chmod g+rw /dev/uinput"
echo ${PASSWORD} | sudo -S chmod g+rw /dev/uinput
echo "result=$?"

echo "> sudo chgrp uinput /dev/uinput"
echo ${PASSWORD} | sudo -S chgrp uinput /dev/uinput
echo "result=$?"

echo "Launching TouchScreenEventsTest.java:"
echo "> $TESTJAVA/bin/java $TESTVMOPTS -cp $TESTCLASSES TouchScreenEventsTest"
$TESTJAVA/bin/java $TESTVMOPTS -cp $TESTCLASSES TouchScreenEventsTest
