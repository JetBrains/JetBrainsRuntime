# Step 1 of notes/plan-flush-submission-2026-10-23.md: a CPU sampling profile (ETW, WPR's CPU
# profile) of the Skia flusher while SyncProbe runs the hand-over 680 times a second. Needs an
# elevated shell (ETW kernel sampling). Writes build\skia-eval-out\profile\<name>.etl; the analysis
# (tracerpt + the local PDB) runs without elevation afterwards.
#
#   powershell -ExecutionPolicy Bypass -File build\scripts\profile-flush.ps1 [-Name flush] [-Rounds 6]
#     [-Arm skia|d3d] [-Probe "lines 256 horizontal pixel 1000"]

param(
    [string] $Name = 'flush',
    [int] $Rounds = 6,
    [string] $Arm = 'skia',
    [string] $Probe = 'lines 256 horizontal pixel 1000',
    [string] $Class = 'SyncProbe',   # or PresentProbe with -Probe "<seconds> heavy big", or ide (the IDE's start-up)
    [int] $Scale = 1,               # sun.java2d.uiScale for the probe
    [int] $Wait = 90                # ide: seconds the IDE runs
)
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'profile-flush.ps1 needs an elevated shell (ETW kernel sampling)' }

$repo = (Resolve-Path "$PSScriptRoot/../../../../..").Path   # the repository root
$java = "$repo\build\conf-skia\images\jdk\bin\java.exe"
$classes = "$repo\build\skia-eval-out\scratch\sp"
$out = "$repo\build\skia-eval-out\profile"
New-Item -ItemType Directory -Force $out | Out-Null
$etl = "$out\$Name-$Arm.etl"
$prop = if ($Arm -eq 'd3d') { '-Dsun.java2d.d3d=true' } else { '-Dsun.java2d.skia=true' }

$uiScale = "-Dsun.java2d.uiScale=$Scale"
if ($Class -eq 'ide') {
    # the IDE on the image through the kit's run-ide.sh (arm A3 = Skia accumulate, R1 = Direct3D 9), start-up and indexing
    $arm = if ($Arm -eq 'd3d') { 'R1' } else { 'A3' }
    $cmd = "source build/scripts/ide-env.sh && bash jb/project/tools/skia-eval/text-quality/run-ide.sh $arm --wait $Wait -- jb/project/tools/skia-eval/text-quality/text-scene"
    & wpr.exe -start CPU -filemode
    try {
        $started = Get-Date
        & 'C:/Program Files/Git/usr/bin/bash.exe' -l "$repo/build/scripts/msys2.sh" $cmd 2>&1 | Select-String 'JRE:|Skia rendering|stopped'
        $seconds = ((Get-Date) - $started).TotalSeconds
    } finally {
        & wpr.exe -stop $etl
    }
} else {
    # a cold round first, so the recording holds steady-state frames only
    & $java $prop $uiScale -cp $classes $Class $Probe.Split(' ') 2>&1 | Out-Null
    & wpr.exe -start CPU -filemode
    try {
        $started = Get-Date
        for ($i = 0; $i -lt $Rounds; $i++) {
            $line = & $java $prop $uiScale -cp $classes $Class $Probe.Split(' ') 2>&1 | Select-String 'median|cycles'
            "round $i $line"
        }
        $seconds = ((Get-Date) - $started).TotalSeconds
    } finally {
        & wpr.exe -stop $etl
    }
}
"recorded $seconds s of $Rounds rounds into $etl ($([int]((Get-Item $etl).Length / 1MB)) MB)"
