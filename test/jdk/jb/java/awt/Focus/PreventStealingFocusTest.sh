#
# Copyright 2000-2022 JetBrains s.r.o.
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
# @summary Regression test for JBR-2496: Prevent JVM stealing focus from other applications on Linux
# @requires jdk.version.major >= 11
# @author sergey.v.shelomentsev@gmail.com
# @build WindowPopupApp
# @run shell PreventStealingFocusTest.sh

# Description
# WindowPopupApp.java creates a simple Swing application (WindowPopupApp) that schedules opening a new window
# in 5 seconds with requesting focus or not depending on the `requestFocus` property value.
# Right after the scheduling of the window opening, the application starts another Swing application
# FocusTrackingApp.java (FocusTrackingApp) that simulates keyboard input into a text field.
# FocusTrackingApp checks for loss of focus and takes a screenshot.
# Once FocusTrackingApp is finished, the WindowPopupApp checks for the exit code of FocusTrackingApp
# and made a simple screenshot analysis to verify that there are no artifacts from WindowPopupApp.

echo "Launching WindowPopupApp..."
echo "WindowPopupApp is going to request focus"
echo "> $TESTJAVA/bin/java${EXE_SUFFIX} $TESTVMOPTS -DrequestFocus=true -cp $TESTCLASSES WindowPopupApp"
$TESTJAVA/bin/java${EXE_SUFFIX} $TESTVMOPTS -DrequestFocus=true -cp $TESTCLASSES WindowPopupApp
result1=$?

echo "Launching WindowPopupApp..."
echo "WindowPopupApp without requesting focus"
echo "> $TESTJAVA/bin/java${EXE_SUFFIX} $TESTVMOPTS -DrequestFocus=false -cp $TESTCLASSES WindowPopupApp"
$TESTJAVA/bin/java${EXE_SUFFIX} $TESTVMOPTS -DrequestFocus=false -cp $TESTCLASSES WindowPopupApp
result2=$?

result=$(( result1 + result2 ))
exit $result
