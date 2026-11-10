// The layout attributes with a platform equivalent, as TextKit 2 draws them (2026-11-10, ledger §13.1 platform
// alignment): an NSTextView with an NSTextLayoutManager rendered by cacheDisplay at 8x (positions to 1/8 point),
// CTLineDraw in a view beside it where CoreText has its own attribute. Values in points; y below the baseline
// positive. Modes:
//
//   swift aktk2attributes.swift super       superscript / subscript: the shifted glyph's scale and baseline shift
//   swift aktk2attributes.swift background  the background fill's extent against the line and the carets
//   swift aktk2attributes.swift decoration  underline and strikethrough over a shifted run and over mixed sizes
//   swift aktk2attributes.swift tracking    .kern / .tracking (and kCTTrackingAttributeName): where the space goes
import AppKit

let mode = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "super"
let S = 8   // render scale

final class Flipped: NSView {
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) { NSColor.white.setFill(); r.fill() }
}

struct Image {
    let w: Int, h: Int, r: [Double], g: [Double], b: [Double]
    init(_ rep: NSBitmapImageRep) {
        w = rep.pixelsWide; h = rep.pixelsHigh
        let p = rep.bitmapData!, st = rep.bytesPerRow, bpp = rep.bitsPerPixel / 8
        var rr = [Double](repeating: 0, count: w * h), gg = rr, bb = rr
        for y in 0..<h { for x in 0..<w { let o = y * st + x * bpp; rr[y * w + x] = Double(p[o]) / 255; gg[y * w + x] = Double(p[o + 1]) / 255; bb[y * w + x] = Double(p[o + 2]) / 255 } }
        r = rr; g = gg; b = bb
    }
    func ink(_ x: Int, _ y: Int) -> Double { 1 - g[y * w + x] }
}

func render(_ view: NSView) -> Image {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(view.bounds.width) * S, pixelsHigh: Int(view.bounds.height) * S, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = view.bounds.size
    view.cacheDisplay(in: view.bounds, to: rep)
    return Image(rep)
}

/** A TextKit 2 view with the string, its first baseline at y 100 of a 600 x 200 host; and the line's fragment. */
func tk2(_ string: NSAttributedString) -> (Flipped, NSTextLineFragment?, CGFloat) {
    let host = Flipped(frame: NSRect(x: 0, y: 0, width: 600, height: 200))
    let tv = NSTextView(usingTextLayoutManager: true)
    tv.frame = NSRect(x: 0, y: 0, width: 580, height: 180)
    tv.textContainerInset = .zero
    tv.textContainer?.lineFragmentPadding = 0
    tv.drawsBackground = true; tv.backgroundColor = .white
    tv.textStorage?.setAttributedString(string)
    let tlm = tv.textLayoutManager!
    tlm.ensureLayout(for: tlm.documentRange)
    var baseline: CGFloat = 0, line: NSTextLineFragment? = nil, fragmentTop: CGFloat = 0
    tlm.enumerateTextLayoutFragments(from: tlm.documentRange.location, options: [.ensuresLayout]) { f in
        if let l = f.textLineFragments.first {
            baseline = f.layoutFragmentFrame.minY + l.typographicBounds.minY + l.glyphOrigin.y
            fragmentTop = f.layoutFragmentFrame.minY + l.typographicBounds.minY
            line = l
        }
        return false
    }
    tv.frame.origin = NSPoint(x: 20, y: 100 - baseline)
    host.addSubview(tv)
    return (host, line, 100 - baseline + fragmentTop)   // the line fragment's top in host points
}

/** CTLineDraw of the string in a view, the baseline at (20, 100). */
final class LineView: NSView {
    var string: NSAttributedString!
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        let ctx = NSGraphicsContext.current!.cgContext
        ctx.saveGState(); ctx.textMatrix = .identity; ctx.translateBy(x: 20, y: 100); ctx.scaleBy(x: 1, y: -1)
        CTLineDraw(CTLineCreateWithAttributedString(string as CFAttributedString), ctx); ctx.restoreGState()
    }
}
func ctView(_ s: NSAttributedString) -> LineView { let v = LineView(frame: NSRect(x: 0, y: 0, width: 600, height: 200)); v.string = s; return v }

