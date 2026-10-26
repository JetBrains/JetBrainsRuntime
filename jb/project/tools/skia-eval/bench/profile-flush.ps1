# Step 1 of notes/plan-flush-submission-2026-10-23.md: a CPU sampling profile (ETW, WPR's CPU
# profile) of the Skia flusher while SyncProbe runs the hand-over 680 times a second. Needs an
# elevated shell (ETW kernel sampling). Writes build\skia-eval-out\profile\<name>.etl; the analysis
# (tracerpt + the local PDB) runs without elevation afterwards.
#
#   powershell -ExecutionPolicy Bypass -File build\scripts\profile-flush.ps1 [-Name flush] [-Rounds 6]
#     [-Arm skia|d3d] [-Probe "lines 256 horizontal pixel 1000"] [-Class SyncProbe|PresentProbe|ide|scenes]

param(
    [string] $Name = 'flush',
    [int] $Rounds = 6,
    [string] $Arm = 'skia',
    [string] $Probe = 'lines 256 horizontal pixel 1000',
    [string] $Class = 'SyncProbe',   # or PresentProbe with -Probe "<seconds> heavy big", ide (the IDE's start-up), or scenes (the IDE scenes)
    [int] $Scale = 1,               # sun.java2d.uiScale for the probe
    [int] $Wait = 90,               # ide: seconds the IDE runs
    [string] $Lcd = 'accumulate'    # scenes, Skia arm: accumulate (A3) or twopass (A2)
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
    $ideArm = if ($Arm -eq 'd3d') { 'R1' } else { 'A3' }   # not $arm: PowerShell names ignore case, it would replace -Arm
    $cmd = "source build/scripts/ide-env.sh && bash jb/project/tools/skia-eval/text-quality/run-ide.sh $ideArm --wait $Wait -- jb/project/tools/skia-eval/text-quality/text-scene"
    & wpr.exe -start CPU -filemode
    try {
        $started = Get-Date
        & 'C:/Program Files/Git/usr/bin/bash.exe' -l "$repo/build/scripts/msys2.sh" $cmd 2>&1 | Select-String 'JRE:|Skia rendering|stopped'
        $seconds = ((Get-Date) - $started).TotalSeconds
    } finally {
        & wpr.exe -stop $etl
    }
} elseif ($Class -eq 'scenes') {
    # the IDE scene workload (run-scenes.sh, light, 2x, pipeline cache warm); the recording covers
    # the scenes only, from IdeScenes' start (its scenes.log appears) to its end, not the IDE's
    # start, indexing and settle
    $ideArm = if ($Arm -eq 'd3d') { 'R1' } elseif ($Lcd -eq 'twopass') { 'A2' } else { 'A3' }   # not $arm: PowerShell names ignore case, it would replace -Arm
    $armDir = @{ R1 = 'R1-d3d9'; A2 = 'A2-twopass'; A3 = 'A3-accumulate' }[$ideArm]
    $dir = "$out\scenes-$Name"
    $dest = "$dir\$armDir\light-2x"
    if ($ideArm -eq 'A2') { $etl = "$out\$Name-skia-twopass.etl" }
    if (Test-Path "$dest\scenes.log") { Remove-Item "$dest\scenes.log" }
    $cache = "$repo\build\skia-eval-out\pipeline-cache-ide"
    $posixDir = ($dir -replace '\\', '/') -replace '^C:', '/c'
    $cmd = "export IDE_EXTRA_VMOPTIONS='-Dsun.java2d.skia.pipelineCache=$cache'; source build/scripts/ide-env.sh && bash jb/project/tools/skia-eval/text-quality/run-scenes.sh $ideArm light 2 $posixDir"
    $job = Start-Job -ScriptBlock {
        param($msys2, $cmd, $log)
        & 'C:/Program Files/Git/usr/bin/bash.exe' -l $msys2 $cmd *> $log
    } -ArgumentList "$repo/build/scripts/msys2.sh", $cmd, "$out\scenes-$Name-$Arm.log"
    $recording = $false
    $started = $null
    $deadline = (Get-Date).AddMinutes(25)
    try {
        while ($job.State -eq 'Running' -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            if (-not $recording -and (Test-Path "$dest\scenes.log")) {
                & wpr.exe -start CPU -filemode
                $recording = $true
                $started = Get-Date
                "recording from $started"
            }
            if ($recording -and (Select-String -Path "$dest\scenes.log" -Pattern 'scenes done|Exception' -Quiet)) {
                break
            }
        }
    } finally {
        if ($recording) {
            & wpr.exe -stop $etl
            $seconds = ((Get-Date) - $started).TotalSeconds
        }
    }
    Wait-Job $job -Timeout 300 | Out-Null
    Remove-Job $job -Force
    if (-not $recording) { throw "the scenes never started; see $out\scenes-$Name-$Arm.log" }
    Get-Content "$dest\scenes.log" -Tail 1
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
