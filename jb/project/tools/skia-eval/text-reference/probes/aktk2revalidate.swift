// TextKit 2 against the earlier references, for the items of ledger §13.1 measured on a bare context or TextKit 1
// (2026-11-10). Every stack draws in a real NSView rendered by cacheDisplay at a backing scale; TextKit 2 is an
// NSTextView with an NSTextLayoutManager. Modes, TSV or plain lines on stdout:
//
//   swift aktk2revalidate.swift phase       decorations and glyphs at sub-pixel baselines (container inset steps)
//   swift aktk2revalidate.swift rotate      decorations under rotated views: TextKit 2, CTLineDraw in a rotated view,
//                                           CTLineDraw in a rotated bare context; centre and thickness in the text frame
//   swift aktk2revalidate.swift whitespace  the underline's span over line-end whitespace, TextKit 2 and CTLineDraw
//   swift aktk2revalidate.swift tabs        tab positions and wrapping, TextKit 2 and CTLine
//   swift aktk2revalidate.swift ctref       CTLineDraw's underline rows in a bare context with the scale in the CTM
//                                           (ctref.swift's arm) for the 450-font set, beside nothing: compare with
//                                           aktk2probe.swift's TextKit 2 column
import AppKit

let TEXT = "x          x"
let mode = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "phase"

final class Flipped: NSView {
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) { NSColor.white.setFill(); r.fill() }
}

