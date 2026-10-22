# Foreground window: prints "<pid>|<process>|<title>". With -Activate <pid>, brings that process's main window to the front first.
param([int]$Activate = 0)
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public class FG { [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd); [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra); public static uint ForegroundPid() { uint pid; GetWindowThreadProcessId(GetForegroundWindow(), out pid); return pid; } }
"@
if ($Activate -ne 0) {
  $p = Get-Process -Id $Activate -ErrorAction SilentlyContinue
  if ($p -and $p.MainWindowHandle -ne 0) {
    # always maximize: the frame came up small in several runs (popup-miss screenshots, 2026-10-21)
    # and the driver's popup detection and the scene captures assume a maximized frame
    [void][FG]::ShowWindow($p.MainWindowHandle, 3)   # SW_MAXIMIZE
    # Windows refuses SetForegroundWindow to a process that did not receive the last input
    # (the user's own IDE kept the foreground once, 2026-10-21): a simulated Alt tap from this
    # process lifts the lock, a minimize/maximize cycle re-activates, three attempts
    for ($i = 0; $i -lt 3 -and [FG]::ForegroundPid() -ne $Activate; $i++) {
      [FG]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [FG]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)   # Alt down, up
      if ($i -gt 0) { [void][FG]::ShowWindow($p.MainWindowHandle, 6); Start-Sleep -Milliseconds 300 }   # SW_MINIMIZE
      [void][FG]::ShowWindow($p.MainWindowHandle, 3)   # SW_MAXIMIZE
      [void][FG]::SetForegroundWindow($p.MainWindowHandle)
      try { (New-Object -ComObject WScript.Shell).AppActivate($Activate) | Out-Null } catch {}
      Start-Sleep -Milliseconds 700
    }
  }
}
$h = [FG]::GetForegroundWindow(); $sb = New-Object System.Text.StringBuilder 512; [void][FG]::GetWindowText($h, $sb, 512)
$fpid = 0; [void][FG]::GetWindowThreadProcessId($h, [ref]$fpid)
$name = (Get-Process -Id $fpid -ErrorAction SilentlyContinue).ProcessName
"$fpid|$name|$($sb.ToString())"
