import Foundation
import AppKit
import ApplicationServices
// Window helper for the scene driver, without osascript (which took 0.6 s per call; a scene makes a dozen).
//   fgfast                      the foreground application as "<pid>|<name>|<title>", like fg.sh
//   fgfast --activate <pid>     activates that application first
//   fgfast --serve              one process for a whole run: a line on stdin, one line on stdout:
//       query                   → "<pid>|<name>|<title>"
//       activate <pid>          → the same, after activating
//       bounds <pid>            → "x y w h" of the pid's largest on-screen window in points (top-left origin, as Java), or "none"
//       place <pid> x y w h     → the same window moved and sized through the accessibility API (needs the
//                                 Accessibility permission, as System Events did), then its bounds
func frontReport() -> String {
    let front = NSWorkspace.shared.frontmostApplication
    let pid = front?.processIdentifier ?? 0
    var title = ""
    if let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] {
        for w in list where (w[kCGWindowOwnerPID as String] as? Int32) == pid && (w[kCGWindowLayer as String] as? Int) == 0 {
            if let name = w[kCGWindowName as String] as? String, !name.isEmpty { title = name; break }
        }
    }
    return "\(pid)|\(front?.localizedName ?? "")|\(title)"
}
func activate(_ pidText: String) {
    if let pid = Int32(pidText), let app = NSRunningApplication(processIdentifier: pid) {
        app.activate(options: [.activateIgnoringOtherApps, .activateAllWindows])
        Thread.sleep(forTimeInterval: 0.3)
    }
}
func largestWindow(_ pid: Int32) -> CGRect? {
    guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return nil }
    var best: CGRect? = nil
    for w in list where (w[kCGWindowOwnerPID as String] as? Int32) == pid && (w[kCGWindowLayer as String] as? Int) == 0 {
        if let b = w[kCGWindowBounds as String] as? [String: CGFloat], let x = b["X"], let y = b["Y"], let wd = b["Width"], let ht = b["Height"] {
            let r = CGRect(x: x, y: y, width: wd, height: ht)
            if best == nil || r.width * r.height > best!.width * best!.height { best = r }
        }
    }
    return best
}
func boundsReport(_ pidText: String) -> String {
    guard let pid = Int32(pidText), let r = largestWindow(pid) else { return "none" }
    return "\(Int(r.minX)) \(Int(r.minY)) \(Int(r.width)) \(Int(r.height))"
}
func place(_ parts: [String]) -> String {
    guard parts.count >= 6, let pid = Int32(parts[1]), let x = Double(parts[2]), let y = Double(parts[3]), let w = Double(parts[4]), let h = Double(parts[5]) else { return "none" }
    let app = AXUIElementCreateApplication(pid)
    var windowsValue: CFTypeRef?
    guard AXUIElementCopyAttributeValue(app, kAXWindowsAttribute as CFString, &windowsValue) == .success, let windows = windowsValue as? [AXUIElement] else { return "ax-denied" }
    // the largest window, as bounds reports it
    var target: AXUIElement? = nil; var targetArea: Double = -1
    for win in windows {
        var sizeValue: CFTypeRef?
        if AXUIElementCopyAttributeValue(win, kAXSizeAttribute as CFString, &sizeValue) == .success, let sv = sizeValue {
            var size = CGSize.zero
            AXValueGetValue(sv as! AXValue, .cgSize, &size)
            let area = Double(size.width * size.height)
            if area > targetArea { targetArea = area; target = win }
        }
    }
    guard let win = target else { return "none" }
    var pos = CGPoint(x: x, y: y); var size = CGSize(width: w, height: h)
    if let pv = AXValueCreate(.cgPoint, &pos) { AXUIElementSetAttributeValue(win, kAXPositionAttribute as CFString, pv) }
    if let sv = AXValueCreate(.cgSize, &size) { AXUIElementSetAttributeValue(win, kAXSizeAttribute as CFString, sv) }
    if let pv = AXValueCreate(.cgPoint, &pos) { AXUIElementSetAttributeValue(win, kAXPositionAttribute as CFString, pv) }   // the size change may have moved it
    Thread.sleep(forTimeInterval: 0.3)
    return boundsReport(parts[1])
}
let args = CommandLine.arguments
if args.count >= 2 && args[1] == "--serve" {
    setvbuf(stdout, nil, _IOLBF, 0)
    while let line = readLine() {
        let parts = line.split(separator: " ").map(String.init)
        switch parts.first {
        case "activate": if parts.count > 1 { activate(parts[1]) }; print(frontReport())
        case "bounds": print(parts.count > 1 ? boundsReport(parts[1]) : "none")
        case "place": print(place(parts))
        default: print(frontReport())
        }
    }
    exit(0)
}
if args.count >= 3 && args[1] == "--activate" { activate(args[2]) }
print(frontReport())
