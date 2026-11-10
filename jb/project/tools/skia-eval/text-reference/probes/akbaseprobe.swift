// String drawing's underline against its true baseline (2026-11-10): string drawing (which TextKit 2 equals) leaves
// the line's baseline where its own metrics put it, fractional; the glyphs snap to rows, the underline follows the
// true baseline. Rendered at 16x, the bottom edge of the flat-bottomed "x" gives the true baseline to 1/16 point, and
// the underline's centre is measured against it; at 1x and 2x the underline's device rows are read, relative to the
// row of the requested baseline. TSV on stdout, one row per family and size of akdecoprobe.swift's set.
//
//   swift akbaseprobe.swift
import AppKit

let TEXT = "x          x"
final class LineView: NSView {
    var font: NSFont!, underline = false
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        var attrs: [NSAttributedString.Key: Any] = [.font: font!, .foregroundColor: NSColor.black]
        if underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        let baseline = NSLayoutManager().defaultBaselineOffset(for: font)
        NSAttributedString(string: TEXT, attributes: attrs).draw(with: NSRect(x: 20, y: 100 - baseline, width: 1000, height: 200), options: [.usesLineFragmentOrigin])
    }
}
func render(_ font: NSFont, _ underline: Bool, _ scale: Int) -> NSBitmapImageRep {
    let v = LineView(frame: NSRect(x: 0, y: 0, width: 200, height: 140)); v.font = font; v.underline = underline
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 200 * scale, pixelsHigh: 140 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: 200, height: 140)
    v.cacheDisplay(in: v.bounds, to: rep)
    return rep
}
func ink(_ rep: NSBitmapImageRep, _ x: Int, _ y: Int) -> Double { 1 - Double(rep.bitmapData![y * rep.bytesPerRow + x * (rep.bitsPerPixel / 8) + 1]) / 255 }

/** The first x's columns (its ink's left and right), the bottom edge of its ink (fractional, in points), at the scale. */
func glyph(_ rep: NSBitmapImageRep, _ scale: Int) -> (Int, Int, Double) {
    let w = 200 * scale, h = 140 * scale
    var left = Int.max
    for y in 0..<h { for x in 0..<w where ink(rep, x, y) > 0.3 { left = min(left, x); break } }
    var right = left
    while right + 1 < w && (0..<h).contains(where: { ink(rep, right + 1, $0) > 0.3 }) { right += 1 }
    // the bottom edge over all its columns (an x stands on its two feet; its middle is the crossing): the last row
    // with ink plus that row's coverage relative to the full rows above it
    var rows = [Double](repeating: 0, count: h)
    for y in 0..<h { for x in left...right { rows[y] += ink(rep, x, y) } }
    var last = 0
    for y in 0..<h where rows[y] > 0.02 { last = y }
    let body = rows[max(0, last - 2)]
    return (left, right, (Double(last) + min(1, rows[last] / max(body, 1e-9))) / Double(scale))
}

/** The underline's centre in points and its device rows (relative to the requested baseline row), over the spaces. */
func underline(_ plain: NSBitmapImageRep, _ decorated: NSBitmapImageRep, _ scale: Int, _ x1: Int, _ x2: Int) -> (Double, String) {
    let h = 140 * scale
    var sum = 0.0, moment = 0.0, solid: [Int] = []
    for y in 0..<h {
        var c = 0.0
        for x in x1..<x2 { c += max(0, ink(decorated, x, y) - ink(plain, x, y)) }
        c /= Double(x2 - x1)
        sum += c; moment += c * (Double(y) + 0.5)
        if c >= 0.9 { solid.append(y - 100 * scale) }
    }
    return (moment / sum / Double(scale), solid.isEmpty ? "-" : "\(solid.first!)..\(solid.last! + 1)")
}

print("family\tsize\tascent\tdescent\tleading\tunderlinePosition\tunderlineThickness\tbaseline16\tcentre16\trows1x\trows2x")
for family in ["Helvetica Neue", "Helvetica", "Arial", "Verdana", "Georgia", "Times New Roman", "Times", "Courier New", "Courier", "Menlo",
               "Monaco", "Avenir Next", "Avenir", "Lucida Grande", "Gill Sans", "Futura", "Optima", "Palatino", "Baskerville", "Didot",
               "American Typewriter", "Trebuchet MS", "Tahoma", "Geneva", "SF Mono", "Charter", "Hoefler Text", "Cochin", "PT Sans", "PT Serif"] {
    guard let base = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: 12) else { continue }
    for size: CGFloat in [9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 40, 48] { autoreleasepool {
        let font = NSFont(descriptor: base.fontDescriptor, size: size)!, ct = font as CTFont
        let p16 = render(font, false, 16), d16 = render(font, true, 16)
        let (left, right, bottom) = glyph(p16, 16)
        // the spaces: from a glyph width right of the first x, four points wide
        let x1 = right + (right - left), x2 = x1 + 4 * 16
        let (centre, _) = underline(p16, d16, 16, x1, x2)
        var cells: [String] = []
        for s in [1, 2] {
            let p = render(font, false, s), d = render(font, true, s)
            cells.append(underline(p, d, s, x1 * s / 16, x2 * s / 16).1)
        }
        print(String(format: "%@\t%.0f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t", family, size, CTFontGetAscent(ct), CTFontGetDescent(ct), CTFontGetLeading(ct),
                     -CTFontGetUnderlinePosition(ct), CTFontGetUnderlineThickness(ct), bottom - 100, centre - bottom) + cells.joined(separator: "\t"))
    } }
}
