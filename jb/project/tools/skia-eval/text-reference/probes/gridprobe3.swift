// Which exact positions CoreGraphics' quantization lands on (floor or nearest), and the scale
// thresholds of its grid: Helvetica Neue 13, 'l', horizontal.
import Foundation
import CoreText
import CoreGraphics
let font13 = CTFontCreateWithName("HelveticaNeue" as CFString, 1.0, nil)
var u = [UniChar]("l".utf16); var gl = [CGGlyph](repeating: 0, count: 1)
CTFontGetGlyphsForCharacters(font13, &u, &gl, 1)
func render(scale: CGFloat, dx: CGFloat, dy: CGFloat, quantize: Bool) -> [UInt8] {
    let w = Int(32 * scale) + 2, h = Int(32 * scale) + 2
    var px = [UInt8](repeating: 0, count: w * h)
    let cg = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    cg.setFillColor(gray: 1, alpha: 1); cg.fill(CGRect(x: 0, y: 0, width: w, height: h))
    cg.setShouldAntialias(true); cg.setAllowsFontSmoothing(true); cg.setShouldSmoothFonts(true)
    cg.setAllowsFontSubpixelPositioning(true); cg.setShouldSubpixelPositionFonts(true)
    cg.setAllowsFontSubpixelQuantization(quantize); cg.setShouldSubpixelQuantizeFonts(quantize)
    cg.setFillColor(gray: 0, alpha: 1)
    cg.textMatrix = CGAffineTransform(scaleX: 13 * scale, y: 13 * scale)
    cg.textPosition = CGPoint(x: 8 * scale + dx, y: 10 * scale + dy)
    var g = gl[0]; var p = CGPoint.zero
    CTFontDrawGlyphs(font13, &g, &p, 1, cg)
    return px
}
for scale: CGFloat in [1, 2] {
    // unquantized renders at 1/96 px steps as the reference set
    var ref: [(Int, [UInt8])] = []
    for j in 0..<96 { ref.append((j, render(scale: scale, dx: CGFloat(j) / 96, dy: 0, quantize: false))) }
    var line = "scale \(scale): quantized offset -> equal unquantized offset(s) (/96): "
    var lastHit = ""
    for k in 0..<64 {
        let q = render(scale: scale, dx: CGFloat(k) / 64, dy: 0, quantize: true)
        let hits = ref.filter { $0.1 == q }.map { String($0.0) }.joined(separator: "|")
        if hits != lastHit { line += "\(k)/64->[\(hits)] "; lastHit = hits }
    }
    print(line)
    // vertical: which unquantized vertical offsets equal the quantized render at dy = k/64
    var vref: [(Int, [UInt8])] = []
    for j in 0..<96 { vref.append((j, render(scale: scale, dx: 0, dy: CGFloat(j) / 96, quantize: false))) }
    var vline = "scale \(scale) vertical: "
    lastHit = ""
    for k in 0..<64 {
        let q = render(scale: scale, dx: 0, dy: CGFloat(k) / 64, quantize: true)
        let hits = vref.filter { $0.1 == q }.map { String($0.0) }.joined(separator: "|")
        if hits != lastHit { vline += "\(k)/64->[\(hits)] "; lastHit = hits }
    }
    print(vline)
}
var thresholds = "steps per device pixel (horizontal/vertical) by scale: "
for i in 10...30 {
    let scale = CGFloat(i) / 10
    var seenH: [[UInt8]] = [], seenV: [[UInt8]] = []
    for k in 0..<48 {
        let h = render(scale: scale, dx: CGFloat(k) / 48, dy: 0, quantize: true); if !seenH.contains(h) { seenH.append(h) }
        let v = render(scale: scale, dx: 0, dy: CGFloat(k) / 48, quantize: true); if !seenV.contains(v) { seenV.append(v) }
    }
    thresholds += String(format: "%.1fx:%d/%d ", scale, seenH.count, seenV.count)
}
print(thresholds)
