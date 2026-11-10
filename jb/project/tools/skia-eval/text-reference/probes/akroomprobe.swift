// AppKit's underline against one input at a time (2026-11-10), for reverse-engineering its rule: one font, a paragraph
// style varied in small steps (lineSpacing, which adds room below the line; or the others by name), and for each step
// the room AppKit hands its decoration drawing (NSLayoutManager's baselineOffset), the stroke it emits into a PDF context
// (centre below the baseline, width) and the device rows the underline covers in a view rendered at 1x, 2x and 3x with
// the baseline on a whole row. TSV on stdout.
//
//   swift akroomprobe.swift <family> <size> [lineSpacing|minimumLineHeight|lineHeightMultiple|paragraphSpacing] [from to step]
import AppKit

let args = CommandLine.arguments
let family = args[1], size = CGFloat(Double(args[2])!)
let knob = args.count > 3 ? args[3] : "lineSpacing"
let from = args.count > 4 ? Double(args[4])! : 0, to = args.count > 5 ? Double(args[5])! : 8, step = args.count > 6 ? Double(args[6])! : 0.25
let TEXT = "x          x"

final class Spy: NSLayoutManager {
    var room: CGFloat = .nan
    override func drawUnderline(forGlyphRange glyphRange: NSRange, underlineType: NSUnderlineStyle, baselineOffset: CGFloat,
                                lineFragmentRect: NSRect, lineFragmentGlyphRange: NSRange, containerOrigin: NSPoint) {
        room = baselineOffset
        super.drawUnderline(forGlyphRange: glyphRange, underlineType: underlineType, baselineOffset: baselineOffset,
                            lineFragmentRect: lineFragmentRect, lineFragmentGlyphRange: lineFragmentGlyphRange, containerOrigin: containerOrigin)
    }
}

final class LineView: NSView {
    var font: NSFont!, style = NSParagraphStyle(), underline = true, spy = Spy()
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        var attrs: [NSAttributedString.Key: Any] = [.font: font!, .foregroundColor: NSColor.black, .paragraphStyle: style]
        if underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        let storage = NSTextStorage(string: TEXT, attributes: attrs)
        let c = NSTextContainer(size: CGSize(width: 10000, height: 1000)); c.lineFragmentPadding = 0
        spy = Spy(); spy.addTextContainer(c); storage.addLayoutManager(spy)
        let baseline = spy.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil).minY + spy.location(forGlyphAt: 0).y
        spy.drawGlyphs(forGlyphRange: spy.glyphRange(for: c), at: NSPoint(x: 20, y: 100 - baseline))
    }
}

func rows(_ v: LineView, _ scale: Int) -> String {
    func render(_ u: Bool) -> (UnsafeMutablePointer<UInt8>, Int, Int, NSBitmapImageRep) {
        v.underline = u
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 400 * scale, pixelsHigh: 160 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: 400, height: 160)
        v.cacheDisplay(in: v.bounds, to: rep)
        return (rep.bitmapData!, rep.bytesPerRow, rep.bitsPerPixel / 8, rep)
    }
    let (p, ps, pb, keepP) = render(false), (d, ds, db, keepD) = render(true)
    _ = (keepP, keepD)
    let w = 400 * scale, h = 160 * scale
    func ink(_ b: UnsafeMutablePointer<UInt8>, _ s: Int, _ bpp: Int, _ x: Int, _ y: Int) -> Double { 1 - Double(b[y * s + x * bpp + 1]) / 255 }
    var first = Int.max, last = 0
    for y in 0..<h { for x in 0..<w where ink(p, ps, pb, x, y) > 0.1 { first = min(first, x); last = max(last, x) } }
    let glyph = (last - first) / 12, x1 = first + glyph * 3 / 2, x2 = last - glyph * 3 / 2
    var covered: [Int] = [], partial: [String] = []
    for y in 0..<h {
        var c = 0.0
        for x in x1...x2 { c += ink(d, ds, db, x, y) - ink(p, ps, pb, x, y) }
        c /= Double(x2 - x1 + 1)
        if c >= 0.9 { covered.append(y - 100 * scale) } else if c > 0.05 { partial.append(String(format: "%+d=%.2f", y - 100 * scale, c)) }
    }
    let solid = covered.isEmpty ? "-" : "\(covered.first!)..\(covered.last! + 1)"
    return partial.isEmpty ? solid : solid + "(" + partial.joined(separator: ",") + ")"
}

func pdfStroke(_ v: LineView) -> String {
    v.underline = true
    let data = v.dataWithPDF(inside: v.bounds)
    guard let doc = CGPDFDocument(CGDataProvider(data: data as CFData)!), let page = doc.page(at: 1), let dict = page.dictionary else { return "-\t-" }
    var contents: CGPDFStreamRef? = nil
    guard CGPDFDictionaryGetStream(dict, "Contents", &contents), let c = contents else { return "-\t-" }
    var format = CGPDFDataFormat.raw
    guard let bytes = CGPDFStreamCopyData(c, &format) as Data?, let text = String(data: bytes, encoding: .isoLatin1) else { return "-\t-" }
    let tokens = text.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init)
    var width = 1.0
    for (i, t) in tokens.enumerated() {
        if t == "w", i > 0, let ww = Double(tokens[i - 1]) { width = ww }
        if t == "m", i > 1, let yy = Double(tokens[i - 1]), tokens.count > i + 4, tokens[i + 3] == "l", tokens[i + 4] == "S" {
            return String(format: "%.4f\t%.4f", yy - 100, width)
        }
    }
    return "-\t-"
}

guard let base = NSFontManager.shared.font(withFamily: family, traits: [], weight: 5, size: size) else { print("no such family"); exit(1) }
let font = NSFont(descriptor: base.fontDescriptor, size: size)!
let ct = font as CTFont
print(String(format: "# %@ %.1f: ascent %.4f descent %.4f leading %.4f underlinePosition %.4f thickness %.4f", family, size, CTFontGetAscent(ct),
             CTFontGetDescent(ct), CTFontGetLeading(ct), -CTFontGetUnderlinePosition(ct), CTFontGetUnderlineThickness(ct)))
print("\(knob)\troom\tpdfY\tpdfWidth\trows1x\trows2x\trows3x")
var value = from
while value <= to + 1e-9 {
    autoreleasepool {
        let style = NSMutableParagraphStyle()
        switch knob {
        case "minimumLineHeight": style.minimumLineHeight = value
        case "lineHeightMultiple": style.lineHeightMultiple = value
        case "paragraphSpacing": style.paragraphSpacing = value
        default: style.lineSpacing = value
        }
        let v = LineView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); v.font = font; v.style = style
        let stroke = pdfStroke(v)
        print(String(format: "%.3f\t%.4f\t", value, v.spy.room) + stroke + "\t" + rows(v, 1) + "\t" + rows(v, 2) + "\t" + rows(v, 3))
    }
    value += step
}
