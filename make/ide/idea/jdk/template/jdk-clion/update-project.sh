#!/bin/sh
#
# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

TOPDIR="###CLION_SCRIPT_TOPDIR###"
BUILD_DIR="###BUILD_DIR###"
PATHTOOL="###PATHTOOL###"



cd "`dirname $0`"
SCRIPT_DIR="`pwd`"
cd "$TOPDIR"

echo "Updating Clion project files in \"$SCRIPT_DIR\" for project \"`pwd`\""

make compile-commands SPEC="$BUILD_DIR/spec.gmk" || exit 1

if [ "x$PATHTOOL" != "x" ]; then
  CLION_PROJECT_DIR="`$PATHTOOL -am $SCRIPT_DIR`"
  sed "s/\\\\\\\\\\\\\\\\/\\\\\\\\/g" "$BUILD_DIR/compile_commands.json" > "$SCRIPT_DIR/compile_commands.json"
else
  CLION_PROJECT_DIR="$SCRIPT_DIR"
  cp "$BUILD_DIR/compile_commands.json" "$SCRIPT_DIR"
fi

echo "Now you can open $CLION_PROJECT_DIR as Clion project"
