#!/bin/bash

#set -euo pipefail
#set -x

BASE_DIR=$(dirname "$0")
source $BASE_DIR/run_inc.sh

RENDERPERFTEST_DIR=$WS_ROOT/test/jdk/performance/client/RenderPerfTest
RENDERPERFTEST=""
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

# removes leading hyphen
mode_param="${1/-}"
MODE="Robot"
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

if [[ ($# -eq 1 && "$1" == "-help") || ($# -eq 0)  ]] ; then
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

OPTS=""
# use time + repeat
OPTS="$OPTS -t -n=$N  -e$MODE $1"

echo "OPTS: $OPTS"

echo "Unit: Milliseconds (not FPS), lower is better"
for i in `seq $R` ; do 
  if [ $i -eq 1 ]; then
    echo x
  fi

#  echo "[debug] " + "test run"
#  $JAVA $J2D_OPTS -DTRACE=$TRACE \
#  -jar $RENDERPERFTEST $OPTS 2>&1 | awk '/'$1'/{print $3 }' | tee test_run.log

  echo "=== DEBUG run_rp.sh ===" >&2
  echo "JAVA=[$JAVA]" >&2
  echo "J2D_OPTS=[$J2D_OPTS]" >&2
  echo "TRACE=[$TRACE]" >&2
  echo "RENDERPERFTEST=[$RENDERPERFTEST]" >&2
  echo "OPTS=[$OPTS]" >&2
  echo "MODE=[$MODE]" >&2
  echo "mode_param=[$mode_param]" >&2
  echo "JAVA_TOOL_OPTIONS=[${JAVA_TOOL_OPTIONS:-}]" >&2
  echo "JAVA_OPTS=[${JAVA_OPTS:-}]" >&2
  echo "_JAVA_OPTIONS=[${_JAVA_OPTIONS:-}]" >&2
  echo "JDK_JAVA_OPTIONS=[${JDK_JAVA_OPTIONS:-}]" >&2
  echo "CMD: $JAVA $J2D_OPTS -DTRACE=$TRACE -jar $RENDERPERFTEST $OPTS -v" >&2
  echo "======================" >&2

  $JAVA $J2D_OPTS -DTRACE=$TRACE \
  -jar $RENDERPERFTEST $OPTS -v 2>&1 | tee render_$1_${mode_param}_$i.log | \
  awk '/^'$1' /{print $3}'
  if [ $i -ne $R ]; then
    sleep $ST
  fi
done | $DATAMASH_CMD | expand -t12 | tee render_$1_${mode_param}.log