func attributes(_ font: NSFont, _ style: String) -> [NSAttributedString.Key: Any] {
    var a: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
    if style == "underline" { a[.underlineStyle] = NSUnderlineStyle.single.rawValue }
    if style == "strike" { a[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
    return a
}

/** A TextKit 2 text view showing the string, without insets or padding but the vertical inset given. */
func textView(_ string: NSAttributedString, width: CGFloat = 380, height: CGFloat = 120, inset: CGFloat = 0) -> NSTextView {
    let tv = NSTextView(usingTextLayoutManager: true)
    tv.frame = NSRect(x: 0, y: 0, width: width, height: height)
    tv.textContainerInset = NSSize(width: 0, height: inset)
    tv.textContainer?.lineFragmentPadding = 0
    tv.drawsBackground = true; tv.backgroundColor = .white
    tv.textStorage?.setAttributedString(string)
    if let tlm = tv.textLayoutManager { tlm.ensureLayout(for: tlm.documentRange) }
    return tv
}

/** The first line's baseline in the text view's coordinates (flipped, from its top), and its line fragment. */
func firstLine(_ tv: NSTextView) -> (CGFloat, NSTextLineFragment?, NSTextLayoutFragment?) {
    var baseline: CGFloat = 0, line: NSTextLineFragment? = nil, frag: NSTextLayoutFragment? = nil
    tv.textLayoutManager?.enumerateTextLayoutFragments(from: tv.textLayoutManager!.documentRange.location, options: [.ensuresLayout]) { f in
        if let l = f.textLineFragments.first {
            baseline = f.layoutFragmentFrame.minY + l.typographicBounds.minY + l.glyphOrigin.y + tv.textContainerOrigin.y
            line = l; frag = f
        }
        return false
    }
    return (baseline, line, frag)
}

func render(_ view: NSView, _ scale: Int) -> NSBitmapImageRep {
    let w = Int(view.bounds.width) * scale, h = Int(view.bounds.height) * scale
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = view.bounds.size
    view.cacheDisplay(in: view.bounds, to: rep)
    return rep
}

struct Image {
    let w: Int, h: Int, ink: [Double]
    init(_ rep: NSBitmapImageRep) {
        w = rep.pixelsWide; h = rep.pixelsHigh
        let b = rep.bitmapData!, st = rep.bytesPerRow, bpp = rep.bitsPerPixel / 8
        var a = [Double](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w { a[y * w + x] = 1 - Double(b[y * st + x * bpp + 1]) / 255 } }
        ink = a
    }
    init(context: CGContext) {
        w = context.width; h = context.height
        let b = context.data!.assumingMemoryBound(to: UInt8.self), st = context.bytesPerRow
        var a = [Double](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w { a[y * w + x] = 1 - Double(b[y * st + x * 4 + 1]) / 255 } }
        ink = a
    }
    subscript(x x: Int, y y: Int) -> Double { ink[y * w + x] }
}

/** Per-row coverage of decorated minus plain over columns x1..<x2. */
func profile(_ p: Image, _ d: Image, _ x1: Int, _ x2: Int) -> [Double] {
    (0..<p.h).map { y in (x1..<x2).reduce(0.0) { $0 + max(0, d[x: $1, y: y] - p[x: $1, y: y]) } / Double(x2 - x1) }
}

func describe(_ prof: [Double], _ origin: Int) -> String {
    prof.enumerated().filter { $0.element > 0.03 }.map { String(format: "%+d=%.2f", $0.offset - origin, $0.element) }.joined(separator: " ")
}

let FAMILIES = ["Helvetica Neue", "Helvetica", "Arial", "Verdana", "Georgia", "Times New Roman", "Times", "Courier New", "Courier", "Menlo",
                "Monaco", "Avenir Next", "Avenir", "Lucida Grande", "Gill Sans", "Futura", "Optima", "Palatino", "Baskerville", "Didot",
                "American Typewriter", "Trebuchet MS", "Tahoma", "Geneva", "SF Mono", "Charter", "Hoefler Text", "Cochin", "PT Sans", "PT Serif"]
func font(_ family: String, _ size: CGFloat) -> NSFont? {
    guard let base = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: size) else { return nil }
    return NSFont(descriptor: base.fontDescriptor, size: size)
}

switch mode {
case "phase":
    // the text view's first baseline placed at y 100 plus a sub-pixel step (the container inset carries the step); per
    // step: the x glyph's bottom edge (last ink row plus its coverage), the decoration's coverage-weighted centre and
    // its rows, in device pixels from the unshifted baseline row
    print("family\tsize\tscale\tstyle\tstep (device px)\tglyph bottom\tdecoration centre\trows")
    for (family, size) in [("Helvetica Neue", 13.0), ("Menlo", 12.0), ("Georgia", 18.0), ("Times New Roman", 16.0)] {
        let f = font(family, CGFloat(size))!
        for scale in [1, 2] { for style in ["underline", "strike"] { for k in 0..<8 { autoreleasepool {
            let step = CGFloat(k) / 8 / CGFloat(scale)
            func shot(_ st: String) -> Image {
                let host = Flipped(frame: NSRect(x: 0, y: 0, width: 400, height: 160))
                let tv = textView(NSAttributedString(string: TEXT, attributes: attributes(f, st)), inset: step)
                let (b, _, _) = firstLine(tv)
                tv.frame.origin = NSPoint(x: 20, y: 100 - (b - step))   // the unshifted baseline on row 100
                host.addSubview(tv)
                return Image(render(host, scale))
            }
            let p = shot("plain"), d = shot(style)
            let x1 = (20 + Int(f.pointSize * 1.1)) * scale, x2 = x1 + 4 * scale
            // the glyph's bottom over the first x's columns
            var rows = [Double](repeating: 0, count: p.h)
            for y in 0..<p.h { for x in (20 * scale)..<(x1 - 2 * scale) { rows[y] += p[x: x, y: y] } }
            var last = 0
            for y in (60 * scale)..<(130 * scale) where rows[y] > 0.02 * Double(scale) { last = y }
            let bottom = Double(last - 100 * scale) + min(1, rows[last] / max(rows[max(0, last - 2)], 1e-9))
            let prof = profile(p, d, x1, x2)
            let sum = prof.reduce(0, +), centre = prof.enumerated().reduce(0.0) { $0 + $1.element * (Double($1.offset) + 0.5) } / max(sum, 1e-9)
            print(String(format: "%@\t%.0f\t%d\t%@\t%.3f\t%.3f\t%.3f\t", family, size, scale, style, Double(k) / 8, bottom + 1, centre - Double(100 * scale)) + describe(prof, 100 * scale))
        } } } }
    }

case "phase2":
    // a fractional baseline as text has it: the second line under a first line whose height is fixed at a fraction
    // (minimum = maximum line height); TextKit 2's own second baseline (its layout) against the x's bottom edge and
    // the decoration's coverage-weighted centre, in device pixels below the second baseline's row
    print("family\tsize\tscale\tstyle\tsecond baseline (device px)\tglyph bottom - floor\tdecoration centre - baseline\trows from floor")
    for (family, size) in [("Helvetica Neue", 13.0), ("Georgia", 18.0)] {
        let f = font(family, CGFloat(size))!
        for scale in [1, 2] { for style in ["underline", "strike"] { for k in 0..<8 { autoreleasepool {
            let first = CGFloat(20) + CGFloat(k) / 8 / CGFloat(scale)
            func string(_ st: String) -> NSAttributedString {
                let m = NSMutableAttributedString(string: "x\n", attributes: attributes(f, "plain"))
                let ps = NSMutableParagraphStyle(); ps.minimumLineHeight = first; ps.maximumLineHeight = first
                m.addAttribute(.paragraphStyle, value: ps, range: NSRange(location: 0, length: 2))
                m.append(NSAttributedString(string: TEXT, attributes: attributes(f, st)))
                return m
            }
            var baseline: CGFloat = 0
            func shot(_ st: String) -> Image {
                let host = Flipped(frame: NSRect(x: 0, y: 0, width: 400, height: 160))
                let tv = textView(string(st)); tv.frame.origin = NSPoint(x: 20, y: 40); host.addSubview(tv)
                var n = 0
                tv.textLayoutManager!.enumerateTextLayoutFragments(from: tv.textLayoutManager!.documentRange.location, options: [.ensuresLayout]) { fr in
                    for l in fr.textLineFragments { if n == 1 { baseline = 40 + fr.layoutFragmentFrame.minY + l.typographicBounds.minY + l.glyphOrigin.y + tv.textContainerOrigin.y }; n += 1 }
                    return n < 2
                }
                return Image(render(host, scale))
            }
            let p = shot("plain"), d = shot(style)
            let b = Double(baseline) * Double(scale), row = Int(floor(b))
            let x1 = (20 + Int(f.pointSize * 1.1)) * scale, x2 = x1 + 4 * scale
            var rows = [Double](repeating: 0, count: p.h)
            for y in 0..<p.h { for x in (20 * scale)..<(x1 - 2 * scale) { rows[y] += p[x: x, y: y] } }
            var last = 0
            for y in (row - 4 * scale)..<min(p.h, row + 4 * scale) where rows[y] > 0.02 * Double(scale) { last = y }
            let bottom = Double(last) + min(1, rows[last] / max(rows[max(0, last - 2)], 1e-9))
            let prof = profile(p, d, x1, x2)
            let sum = prof.reduce(0, +), centre = prof.enumerated().reduce(0.0) { $0 + $1.element * (Double($1.offset) + 0.5) } / max(sum, 1e-9)
            print(String(format: "%@\t%.0f\t%d\t%@\t%.3f\t%.3f\t%.3f\t", family, size, scale, style, b, bottom - Double(row), centre - b) + describe(prof, row))
        } } } }
    }

case "glyphrow":
    // where the glyphs go at a fractional baseline: the x's bottom edge (device rows, fractional) for a baseline at
    // 100 + f, by CTLineDraw and string drawing in a view, at 1x and 2x (TextKit 2's is phase2's)
    final class GlyphView: NSView {
        var f: CGFloat = 0, stack = "ct"
        override var isFlipped: Bool { true }
        override func draw(_ r: NSRect) {
            NSColor.white.setFill(); r.fill()
            let font = NSFont(name: "HelveticaNeue", size: CGFloat(Double(ProcessInfo.processInfo.environment["SIZE"] ?? "13")!))!   // SIZE=150: large glyphs
            let s = NSAttributedString(string: "xxxx", attributes: [.font: font, .foregroundColor: NSColor.black])
            if stack == "ct" {
                let ctx = NSGraphicsContext.current!.cgContext
                ctx.saveGState(); ctx.textMatrix = .identity; ctx.translateBy(x: 20, y: 100 + f); ctx.scaleBy(x: 1, y: -1)
                CTLineDraw(CTLineCreateWithAttributedString(s), ctx); ctx.restoreGState()
            } else {
                s.draw(with: NSRect(x: 20, y: 100 + f - NSLayoutManager().defaultBaselineOffset(for: font), width: 200, height: 40), options: [.usesLineFragmentOrigin])
            }
        }
    }
    let font13x = 3.0 * Double(ProcessInfo.processInfo.environment["SIZE"] ?? "13")!   // the glyph run's width, roughly
    for scale in [1, 2] { for stack in ["ct", "string"] {
        var out: [String] = []
        // eighths, then small fractions (where the row starts to move), in device pixels
        let fractions: [Double] = (0..<8).map { Double($0) / 8 } + [0.0005, 0.001, 0.002, 0.004, 0.008, 0.016, 0.031, 0.999]
        for fraction in fractions { autoreleasepool {
            let v = GlyphView(frame: NSRect(x: 0, y: 0, width: 400, height: 140)); v.f = CGFloat(fraction) / CGFloat(scale); v.stack = stack
            let img = Image(render(v, scale))
            var rows = [Double](repeating: 0, count: img.h)
            for y in 0..<img.h { for x in (20 * scale)..<(min(380, 20 + Int(font13x)) * scale) { rows[y] += img[x: x, y: y] } }
            var last = 0; for y in 2..<img.h where rows[y] > 0.05 { last = y }
            out.append(String(format: "%.4f→%.2f", fraction, Double(last) + min(1, rows[last] / rows[last - 2]) - Double(100 * scale)))
        } }
        print("\(scale)x \(stack): " + out.joined(separator: " "))
    } }

case "rotate":
    // three stacks rotated by the view's frame rotation (TextKit 2, CTLineDraw in a custom view) and by a bare
    // context's CTM (CTLineDraw, night 13's method); the decoration's pixels mapped into the text frame: its centre
    // below the baseline and its thickness (coverage integral) over the spaces
    final class LineView: NSView {
        var font: NSFont!, style = "plain"
        override var isFlipped: Bool { true }
        override func draw(_ r: NSRect) {
            NSColor.white.setFill(); r.fill()
            guard let ctx = NSGraphicsContext.current?.cgContext else { return }
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: TEXT, attributes: attributes(font, style)) as CFAttributedString)
            ctx.saveGState(); ctx.textMatrix = .identity
            ctx.translateBy(x: 0, y: 60); ctx.scaleBy(x: 1, y: -1)   // the baseline at (0, 60) in the view, y up for CoreText
            CTLineDraw(line, ctx); ctx.restoreGState()
        }
    }
    func measure(_ p: Image, _ d: Image, scale: Int, origin: CGPoint, dir: CGVector, from: Double, to: Double) -> (Double, Double) {
        // origin: the baseline's start in pixels, dir: the unit baseline direction in pixels (y down)
        var sum = 0.0, moment = 0.0
        for y in 0..<p.h { for x in 0..<p.w {
            let c = max(0, d[x: x, y: y] - p[x: x, y: y]); if c < 0.004 { continue }
            let vx = Double(x) + 0.5 - origin.x, vy = Double(y) + 0.5 - origin.y
            let along = vx * dir.dx + vy * dir.dy, across = -vx * dir.dy + vy * dir.dx   // across: below the baseline positive
            if along < from * Double(scale) || along > to * Double(scale) || abs(across) > 30 * Double(scale) { continue }
            sum += c; moment += c * across
        } }
        let length = (to - from) * Double(scale)
        return (moment / max(sum, 1e-9) / Double(scale), sum / length / Double(scale))
    }
    print("family\tsize\tscale\tangle\tstyle\tTextKit 2 centre,thickness\tCTLineDraw view\tCTLineDraw bare context")
    for (family, size) in [("Helvetica Neue", 13.0), ("Menlo", 12.0), ("Georgia", 18.0)] {
        let f = font(family, CGFloat(size))!
        let glyph = Double(("x" as NSString).size(withAttributes: [.font: f]).width)
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: TEXT, attributes: attributes(f, "plain")) as CFAttributedString)
        let width = Double(CTLineGetTypographicBounds(line, nil, nil, nil))
        let from = glyph + 1.5, to = width - glyph - 1.5
        for scale in [1, 2] { for angle in [0.0, 2.0, 10.0, 30.0, 45.0, 90.0] { for style in ["underline", "strike"] { autoreleasepool {
            var cells: [String] = []
            // TextKit 2 and CTLineDraw in views rotated about their centre inside a 400 x 400 host
            for stack in ["tk2", "ct"] {
                var shots: [Image] = [], origin = CGPoint.zero, dir = CGVector.zero
                for st in ["plain", style] {
                    let host = Flipped(frame: NSRect(x: 0, y: 0, width: 400, height: 400))
                    let v: NSView; var baseline: CGFloat = 0
                    if stack == "tk2" {
                        let tv = textView(NSAttributedString(string: TEXT, attributes: attributes(f, st)), width: 300, height: 120)
                        baseline = firstLine(tv).0; v = tv
                    } else {
                        let lv = LineView(frame: NSRect(x: 0, y: 0, width: 300, height: 120)); lv.font = f; lv.style = st
                        baseline = 60; v = lv
                    }
                    v.frame.origin = NSPoint(x: 50, y: 140)
                    host.addSubview(v)
                    v.frameCenterRotation = CGFloat(-angle)
                    shots.append(Image(render(host, scale)))
                    // the mapping as drawn (a text view may resize while it lays out), the baseline read again
                    if let tv = v as? NSTextView { baseline = firstLine(tv).0 }
                    let a = v.convert(NSPoint(x: 0, y: baseline), to: host), b = v.convert(NSPoint(x: 100, y: baseline), to: host)
                    origin = CGPoint(x: a.x * CGFloat(scale), y: a.y * CGFloat(scale))
                    let len = hypot(b.x - a.x, b.y - a.y); dir = CGVector(dx: (b.x - a.x) / len, dy: (b.y - a.y) / len)
                }
                let (c, t) = measure(shots[0], shots[1], scale: scale, origin: origin, dir: dir, from: from, to: to)
                cells.append(String(format: "%.3f,%.3f", c, t))
            }
            // CTLineDraw in a bare context: the CTM scaled and rotated (y down), the baseline at (100, 200) points
            var bare: [Image] = []
            let s = CGFloat(scale), rad = CGFloat(angle * .pi / 180)
            for st in ["plain", style] {
                let ctx = CGContext(data: nil, width: 400 * scale, height: 400 * scale, bitsPerComponent: 8, bytesPerRow: 0,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                ctx.setFillColor(CGColor(gray: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: 400 * scale, height: 400 * scale))
                ctx.setShouldSmoothFonts(false)
                // device y down: flip, then scale, translate to the baseline and rotate (clockwise on screen for a positive angle)
                ctx.translateBy(x: 0, y: CGFloat(400 * scale)); ctx.scaleBy(x: s, y: -s)
                ctx.translateBy(x: 100, y: 200); ctx.rotate(by: rad); ctx.scaleBy(x: 1, y: -1)
                ctx.textMatrix = .identity
                CTLineDraw(CTLineCreateWithAttributedString(NSAttributedString(string: TEXT, attributes: attributes(f, st)) as CFAttributedString), ctx)
                bare.append(Image(context: ctx))
            }
            // CGBitmapContext rows run from the top of memory: row 0 is the top of the image, as the views'
            let (c, t) = measure(bare[0], bare[1], scale: scale, origin: CGPoint(x: 100 * s, y: 200 * s),
                                 dir: CGVector(dx: cos(rad), dy: sin(rad)), from: from, to: to)
            cells.append(String(format: "%.3f,%.3f", c, t))
            print("\(family)\t\(Int(size))\t\(scale)x\t\(angle)\t\(style)\t" + cells.joined(separator: "\t"))
        } } } }
    }

