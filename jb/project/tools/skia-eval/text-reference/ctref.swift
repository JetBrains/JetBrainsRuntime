// The platform's own text rendering as the reference for the text sheets: the same rows and sheets
// as TextReference.java, drawn by CoreText into CoreGraphics bitmaps with the flags that map to
// Java's hints:
//   aa        gray and lcd = antialias on with font smoothing on (AppKit's default; this macOS has no
//             colour sub-pixels, so both sheets are the same rendering); aliased = antialias off;
//             with --unsmoothed the gray and lcd sheets have smoothing off
//   positioning  fractional = CoreText's own layout, sub-pixel positioning on.
//             whole-pixel = glyphs placed like Java's whole-pixel mode: every advance rounded to a
//             whole pixel (in device pixels for the file fonts, as the FreeType strike does; in points
//             for the system fonts, as CStrike does) and summed, drawn with quantization on. The
//             platform itself has no integer-advance mode; this keeps the sheets comparable.
//   scale     1x, 2x (CTM scale; the cell is W*scale x H*scale px)
//   paper     black-on-white, white-on-black (plain black and white since 2026-10-26; before: the IDE's
//             editor colours), as DeviceRGB components, i.e. the exact pixel values Java writes
// Rows: family x {regular, italic, bold, bold italic, regular underlined} for Inter 13 and JetBrains
// Mono 13 (font files from the JDK image) and Helvetica Neue 13, Menlo 12 (system faces).
// Usage: ctref <out-dir> <rows.txt> <fonts-dir>
import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

let W = 460, H = 28, BASE = 19, X = 4            // the cell in points
let TEXT = "Quick fox jumps 1234567890 {}[]();:,. ∑∂Δ → ≠ ≤ ≥ äöü"
let STYLES = ["regular", "italic", "bold", "bold italic", "regular underlined"]

let args = CommandLine.arguments
guard args.count == 4 || args.count == 5 else { FileHandle.standardError.write("usage: ctref <out-dir> <rows.txt> <fonts-dir> [--unsmoothed]\n".data(using: .utf8)!); exit(2) }
let outDir = args[1], fontsDir = args[3]
// AppKit's default is font smoothing on (the AppleFontSmoothing preference) for all antialiased text;
// --unsmoothed renders the gray and lcd sheets with smoothing off (what Skia and Chrome draw unhinted)
let unsmoothed = args.count == 5 && args[4] == "--unsmoothed"
let continuous = CommandLine.arguments.contains("--continuous")

func fileFont(_ name: String, _ size: CGFloat) -> CTFont {
    let url = URL(fileURLWithPath: fontsDir + "/" + name) as CFURL
    guard let descs = CTFontManagerCreateFontDescriptorsFromURL(url) as? [CTFontDescriptor], let d = descs.first else {
        FileHandle.standardError.write("cannot read font \(name)\n".data(using: .utf8)!); exit(1)
    }
    return CTFontCreateWithFontDescriptor(d, size, nil)
}
func systemFont(_ postScriptName: String, _ size: CGFloat) -> CTFont { CTFontCreateWithName(postScriptName as CFString, size, nil) }

struct Row { let label: String; let font: CTFont; let underlined: Bool; let fileFont: Bool }
var rows: [Row] = []
func add(_ family: String, _ faces: [CTFont], file: Bool) {
    for i in 0..<4 { rows.append(Row(label: "\(family) \(STYLES[i])", font: faces[i], underlined: false, fileFont: file)) }
    rows.append(Row(label: "\(family) \(STYLES[4])", font: faces[0], underlined: true, fileFont: file))
}
add("Inter 13", ["Inter-Regular.otf", "Inter-Italic.otf", "Inter-SemiBold.otf", "Inter-SemiBoldItalic.otf"].map { fileFont($0, 13) }, file: true)
add("JetBrains Mono 13", ["JetBrainsMono-Regular.ttf", "JetBrainsMono-Italic.ttf", "JetBrainsMono-Bold.ttf", "JetBrainsMono-BoldItalic.ttf"].map { fileFont($0, 13) }, file: true)
add("Helvetica Neue 13", ["HelveticaNeue", "HelveticaNeue-Italic", "HelveticaNeue-Bold", "HelveticaNeue-BoldItalic"].map { systemFont($0, 13) }, file: false)
add("Menlo 12", ["Menlo-Regular", "Menlo-Italic", "Menlo-Bold", "Menlo-BoldItalic"].map { systemFont($0, 12) }, file: false)

let cs = CGColorSpaceCreateDeviceRGB()
func rgb(_ r: Int, _ g: Int, _ b: Int) -> CGColor { CGColor(colorSpace: cs, components: [CGFloat(r) / 255, CGFloat(g) / 255, CGFloat(b) / 255, 1])! }

// Java's whole-pixel layout for a CoreText line: positions from individually rounded advances
func wholePixelPositions(_ run: CTRun, scale: CGFloat, roundInDevice: Bool, start: CGFloat) -> ([CGPoint], CGFloat) {
    let n = CTRunGetGlyphCount(run)
    var advances = [CGSize](repeating: .zero, count: n)
    CTRunGetAdvances(run, CFRangeMake(0, n), &advances)
    var x = start, positions: [CGPoint] = []
    for a in advances {
        positions.append(CGPoint(x: x, y: 0))
        x += roundInDevice ? (a.width * scale).rounded() / scale : a.width.rounded()
    }
    return (positions, x)
}

