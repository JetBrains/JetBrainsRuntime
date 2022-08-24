#!/bin/bash

set -euo pipefail

TC_PRINT=0
# Always print TeamCity service messages if running under TeamCity
[[ -n "${TEAMCITY_VERSION:-}" ]] && TC_PRINT=1

while getopts ":t" o; do
    case "${o}" in
        t) TC_PRINT=1 ;;
        *);;
    esac
done
shift $((OPTIND-1))

NEWFILEPATH="$1"
CONFIGID="$2"
BUILDID="$3"
TOKEN="$4"

if [ ! -f "$NEWFILEPATH" ]; then
  echo "File not found: $NEWFILEPATH"
  exit 1
fi
#
# Get the size of new artifact
#

unameOut="$(uname -s)"
case "${unameOut}" in
    Linux*)
      NEWFILESIZE=$(stat -c%s "$NEWFILEPATH")
      ;;
    Darwin*)
      NEWFILESIZE=$(stat -f%z "$NEWFILEPATH")
      ;;
    CYGWIN*)
      NEWFILESIZE=$(stat -c%s "$NEWFILEPATH")
      ;;
    MINGW*)
      NEWFILESIZE=$(stat -c%s "$NEWFILEPATH")
      ;;
    *)
      echo "Unknown machine: ${unameOut}"
      exit 1
esac
FILENAME=$(basename "${NEWFILEPATH}")

#
# Get pattern of artifact name
# Base filename pattern: <BUNDLE_TYPE>-<JDK_VERSION>-<OS>-<ARCH>-b<BUILD>.tar.gz: jbr_dcevm-17.0.2-osx-x64-b1234.tar.gz
# BUNDLE_TYPE: jbr, jbrsdk, jbr_dcevm, jbrsdk_jcef etc.
# OS_ARCH_PATTERN - <os_architecture>: osx-x64, linux-aarch64, linux-musl-x64, windows-x64 etc.

BUNDLE_TYPE=jbrsdk
OS_ARCH_PATTERN=""
FILE_EXTENSION=tar.gz

re='(jbr[a-z_]*).*-[0-9_\.]+-(.+)-b[0-9]+(.+)'
if [[ $FILENAME =~ $re ]]; then
  BUNDLE_TYPE=${BASH_REMATCH[1]}
  OS_ARCH_PATTERN=${BASH_REMATCH[2]}
  FILE_EXTENSION=${BASH_REMATCH[3]}
else
  echo "File name $FILENAME does not match regex $re"
  exit 1
fi

function test_started_msg() {
  if [ $TC_PRINT -eq 1 ]; then
    echo "##teamcity[testStarted name='$1']"
  fi
}

function test_failed_msg() {
  if [ $TC_PRINT -eq 1 ]; then
    echo "##teamcity[testFailed name='$1' message='$2']"
  fi
}

function test_finished_msg() {
  if [ $TC_PRINT -eq 1 ]; then
    echo "##teamcity[testFinished name='$1']"
  fi
}

test_name="${BUNDLE_TYPE}_${OS_ARCH_PATTERN//\-/_}${FILE_EXTENSION//\./_}"
test_started_msg "$test_name"

echo "BUNDLE_TYPE: $BUNDLE_TYPE"
echo "OS_ARCH_PATTERN: $OS_ARCH_PATTERN"
echo "FILE_EXTENSION: $FILE_EXTENSION"
echo "Size of $FILENAME is $NEWFILESIZE bytes"

#
# Get previous successful build ID
# Example:
# CONFIGID=IntellijCustomJdk_Jdk17_Master_LinuxX64jcef
# BUILDID=12345678
#
# expected return value
# id="123".number="567"
#
CURL_RESPONSE=$(curl -sSL --header "Authorization: Bearer $TOKEN" "https://buildserver.labs.intellij.net/app/rest/builds/?locator=buildType:(id:$CONFIGID),status:success,count:1,finishDate:(build:$BUILDID,condition:before)")
re='id=\"([0-9]+)\".+number=\"([0-9\.]+)\"'

# ID: Previous successful build id
ID=0
if [[ $CURL_RESPONSE =~ $re ]]; then
  ID=${BASH_REMATCH[1]}
  echo "Previous build ID: $ID"
  echo "Previous build number: ${BASH_REMATCH[2]}"
else
  msg="ERROR: cannot find previous build"
  echo "$msg"
  echo "$CURL_RESPONSE"
  test_failed_msg "$test_name" "$msg"
  test_finished_msg "$test_name"
  exit 1
fi

#
# Get artifacts from previous successful build
#
# expected return value
# name="jbrsdk_jcef*.tar.gz size="123'
#
CURL_RESPONSE=$(curl -sSL --header "Authorization: Bearer $TOKEN" "https://buildserver.labs.intellij.net/app/rest/builds/$ID?fields=id,number,artifacts(file(name,size))")
echo "Artifacts of the previous build:"
echo "$CURL_RESPONSE"

# Find binary size (in response) with reg exp
re="name=\"(${BUNDLE_TYPE}[^\"]+${OS_ARCH_PATTERN}[^\"]+${FILE_EXTENSION})\" size=\"([0-9]+)\""

if [[ $CURL_RESPONSE =~ $re ]]; then
  prevFileName=${BASH_REMATCH[1]}
  echo "Previous artifact name: $prevFileName"
  prevFileSize=${BASH_REMATCH[2]}
  echo "Previous artifact size: $prevFileSize"

  ((allowedSize=prevFileSize+prevFileSize/20)) # use 5% threshold
  echo "Allowed size: $allowedSize"
  if [[ "$NEWFILESIZE" -gt "$allowedSize" ]]; then
    msg="ERROR: new size is significantly greater than previous size (need to investigate)"
    echo "$msg"
    test_failed_msg "$test_name" "$msg"
    test_finished_msg "$test_name"
    exit 1
  else
    echo "PASSED"
    test_finished_msg "$test_name"
  fi
else
  msg="ERROR: cannot find string with size in xml response:"
  echo "Regex: $re"
  echo "$msg"
  echo "$CURL_RESPONSE"
  test_failed_msg "$test_name" "$msg"
  test_finished_msg "$test_name"
  exit 1
fi
