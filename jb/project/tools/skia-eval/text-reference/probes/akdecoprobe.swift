// AppKit's underline and strikethrough geometry over many fonts and sizes (2026-11-10), for fitting its rule: for
// each font and size one TSV row with CoreText's metrics, the descent room AppKit hands its decoration drawing
// (NSLayoutManager's baselineOffset: the line fragment's bottom below the baseline), the exact strokes AppKit emits
// into a PDF context (y of the stroke's centre below the baseline and its width, both decorations), and the device
// rows the decorations cover in an NSView rendered at 1x and 2x backing scale with the baseline on a whole row.
//
//   swift akdecoprobe.swift <out.tsv> [family ...]
import AppKit

let args = CommandLine.arguments
let TEXT = "x          x"

final class Spy: NSLayoutManager {
    var underlineRoom: CGFloat = .nan, strikeRoom: CGFloat = .nan
    override func drawUnderline(forGlyphRange glyphRange: NSRange, underlineType: NSUnderlineStyle, baselineOffset: CGFloat,
                                lineFragmentRect: NSRect, lineFragmentGlyphRange: NSRange, containerOrigin: NSPoint) {
        underlineRoom = baselineOffset
        super.drawUnderline(forGlyphRange: glyphRange, underlineType: underlineType, baselineOffset: baselineOffset,
                            lineFragmentRect: lineFragmentRect, lineFragmentGlyphRange: lineFragmentGlyphRange, containerOrigin: containerOrigin)
    }
    override func drawStrikethrough(forGlyphRange glyphRange: NSRange, strikethroughType: NSUnderlineStyle, baselineOffset: CGFloat,
                                    lineFragmentRect: NSRect, lineFragmentGlyphRange: NSRange, containerOrigin: NSPoint) {
        strikeRoom = baselineOffset
        super.drawStrikethrough(forGlyphRange: glyphRange, strikethroughType: strikethroughType, baselineOffset: baselineOffset,
                                lineFragmentRect: lineFragmentRect, lineFragmentGlyphRange: lineFragmentGlyphRange, containerOrigin: containerOrigin)
    }
}

// STACK=string draws through NSAttributedString string drawing, which TextKit 2 (NSTextView's default) equals
// (aktk2probe.swift: 893 of 893 underline cases); the default is TextKit 1 (NSLayoutManager)
let STRING_DRAWING = ProcessInfo.processInfo.environment["STACK"] == "string"

/** A line with the layout manager's baseline at y 100 (flipped), the decoration as asked. */
final class LineView: NSView {
    var font: NSFont!, decoration = "plain", spy = Spy()
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        var attrs: [NSAttributedString.Key: Any] = [.font: font!, .foregroundColor: NSColor.black]
        if decoration == "underline" { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if decoration == "strike" { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        if STRING_DRAWING {
            let baseline = NSLayoutManager().defaultBaselineOffset(for: font)
            NSAttributedString(string: TEXT, attributes: attrs).draw(with: NSRect(x: 20, y: 100 - baseline, width: 1000, height: 200), options: [.usesLineFragmentOrigin])
            return
        }
        let storage = NSTextStorage(string: TEXT, attributes: attrs)
        let c = NSTextContainer(size: CGSize(width: 10000, height: 1000)); c.lineFragmentPadding = 0
        spy = Spy(); spy.addTextContainer(c); storage.addLayoutManager(spy)
        let baseline = spy.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil).minY + spy.location(forGlyphAt: 0).y
        spy.drawGlyphs(forGlyphRange: spy.glyphRange(for: c), at: NSPoint(x: 20, y: 100 - baseline))
    }
}

func ink(_ rep: NSBitmapImageRep) -> [[Double]] {
    var rows = [[Double]](repeating: [Double](repeating: 0, count: rep.pixelsWide), count: rep.pixelsHigh)
    // the bytes themselves (black ink on white: one minus the green channel): colorAt makes an autoreleased NSColor
    // per pixel, which a command-line loop never drains (the first run grew to 18 GB)
    let data = rep.bitmapData!, stride = rep.bytesPerRow, bpp = rep.bitsPerPixel / 8
    for y in 0..<rep.pixelsHigh { for x in 0..<rep.pixelsWide { rows[y][x] = 1 - Double(data[y * stride + x * bpp + 1]) / 255 } }
    return rows
}

/** The device rows the decoration covers (>= 0.9 over the middle of the spaces), relative to the baseline row. */
func decorationRows(_ font: NSFont, _ decoration: String, _ scale: Int) -> String {
    func render(_ d: String) -> [[Double]] {
        let v = LineView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); v.font = font; v.decoration = d
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 400 * scale, pixelsHigh: 160 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: 400, height: 160)
        v.cacheDisplay(in: v.bounds, to: rep)
        return ink(rep)
    }
    let plain = render("plain"), decorated = render(decoration)
    // the spaces' middle: right of the first x's ink, left of the last's
    var first = Int.max, last = 0
    for row in plain { for (x, c) in row.enumerated() where c > 0.1 { first = min(first, x); last = max(last, x) } }
    let glyph = (last - first) / 12, x1 = first + glyph * 3 / 2, x2 = last - glyph * 3 / 2
    var covered: [Int] = []
    for y in 0..<decorated.count {
        var c = 0.0
        for x in x1...x2 { c += decorated[y][x] - plain[y][x] }
        if c / Double(x2 - x1 + 1) >= 0.9 { covered.append(y - 100 * scale) }
    }
    return covered.isEmpty ? "-" : "\(covered.first!)..\(covered.last! + 1)"
}

