#!/usr/bin/env bash

set -x

# @test
# @summary CDSArchivesTest.sh checks jsa files exist in jbrsdk distributions, jbr distributions are skipped
# @run shell CDSArchivesTest.sh

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA undefined: testing cancelled"
  exit 1
fi

IMPLEMENTOR_VERSION=$(cat ${TESTJAVA}/release | grep "IMPLEMENTOR_VERSION" | cut -d"=" -f2)

echo "Checking ${IMPLEMENTOR_VERSION}"
if [ "${IMPLEMENTOR_VERSION}" != *"JBRSDK"* ]; then
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
