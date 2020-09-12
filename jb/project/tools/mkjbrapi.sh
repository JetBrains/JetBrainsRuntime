#!/bin/bash -x

# How to call this script:
# eval $(jb/project/tools/mkjbrapi.sh)

# It is used to build jetbrains.api module
# After properly calling this script, you can use following variables:
# JBR_API_JAR - absolute path to resulting JAR
# JBR_API_SOURCES_JAR - absolute path to JAR with sources
# JBR_API_VERSION - JBR API version in form <major>.<minor>
# JBR_API_VERSION_MAJOR, JBR_API_VERSION_MINOR - JBR API version components
# JBR_BUILD_DIR - absolute path to JBR build directory
# JBR_BOOT_JDK - absolute path to used boot JDK

ROOT=$(pwd)

sh configure --with-debug-level=release --disable-warnings-as-errors 1>&2 || exit $?

# Get boot JDK & build directory using make script
make -f $ROOT/make/JBRApi.gmk -I $ROOT jbr-api MAKEOVERRIDES= CONF=release OUT="$ROOT/build/jbr-api.cfg" 1>&2 || exit $?
source "$ROOT/build/jbr-api.cfg" || exit $?

# Build module
make jetbrains.api 1>&2 || exit $?

# Get JBR API version from compiled class
JSHELL_COMMAND='
System.out.println("VERSION_MAJOR=" + com.jetbrains.JBRApi.getMajorVersion());
System.out.println("VERSION_MINOR=" + com.jetbrains.JBRApi.getMinorVersion());
/exit'
VERSION_VARIABLES=$("$BOOT_JDK/bin/jshell" -s --module-path "$BUILD_DIR/jdk/modules/jetbrains.api" \
--add-modules jetbrains.api <<< "$JSHELL_COMMAND" | grep "^VERSION\|^|") || exit $?
eval "$VERSION_VARIABLES" || exit $?

# Create JAR
(
  cd "$BUILD_DIR/jdk/modules/jetbrains.api"
  "$BOOT_JDK/bin/jar" -cf "$BUILD_DIR/jbr-api.jar" * 1>&2
) || exit $?

# Create source JAR
(
  cd "src/jetbrains.api/share/classes"
  "$BOOT_JDK/bin/jar" -cf "$BUILD_DIR/jbr-api-sources.jar" * 1>&2
) || exit $?

# Print output values
echo "JBR_API_JAR=$BUILD_DIR/jbr-api.jar"
echo "JBR_API_SOURCES_JAR=$BUILD_DIR/jbr-api-sources.jar"
echo "JBR_API_VERSION=$VERSION_MAJOR.$VERSION_MINOR"
echo "JBR_API_VERSION_MAJOR=$VERSION_MAJOR"
echo "JBR_API_VERSION_MINOR=$VERSION_MINOR"
echo "JBR_BUILD_DIR=$BUILD_DIR"
echo "JBR_BOOT_JDK=$BOOT_JDK"

echo "Success!" 1>&2
