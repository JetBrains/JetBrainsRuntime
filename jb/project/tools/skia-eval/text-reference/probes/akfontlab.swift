// AppKit's underline against font metrics varied one at a time (2026-11-10): a TrueType file patched in memory — the
// post table's underline position and thickness, the hhea table's descender and line gap, in font units — loaded
// through CGFont, and for each variant the metrics CoreText reads back, the room AppKit hands its decoration drawing,
// the stroke it emits into a PDF context and the device rows the underline covers in a view at 1x, 2x and 3x with the
// baseline on a whole row. TSV on stdout.
//
//   swift akfontlab.swift <font.ttf> <size> <field> <from> <to> <step> [field=value ...]
//   field: underlinePosition | underlineThickness | descender | lineGap   (font units; position negative below)
import AppKit

let args = CommandLine.arguments
let original = try! Data(contentsOf: URL(fileURLWithPath: args[1]))
let size = CGFloat(Double(args[2])!)
let field = args[3], from = Int(args[4])!, to = Int(args[5])!, step = Int(args[6])!
var fixed: [String: Int] = [:]
for a in args.dropFirst(7) { let kv = a.split(separator: "="); fixed[String(kv[0])] = Int(kv[1])! }
let TEXT = "x          x"

func tableOffset(_ d: Data, _ tag: String) -> Int {
    let n = Int(d[4]) << 8 | Int(d[5])
    for i in 0..<n {
        let r = 12 + 16 * i
        if String(bytes: d[r..<r + 4], encoding: .ascii) == tag { return Int(d[r + 8]) << 24 | Int(d[r + 9]) << 16 | Int(d[r + 10]) << 8 | Int(d[r + 11]) }
    }
    fatalError("no \(tag) table")
}
func put16(_ d: inout Data, _ at: Int, _ v: Int) { let u = UInt16(bitPattern: Int16(v)); d[at] = UInt8(u >> 8); d[at + 1] = UInt8(u & 0xff) }
let locations = ["underlinePosition": ("post", 8), "underlineThickness": ("post", 10), "descender": ("hhea", 6), "lineGap": ("hhea", 8)]

func font(_ values: [String: Int]) -> NSFont {
    var d = original
    for (k, v) in values { let (tag, off) = locations[k]!; put16(&d, tableOffset(d, tag) + off, v) }
    let cg = CGFont(CGDataProvider(data: d as CFData)!)!
    return CTFontCreateWithGraphicsFont(cg, size, nil, nil) as NSFont
}

final class Spy: NSLayoutManager {
    var room: CGFloat = .nan
    override func drawUnderline(forGlyphRange glyphRange: NSRange, underlineType: NSUnderlineStyle, baselineOffset: CGFloat,
                                lineFragmentRect: NSRect, lineFragmentGlyphRange: NSRange, containerOrigin: NSPoint) {
        room = baselineOffset
        super.drawUnderline(forGlyphRange: glyphRange, underlineType: underlineType, baselineOffset: baselineOffset,
                            lineFragmentRect: lineFragmentRect, lineFragmentGlyphRange: lineFragmentGlyphRange, containerOrigin: containerOrigin)
    }
}
// STACK=string draws through NSAttributedString string drawing, which TextKit 2 (NSTextView's default) equals
// (aktk2probe.swift: 893 of 893 cases); the default is TextKit 1 (NSLayoutManager)
let STRING_DRAWING = ProcessInfo.processInfo.environment["STACK"] == "string"