/** Ink components along x (columns with ink, split at empty columns): for each, left, right, top and bottom in points
 *  (fractional edges from the edge rows' coverage), relative to (20, 100). */
func components(_ img: Image, threshold: Double = 0.05) -> [(Double, Double, Double, Double)] {
    var colInk = [Double](repeating: 0, count: img.w)
    for x in 0..<img.w { for y in 0..<img.h { colInk[x] += img.ink(x, y) } }
    var out: [(Double, Double, Double, Double)] = []
    var x = 0
    while x < img.w {
        if colInk[x] < threshold { x += 1; continue }
        let start = x
        while x < img.w && colInk[x] >= threshold { x += 1 }
        var rows = [Double](repeating: 0, count: img.h)
        for y in 0..<img.h { for c in start..<x { rows[y] += img.ink(c, y) } }
        let inked = rows.indices.filter { rows[$0] > threshold * 4 }
        guard let first = inked.first, let last = inked.last else { continue }
        let body = rows[(first + last) / 2] > 0 ? max(rows[min(last, first + 2)], rows[max(first, last - 2)]) : 1
        let top = Double(first + 1) - min(1, rows[first] / max(body, 1e-9)), bottom = Double(last) + min(1, rows[last] / max(body, 1e-9))
        out.append((Double(start) / Double(S) - 20, Double(x) / Double(S) - 20, top / Double(S) - 100, bottom / Double(S) - 100))
    }
    return out
}

func f(_ v: Double) -> String { String(format: "%.3f", v) }
let FONTS: [(String, CGFloat)] = [("Helvetica Neue", 24), ("Times New Roman", 24), ("Georgia", 24), ("Menlo", 24), ("Avenir Next", 24)]

switch mode {
case "super":
    // "x" then the shifted "x": the second x's height against the first's (the scale) and its bottom (the shift);
    // TextKit 2 with NSAttributedString.Key.superscript, CTLineDraw with kCTSuperscriptAttributeName
    print("font\tlevel\tstack\tscale\tbaseline shift (pt, up negative)\tadvance of the first x\tleft of the second x\tfont of the run (TextKit 2)")
    for (family, size) in FONTS {
        let font = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: size)!
        for level in [1, 2, -1, -2] { autoreleasepool {
            let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
            for stack in ["tk2", "ct"] {
                let s = NSMutableAttributedString(string: "x", attributes: base)
                var shifted = base
                if stack == "tk2" { shifted[.superscript] = level } else { shifted[NSAttributedString.Key(kCTSuperscriptAttributeName as String)] = level }
                s.append(NSAttributedString(string: "x", attributes: shifted))
                let view: NSView = stack == "tk2" ? tk2(s).0 : ctView(s)
                let c = components(render(view))
                guard c.count >= 2 else { print("\(family)\t\(level)\t\(stack)\t-"); continue }
                let h0 = c[0].3 - c[0].2, h1 = c[1].3 - c[1].2
                var runFont = "-"
                if stack == "tk2", let tv = view.subviews.first as? NSTextView, let ts = tv.textStorage, ts.length > 1, let rf = ts.attribute(.font, at: 1, effectiveRange: nil) as? NSFont {
                    runFont = "\(rf.fontName) \(rf.pointSize)"
                }
                let adv = Double(("x" as NSString).size(withAttributes: [.font: font]).width)
                print("\(family) \(Int(size))\t\(level)\t\(stack)\t\(f(h1 / h0))\t\(f(c[1].3 - c[0].3))\t\(f(adv))\t\(f(c[1].0))\t\(runFont)")
            }
        } }
    }

