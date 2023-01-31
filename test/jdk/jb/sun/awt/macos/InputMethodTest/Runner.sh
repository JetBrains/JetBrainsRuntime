#!/bin/sh

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
