#!/bin/bash
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-$HERE/../windows-aarch64-skia/jdk}"
mkdir -p "$HERE/out-classes"
"$JDK/bin/javac" --add-exports java.desktop/sun.java2d=ALL-UNNAMED -d "$HERE/out-classes" "$HERE"/TextSheet.java "$HERE"/TextSheetDiff.java "$HERE"/TextSheetCompare.java "$HERE"/Zoom.java "$HERE"/IdeScenes.java "$HERE"/SceneDiff.java "$HERE"/Contact.java
echo compiled
