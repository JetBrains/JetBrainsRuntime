ST=1 # sleep between iterations

# number of iterations (jvm spawned)
N=5
# number of repeats (within jvm)
R=3

type datamash 2>&1 > /dev/null ; ec=$?
if [ $ec -ne 0 ] ; then
  echo "Missing datamash utility"
  exit 1
fi

DATAMASH_CMD="datamash --format=%.2f -H count x min x q1 x median x q3 x max x mad x"

J2D_OPTS=""
OS=""
case "$OSTYPE" in
  linux*)  echo "Linux" 
      ;;	  
  darwin*)  echo "OSX" 
      ;; 
  *)        echo "unknown: $OSTYPE" 
      exit 1
      ;;
esac

read -r -d '' RENDER_OPS_DOC << EOM
rendering_options:
  -opengl # OpenGL pipeline (windows, linux, macOS)
  -metal  # Metal pipeline (macOS)
  -tk tk_name # AWT toolkit (linux: WLToolkit|XToolkit)
  -scale # UI scale
EOM

while [ $# -ge 1 ] ; do
  case "$1" in
    -opengl) J2D_OPTS=$J2D_OPTS" -Dsun.java2d.opengl=true"
      shift
        ;;
    -metal) J2D_OPTS=$J2D_OPTS" -Dsun.java2d.metal=true"
      shift
        ;;
    -tk) shift
      if [ $# -ge 1 ] ; then
        J2D_OPTS=$J2D_OPTS" -Dawt.toolkit.name="$1
         shift
      else
         echo "Invalid parameters for -tk option. Use: -tk tkname"
         exit 1
      fi
      ;;
    -scale) shift
      if [ $# -ge 1 ] ; then
        J2D_OPTS=$J2D_OPTS" -Dsun.java2d.uiScale="$1
         shift
      else
         echo "Invalid parameters for -scale option. Use: -scale scale"
         exit 1
      fi
      ;;
    -dSync) shift   
      if [ $# -ge 1 ] ; then
        J2D_OPTS=$J2D_OPTS" -Dsun.java2d.metal.displaySync="$1
         shift
      else
         echo "Invalid parameters for -dSync option. Use: -dSync [true|false]"
         exit 1
      fi
        ;;
    -jdk) shift
      if [ $# -ge 1 ] ; then
         JAVA=$1/bin/java
         shift
      else
         echo "Invalid parameters for -jdk option"
         exit 1
      fi
        ;;
      *) break
        ;;
  esac
done    
if [ -z "$JAVA" ] ; then
    BUILD_DIR=`find $BASE_DIR/../../../../build -name '*-release' -type d | head -n 1`
    JAVA=`find $BUILD_DIR/images/jdk -name java -type f | head -n 1`
fi

JAVA_HOME=`dirname $JAVA`/../
"$JAVA" -version

LANG=C

WS_ROOT=$BASE_DIR/../../../..

echo "N: $N"
echo "R: $R"  
echo "J2D_OPTS: $J2D_OPTS"
