#!/bin/bash

set -euo pipefail

while getopts ":t" o; do
    case "${o}" in
        t)
          t="With Teamcity tests info"
          TC_PRINT=1
          ;;
    esac
done
shift $((OPTIND-1))

NEWFILEPATH=$1
CONFIGID=$2
BUILDID=$3
TOKEN=$4
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
      NEWFILESIZE=$(stat -c%s$4
#
# Get the size of new artifact
#
 "$NEWFILEPATH")
      ;;
    MINGW*)
      NEWFILESIZE=$(stat -c%s "$NEWFILEPATH")
      ;;
    *)
      echo "Unknown machine: ${unameOut}"
      exit 1
esac
FILENAME=$(basename ${NEWFILEPATH})

#
# Get pattern of artifact name
# Base filename pattern: <BUNDLE_TYPE>-<JDK_VERSION>-<OS>-<ARCH>-b<BUILD>.tar.gz: jbr_dcevm-17.0.2-osx-x64-b1234.tar.gz
# BUNDLE_TYPE: jbr, jbrsdk, jbr_dcevm, jbrsdk_jcef etc.
# OS_ARCH_PATTERN - <os_architecture>: osx-x64, linux-aarch64, windows-x64 etc.

BUNDLE_TYPE=jbrsdk
OS_ARCH_PATTERN=""
FILE_EXTENSION=tar.gz

re='(jbr[a-z_]*).*-[0-9_\.]+-(.+)-b[0-9]+(.+)'
if [[ $FILENAME =~ $re ]]; then
  BUNDLE_TYPE=${BASH_REMATCH[1]}
  OS_ARCH_PATTERN=${BASH_REMATCH[2]}
  FILE_EXTENSION=${BASH_REMATCH[3]}
fi

if [ $TC_PRINT -eq 1 ]; then
  testname_file_ext=`echo $FILE_EXTENSION | sed 's/\./_/g'`
  testname=$BUNDLE_TYPE"_"$OS_ARCH_PATTERN$testname_file_ext
  echo \#\#teamcity[testStarted name=\'$testname\']
fi


echo "BUNDLE_TYPE: "  $BUNDLE_TYPE
echo "OS_ARCH_PATTERN: " $OS_ARCH_PATTERN
echo "FILE_EXTENSION: " $FILE_EXTENSION
echo "New size of $FILENAME = $NEWFILESIZE bytes."


function test_failed_msg() {
  if [ $3 -eq 1 ]; then
    echo \#\#teamcity[testFailed name=\'$1\' message=\'$2\']
  fi
}

function test_finished_msg() {
  if [ $2 -eq 1 ]; then
    echo \#\#teamcity[testFinished name=\'$1\']
  fi
}

#
# Get previous successful build ID
# Example:
# CONFIGID=IntellijCustomJdk_Jdk17_Master_LinuxX64jcef
# BUILDID=12345678
#
# expected return value
# id="123".number="567"
#
CURL_RESPONSE=$(curl --header "Authorization: Bearer $TOKEN" "https://buildserver.labs.intellij.net/app/rest/builds/?locator=buildType:(id:$CONFIGID),status:success,count:1,finishDate:(build:$BUILDID,condition:before)")
re='id=\"([0-9]+)\".+number=\"([0-9\.]+)\"'

# ID: Previous successful build id
ID=0
if [[ $CURL_RESPONSE =~ $re ]]; then
  ID=${BASH_REMATCH[1]}
  echo "BUILD Number: ${BASH_REMATCH[2]}"
else
  msg="ERROR: can't find previous build"
  echo $msg
  echo $CURL_RESPONSE
  test_failed_msg $testname $msg $TC_PRINT
  test_finished_msg $testname $TC_PRINT
  exit 1
fi

#
# Get artifacts from previous successful build
#
# expected return value
# name="jbrsdk_jcef*.tar.gz size="123'
#
CURL_RESPONSE=$(curl --header "Authorization: Bearer $TOKEN" "https://buildserver.labs.intellij.net/app/rest/builds/$ID?fields=id,number,artifacts(file(name,size))")
echo "Atrifacts of previous build of $CONFIGID :"
echo $CURL_RESPONSE

# Find binary size (in response) with reg exp
re='name=\"('$BUNDLE_TYPE'[^\"]+'${OS_ARCH_PATTERN}'[^\"]+'${FILE_EXTENSION}')\" size=\"([0-9]+)\"'

if [[ $CURL_RESPONSE =~ $re ]]; then
  OLDFILENAME=${BASH_REMATCH[1]}
  echo "Prev artifact name: $OLDFILENAME"
  OLDFILESIZE=${BASH_REMATCH[2]}
  echo "Prev artifact size = $OLDFILESIZE"

  let allowedSize=OLDFILESIZE+OLDFILESIZE/20 # use 5% threshold
  echo "Allowed size = $allowedSize"
  if [[ "$NEWFILESIZE" -gt "$allowedSize" ]]; then
    msg="ERROR: new size is significally greater than prev size (need to investigate)"
    echo $msg
    test_failed_msg $testname $msg $TC_PRINT
    test_finished_msg $testname $TC_PRINT
    exit 1
  else
    echo "PASSED"
   test_finished_msg $testname $TC_PRINT
  fi
else
  msg="ERROR: can't find string with size in xml response:"
  echo $msg
  echo $CURL_RESPONSE
  test_failed_msg $testname $msg $TC_PRINT
  test_finished_msg $testname $TC_PRINT
  exit 1
fi