final class LineView: NSView {
    var font: NSFont!, underline = true, spy = Spy(), shift: CGFloat = 0
    override var isFlipped: Bool { true }
    override func draw(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        var attrs: [NSAttributedString.Key: Any] = [.font: font!, .foregroundColor: NSColor.black]
        if underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if STRING_DRAWING {
            let baseline = NSLayoutManager().defaultBaselineOffset(for: font)
            NSAttributedString(string: TEXT, attributes: attrs).draw(with: NSRect(x: 20, y: 100 + shift - baseline, width: 1000, height: 200), options: [.usesLineFragmentOrigin])
            return
        }
        let storage = NSTextStorage(string: TEXT, attributes: attrs)
        let c = NSTextContainer(size: CGSize(width: 10000, height: 1000)); c.lineFragmentPadding = 0
        spy = Spy(); spy.addTextContainer(c); storage.addLayoutManager(spy)
        let baseline = spy.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil).minY + spy.location(forGlyphAt: 0).y
        spy.drawGlyphs(forGlyphRange: spy.glyphRange(for: c), at: NSPoint(x: 20, y: 100 - baseline))
    }
}
func rows(_ v: LineView, _ scale: Int) -> String {
    func render(_ u: Bool) -> NSBitmapImageRep {
        v.underline = u
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 400 * scale, pixelsHigh: 160 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: 400, height: 160)
        v.cacheDisplay(in: v.bounds, to: rep)
        return rep
    }
    let p = render(false), d = render(true)
    let pb = p.bitmapData!, db = d.bitmapData!, s = p.bytesPerRow, bpp = p.bitsPerPixel / 8, w = 400 * scale, h = 160 * scale
    func ink(_ b: UnsafeMutablePointer<UInt8>, _ x: Int, _ y: Int) -> Double { 1 - Double(b[y * s + x * bpp + 1]) / 255 }
    var first = Int.max, last = 0
    for y in 0..<h { for x in 0..<w where ink(pb, x, y) > 0.1 { first = min(first, x); last = max(last, x) } }
    let glyph = (last - first) / 12, x1 = first + glyph * 3 / 2, x2 = last - glyph * 3 / 2
    var covered: [Int] = [], partial: [String] = []
    for y in 0..<h {
        var c = 0.0
        for x in x1...x2 { c += ink(db, x, y) - ink(pb, x, y) }
        c /= Double(x2 - x1 + 1)
        if c >= 0.9 { covered.append(y - 100 * scale) } else if c > 0.05 { partial.append(String(format: "%+d=%.2f", y - 100 * scale, c)) }
    }
    let solid = covered.isEmpty ? "-" : "\(covered.first!)..\(covered.last! + 1)"
    return partial.isEmpty ? solid : solid + "(" + partial.joined(separator: ",") + ")"
}
/** The underline's coverage-weighted centre and coverage height in user units, rendered at a high backing scale. */
func fine(_ v: LineView, _ scale: Int) -> String {
    func render(_ u: Bool) -> NSBitmapImageRep {
        v.underline = u
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 200 * scale, pixelsHigh: 140 * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: 200, height: 140)
        v.cacheDisplay(in: NSRect(x: 0, y: 0, width: 200, height: 140), to: rep)
        return rep
    }
    let p = render(false), d = render(true)
    let pb = p.bitmapData!, db = d.bitmapData!, s = p.bytesPerRow, bpp = p.bitsPerPixel / 8, w = 200 * scale, h = 140 * scale
    func ink(_ b: UnsafeMutablePointer<UInt8>, _ x: Int, _ y: Int) -> Double { 1 - Double(b[y * s + x * bpp + 1]) / 255 }
    // a column band in the spaces: right of the first x
    var first = Int.max
    for y in 0..<h { for x in 0..<w where ink(pb, x, y) > 0.1 { first = min(first, x); break } }
    let x1 = first + 12 * scale, x2 = first + 20 * scale
    // the first x's bottom edge over all its columns: the true baseline, to 1/16 point
    var right = first
    while right + 1 < w && (0..<h).contains(where: { ink(pb, right + 1, $0) > 0.3 }) { right += 1 }
    var glyphRows = [Double](repeating: 0, count: h)
    for y in 0..<h { for x in first...right { glyphRows[y] += ink(pb, x, y) } }
    var last = 0
    for y in 0..<h where glyphRows[y] > 0.02 { last = y }
    let baseline = (Double(last) + min(1, glyphRows[last] / max(glyphRows[max(0, last - 2)], 1e-9))) / Double(scale) - 100
    var sum = 0.0, moment = 0.0
    for y in (100 * scale - 5 * scale)..<min(h, 100 * scale + 20 * scale) {
        var c = 0.0
        for x in x1..<x2 { c += max(0, ink(db, x, y) - ink(pb, x, y)) }
        c /= Double(x2 - x1)
        sum += c; moment += c * (Double(y) + 0.5)
    }
    return sum == 0 ? "-\t-\t-" : String(format: "%.4f\t%.4f\t%.4f", moment / sum / Double(scale) - 100, sum / Double(scale), baseline)
}

