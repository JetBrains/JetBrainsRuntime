#!/bin/bash

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
      NEWFILESIZE=$(stat -c%s "$NEWFILEPATH")
      ;;
    MINGW*)
      NEWFILESIZE=$(stat -c%s "$NEWFILEPATH")
      ;;
    *)
      echo "Unknown machine: ${unameOut}"
      exit 1
esac

echo "New size of $NEWFILEPATH = $NEWFILESIZE bytes."

# example: IntellijCustomJdk_Jdk17_Master_LinuxX64jcef

CURL_RESPONSE=$(curl --header "Authorization: Bearer $TOKEN" "https://buildserver.labs.intellij.net/app/rest/builds/?locator=buildType:(id:$CONFIGID),status:success,count:1,finishDate:(build:$BUILDID,condition:before)")

re='id=\"([0-9]+)\".+number=\"([0-9]+)\"'
ID=0

if [[ $CURL_RESPONSE =~ $re ]]; then
  ID=${BASH_REMATCH[1]}
  echo "BUILD Number: ${BASH_REMATCH[2]}"
else
  echo "ERROR: can't find previous build"
  echo $CURL_RESPONSE
  exit 1
fi

CURL_RESPONSE=$(curl --header "Authorization: Bearer $TOKEN" "https://buildserver.labs.intellij.net/app/rest/builds/$ID?fields=id,number,artifacts(file(name,size))")

echo "Atrifacts of last pinned build of $CONFIGID :\n"
echo $CURL_RESPONSE

# Find size (in response) with reg exp
re='name=\"(jbrsdk_jcef[^\"]+\.tar\.gz)\" size=\"([0-9]+)\"'

if [[ $CURL_RESPONSE =~ $re ]]; then
  OLDFILENAME=${BASH_REMATCH[1]}
  echo "Prev artifact name: $OLDFILENAME"
  OLDFILESIZE=${BASH_REMATCH[2]}
  echo "Prev artifact size = $OLDFILESIZE"

  let allowedSize=OLDFILESIZE+OLDFILESIZE/20 # use 5% threshold
  echo "Allowed size = $allowedSize"
  if [[ "$NEWFILESIZE" -gt "$allowedSize" ]]; then
    echo "ERROR: new size is significally greater than prev size (need to investigate)"
    exit 1
  fi
else
  echo "ERROR: can't find string with size in xml response:"
  echo $CURL_RESPONSE
  exit 1
fi

