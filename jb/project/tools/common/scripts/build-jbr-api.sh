#!/bin/sh

# $1 - Boot JDK
# $2 - JBR part of API version

cd "`dirname "$0"`/../../../../.."
PWD="`pwd`"
CONF="$PWD/build/jbr-api.conf"
./configure --with-debug-level=release --with-boot-jdk=$1 || exit $?
make jbr-api CONF=release MAKEOVERRIDES= "JBR_API_CONF_FILE=$CONF" JBR_API_JBR_VERSION=$2 || exit $?
. $CONF || exit $?
echo "##teamcity[buildNumber '$VERSION']"
cp "$JAR" ./jbr-api-${VERSION}.jar || exit $?
cp "$SOURCES_JAR" ./jbr-api-${VERSION}-sources.jar || exit $?
echo "##teamcity[publishArtifacts '$PWD/jbr-api-${VERSION}.jar']"
echo "##teamcity[publishArtifacts '$PWD/jbr-api-${VERSION}-sources.jar']"