var count = 0
for aa in ["gray", "lcd", "aliased"] { for positioning in ["whole-pixel", "fractional"] { for scale in [1, 2] { for paper in ["black-on-white", "white-on-black"] {
    let dark = paper == "white-on-black"
    var ink = dark ? rgb(255, 255, 255) : rgb(0, 0, 0)
    if let hex = ProcessInfo.processInfo.environment["TEXTREF_INK"], let v = UInt32(hex, radix: 16) { ink = rgb(Int((v >> 16) & 255), Int((v >> 8) & 255), Int(v & 255)) }   // experiments: override both papers' colours
    var bg = dark ? rgb(0, 0, 0) : rgb(255, 255, 255)
    if let hex = ProcessInfo.processInfo.environment["TEXTREF_PAPER"], let v = UInt32(hex, radix: 16) { bg = rgb(Int((v >> 16) & 255), Int((v >> 8) & 255), Int(v & 255)) }
    let cellW = W * scale, cellH = H * scale
    let sheetW = cellW, sheetH = cellH * rows.count
    guard let ctx = CGContext(data: nil, width: sheetW, height: sheetH, bitsPerComponent: 8, bytesPerRow: sheetW * 4, space: cs,
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { exit(1) }   // opaque: smoothing is allowed
    ctx.setFillColor(bg); ctx.fill(CGRect(x: 0, y: 0, width: sheetW, height: sheetH))
    let fractional = positioning == "fractional"
    for (i, r) in rows.enumerated() {
        ctx.saveGState()
        // the row's cell, origin at its top-left in a y-down coordinate system like Java's
        let top = CGFloat(sheetH - (i + 1) * cellH)
        ctx.translateBy(x: 0, y: top + CGFloat(cellH)); ctx.scaleBy(x: 1, y: -1)
        ctx.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
        ctx.setShouldAntialias(aa != "aliased")
        ctx.setShouldSmoothFonts(aa != "aliased" && !unsmoothed)
        ctx.setAllowsAntialiasing(true); ctx.setAllowsFontSmoothing(true)
        ctx.setAllowsFontSubpixelPositioning(fractional); ctx.setShouldSubpixelPositionFonts(fractional)
        // AppKit's default quantizes sub-pixel positions (thirds of a pixel at 1x, halves at 2x, measured
        // 2026-10-23 with gridprobe); --continuous switches it off for the fractional sheets (the reference
        // before that measurement)
        ctx.setAllowsFontSubpixelQuantization(!fractional || !continuous); ctx.setShouldSubpixelQuantizeFonts(!fractional || !continuous)
        ctx.setFillColor(ink)
        // no kerning and no default OpenType features: Java's drawString applies none, and the sheets compare
        // glyph rendering, not layout features (Inter's contextual alternates swap the brackets after digits
        // and the arrow and comparison signs for other glyphs; measured 2026-10-24)
        let noFeatures: [[CFString: Any]] = ["calt", "liga", "clig", "kern"].map { [kCTFontOpenTypeFeatureTag: $0, kCTFontOpenTypeFeatureValue: 0 as CFNumber] }
        let plainFont = CTFontCreateCopyWithAttributes(r.font, 0, nil, CTFontDescriptorCreateWithAttributes([kCTFontFeatureSettingsAttribute: noFeatures] as CFDictionary))
        var attrs: [CFString: Any] = [kCTFontAttributeName: plainFont, kCTForegroundColorAttributeName: ink, kCTKernAttributeName: 0 as CFNumber]
        if r.underlined && fractional { attrs[kCTUnderlineStyleAttributeName] = CTUnderlineStyle.single.rawValue; attrs[kCTUnderlineColorAttributeName] = ink }
        let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, TEXT as CFString, attrs as CFDictionary))
        // CTLineDraw and CTFontDrawGlyphs draw y-up: flip around the baseline so glyphs are upright in our y-down cell
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        if fractional {
            ctx.textPosition = CGPoint(x: CGFloat(X), y: CGFloat(BASE))
            CTLineDraw(line, ctx)
        } else {
            var x = CGFloat(X)
            for run in CTLineGetGlyphRuns(line) as! [CTRun] {
                let runFont = (CTRunGetAttributes(run) as NSDictionary)[kCTFontAttributeName] as! CTFont   // fallback runs have their own font
                let n = CTRunGetGlyphCount(run)
                var glyphs = [CGGlyph](repeating: 0, count: n)
                CTRunGetGlyphs(run, CFRangeMake(0, n), &glyphs)
                let (positions, end) = wholePixelPositions(run, scale: CGFloat(scale), roundInDevice: r.fileFont, start: x)
                // CTFontDrawGlyphs takes positions in text space: relative to textPosition and through the
                // (flipped) text matrix, so the baseline goes into textPosition and the points carry y = 0
                ctx.textPosition = CGPoint(x: 0, y: CGFloat(BASE))
                var points = positions.map { CGPoint(x: $0.x, y: 0) }
                CTFontDrawGlyphs(runFont, glyphs, &points, n, ctx)
                x = end
            }
            if r.underlined {   // CoreText's underline metrics as a rectangle, like Java's Decoration does with its own
                let pos = -CTFontGetUnderlinePosition(r.font), thick = CTFontGetUnderlineThickness(r.font)
                ctx.fill(CGRect(x: CGFloat(X), y: CGFloat(BASE) + pos, width: x - CGFloat(X), height: thick))
            }
        }
        ctx.restoreGState()
    }
    let dir = "\(outDir)/\(aa)/\(positioning)/\(scale)x"
    try! FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
    guard let image = ctx.makeImage(), let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: "\(dir)/\(paper).png") as CFURL, UTType.png.identifier as CFString, 1, nil) else { exit(1) }
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
    count += 1
} } } }
try! (rows.map { $0.label }.joined(separator: "\n") + "\n").write(toFile: args[2], atomically: true, encoding: .utf8)
print("wrote \(count) sheets of \(rows.count) rows under \(outDir)")