case "whitespace":
    // the underline's columns (any coverage in its rows) from the line's start, in points, against the characters'
    // carets; TextKit 2 and CTLineDraw (a view drawing the CTLine at the same place)
    let f = font("Helvetica Neue", 13)!
    let texts = ["x      ", "      x", "\tx\t", "x  \t", "x   \n", "x\u{00A0}\u{00A0}\u{00A0}", "x\u{2003}\u{2003}", "x\u{3000}\u{3000}",
                 "x\u{2028}", "x   \u{2029}", "x\u{0085}", "x\r", "  x  y  ", "x\u{200B}\u{200B}", "x\u{2060}\u{2060}"]
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
    func span(_ p: Image, _ d: Image) -> String {
        var cols: [Int] = []
        for x in 0..<p.w { var c = 0.0; for y in 100..<110 { c += max(0, d[x: x, y: y] - p[x: x, y: y]) }; if c > 0.3 { cols.append(x) } }
        return cols.isEmpty ? "none" : "\(cols.first! - 20)..\(cols.last! + 1 - 20)"
    }
    print("text\tcarets (CTLine)\tTextKit 2 underline\tCTLineDraw underline")
    for t in texts { autoreleasepool {
        let plain = NSAttributedString(string: t, attributes: attributes(f, "plain")), ul = NSAttributedString(string: t, attributes: attributes(f, "underline"))
        let line = CTLineCreateWithAttributedString(plain as CFAttributedString)
        let carets = (0...(t as NSString).length).map { String(format: "%.1f", CTLineGetOffsetForStringIndex(line, $0, nil)) }.joined(separator: " ")
        var cells: [String] = []
        var shots: [Image] = []
        for s in [plain, ul] {
            let host = Flipped(frame: NSRect(x: 0, y: 0, width: 400, height: 160))
            let tv = textView(s); tv.frame.origin = NSPoint(x: 20, y: 100 - firstLine(tv).0); host.addSubview(tv)
            shots.append(Image(render(host, 1)))
        }
        cells.append(span(shots[0], shots[1])); shots = []
        for s in [plain, ul] { let v = LineView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); v.string = s; shots.append(Image(render(v, 1))) }
        cells.append(span(shots[0], shots[1]))
        let shown = t.unicodeScalars.map { $0.value < 0x21 || $0.value > 0x7e ? String(format: "<%04X>", $0.value) : String($0) }.joined()
        print("\(shown)\t\(carets)\t" + cells.joined(separator: "\t"))
    } }

