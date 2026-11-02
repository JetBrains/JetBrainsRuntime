// Does CoreGraphics' quantization grid follow the CTM or the text matrix? The route's context (y flip in
// the CTM, the whole size in the text matrix) vs AppKit's shape (scale in the CTM, points in the text matrix).
import Foundation
import CoreText
import CoreGraphics
let font = CTFontCreateWithName("HelveticaNeue" as CFString, 1.0, nil)
var u = [UniChar]("l".utf16); var gl = [CGGlyph](repeating: 0, count: 1)
CTFontGetGlyphsForCharacters(font, &u, &gl, 1)
func render(scale: CGFloat, dx: CGFloat, ctmScale: Bool, flip: Bool) -> [UInt8] {
    let w = Int(32 * scale) + 2, h = Int(32 * scale) + 2
    var px = [UInt8](repeating: 0, count: w * h)
    let cg = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    cg.setFillColor(gray: 1, alpha: 1); cg.fill(CGRect(x: 0, y: 0, width: w, height: h))
    cg.setShouldAntialias(true); cg.setAllowsFontSmoothing(true); cg.setShouldSmoothFonts(true)
    cg.setAllowsFontSubpixelPositioning(true); cg.setShouldSubpixelPositionFonts(true)
    cg.setAllowsFontSubpixelQuantization(true); cg.setShouldSubpixelQuantizeFonts(true)
    cg.setFillColor(gray: 0, alpha: 1)
    if flip { cg.translateBy(x: 0, y: CGFloat(h)); cg.scaleBy(x: 1, y: -1) }
    let s: CGFloat = ctmScale ? scale : 1
    if ctmScale { cg.scaleBy(x: scale, y: scale) }
    let tm = (13 * scale) / s
    cg.textMatrix = CGAffineTransform(a: tm, b: 0, c: 0, d: flip ? -tm : tm, tx: 0, ty: 0)
    cg.textPosition = CGPoint(x: (8 * scale + dx) / s, y: (10 * scale) / s)
    var g = gl[0]; var p = CGPoint.zero
    CTFontDrawGlyphs(font, &g, &p, 1, cg)
    return px
}
for scale: CGFloat in [1, 2] {
    for (label, ctmScale, flip) in [("size in text matrix, no flip (probe 1)", false, false), ("size in text matrix, CTM flip (the route)", false, true), ("scale in CTM, points in text matrix (AppKit)", true, false), ("scale in CTM + flip", true, true)] {
        var seen: [[UInt8]] = []; var changes: [Int] = []; var last: [UInt8]? = nil
        for k in 0..<48 { let img = render(scale: scale, dx: CGFloat(k) / 48, ctmScale: ctmScale, flip: flip); if !seen.contains(img) { seen.append(img) }; if let l = last, l != img { changes.append(k) }; last = img }
        print(String(format: "%gx  %-46@  %d distinct (changes /48: %@)", scale, label as NSString, seen.count, changes.map(String.init).joined(separator: ",") as NSString))
    }
}