/** The decoration's coverage-weighted centre below the baseline at 16x (AppKit's own position, snapped to 1/16). */
func fineCentre(_ font: NSFont, _ decoration: String) -> String {
    let scale = 16
    func render(_ d: String) -> NSBitmapImageRep {
        let v = LineView(frame: NSRect(x: 0, y: 0, width: 200, height: 140)); v.font = font; v.decoration = d
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 200 * scale, pixelsHigh: 140 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: 200, height: 140)
        v.cacheDisplay(in: v.bounds, to: rep)
        return rep
    }
    let p = render("plain"), d = render(decoration)
    let pb = p.bitmapData!, db = d.bitmapData!, st = p.bytesPerRow, bpp = p.bitsPerPixel / 8, w = 200 * scale, h = 140 * scale
    func ink(_ b: UnsafeMutablePointer<UInt8>, _ x: Int, _ y: Int) -> Double { 1 - Double(b[y * st + x * bpp + 1]) / 255 }
    var first = Int.max
    for y in 0..<h { for x in 0..<w where ink(pb, x, y) > 0.1 { first = min(first, x); break } }
    // the spaces: a band of columns right of the first x (its advance is under 1.1 em)
    let x1 = first + Int(font.pointSize * 1.1) * scale, x2 = x1 + 4 * scale
    var sum = 0.0, moment = 0.0
    for y in 0..<h {
        var c = 0.0
        for x in x1..<min(x2, w) { c += max(0, ink(db, x, y) - ink(pb, x, y)) }
        c /= Double(x2 - x1)
        sum += c; moment += c * (Double(y) + 0.5)
    }
    return sum == 0 ? "-" : String(format: "%.4f", moment / sum / Double(scale) - 100)
}

/** The decoration's stroke in a PDF context: its centre below the baseline and its width (1 when the stream sets none). */
func pdfStroke(_ font: NSFont, _ decoration: String) -> String {
    let v = LineView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); v.font = font; v.decoration = decoration
    let data = v.dataWithPDF(inside: v.bounds)
    guard let doc = CGPDFDocument(CGDataProvider(data: data as CFData)!), let page = doc.page(at: 1), let dict = page.dictionary else { return "-\t-" }
    var contents: CGPDFStreamRef? = nil
    guard CGPDFDictionaryGetStream(dict, "Contents", &contents), let c = contents else { return "-\t-" }
    var format = CGPDFDataFormat.raw
    guard let bytes = CGPDFStreamCopyData(c, &format) as Data?, let text = String(data: bytes, encoding: .isoLatin1) else { return "-\t-" }
    // "[w w] ... 1 0 0 -1 0 160 cm 20 <y> m <x> <y> l S"
    let tokens = text.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init)
    var width = 1.0, y = Double.nan
    for (i, t) in tokens.enumerated() {
        if t == "w", i > 0, let w = Double(tokens[i - 1]) { width = w }
        // a stroked segment "x y m x y l S" (the background is a filled path)
        if t == "m", i > 1, let yy = Double(tokens[i - 1]), tokens.count > i + 4, tokens[i + 3] == "l", tokens[i + 4] == "S" { y = yy; break }
    }
    return y.isNaN ? "-\t-" : String(format: "%.4f\t%.4f", y - 100, width)
}

var families = Array(args.dropFirst(2))
if families.isEmpty {
    families = ["Helvetica Neue", "Helvetica", "Arial", "Verdana", "Georgia", "Times New Roman", "Times", "Courier New", "Courier", "Menlo",
                "Monaco", "Avenir Next", "Avenir", "Lucida Grande", "Gill Sans", "Futura", "Optima", "Palatino", "Baskerville", "Didot",
                "American Typewriter", "Trebuchet MS", "Tahoma", "Geneva", "SF Mono", "Charter", "Hoefler Text", "Cochin", "PT Sans", "PT Serif"]
}
var out = "family\tsize\tascent\tdescent\tleading\txHeight\tcapHeight\tunderlinePosition\tunderlineThickness\tunderlineRoom\tstrikeRoom\t" +
          "underlineY\tunderlineWidth\tstrikeY\tstrikeWidth\tunderline1x\tunderline2x\tstrike1x\tstrike2x\tunderlineFine\tstrikeFine\n"
for family in families {
    guard let base = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: 12) else { continue }
    for size: CGFloat in [9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 40, 48] { autoreleasepool {
        let font = NSFont(descriptor: base.fontDescriptor, size: size)!
        let ct = font as CTFont
        let spy = LineView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); spy.font = font; spy.decoration = "underline"
        _ = spy.dataWithPDF(inside: spy.bounds); let uRoom = spy.spy.underlineRoom
        spy.decoration = "strike"; _ = spy.dataWithPDF(inside: spy.bounds); let sRoom = spy.spy.strikeRoom
        out += String(format: "%@\t%.0f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t", family, size, CTFontGetAscent(ct), CTFontGetDescent(ct), CTFontGetLeading(ct),
                      CTFontGetXHeight(ct), CTFontGetCapHeight(ct), -CTFontGetUnderlinePosition(ct), CTFontGetUnderlineThickness(ct), uRoom, sRoom)
        out += pdfStroke(font, "underline") + "\t" + pdfStroke(font, "strike") + "\t"
        out += [decorationRows(font, "underline", 1), decorationRows(font, "underline", 2), decorationRows(font, "strike", 1), decorationRows(font, "strike", 2),
                fineCentre(font, "underline"), fineCentre(font, "strike")].joined(separator: "\t") + "\n"
    } }
}
try! out.write(toFile: args[1], atomically: true, encoding: .utf8)
print("wrote \(out.split(separator: "\n").count - 1) rows to \(args[1])")
