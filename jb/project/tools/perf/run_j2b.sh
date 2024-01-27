#!/bin/bash

BASE_DIR=$(dirname "$0")
source $BASE_DIR/run_inc.sh

J2DBENCH_DIR=$WS_ROOT/src/demo/share/java2d/J2DBench

if [ -z "$J2DBENCH" ]; then
  if [ ! -f "$J2DBENCH_DIR/dist/J2DBench.jar" ]; then
    PATH=$JAVA_HOME/bin:$PATH make -C $J2DBENCH_DIR
  fi
  if [ ! -f "$J2DBENCH_DIR/dist/J2DBench.jar" ]; then
    echo "Cannot build J2DBench. You may use J2DBench env variable instead pointing to the J2DBench.jar."
    exit 1
  fi
  J2DBENCH=$J2DBENCH_DIR/dist/J2DBench.jar
fi

if [ $# -ne 1 ] ; then
  echo "Usage: run_j2b.sh [rendering_options] bench_name"
  echo 
  echo "bench_name: poly250 poly250-rand_col poly250-AA-rand_col"
  echo ""            
  echo "$RENDER_OPS_DOC"
  exit 2
fi 

if [ ! -f "$BASE_DIR/j2dbopts_$1.txt" ]; then
  echo "Unknown test: $1"
  exit 1
fi
 


OPTS="j2dbopts_$1.txt"
#OPTS=j2dbopts_poly250.txt
#OPTS=j2dbopts_poly250-rand_col.txt
#OPTS=j2dbopts_poly250-AA-rand_col.txt

echo "OPTS: $OPTS"

for i in `seq $N`; do 
  if [ $i -eq 1 ]; then
    echo x
  fi
  echo `$JAVA $J2D_OPTS -jar $J2DBENCH \
  -batch -loadopts $BASE_DIR/$OPTS -saveres pl.res \
  -title pl -desc pl | awk '/averaged/{print $3}' | head -n1`

  if [ $i -ne $N ]; then
    sleep $ST
  fi
done | $DATAMASH_CMD | expand -t12
