#!/usr/bin/env bash

# @test
# @summary jsa6246.sh checks jsa files exist in jbrsdk distributions, jbr distributions are skipped
# @run shell jsa6246.sh

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA undefined: testing cancelled"
  exit 1
fi

source ${TESTJAVA}/release
echo "Checking $IMPLEMENTOR_VERSION"
if [[ "$IMPLEMENTOR_VERSION" != *"JBRSDK"* ]]; then
  echo "Test executed for JBRSDK only"
  echo "skipping the test"
  exit 0
fi

FIND="/usr/bin/find"
files=$(${FIND} ${TESTJAVA} -name "*.jsa")
ls -l $files
if [ $? != 0 ]; then
    echo "Command failed."
    exit 1
elif [ -z "$files" ]; then
    echo "*** FAILED *** jsa-files not found"
    echo "\n*** FAILED *** Test failed"
    exit 1
fi
echo "\nTest passed"
