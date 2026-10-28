# Forces a real GPU device loss (TDR) under TdrProbe per arm (skia, skia-d3d12 = Skia on Dawn's D3D12 backend,
# d3d = Direct3D 9, gdi = no acceleration;
# -Arms selects), then runs DisplayChangeProbe on Skia. Needs an elevated shell and the "Graphics Tools"
# optional feature (dxcap.exe). Every process on the GPU sees the reset: close unrelated work first.
#
#   powershell -ExecutionPolicy Bypass -File run-tdr.ps1 -Jdk <image> -Out <dir>

param(
    [Parameter(Mandatory)] [string] $Jdk,
    [Parameter(Mandatory)] [string] $Out,
    [int] $Seconds = 60,
    [int] $TdrAfter = 20,
    [string[]] $Arms = @('skia', 'd3d', 'gdi')
)

$ErrorActionPreference = 'Stop'
$Arms = @($Arms | ForEach-Object { $_ -split ',' } | Where-Object { $_ })   # -File passes "a,b" as one string
$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'run-tdr.ps1 needs an elevated shell' }
$dxcap = Join-Path $env:SystemRoot 'System32\dxcap.exe'
if (-not (Test-Path $dxcap)) { throw "dxcap.exe not found; install the Graphics Tools optional feature" }

$smoke = $PSScriptRoot
$classes = Join-Path $Out 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$exports = @('--add-exports', 'java.desktop/sun.java2d=ALL-UNNAMED', '--add-exports', 'java.desktop/sun.awt=ALL-UNNAMED',
             '--add-exports', 'java.desktop/sun.awt.windows=ALL-UNNAMED', '--add-exports', 'java.desktop/sun.java2d.skia=ALL-UNNAMED')
& "$Jdk\bin\javac.exe" @exports -d $classes "$smoke\TdrProbe.java" "$smoke\DisplayChangeProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$summary = Join-Path $Out 'summary.txt'
"run-tdr.ps1 $(Get-Date -Format s) jdk=$Jdk seconds=$Seconds tdrAfter=$TdrAfter" | Set-Content -Encoding utf8 $summary
$env:J2D_TRACE_LEVEL = '3'
$cacheProp = "-Dsun.java2d.skia.pipelineCache=$(Join-Path $Out 'pipeline-cache')"   # not the default under user.home

$armProperties = [ordered]@{
    'skia' = @('-Dsun.java2d.skia=true', '-Dsun.java2d.skia.verbose=true')
    'skia-d3d12' = @('-Dsun.java2d.skia=true', '-Dsun.java2d.skia.verbose=true', '-Dsun.java2d.skia.dawn.backend=d3d12')
    'd3d'  = @('-Dsun.java2d.d3d=true', '-Dsun.java2d.d3d.verbose=true')
    'gdi'  = @('-Dsun.java2d.d3d=false')
}
foreach ($arm in @($armProperties.Keys | Where-Object { $Arms -contains $_ })) {
    $armOut = Join-Path $Out $arm
    New-Item -ItemType Directory -Force $armOut | Out-Null
    $javaArgs = @($exports) + $armProperties[$arm] + @($cacheProp, '-Dsun.java2d.uiScale=1', '-cp', $classes, 'TdrProbe', $Seconds, $armOut)
    $started = Get-Date
    $p = Start-Process -FilePath "$Jdk\bin\java.exe" -ArgumentList $javaArgs -PassThru -NoNewWindow `
        -RedirectStandardOutput "$armOut\stdout.txt" -RedirectStandardError "$armOut\stderr.txt"
    Start-Sleep -Seconds $TdrAfter
    $tdrAt = Get-Date
    & $dxcap -forcetdr *>&1 | Out-File -Encoding utf8 "$armOut\dxcap.txt"
    $p | Wait-Process -Timeout ($Seconds + 60) -ErrorAction SilentlyContinue
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; $exit = 'killed (hung)' } else { $exit = $p.ExitCode }
    $events = Get-WinEvent -FilterHashtable @{ LogName = 'System'; StartTime = $started } -ErrorAction SilentlyContinue |
        Where-Object { $_.ProviderName -match 'Display|nvlddmkm|Dxgkrnl|amdkmdag|WHEA' -or $_.Id -eq 4101 } |
        ForEach-Object { '{0:HH:mm:ss} {1} id={2}: {3}' -f $_.TimeCreated, $_.ProviderName, $_.Id, (($_.Message -split "`n")[0]).Trim() }
    $events | Set-Content -Encoding utf8 "$armOut\events.txt"
    Add-Content -Encoding utf8 $summary "`n== ${arm}: exit=$exit, TDR forced at $($tdrAt.ToString('HH:mm:ss')) (+$TdrAfter s)"
    Add-Content -Encoding utf8 $summary (Get-Content "$armOut\dxcap.txt")
    Add-Content -Encoding utf8 $summary (Get-Content "$armOut\stdout.txt" | Select-String -Pattern 'Skia rendering enabled|^t=' | ForEach-Object { $_.Line })
    Add-Content -Encoding utf8 $summary '-- stderr (trace) --'
    Add-Content -Encoding utf8 $summary (Get-Content "$armOut\stderr.txt" -ErrorAction SilentlyContinue | Select-Object -First 60)
    Add-Content -Encoding utf8 $summary '-- System events --'
    Add-Content -Encoding utf8 $summary $events
    Start-Sleep -Seconds 10
}

$dc = Join-Path $Out 'displaychange'
New-Item -ItemType Directory -Force $dc | Out-Null
$ErrorActionPreference = 'Continue'   # its stderr would otherwise end the script (PowerShell 5.1 NativeCommandError)
& "$Jdk\bin\java.exe" @exports '-Dsun.java2d.skia=true' '-Dsun.java2d.skia.verbose=true' $cacheProp -cp $classes DisplayChangeProbe *>&1 | Out-File -Encoding utf8 "$dc\output.txt"
Add-Content -Encoding utf8 $summary "`n== DisplayChangeProbe (skia): exit=$LASTEXITCODE"
Add-Content -Encoding utf8 $summary (Get-Content "$dc\output.txt")
"done; summary in $summary"