case "tabs":
    // where TextKit 2 puts the character after n tabs (its line fragment's location for it, and the line it lands on)
    // against CTLine's offset; the default paragraph style
    let f = font("Helvetica Neue", 13)!
    print("text\tCTLine offset of the last character\tTextKit 2 location (x), line\tTextKit 2 lines")
    for n in [1, 2, 5, 11, 12, 13, 14, 20] {
        let t = "a" + String(repeating: "\t", count: n) + "b"
        let s = NSAttributedString(string: t, attributes: attributes(f, "plain"))
        let ct = CTLineGetOffsetForStringIndex(CTLineCreateWithAttributedString(s as CFAttributedString), t.utf16.count - 1, nil)
        let tv = textView(s, width: 2000, height: 400)
        var lines = 0, where_ = "-"
        tv.textLayoutManager!.enumerateTextLayoutFragments(from: tv.textLayoutManager!.documentRange.location, options: [.ensuresLayout]) { frag in
            for line in frag.textLineFragments {
                let r = line.characterRange
                if r.location <= t.utf16.count - 1 && t.utf16.count - 1 < r.location + r.length {
                    where_ = String(format: "%.2f, line %d", line.locationForCharacter(at: t.utf16.count - 1).x, lines)
                }
                lines += 1
            }
            return true
        }
        print("a+\(n)tab+b\t\(String(format: "%.2f", ct))\t\(where_)\t\(lines)")
    }
    // past the last default stop on a narrow view: does TextKit 2 wrap the tab?
    for width: CGFloat in [2000, 300] {
        let t = "a" + String(repeating: "\t", count: 13) + "b"
        let tv = textView(NSAttributedString(string: t, attributes: attributes(f, "plain")), width: width, height: 400)
        var lines = 0
        tv.textLayoutManager!.enumerateTextLayoutFragments(from: tv.textLayoutManager!.documentRange.location, options: [.ensuresLayout]) { frag in lines += frag.textLineFragments.count; return true }
        print("a+13tab+b in a view \(Int(width)) wide: \(lines) line(s)")
    }

