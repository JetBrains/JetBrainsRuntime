#!/bin/bash

BASE_DIR=$(dirname "$0")
source $BASE_DIR/run_inc.sh

RENDERPERFTEST_DIR=$WS_ROOT/test/jdk/performance/client/RenderPerfTest

if [ -z "$RENDERPERFTEST" ]; then
  if [ ! -f "$RENDERPERFTEST_DIR/dist/RenderPerfTest.jar" ]; then
    PATH=$JAVA_HOME/bin:$PATH make -C $RENDERPERFTEST_DIR
  fi
  if [ ! -f "$RENDERPERFTEST_DIR/dist/RenderPerfTest.jar" ]; then
    echo "Cannot build RenderPerfTest. You may use RENDERPERFTEST env variable instead pointing to the RenderPerfTest.jar."
    exit 1
  fi
  RENDERPERFTEST=$RENDERPERFTEST_DIR/dist/RenderPerfTest.jar
fi


TRACE=false

#MODE=Robot
#MODE=Buffer
MODE=Volatile

if [ $# -eq 1 -a "$1" == "-help"  ] ; then
  echo "Usage: run_rp.sh [rp_rendering_mode] [rendering_options] bench_name"
  echo 
  echo "bench_name: ArgbSurfaceBlitImage ArgbSwBlitImage BgrSurfaceBlitImage BgrSwBlitImage"
  echo "            Image ImageAA Image_XOR VolImage VolImageAA"
  echo "            ClipFlatBox ClipFlatBoxAA ClipFlatOval ClipFlatOvalAA"
  echo "            FlatBox FlatBoxAA FlatOval FlatOvalAA FlatOval_XOR FlatQuad FlatQuadAA"
  echo "            RotatedBox RotatedBoxAA RotatedBox_XOR RotatedOval RotatedOvalAA" 
  echo "            WiredBox WiredBoxAA WiredBubbles WiredBubblesAA WiredQuad WiredQuadAA"
  echo "            Lines LinesAA Lines_XOR"
  echo "            TextGray TextLCD TextLCD_XOR TextNoAA TextNoAA_XOR"
  echo "            LargeTextGray LargeTextLCD LargeTextNoAA WhiteTextGray WhiteTextLCD WhiteTextNoAA"
  echo "            LinGrad3RotatedOval LinGrad3RotatedOvalAA LinGradRotatedOval LinGradRotatedOvalAA"
  echo "            RadGrad3RotatedOval RadGrad3RotatedOvalAA" 
  echo ""            
  echo "rp_rendering_mode: "
  echo "            -onscreen : rendering to the window and check it using Robot" 
  echo "            -volatile : rendering to volatile image (default)" 
  echo "            -buffer   : rendering to buffered image" 
  echo "$RENDER_OPS_DOC"
  exit 2
fi 

while [ $# -ge 1 ] ; do
  case "$1" in
    -onscreen) MODE="Robot"
      shift
        ;;
    -volatile) MODE="Volatile"
      shift
        ;;
    -buffer) MODE="Buffer"
      shift
        ;;
      *) break
        ;;
  esac
done    

# use time + repeat
OPTS="$OPTS -r$R -t -e$MODE $1"

echo "OPTS: $OPTS"

echo "Unit: Milliseconds (not FPS), lower is better"

for i in `seq $N` ; do 
  if [ $i -eq 1 ]; then
    echo x
  fi

  $JAVA $J2D_OPTS -DTRACE=$TRACE \
  -jar $RENDERPERFTEST $OPTS 2>&1 | tail -n +2 | \
  awk '/'$1'/{print $3 }' 
  if [ $i -ne $N ]; then
    sleep $ST
  fi
done | $DATAMASH_CMD | expand -t12
