# Foreground window: prints "<pid>|<process>|<title>". With -Activate <pid>, brings that process's main window to the front first.
# With -Serve, one process answers for a whole run: a line "query" or "activate <pid>" on stdin, one report line on stdout
# (a PowerShell start-up cost 0.5-1 s per call before, and a scene makes a dozen calls; 2026-11-02).
param([int]$Activate = 0, [switch]$Serve)
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public struct RECT { public int Left, Top, Right, Bottom; }
public class FG { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int ht, uint flags); [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r); [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size); [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd); [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra); public static uint ForegroundPid() { uint pid; GetWindowThreadProcessId(GetForegroundWindow(), out pid); return pid; } }
"@
[void][FG]::SetProcessDPIAware()   # window coordinates in device pixels, whatever the display scale (reported as "phys ...")
function Activate-Target([int]$Target) {
  $p = Get-Process -Id $Target -ErrorAction SilentlyContinue
  if ($p -and $p.MainWindowHandle -ne 0) {
    # restored, not maximized: the driver places the frame at a fixed size (2026-11-02; the maximize of
    # 2026-10-21 made the frame fill the screen, taskbar excepted)
    [void][FG]::ShowWindow($p.MainWindowHandle, 9)   # SW_RESTORE
    # Windows refuses SetForegroundWindow to a process that did not receive the last input
    # (the user's own IDE kept the foreground once, 2026-10-21): a simulated Alt tap from this
    # process lifts the lock, a minimize/maximize cycle re-activates, three attempts
    for ($i = 0; $i -lt 3 -and [FG]::ForegroundPid() -ne $Target; $i++) {
      [FG]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [FG]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)   # Alt down, up
      if ($i -gt 0) { [void][FG]::ShowWindow($p.MainWindowHandle, 6); Start-Sleep -Milliseconds 300 }   # SW_MINIMIZE
      [void][FG]::ShowWindow($p.MainWindowHandle, 9)   # SW_RESTORE
      [void][FG]::SetForegroundWindow($p.MainWindowHandle)
      try { (New-Object -ComObject WScript.Shell).AppActivate($Target) | Out-Null } catch {}
      Start-Sleep -Milliseconds 700
    }
  }
}
function Report {
  $h = [FG]::GetForegroundWindow(); $sb = New-Object System.Text.StringBuilder 512; [void][FG]::GetWindowText($h, $sb, 512)
  $fpid = 0; [void][FG]::GetWindowThreadProcessId($h, [ref]$fpid)
  $name = (Get-Process -Id $fpid -ErrorAction SilentlyContinue).ProcessName
  "$fpid|$name|$($sb.ToString())"
}
# the visible frame bounds in device pixels ("phys x y w h"): DWM's extended frame, without the invisible resize borders
function Bounds-Of([int]$Target) {
  $p = Get-Process -Id $Target -ErrorAction SilentlyContinue
  if (-not $p -or $p.MainWindowHandle -eq 0) { return "none" }
  $r = New-Object RECT
  if ([FG]::DwmGetWindowAttribute($p.MainWindowHandle, 9, [ref]$r, 16) -ne 0) { [void][FG]::GetWindowRect($p.MainWindowHandle, [ref]$r) }
  "phys $($r.Left) $($r.Top) $($r.Right - $r.Left) $($r.Bottom - $r.Top)"
}
# "place <pid> x y w h" in device pixels: the outer window rectangle; the driver corrects for the border difference once
function Place-Target([string[]]$f) {
  $p = Get-Process -Id ([int]$f[1]) -ErrorAction SilentlyContinue
  if (-not $p -or $p.MainWindowHandle -eq 0) { return "none" }
  [void][FG]::ShowWindow($p.MainWindowHandle, 9)   # SW_RESTORE
  [void][FG]::SetWindowPos($p.MainWindowHandle, [IntPtr]::Zero, [int]$f[2], [int]$f[3], [int]$f[4], [int]$f[5], 0x0014)   # SWP_NOZORDER | SWP_NOACTIVATE
  Start-Sleep -Milliseconds 300
  Bounds-Of ([int]$f[1])
}
if ($Serve) {
  while ($null -ne ($line = [Console]::In.ReadLine())) {
    $f = $line -split ' '
    if ($f[0] -eq 'activate') { Activate-Target ([int]$f[1]); Write-Output (Report) }
    elseif ($f[0] -eq 'bounds') { Write-Output (Bounds-Of ([int]$f[1])) }
    elseif ($f[0] -eq 'place') { Write-Output (Place-Target $f) }
    else { Write-Output (Report) }
    [Console]::Out.Flush()
  }
  exit 0
}
if ($Activate -ne 0) { Activate-Target $Activate }
Report
