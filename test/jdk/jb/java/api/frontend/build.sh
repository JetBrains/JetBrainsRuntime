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

SRC="$TESTSRC/../../../../../../src"
PWD="`pwd`"
# Generate sources
"$COMPILEJAVA/bin/java" "$SRC/jetbrains.api/templates/Gensrc.java" "$SRC" "$PWD/jbr-api" "TEST" || exit $?
# Validate version
"$COMPILEJAVA/bin/java" "$SRC/jetbrains.api/templates/CheckVersion.java" "$SRC/jetbrains.api" "$PWD/jbr-api/gensrc" "true" || exit $?
# Compile API
find "$SRC/jetbrains.api/src" -name *.java > compile.list
find jbr-api/gensrc -name *.java >> compile.list
"$COMPILEJAVA/bin/javac" $TESTJAVACOPTS -d "$TESTCLASSES" @compile.list || exit $?
rm "$TESTCLASSES/module-info.class"
exit 0
