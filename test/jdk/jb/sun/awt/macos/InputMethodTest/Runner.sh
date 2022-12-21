#!/bin/sh

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

[ -z "${TESTSRC}" ] && echo "TESTSRC not set" && exit 1
[ -z "${TESTCLASSES}" ] && echo "TESTCLASSES not set" && exit 1
[ -z "${TESTJAVA}" ] && echo "TESTJAVA not set" && exit 1
cd "${TESTSRC}" || exit 1

"${TESTJAVA}/bin/javac" -d "${TESTCLASSES}" --add-modules java.desktop --add-exports java.desktop/sun.lwawt.macosx=ALL-UNNAMED InputMethodTest.java

half_width_override=

while :; do
  case $1 in
    --halfwidth)
      half_width_override=1
      ;;

    --fullwidth)
      half_width_override=0
      ;;

    --)
      shift
      break
      ;;

    *)
      break
      ;;
  esac
  shift
done

if [ -n "$half_width_override" ]; then
  half_width_old_value=$(defaults read com.apple.inputmethod.CoreChineseEngineFramework usesHalfwidthPunctuation)
  defaults write com.apple.inputmethod.CoreChineseEngineFramework usesHalfwidthPunctuation "$half_width_override"
fi

"${TESTJAVA}/bin/java" -cp "${TESTCLASSES}" --add-modules java.desktop --add-exports java.desktop/sun.lwawt.macosx=ALL-UNNAMED InputMethodTest "$1"
exit_code=$?

if [ -n "$half_width_override" ]; then
  defaults write com.apple.inputmethod.CoreChineseEngineFramework usesHalfwidthPunctuation "$half_width_old_value"
fi

case $exit_code in
0) echo "PASSED"
   ;;
*) echo "FAILED"
   exit 1
   ;;
esac
exit 0
