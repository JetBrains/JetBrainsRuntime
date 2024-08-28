#!/bin/bash

#set -euo pipefail
set -x

BASE_DIR=$(dirname "$0")
source $BASE_DIR/run_inc.sh

if [ -z "$DACAPOTEST_DIR" ]; then
  DACAPOTEST_DIR="./"
fi

DACAPOTEST=${DACAPOTEST:='dacapo-9.12-bach.jar'}
if [ -z "$DACAPOTEST" ]; then
  if [ ! -f "$DACAPOTEST_DIR/$DACAPOTEST" ]; then
    echo "ERR### cannot find $DACAPOTEST_DIR/$DACAPOTEST"
    exit 2
  fi
fi


TRACE=false

if [[ ($# -eq 1 && "$1" == "-help") ]] ; then
  echo "Usage: run_dc.sh [rendering_options]"
  echo "$RENDER_OPS_DOC"
  exit 3
fi

OPTS=""
# use time + repeat
OPTS="$OPTS -no-validation $1"

echo "OPTS: $OPTS"

echo "Unit: Milliseconds (not FPS), lower is better"

for i in `seq $N` ; do 
  if [ $i -eq 1 ]; then
    echo x
  fi

  $JAVA \
  -jar $DACAPOTEST $OPTS 2>&1 | tee dacapo_$1$MODE_$i.log | grep "PASSED" | awk '{print $7 }'

  if [ $i -ne $N ]; then
    sleep $ST
  fi
done | $DATAMASH_CMD | expand -t12 > dacapo_$1.log
