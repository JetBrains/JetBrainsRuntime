#!/bin/sh
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

# COMPILEJAVA and TESTJAVA are always in UNIX format, other paths are Win-style on Windows

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
TESTCLASSES_UNIX=`$PATHTOOL "$TESTCLASSES"`

SRC="$TESTROOT/../../src"
# Generate sources
"$COMPILEJAVA/bin/java$EXE_SUFFIX" "$SRC/jetbrains.api/tools/Gensrc.java" "$SRC" "$PWD/jbr-api" "TEST" || exit $?
# Validate version
"$COMPILEJAVA/bin/java$EXE_SUFFIX" "$SRC/jetbrains.api/tools/CheckVersion.java" "$SRC/jetbrains.api" "$PWD/jbr-api/gensrc" "true" || exit $?
# Compile API
if [ "$PATHTOOL" != "echo" ]; then
  where.exe /r "jbr-api\\gensrc" *.java > compile.list
else
  find jbr-api/gensrc -name *.java > compile.list
fi
"$COMPILEJAVA/bin/javac$EXE_SUFFIX" $TESTJAVACOPTS -d "$TESTCLASSES" @compile.list || exit $?
rm "$TESTCLASSES_UNIX/module-info.class"
exit 0
