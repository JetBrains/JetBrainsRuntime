#!/bin/bash
# The memory of RenderPerfTest's 59 non-XOR tests (-e=volatile: no Robot, works with the display asleep) on Skia and
# Metal, interleaved in a fresh random order per repetition (macOS):
#   run-gpumem-mac.sh <out dir> <reps> [idle seconds]
# GpuMemDriver samples the footprint (and MTLDevice.currentAllocatedSize, probe built here) every 5 s and writes
# SKEnvironment.gpuMemoryReport and vmmap --summary at exit into <out dir>/gpumem-<arm>-<rep>.txt; with idle seconds,
# after a collection and that long idle (the steady idle state, Graphite's caches purged). Summary: gpumem-summary.py.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
OUT=$1; REPS=$2; IDLE=${3:-0}
CLASSES="$OUT_ROOT/gpumem/classes"; mkdir -p "$CLASSES" "$OUT"
SRC="$REPO/test/jdk/performance/client/RenderPerfTest"
"$JDK/bin/javac" -d "$CLASSES" "$SRC/src/renderperf/RenderPerfTest.java" "$HERE/GpuMemDriver.java" || exit 2
cp -R "$SRC/resources/." "$CLASSES/"
cat > "$CLASSES/MetalAllocated.m" <<'M'
#import <Metal/Metal.h>
#include <jni.h>
JNIEXPORT jlong JNICALL Java_GpuMemDriver_metalAllocated(JNIEnv *env, jclass clazz) {
    @autoreleasepool { id<MTLDevice> d = MTLCreateSystemDefaultDevice(); jlong b = d != nil ? (jlong) [d currentAllocatedSize] : -1; [d release]; return b; }
}
M
clang -dynamiclib -fno-objc-arc -framework Metal -framework Foundation -I"$JDK/include" -I"$JDK/include/darwin" \
      -o "$CLASSES/libmetalallocated.dylib" "$CLASSES/MetalAllocated.m" || exit 2
TESTS=$("$JDK/bin/java" -cp "$CLASSES" renderperf.RenderPerfTest -h 2>&1 | sed -n 's/.*Supported test arguments <tests>://p' | tr ' ' '\n' | grep -v XOR | grep . | tr '\n' ' ')
for rep in $(seq 1 "$REPS"); do
  for arm in $(printf 'skia\nmetal\n' | awk 'BEGIN{srand()} {print rand() "\t" $0}' | sort -n | cut -f2); do
    P=$([ "$arm" = metal ] && echo -Dsun.java2d.metal=true || echo -Dsun.java2d.skia=true)
    echo "rep $rep arm $arm"
    (cd "$OUT" && perl -e 'alarm 1500; exec @ARGV' "$JDK/bin/java" -Dsun.java2d.uiScale=1 $P -Dgpumem.probe="$CLASSES/libmetalallocated.dylib" \
        -Dgpumem.idleSeconds="$IDLE" --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED -cp "$CLASSES" GpuMemDriver "$arm-$rep" -e=volatile $TESTS > "rpt-$arm-$rep.log" 2>&1)
  done
done
(cd "$OUT" && python3 -I "$HERE/gpumem-summary.py")
