#!/usr/bin/env bash

# @test
# @summary permissions458.sh checks readability permissions of JDK build
# @run shell permissions458.sh

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA undefined: testing cancelled"
  exit 1
fi

FIND="/usr/bin/find"
files=$(${FIND} ${TESTJAVA} -not -perm -444)
if [ $? != 0 ]; then
    echo "Command failed."
    exit 1
elif [ "$files" ]; then
    echo "Files with no readability permissions:"
    ls -l $files
    echo "\nTest failed"
    exit 1
fi