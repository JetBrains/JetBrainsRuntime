#!/bin/sh
#
# Copyright 2000-2021 JetBrains s.r.o.
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

PATHTOOL=echo
PATHTOOL_WIN=echo
case "`uname -a | tr '[:upper:]' '[:lower:]'`" in
    cygwin* )
        PATHTOOL="cygpath -au" ; PATHTOOL_WIN="cygpath -aw" ;;
    *microsoft* )
        PATHTOOL="wslpath -au" ; PATHTOOL_WIN="wslpath -aw" ;;
esac
PWD=`pwd`
PWD=`$PATHTOOL_WIN "$PWD"`
TESTSRC_UNIX=`$PATHTOOL "$TESTSRC"`
COMPILEJAVA_UNIX=`$PATHTOOL "$COMPILEJAVA"`
TESTCLASSES_UNIX=`$PATHTOOL "$TESTCLASSES"`

SRC="$TESTSRC/../../../../../../src"
SRC_UNIX="$TESTSRC_UNIX/../../../../../../src"
# Generate sources
"$COMPILEJAVA_UNIX/bin/java$EXE_SUFFIX" "$SRC/jetbrains.api/tools/Gensrc.java" "$SRC" "$PWD/jbr-api" "TEST" || exit $?
# Validate version
"$COMPILEJAVA_UNIX/bin/java$EXE_SUFFIX" "$SRC/jetbrains.api/tools/CheckVersion.java" "$SRC/jetbrains.api" "$PWD/jbr-api/gensrc" "true" || exit $?
# Compile API
if [ "$PATHTOOL" != "echo" ]; then
  where.exe /r "jbr-api\\gensrc" *.java > compile.list
else
  find jbr-api/gensrc -name *.java > compile.list
fi
"$COMPILEJAVA_UNIX/bin/javac$EXE_SUFFIX" $TESTJAVACOPTS -d "$TESTCLASSES" @compile.list || exit $?
rm "$TESTCLASSES_UNIX/module-info.class"
exit 0
