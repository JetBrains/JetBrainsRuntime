#!/bin/bash

BASE_DIR=$(dirname "$0")
source $BASE_DIR/run_inc.sh

SWINGMARK_DIR=$WS_ROOT/test/jdk/performance/client/SwingMark

if [ -z "$SWINGMARK" ]; then
  if [ ! -f "$SWINGMARK_DIR/dist/SwingMark.jar" ]; then
    PATH=$JAVA_HOME/bin:$PATH make -C $SWINGMARK_DIR
  fi
  if [ ! -f "$SWINGMARK_DIR/dist/SwingMark.jar" ]; then
    echo "Cannot build SwingMark. You may use SWINGMARK env variable instead pointing to the SwingMark.jar."
    exit 1
  fi
  SWINGMARK=$SWINGMARK_DIR/dist/SwingMark.jar
fi

if [ $# -eq 1 -a "$1" == "--help" ] ; then
  shift	
  echo "Usage: run_sm [rendering_options]"
  echo ""            
  echo "$RENDER_OPS_DOC"
  exit 0
fi 

for i in `seq $N` ; do 
  if [ $i -eq 1 ]; then
    echo x
  fi
 
  # SwingMark gives 1 global 'Score: <value>'
  echo `$JAVA $J2D_OPTS -jar $BASE_DIR/../../../../test/jdk/performance/client/SwingMark/dist/SwingMark.jar \
  -r $R -q -lf javax.swing.plaf.metal.MetalLookAndFeel | awk '/Score/{print $2}'`

  if [ $i -ne $N ]; then
    sleep $ST
  fi
done | $DATAMASH_CMD | expand -t12
