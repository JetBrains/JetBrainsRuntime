// AppKit's underline position at a given backing scale, from its rounding (2026-11-10): the line's baseline shifted by
// sub-pixel steps of 1/32 device pixel, and for each step the device rows the underline covers, relative to the
// baseline's row (where the glyphs go). The step where a row boundary moves gives the position AppKit rounds, in
// device pixels below the baseline, independent of any device grid. TSV on stdout.
//
//   swift akphaseprobe.swift <scale> <family> <size> [<family> <size> ...]
import AppKit

let args = CommandLine.arguments
let scale = Int(args[1])!
let TEXT = "x          x"

final class LineView: NSView {
    var font: NSFont!, underline = true, shift: CGFloat = 0
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        var attrs: [NSAttributedString.Key: Any] = [.font: font!, .foregroundColor: NSColor.black]
        if underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        let storage = NSTextStorage(string: TEXT, attributes: attrs)
        let c = NSTextContainer(size: CGSize(width: 10000, height: 1000)); c.lineFragmentPadding = 0
        let lm = NSLayoutManager(); lm.addTextContainer(c); storage.addLayoutManager(lm)
        let baseline = lm.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil).minY + lm.location(forGlyphAt: 0).y
        lm.drawGlyphs(forGlyphRange: lm.glyphRange(for: c), at: NSPoint(x: 20, y: 100 + shift - baseline))
    }
}

var profile = ProcessInfo.processInfo.environment["PROFILE"] != nil

/** The covered rows (>= 0.5 coverage over the spaces) in device rows, absolute; with PROFILE every row's coverage printed. */
func covered(_ font: NSFont, _ shift: CGFloat) -> [Int] {
    func render(_ u: Bool) -> NSBitmapImageRep {
        let v = LineView(frame: NSRect(x: 0, y: 0, width: 200, height: 140)); v.font = font; v.underline = u; v.shift = shift
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 200 * scale, pixelsHigh: 140 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: 200, height: 140)
        v.cacheDisplay(in: v.bounds, to: rep)
        return rep
    }
    let p = render(false), d = render(true)
    let pb = p.bitmapData!, db = d.bitmapData!, st = p.bytesPerRow, bpp = p.bitsPerPixel / 8, w = 200 * scale, h = 140 * scale
    func ink(_ b: UnsafeMutablePointer<UInt8>, _ x: Int, _ y: Int) -> Double { 1 - Double(b[y * st + x * bpp + 1]) / 255 }
    var first = Int.max
    for y in 0..<h { for x in 0..<w where ink(pb, x, y) > 0.1 { first = min(first, x); break } }
    let x1 = first + Int(font.pointSize * 1.1) * scale, x2 = min(w, x1 + 4 * scale)
    var rows: [Int] = []
    for y in 0..<h {
        var c = 0.0
        for x in x1..<x2 { c += max(0, ink(db, x, y) - ink(pb, x, y)) }
        if c / Double(x2 - x1) >= 0.5 { rows.append(y) }
        if profile && c / Double(x2 - x1) > 0.02 { print(String(format: "    shift %.4f row %+d: %.3f", Double(shift) * Double(scale), y - 100 * scale, c / Double(x2 - x1))) }
    }
    return rows
}

print("family\tsize\tscale\tshift (device px)\ttop row\tbottom row (exclusive), relative to the unshifted baseline row")
var i = 2
while i + 1 < args.count {
    let family = args[i], size = CGFloat(Double(args[i + 1])!)
    i += 2
    guard let base = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: size) else { continue }
    let font = NSFont(descriptor: base.fontDescriptor, size: size)!
    var last = ""
    for k in 0..<32 {
        autoreleasepool {
            let shift = CGFloat(k) / 32 / CGFloat(scale)
            let r = covered(font, shift)
            let line = r.isEmpty ? "-" : "\(r.first! - 100 * scale)\t\(r.last! + 1 - 100 * scale)"
            if line != last { print("\(family)\t\(Int(size))\t\(scale)\t\(String(format: "%.4f", Double(k) / 32))\t\(line)"); last = line }
        }
    }
}
