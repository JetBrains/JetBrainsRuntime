#!/bin/bash -x

__jtreg_home=$1
__test_jdk=$2
__java_opts=$3
__test=$4
__count=${5:-20}

i=0
while true; do

  ((i=i+1))
  echo i: $i

  $__jtreg_home/bin/jtreg -v -a -testjdk:$__test_jdk -javaoptions:"$__java_opts" $__test || break

  if [ "$i" -ge "$__count" ]; then
    break
  fi
done