/**
 * The underline's top row at 1x as the baseline moves down by k/32 of a pixel: "row@phase" at each change. A change
 * at phase f puts the edge CoreText rounds at f before the next row boundary (for a rounding to the nearest row:
 * the band's top edge lies at the row + 0.5 - f below the unshifted baseline).
 */
func phases(_ v: LineView, _ scale: Int) -> String {
    var out: [String] = [], last = ""
    for k in 0..<32 {
        v.shift = CGFloat(k) / 32 / CGFloat(scale)
        let r = rows(v, scale)
        let top = r.split(separator: ".").first.map(String.init) ?? r
        if top != last { out.append("\(top)@\(k)"); last = top }
    }
    v.shift = 0
    return out.joined(separator: ",")
}

/** The underline's edges in device pixels below the unshifted baseline at 1x, from its coverage with the baseline moved by half a pixel. */
func edges1x(_ v: LineView) -> String {
    v.shift = 0.5
    let r = rows(v, 1)
    v.shift = 0
    // "-(+1=0.30,+2=0.40)" or "a..b(+c=x,...)": add the coverages up as a band [top, top + sum)
    var cover: [(Int, Double)] = []
    if let solid = r.split(separator: "(").first, solid.contains("..") {
        let ab = solid.split(separator: ".").filter { !$0.isEmpty }.compactMap { Int($0) }
        if ab.count == 2 { for y in ab[0]..<ab[1] { cover.append((y, 1)) } }
    }
    if let open = r.firstIndex(of: "(") {
        for part in r[r.index(after: open)...].dropLast().split(separator: ",") {
            let kv = part.split(separator: "="); if kv.count == 2, let y = Int(kv[0]), let c = Double(kv[1]) { cover.append((y, c)) }
        }
    }
    cover.sort { $0.0 < $1.0 }
    guard let first = cover.first else { return "-\t-" }
    let height = cover.reduce(0) { $0 + $1.1 }
    let top = Double(first.0) + 1 - first.1 - 0.5   // the first row's uncovered part, and the half-pixel shift taken back
    return String(format: "%.3f\t%.3f", top, height)
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

let upem = CGFloat(CTFontGetUnitsPerEm(font([:]) as CTFont))
print("# \(args[1].split(separator: "/").last!) at \(size), \(Int(upem)) units per em; fixed \(fixed)")
let PHASES = ProcessInfo.processInfo.environment["PHASES"] != nil
print("\(field)\tposition\tthickness\tdescent\tleading\troom\tpdfY\tpdfWidth\tfineCentre\tfineHeight\tbaseline16\trows1x\trows2x\trows3x" + (PHASES ? "\tphases1x\tphases2x" : ""))
var value = from
while (step > 0 && value <= to) || (step < 0 && value >= to) {
    autoreleasepool {
        var values = fixed; values[field] = value
        let f = font(values), ct = f as CTFont
        let v = LineView(frame: NSRect(x: 0, y: 0, width: 400, height: 160)); v.font = f
        let stroke = pdfStroke(v)
        print(String(format: "%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t", value, -CTFontGetUnderlinePosition(ct), CTFontGetUnderlineThickness(ct), CTFontGetDescent(ct),
                     CTFontGetLeading(ct), v.spy.room) + stroke + "\t" + fine(v, 16) + "\t" + rows(v, 1) + "\t" + rows(v, 2) + "\t" + rows(v, 3)
              + (PHASES ? "\t" + phases(v, 1) + "\t" + phases(v, 2) + "\t" + edges1x(v) : ""))
    }
    value += step
}
