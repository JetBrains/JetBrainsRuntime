#!/bin/bash

set -euo pipefail

test_jdk=$1

if [[ ! -d "$test_jdk/bin" ]] || [[ ! -d "$test_jdk/lib" ]]; then
  echo "No JDK found in $test_jdk"
  exit 1
fi

test_passed=true

function check_glibc_for_file() {

  #Specify the path to the shared library file
  shared_library=$1

  echo "> Checking $shared_library"

  # Get the list of symbols from the shared library using "nm" command
  symbols=$(nm -D "$shared_library" | awk -F "@@" '{print $2}')

  # Check if any symbol is from glibc version higher than 2.17
  glibc_2_17_found=false
  while read -r symbol; do
      # Check if the symbol starts with "GLIBC_" and extract the version
      if [[ "$symbol" == GLIBC_* ]]; then
          version=${symbol#GLIBC_}

          # Compare the version with 2.17
          if [[ $(echo -e "2.17\n$version" | sort -rV | head -n1) != "2.17" ]]; then
              echo -e "\tDependency on symbol $symbol from glibc version higher than 2.17 found!"
              glibc_2_17_found=true
          fi
      fi
  done <<< "$symbols"

  # Print the result
  if [ "$glibc_2_17_found" = false ]; then
      echo -e "\tNo dependency on symbols from glibc version higher than 2.17 found."
  else
      test_passed=false
  fi
}

export -f check_glibc_for_file

find "$test_jdk/bin" -name "*"    -type f -exec bash -c 'check_glibc_for_file {}' \;
find "$test_jdk/lib" -name "*.so" -type f -exec bash -c 'check_glibc_for_file {}' \;

if [ "$test_passed" = false ]; then
  echo "****TEST FAILED"
  exit 1
fi
