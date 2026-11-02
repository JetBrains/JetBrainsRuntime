#!/bin/bash
# Compiles the capture routine's Java (the driver, the diff and sheet tools) into build/skia-eval-out/capture/classes
# and, on a Mac, the window helper (fgfast) next to them. The scene-driver plugin has its own build (scene-driver/build-plugin.sh).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
WORK="$OUT_ROOT/capture"; mkdir -p "$WORK/classes"
"$JDK/bin/javac" --add-exports java.desktop/sun.java2d=ALL-UNNAMED -d "$WORK/classes" "$HERE"/*.java
if [ "$(uname -s)" = Darwin ]; then swiftc -O -o "$WORK/fgfast" "$HERE/fgfast.swift" 2>&1 | grep -v "deprecated" || true; fi
echo "compiled into $WORK/classes"
