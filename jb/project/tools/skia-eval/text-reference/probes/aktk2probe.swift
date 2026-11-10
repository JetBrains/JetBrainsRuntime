// The underline rows of AppKit's text stacks side by side (2026-11-10): TextKit 2 (an NSTextView with an
// NSTextLayoutManager, what NSTextView and TextEdit use by default on current macOS), TextKit 1 (NSLayoutManager)
// and NSAttributedString string drawing, each with the line's baseline on a whole device row at 1x and 2x backing
// scale. TSV on stdout: the underline's device rows from the baseline row, per stack (with STYLE=strike the
// strikethrough's, negative above the baseline row).
//
//   [STYLE=strike] swift aktk2probe.swift <family> <size> [<family> <size> ...] | --all
import AppKit

let args = CommandLine.arguments
let TEXT = "x          x"
let STRIKE = ProcessInfo.processInfo.environment["STYLE"] == "strike"

func attributes(_ font: NSFont, _ underline: Bool) -> [NSAttributedString.Key: Any] {
    var a: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
    if underline { a[STRIKE ? .strikethroughStyle : .underlineStyle] = NSUnderlineStyle.single.rawValue }
    return a
}

/** A view drawing the line by one stack with its baseline at y 100 (flipped). */
final class StackView: NSView {
    var font: NSFont!, underline = false, stack = "tk1"
    var textView: NSTextView?
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        let string = NSAttributedString(string: TEXT, attributes: attributes(font, underline))
        switch stack {
        case "tk1":
            let storage = NSTextStorage(attributedString: string)
            let c = NSTextContainer(size: CGSize(width: 10000, height: 1000)); c.lineFragmentPadding = 0
            let lm = NSLayoutManager(); lm.addTextContainer(c); storage.addLayoutManager(lm)
            let baseline = lm.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil).minY + lm.location(forGlyphAt: 0).y
            lm.drawGlyphs(forGlyphRange: lm.glyphRange(for: c), at: NSPoint(x: 20, y: 100 - baseline))
        case "string":
            // string drawing from the line fragment origin: the baseline lies the typesetter's baseline offset below it
            let lm = NSLayoutManager()
            let baseline = lm.defaultBaselineOffset(for: font)
            string.draw(with: NSRect(x: 20, y: 100 - baseline, width: 1000, height: 200), options: [.usesLineFragmentOrigin])
        default: break
        }
    }
}

/** TextKit 2: an NSTextView using an NSTextLayoutManager, positioned so its first line's baseline is at y 100. */
func tk2View(_ font: NSFont, _ underline: Bool) -> NSView {
    let host = Flipped(frame: NSRect(x: 0, y: 0, width: 400, height: 160))   // flipped: the frame origin from the top, as the baseline
    let tv = NSTextView(usingTextLayoutManager: true)
    tv.frame = NSRect(x: 0, y: 0, width: 380, height: 120)
    tv.textContainerInset = .zero
    tv.textContainer?.lineFragmentPadding = 0
    tv.drawsBackground = true; tv.backgroundColor = .white
    tv.textStorage?.setAttributedString(NSAttributedString(string: TEXT, attributes: attributes(font, underline)))
    var baseline: CGFloat = 0
    if let tlm = tv.textLayoutManager {
        tlm.ensureLayout(for: tlm.documentRange)
        tlm.enumerateTextLayoutFragments(from: tlm.documentRange.location, options: [.ensuresLayout]) { fragment in
            if let line = fragment.textLineFragments.first {
                baseline = fragment.layoutFragmentFrame.minY + line.typographicBounds.minY + line.glyphOrigin.y
            }
            return false
        }
    }
    tv.frame.origin = NSPoint(x: 20, y: 100 - baseline)
    host.addSubview(tv)
    return host
}

final class Flipped: NSView {
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) { NSColor.white.setFill(); r.fill() }   // transparent pixels would read as ink
}

func render(_ view: NSView, _ scale: Int) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 400 * scale, pixelsHigh: 160 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: 400, height: 160)
    view.cacheDisplay(in: view.bounds, to: rep)
    return rep
}

/** The underline's rows (>= 0.9 coverage over the spaces), and any partial rows, from the baseline row. */
func rows(_ plain: NSBitmapImageRep, _ decorated: NSBitmapImageRep, _ scale: Int, _ font: NSFont) -> String {
    let pb = plain.bitmapData!, db = decorated.bitmapData!, st = plain.bytesPerRow, bpp = plain.bitsPerPixel / 8, w = 400 * scale, h = 160 * scale
    func ink(_ b: UnsafeMutablePointer<UInt8>, _ x: Int, _ y: Int) -> Double { 1 - Double(b[y * st + x * bpp + 1]) / 255 }
    var first = Int.max, glyphBottom = 0
    // the glyphs' ink near the line only (a text view draws its own border at the image's edge)
    for y in (60 * scale)..<(130 * scale) { for x in 0..<(380 * scale) where ink(pb, x, y) > 0.5 { first = min(first, x); glyphBottom = max(glyphBottom, y + 1) } }
    let x1 = first + Int(font.pointSize * 1.1) * scale, x2 = min(w, x1 + 4 * scale)
    var solid: [Int] = [], partial: [String] = []
    for y in 0..<h {
        var c = 0.0
        for x in x1..<x2 { c += max(0, ink(db, x, y) - ink(pb, x, y)) }
        c /= Double(x2 - x1)
        if c >= 0.9 { solid.append(y - 100 * scale) } else if c > 0.05 { partial.append(String(format: "%+d=%.2f", y - 100 * scale, c)) }
    }
    let s = solid.isEmpty ? "-" : "\(solid.first!)..\(solid.last! + 1)"
    return s + (partial.isEmpty ? "" : "(" + partial.joined(separator: ",") + ")") + " [x bottom \(glyphBottom - 100 * scale)]"
}

print("family\tsize\tscale\tTextKit 2\tTextKit 1\tstring drawing")
var pairs: [(String, CGFloat)] = []
var i = 1
while i + 1 < args.count { pairs.append((args[i], CGFloat(Double(args[i + 1])!))); i += 2 }
if args.count == 2 && args[1] == "--all" {
    // the 450-font set of akdecoprobe.swift
    for family in ["Helvetica Neue", "Helvetica", "Arial", "Verdana", "Georgia", "Times New Roman", "Times", "Courier New", "Courier", "Menlo",
                   "Monaco", "Avenir Next", "Avenir", "Lucida Grande", "Gill Sans", "Futura", "Optima", "Palatino", "Baskerville", "Didot",
                   "American Typewriter", "Trebuchet MS", "Tahoma", "Geneva", "SF Mono", "Charter", "Hoefler Text", "Cochin", "PT Sans", "PT Serif"] {
        for size: CGFloat in [9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 40, 48] { pairs.append((family, size)) }
    }
}
for (family, size) in pairs {
    guard let base = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: size) else { continue }
    let font = NSFont(descriptor: base.fontDescriptor, size: size)!
    for scale in [1, 2] {
        autoreleasepool {
            var cells: [String] = []
            cells.append(rows(render(tk2View(font, false), scale), render(tk2View(font, true), scale), scale, font))
            for stack in ["tk1", "string"] {
                let p = StackView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); p.font = font; p.stack = stack
                let d = StackView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); d.font = font; d.stack = stack; d.underline = true
                cells.append(rows(render(p, scale), render(d, scale), scale, font))
            }
            print("\(family)\t\(Int(size))\t\(scale)x\t" + cells.joined(separator: "\t"))
        }
    }
}
