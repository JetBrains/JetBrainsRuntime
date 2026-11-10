// AppKit's text decorations as a view draws them at a backing scale (2026-11-10): NSAttributedString in an NSView,
// rendered with cacheDisplay into a bitmap rep of 1x or 2x pixel density, so AppKit knows the backing scale as in a
// window (decoprobe.swift's AppKit route draws into a bare CGContext whose CTM scale AppKit does not take for one).
// For each font of decofonts.txt and each scale: the plain line, underlined, struck through; gray PNGs, black on
// white, the baseline at view y 100 (top-left origin), the line at x 20. Measured by DecoProbe.java ("akview").
//
//   swift akviewprobe.swift <probes dir> <out dir> [--whole-row-baseline]
import AppKit

let args = CommandLine.arguments
let probes = args[1], out = args[2]
let TEXT = "x          x", W = 320.0, H = 160.0, BASELINE = 100.0, X = 20.0
try! FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

let WHOLE_ROW_BASELINE = CommandLine.arguments.count > 3 && CommandLine.arguments[3] == "--whole-row-baseline"

final class LineView: NSView {
    var text = NSAttributedString()
    override var isFlipped: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        NSColor.white.setFill(); dirtyRect.fill()
        if WHOLE_ROW_BASELINE {
            // the layout manager's own baseline put exactly on BASELINE (a whole device row at 1x and 2x): the
            // decorations' rows are then AppKit's from the baseline, readable directly
            let storage = NSTextStorage(attributedString: text)
            let layout = NSLayoutManager(), container = NSTextContainer(size: CGSize(width: 10000, height: 1000))
            container.lineFragmentPadding = 0
            layout.addTextContainer(container); storage.addLayoutManager(layout)
            let range = layout.glyphRange(for: container)
            let baseline = layout.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil).minY + layout.location(forGlyphAt: 0).y
            layout.drawGlyphs(forGlyphRange: range, at: NSPoint(x: X, y: BASELINE - baseline))
            return
        }
        let font = text.attribute(.font, at: 0, effectiveRange: nil) as! NSFont
        // draw(at:) puts the line fragment's top at the point: the baseline lies the font's ascender below it
        text.draw(at: NSPoint(x: X, y: BASELINE - font.ascender))
    }
}

let fonts = try! String(contentsOfFile: "\(probes)/decofonts.txt", encoding: .utf8).split(separator: "\n")
    .filter { !$0.hasPrefix("#") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    .map { $0.split(separator: " ").map(String.init) }
var baselines = ""
for f in fonts {
    let font = NSFont(name: f[0], size: CGFloat(Double(f[2])!))!
    for scale in [1, 2] {
        for decoration in ["plain", "underline", "strike"] {
            var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
            if decoration == "underline" { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
            if decoration == "strike" { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
            let view = LineView(frame: NSRect(x: 0, y: 0, width: W, height: H))
            view.text = NSAttributedString(string: TEXT, attributes: attrs)
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(W) * scale, pixelsHigh: Int(H) * scale, bitsPerSample: 8,
                                       samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            rep.size = NSSize(width: W, height: H)   // points: the rep's pixel density is the backing scale
            view.cacheDisplay(in: view.bounds, to: rep)
            try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(out)/\(f[1])-\(f[2])-\(scale)x-akview-\(decoration).png"))
        }
        baselines += "\(f[1]) \(f[2]) \(scale) ascender \(font.ascender)\n"
    }
}
try! baselines.write(toFile: "\(out)/akview.txt", atomically: true, encoding: .utf8)
print("wrote \(fonts.count * 6) images under \(out)")