case "supersweep":
    // TextKit 2's superscript shift per level over sizes (the shift is whole points): level 1 and -1 at 10 ... 60 pt,
    // beside the font's metrics, for fitting the metric it derives from
    print("family\tsize\tup 1\tdown 1\tup 3\tascender\tdescender\tleading\txHeight\tcapHeight\tdefault line height")
    for family in ["Helvetica Neue", "Times New Roman", "Menlo", "Avenir Next", "Lucida Grande", "Helvetica"] {
        for size in stride(from: 10.0, through: 60.0, by: 2.0) { autoreleasepool {
            let font = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: CGFloat(size))!
            let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
            var cells: [String] = []
            for level in [1, -1, 3] {
                // "l" glyphs (flat bottoms, apart): the second's bottom against the first's
                let s = NSMutableAttributedString(string: "l  ", attributes: base)
                var shifted = base; shifted[.superscript] = level
                s.append(NSAttributedString(string: "l", attributes: shifted))
                let c = components(render(tk2(s).0))
                cells.append(c.count >= 2 ? f(c[1].3 - c[0].3) : "-")
            }
            print("\(family)\t\(Int(size))\t" + cells.joined(separator: "\t") + "\t" + [font.ascender, -font.descender, font.leading, font.xHeight, font.capHeight, NSLayoutManager().defaultLineHeight(for: font)].map { f(Double($0)) }.joined(separator: "\t"))
        } }
    }

case "background":
    // a red background over the middle run of "ab" + "cd" + "ef"; the fill's rows and columns (pixels at the
    // gray, not ink) against the line fragment and the carets; also with the middle run at twice the size
    print("case\tstack\tfill top\tfill bottom\tfill left\tfill right\tcarets of the run\tline fragment top\tascent\tdescent\tleading")
    for (family, size) in [("Helvetica Neue", 13.0), ("Times New Roman", 16.0), ("Menlo", 12.0)] {
        let font = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: CGFloat(size))!
        for mixed in [false, true] { autoreleasepool {
            let big = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: CGFloat(size * 2))!
            let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
            let s = NSMutableAttributedString(string: "ab", attributes: base)
            var bg = base; bg[.backgroundColor] = NSColor(deviceRed: 1, green: 0, blue: 0, alpha: 1)   // red: no glyph edge reads as it
            s.append(NSAttributedString(string: "cd", attributes: bg))
            s.append(NSAttributedString(string: "ef", attributes: mixed ? [.font: big, .foregroundColor: NSColor.black] : base))
            let (host, line, fragTop) = tk2(s)
            let img = render(host)
            var top = Int.max, bottom = -1, left = Int.max, right = -1
            for y in 0..<img.h { for x in 0..<img.w where img.r[y * img.w + x] > 0.9 && img.g[y * img.w + x] < 0.1 && img.b[y * img.w + x] < 0.1 {
                top = min(top, y); bottom = max(bottom, y + 1); left = min(left, x); right = max(right, x + 1)
            } }
            let carets = line.map { l in "\(f(Double(l.locationForCharacter(at: 2).x)))..\(f(Double(l.locationForCharacter(at: 4).x)))" } ?? "-"
            print("\(family) \(Int(size))\(mixed ? " + \(Int(size * 2)) after" : "")\ttk2\t\(f(Double(top) / 8 - 100))\t\(f(Double(bottom) / 8 - 100))\t\(f(Double(left) / 8 - 20))\t\(f(Double(right) / 8 - 20))\t\(carets)\t\(f(Double(fragTop) - 100))\t\(f(Double(font.ascender)))\t\(f(Double(-font.descender)))\t\(f(Double(font.leading)))")
        } }
    }

