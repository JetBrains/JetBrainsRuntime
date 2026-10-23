#!/bin/bash
# One IDE start-up (A3, 90 s by default) with J2D_SKIA_STATS, summarised: the flusher's insert,
# insert:compiling, snap, submit and present totals, pipelines built / precompiled / found, and
# StartupCost's first frame. Environment variables for the experiment (J2D_SKIA_PIPELINE_CACHE,
# J2D_SKIA_EXECUTOR) pass through msys2.sh to the IDE and the probe; PIPELINE_CACHE=<dir|off> (default off)
# sets sun.java2d.skia.pipelineCache for both.
#   build/scripts/ide-startup-stats.sh <label> [wait seconds]
set -u
cd "$(dirname "$0")/../.."
label=$1; wait=${2:-90}
export J2D_SKIA_STATS=1
passthrough=""   # only the variables that are set, so that an empty value cannot switch an experiment on
pc=${PIPELINE_CACHE:-off}; [ "$pc" != off ] && pc=$(cygpath -w "$pc" 2>/dev/null || echo "$pc")   # a POSIX path would land in C:...
nl=$'\n'; export IDE_EXTRA_VMOPTIONS="-Dsun.java2d.skia.pipelineCache=$pc${IDE_EXTRA_VMOPTIONS:+$nl$IDE_EXTRA_VMOPTIONS}"   # the caller's options stay
arm=${ARM:-A3}   # ARM=A4: present per paint cycle
for v in J2D_SKIA_PIPELINE_CACHE J2D_SKIA_EXECUTOR IDE_EXTRA_VMOPTIONS; do [ -n "${!v:-}" ] && passthrough="$passthrough $v='${!v}'"; done
bash build/scripts/msys2.sh "export J2D_SKIA_STATS=1 $passthrough; source build/scripts/ide-env.sh && bash jb/project/tools/skia-eval/text-quality/run-ide.sh $arm --wait $wait -- jb/project/tools/skia-eval/text-quality/text-scene" > "build/tmp/ide-startup-$label.txt" 2>&1
sleep 3
s=$(ls -d build/skia-eval-out/ide/$arm-*/stdout.txt)
cp "$s" "build/skia-eval-out/ide/startup-$label.stdout.txt"
built=$(grep -c 'pipeline built' "$s"); pre=$(grep -c 'pipeline precompiled' "$s")
keys=$(grep -oE 'precompiled [0-9]+ of [0-9]+ stored pipelines|[0-9]+ pipeline keys from' "$s" | tr '\n' ';')
sums=$(grep -E 'SKStats [0-9.]+ s:' "$s" | grep -oE '(snap|insert|insert:compiling|submit|present) [0-9.]+ ms \([0-9]+\)|: [0-9]+ entries' \
  | awk '/entries/ {e+=$2; next} {ms[$1]+=$2; n[$1]+=substr($4,2)} END {printf "entries %d; ", e; for (k in ms) printf "%s %.0f ms/%d; ", k, ms[k], n[k]}')
J=build/conf-skia/images/jdk
blobs=$(grep -oE 'blobs loaded [0-9]+ \(hits [0-9]+\), stored [0-9]+' "$s" | awk '{l+=$3; h+=substr($5,1,length($5)-2); st+=$7} END {printf "blobs loaded %d (hits %d), stored %d", l, h, st}')
first=$("$J/bin/java.exe" --add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache="$pc" -Dsun.java2d.uiScale=1 -cp build/skia-eval-out/smoke-classes StartupCost 2>&1 | grep -oE 'off-screen frame 1: [0-9.]+ ms|window first paint\+sync: [0-9.]+ ms' | tr '\n' ';')
echo "$label: $sums pipelines built $built (precompiled $pre); $blobs; $keys StartupCost: $first"