case "ctref":
    // ctref.swift's arm: CTLineDraw into a CGBitmapContext with the scale in the CTM, the baseline on a whole row;
    // the underline's solid rows over the spaces from the baseline row, at 1x and 2x
    print("family\tsize\tscale\tCTLineDraw, scaled bare context")
    for family in FAMILIES { for size: CGFloat in [9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 40, 48] { autoreleasepool {
        guard let f = font(family, size) else { return }
        for scale in [1, 2] {
            var shots: [Image] = []
            for st in ["plain", "underline"] {
                let ctx = CGContext(data: nil, width: 400 * scale, height: 160 * scale, bitsPerComponent: 8, bytesPerRow: 0,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                ctx.setFillColor(CGColor(gray: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: 400 * scale, height: 160 * scale))
                ctx.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
                ctx.textMatrix = .identity
                ctx.textPosition = CGPoint(x: 20, y: 60)   // y up: 100 from the top of the 160-point image
                CTLineDraw(CTLineCreateWithAttributedString(NSAttributedString(string: TEXT, attributes: attributes(f, st)) as CFAttributedString), ctx)
                shots.append(Image(context: ctx))
            }
            let x1 = (20 + Int(f.pointSize * 1.1)) * scale, x2 = x1 + 4 * scale
            let prof = profile(shots[0], shots[1], x1, x2)
            let solid = prof.enumerated().filter { $0.element >= 0.9 }.map { $0.offset - 100 * scale }
            print("\(family)\t\(Int(size))\t\(scale)x\t" + (solid.isEmpty ? "-" : "\(solid.first!)..\(solid.last! + 1)"))
        }
    } } }

default:
    print("unknown mode \(mode)")
}