case "decoration":
    // underline and strikethrough over "x   " + shifted or larger "x   " + "x   ": each run's band (rows with the
    // decoration's ink over its spaces) in points, against the run's font metrics
    print("case\tstyle\trun 1 band\trun 2 band\trun 3 band")
    for (family, size) in [("Helvetica Neue", 24.0), ("Times New Roman", 24.0)] {
        let font = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: CGFloat(size))!
        for variant in ["superscript", "subscript", "double size"] { for style in ["underline", "strike"] { autoreleasepool {
            let key: NSAttributedString.Key = style == "underline" ? .underlineStyle : .strikethroughStyle
            let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black, key: NSUnderlineStyle.single.rawValue]
            var second = base
            if variant == "superscript" { second[.superscript] = 1 }
            if variant == "subscript" { second[.superscript] = -1 }
            if variant == "double size" { second[.font] = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: CGFloat(size * 2))! }
            let runs = [NSAttributedString(string: "x     ", attributes: base), NSAttributedString(string: "x     ", attributes: second), NSAttributedString(string: "x     ", attributes: base)]
            let s = NSMutableAttributedString(); runs.forEach { s.append($0) }
            let plain = NSMutableAttributedString(attributedString: s); plain.removeAttribute(key, range: NSRange(location: 0, length: plain.length))
            let (hd, line, _) = tk2(s), (hp, _, _) = tk2(plain)
            let d = render(hd), p = render(hp)
            var cells: [String] = []
            for r in 0..<3 {
                guard let l = line else { break }
                let x1 = Int((Double(l.locationForCharacter(at: r * 6 + 2).x) + 20) * 8), x2 = Int((Double(l.locationForCharacter(at: r * 6 + 5).x) + 20) * 8)
                var rows: [Int] = [], sum = 0.0, moment = 0.0
                for y in 0..<d.h {
                    var c = 0.0
                    for x in x1..<x2 { c += max(0, d.ink(x, y) - p.ink(x, y)) }
                    c /= Double(max(1, x2 - x1))
                    if c > 0.5 { rows.append(y) }
                    sum += c; moment += c * (Double(y) + 0.5)
                }
                cells.append(rows.isEmpty ? "-" : "centre \(f(moment / sum / 8 - 100)) thickness \(f(sum / 8))")
            }
            print("\(family) \(Int(size)), run 2 \(variant)\t\(style)\t" + cells.joined(separator: "\t"))
        } } }
    }

case "tracking":
    // "abcd" with the attribute on "bc": the carets (layout) and the glyphs' ink left edges, against the plain line
    print("attribute\tvalue\tstack\tcarets a b c d end\tink left edges a b c d")
    let font = NSFontManager.shared.font(withFamily: "Helvetica Neue", traits: [], weight: 5, size: 24)!
    let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
    var cases: [(String, NSAttributedString.Key?, Any)] = [("none", nil, 0)]
    for v in [2.0, 6.0, -1.0] {
        cases.append(("kern", .kern, v))
        cases.append(("tracking", .tracking, v))
        cases.append(("kCTTracking", NSAttributedString.Key(kCTTrackingAttributeName as String), v))
    }
    for (name, key, value) in cases { autoreleasepool {
        let s = NSMutableAttributedString(string: "a", attributes: base)
        var mid = base; if let k = key { mid[k] = value }
        s.append(NSAttributedString(string: "bc", attributes: mid))
        s.append(NSAttributedString(string: "d", attributes: base))
        for stack in ["tk2", "ct"] {
            var carets = "-"
            let view: NSView
            if stack == "tk2" {
                let (h, line, _) = tk2(s); view = h
                if let l = line { carets = (0...4).map { f(Double(l.locationForCharacter(at: $0).x)) }.joined(separator: " ") }
            } else {
                view = ctView(s)
                let ctl = CTLineCreateWithAttributedString(s as CFAttributedString)
                carets = (0...4).map { f(Double(CTLineGetOffsetForStringIndex(ctl, $0, nil))) }.joined(separator: " ")
            }
            let lefts = components(render(view)).map { f($0.0) }.joined(separator: " ")
            print("\(name)\t\(name == "none" ? "-" : "\(value)")\t\(stack)\t\(carets)\t\(lefts)")
        }
    } }

default:
    print("unknown mode \(mode)")
}
