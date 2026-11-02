// CoreGraphics' glyph placement grid: one glyph drawn through CTFontDrawGlyphs at 64 offsets across a
// pixel (horizontal, then vertical), smoothing on, sub-pixel positioning on; the number of distinct
// bitmaps is the number of positions per pixel the rasterizer distinguishes. Variants: display scale
// (the glyph's size in device pixels through the text matrix), CoreGraphics' sub-pixel quantization
// switch, smoothing off.   Usage: gridprobe <fonts-dir>
import Foundation
import CoreText
import CoreGraphics

let fontsDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
func fileFont(_ name: String) -> CTFont? {
    let url = URL(fileURLWithPath: fontsDir).appendingPathComponent(name)
    guard let ds = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let d = ds.first else { return nil }
    return CTFontCreateWithFontDescriptor(d, 1.0, nil)
}
var fonts: [(String, CTFont, CGFloat)] = [
    ("Helvetica Neue 13", CTFontCreateWithName("HelveticaNeue" as CFString, 1.0, nil), 13),
    ("Menlo 12", CTFontCreateWithName("Menlo-Regular" as CFString, 1.0, nil), 12),
]
if let f = fileFont("Inter-Regular.ttf") { fonts.append(("Inter 13", f, 13)) }
if let f = fileFont("JetBrainsMono-Regular.ttf") { fonts.append(("JetBrains Mono 13", f, 13)) }

func render(_ font: CTFont, _ glyph: CGGlyph, size: CGFloat, scale: CGFloat, dx: CGFloat, dy: CGFloat,
            quantize: Bool, smooth: Bool) -> [UInt8] {
    let w = Int(32 * scale), h = Int(32 * scale)
    var px = [UInt8](repeating: 0, count: w * h)
    let cs = CGColorSpaceCreateDeviceGray()
    let cg = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w, space: cs, bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    cg.setFillColor(gray: 1, alpha: 1); cg.fill(CGRect(x: 0, y: 0, width: w, height: h))
    cg.setShouldAntialias(true)
    cg.setAllowsFontSmoothing(true); cg.setShouldSmoothFonts(smooth)
    cg.setAllowsFontSubpixelPositioning(true); cg.setShouldSubpixelPositionFonts(true)
    cg.setAllowsFontSubpixelQuantization(quantize); cg.setShouldSubpixelQuantizeFonts(quantize)
    cg.setFillColor(gray: 0, alpha: 1)
    // the glyph's size in device pixels through the text matrix, as the route does; y up in CG
    cg.textMatrix = CGAffineTransform(scaleX: size * scale, y: size * scale)
    cg.textPosition = CGPoint(x: 8 * scale + dx, y: 10 * scale + dy)
    var g = glyph; var p = CGPoint.zero
    CTFontDrawGlyphs(font, &g, &p, 1, cg)
    return px
}

func distinct(_ font: CTFont, _ glyph: CGGlyph, size: CGFloat, scale: CGFloat, vertical: Bool, quantize: Bool, smooth: Bool) -> (Int, [Int]) {
    var seen: [[UInt8]] = []; var changes: [Int] = []
    var last: [UInt8]? = nil
    for k in 0..<64 {
        let f = CGFloat(k) / 64
        let img = render(font, glyph, size: size, scale: scale, dx: vertical ? 0 : f, dy: vertical ? f : 0, quantize: quantize, smooth: smooth)
        if !seen.contains(img) { seen.append(img) }
        if let l = last, l != img { changes.append(k) }
        last = img
    }
    return (seen.count, changes)
}

for (name, font, size) in fonts {
    for ch in ["l", "o"] {
        var u = [UniChar](ch.utf16); var gl = [CGGlyph](repeating: 0, count: 1)
        CTFontGetGlyphsForCharacters(font, &u, &gl, 1)
        for scale: CGFloat in [1, 2] {
            let (nH, cH) = distinct(font, gl[0], size: size, scale: scale, vertical: false, quantize: false, smooth: true)
            let (nV, cV) = distinct(font, gl[0], size: size, scale: scale, vertical: true, quantize: false, smooth: true)
            let (nQ, cQ) = distinct(font, gl[0], size: size, scale: scale, vertical: false, quantize: true, smooth: true)
            let (nU, _) = distinct(font, gl[0], size: size, scale: scale, vertical: false, quantize: false, smooth: false)
            print(String(format: "%-18@ '%@' %gx  horizontal: %2d distinct (changes at /64: %@)  vertical: %2d (%@)  quantized: %2d (%@)  unsmoothed horizontal: %2d",
                         name as NSString, ch as NSString, scale, nH, cH.map(String.init).joined(separator: ",") as NSString,
                         nV, cV.map(String.init).joined(separator: ",") as NSString, nQ, cQ.map(String.init).joined(separator: ",") as NSString, nU))
        }
    }
}
