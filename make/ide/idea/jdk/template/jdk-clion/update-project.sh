#!/bin/bash

TOPDIR="###CLION_SCRIPT_TOPDIR###"
BUILD_DIR="###RELATIVE_BUILD_DIR###"
PATHTOOL="###PATHTOOL###"



cd "`dirname $0`"
SCRIPT_DIR="`pwd`"
cd "$TOPDIR"

echo "Updating Clion project files in \"$SCRIPT_DIR\" for project \"`pwd`\""

set -o pipefail
make compile-commands SPEC="$BUILD_DIR/spec.gmk" | sed 's/^/  /' || exit 1

if [ "x$PATHTOOL" != "x" ]; then
  CLION_PROJECT_DIR="`$PATHTOOL -am $SCRIPT_DIR`"
  sed "s/\\\\\\\\\\\\\\\\/\\\\\\\\/g" "$BUILD_DIR/compile_commands.json" > "$SCRIPT_DIR/compile_commands.json"
else
  CLION_PROJECT_DIR="$SCRIPT_DIR"
  cp "$BUILD_DIR/compile_commands.json" "$SCRIPT_DIR"
fi

echo "
Now you can open \"$CLION_PROJECT_DIR\" as Clion project
If Clion complains about missing files when loading a project, building it may help:
    cd \"`pwd`\" && make SPEC=\"$BUILD_DIR/spec.gmk\""
