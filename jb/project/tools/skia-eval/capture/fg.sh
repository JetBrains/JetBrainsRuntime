#!/bin/bash
# Foreground application: prints "<pid>|<name>|<title>". With --activate <pid>, activates that
# application first (AppKit, no accessibility permission needed; the title needs it and may be empty).
if [ "${1:-}" = "--activate" ]; then
  osascript -l JavaScript -e "ObjC.import('AppKit'); var a = \$.NSRunningApplication.runningApplicationWithProcessIdentifier($2); if (a.js !== undefined && !a.isNil()) { a.activateWithOptions(\$.NSApplicationActivateIgnoringOtherApps | \$.NSApplicationActivateAllWindows); }" >/dev/null 2>&1
  sleep 0.7
fi
osascript -l JavaScript -e 'ObjC.import("AppKit"); var f = $.NSWorkspace.sharedWorkspace.frontmostApplication; var pid = f.processIdentifier; var name = ObjC.unwrap(f.localizedName); var title = ""; try { var se = Application("System Events"); var p = se.processes.whose({unixId: pid})[0]; title = p.windows[0].name(); } catch (e) {} pid + "|" + name + "|" + title'
