import AppKit
import CoreText

// Font smoothing probe: the same line of text through AppKit (a bitmap-backed NSGraphicsContext and an
// offscreen text field), a plain CoreGraphics bitmap context as the JDK's glyph canvas starts out, and
// contexts with smoothing forced on and off; the ink and the colour-fringe count of each, PNGs saved.
// AppleFontSmoothing is read from user defaults, so `-AppleFontSmoothing N` on the command line sets it
// for this process alone (the argument domain).
//   smoothprobe <label> <out-dir> [-AppleFontSmoothing N]
let text = "Hamburgefonts 123 Quick fox"
let W = 360, H = 26
let label = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "x"
let outDir = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "."
let font = NSFont(name: "Helvetica Neue", size: 13)!

func inkOf(_ img: CGImage) -> (Int, Int) {   // ink = sum of 255 - avg(R, G, B); pixels with R != B
    let w = img.width, h = img.height, bpr = w * 4
    var buf = [UInt8](repeating: 0, count: bpr * h)
    let cs = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: bpr, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
    var ink = 0, fringes = 0
    for i in stride(from: 0, to: bpr * h, by: 4) {
        let r = Int(buf[i]), g = Int(buf[i + 1]), b = Int(buf[i + 2])
        ink += 255 - (r + g + b) / 3
        if r != b { fringes += 1 }
    }
    return (ink, fringes)
}
func save(_ img: CGImage, _ name: String) {
    let rep = NSBitmapImageRep(cgImage: img)
    if let d = rep.representation(using: .png, properties: [:]) { try? d.write(to: URL(fileURLWithPath: "\(outDir)/\(label)-\(name).png")) }
}
func report(_ name: String, _ img: CGImage, _ extra: String = "") {
    let (ink, fringes) = inkOf(img)
    print("\(label) \(name): ink \(ink) fringes \(fringes) \(extra)")
    save(img, name)
}

// private entry points, looked up rather than declared
typealias GetBool = @convention(c) (CGContext) -> Bool
typealias GetInt = @convention(c) (CGContext) -> Int32
typealias SetInt = @convention(c) (CGContext, Int32) -> Void
let handle = dlopen(nil, RTLD_NOW)
let getSmooth = dlsym(handle, "CGContextGetShouldSmoothFonts").map { unsafeBitCast($0, to: GetBool.self) }
let getStyle = dlsym(handle, "CGContextGetFontSmoothingStyle").map { unsafeBitCast($0, to: GetInt.self) }
let getAllows = dlsym(handle, "CGContextGetAllowsFontSmoothing").map { unsafeBitCast($0, to: GetBool.self) }
let setStyle = dlsym(handle, "CGContextSetFontSmoothingStyle").map { unsafeBitCast($0, to: SetInt.self) }
let disabledFn = dlsym(handle, "CGFontRenderingGetFontSmoothingDisabled")
let prefHost = CFPreferencesCopyValue("AppleFontSmoothing" as CFString, kCFPreferencesAnyApplication, kCFPreferencesCurrentUser, kCFPreferencesCurrentHost)
let prefApp = CFPreferencesCopyAppValue("AppleFontSmoothing" as CFString, kCFPreferencesCurrentApplication)
let prefNS = UserDefaults.standard.object(forKey: "AppleFontSmoothing")
let cgDisabled = CFPreferencesCopyAppValue("CGFontRenderingFontSmoothingDisabled" as CFString, kCFPreferencesAnyApplication)
print("\(label) prefs: CFPreferences(anyApp,currentHost)=\(String(describing: prefHost)) CFPreferences(app)=\(String(describing: prefApp)) NSUserDefaults=\(String(describing: prefNS)) CGFontRenderingFontSmoothingDisabled=\(String(describing: cgDisabled)) symbols: getShouldSmooth=\(getSmooth != nil) getStyle=\(getStyle != nil) setStyle=\(setStyle != nil) disabledFn=\(disabledFn != nil)")

func stateOf(_ ctx: CGContext) -> String {
    "shouldSmooth=\(getSmooth.map { String($0(ctx)) } ?? "?") allows=\(getAllows.map { String($0(ctx)) } ?? "?") style=\(getStyle.map { String($0(ctx)) } ?? "?")"
}
func cgDraw(_ name: String, smooth: Bool?, style: Int32? = nil, alpha: Bool = true) {
    let cs = CGColorSpaceCreateDeviceRGB()
    let info = alpha ? (CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) : CGImageAlphaInfo.noneSkipLast.rawValue
    let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: W * 4, space: cs, bitmapInfo: info)!
    ctx.setFillColor(CGColor(gray: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
    ctx.setShouldAntialias(true)
    if let s = smooth { ctx.setAllowsFontSmoothing(true); ctx.setShouldSmoothFonts(s) }
    if let st = style, let f = setStyle { f(ctx, st) }
    let state = stateOf(ctx)
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor.black]))
    ctx.textPosition = CGPoint(x: 4, y: 7)
    CTLineDraw(line, ctx)
    report(name, ctx.makeImage()!, state)
}

cgDraw("cg-default-argb", smooth: nil)                 // the JDK's glyph canvas before JRS sets its rendering style
cgDraw("cg-default-opaque", smooth: nil, alpha: false)
cgDraw("cg-smooth-on", smooth: true)
cgDraw("cg-smooth-off", smooth: false)
if setStyle != nil { for st: Int32 in [1, 2, 3, 16] { cgDraw("cg-style\(st)", smooth: true, style: st) } }

// AppKit: a bitmap-backed NSGraphicsContext
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
let gc = NSGraphicsContext(bitmapImageRep: rep)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = gc
NSColor.white.setFill()
NSRect(x: 0, y: 0, width: W, height: H).fill()
NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor.black]).draw(at: NSPoint(x: 4, y: 7))
gc.flushGraphics()
NSGraphicsContext.restoreGraphicsState()
report("appkit-bitmaprep", rep.cgImage!, stateOf(gc.cgContext))

// AppKit: an offscreen window with a text field, cached display
_ = NSApplication.shared
let win = NSWindow(contentRect: NSRect(x: 0, y: 0, width: W, height: H), styleMask: .borderless, backing: .buffered, defer: false)
let tf = NSTextField(labelWithString: text)
tf.font = font; tf.textColor = .black; tf.drawsBackground = true; tf.backgroundColor = .white
tf.frame = NSRect(x: 0, y: 0, width: W, height: H)
win.contentView = tf
if let r = tf.bitmapImageRepForCachingDisplay(in: tf.bounds) {
    tf.cacheDisplay(in: tf.bounds, to: r)
    report("appkit-textfield", r.cgImage!)
}
