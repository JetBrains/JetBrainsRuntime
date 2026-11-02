import Foundation
import CoreText
import AppKit
// CoreText's derived italic (what the JDK's CFont gets for a face the font lacks): its matrix and the measured shear.
for name in ["Monaco-Italic-Derived", "Chalkduster-Italic-Derived", "Monaco"] {
    let f = CTFontCreateWithName(name as CFString, 100, nil)
    let m = CTFontGetMatrix(f)
    print("\(name): PostScript \(CTFontCopyPostScriptName(f) as String), traits 0x\(String(CTFontGetSymbolicTraits(f).rawValue, radix: 16)), matrix a \(m.a) b \(m.b) c \(m.c) d \(m.d)")
    // measure: render 'l' and compare the ink's horizontal centre at two heights
    let W = 300, H = 300
    let cs = CGColorSpaceCreateDeviceGray()
    let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: W, space: cs, bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    ctx.setFillColor(gray: 1, alpha: 1); ctx.fill(CGRect(x: 0, y: 0, width: W, height: H)); ctx.setFillColor(gray: 0, alpha: 1)
    var glyph = CGGlyph(0); var ch = [UniChar]("l".utf16); CTFontGetGlyphsForCharacters(f, &ch, &glyph, 1)
    var pos = CGPoint(x: 100, y: 60); CTFontDrawGlyphs(f, &glyph, &pos, 1, ctx)
    let data = ctx.data!.assumingMemoryBound(to: UInt8.self)
    func centre(_ row: Int) -> Double { var s = 0.0, w = 0.0; for x in 0..<W { let v = 255.0 - Double(data[row * W + x]); s += v * Double(x); w += v }; return w > 0 ? s / w : .nan }
    let lo = 70, hi = 120   // rows (y up in CG: higher index = higher on the glyph)
    let shear = (centre(hi) - centre(lo)) / Double(hi - lo)
    print(String(format: "   measured shear from 'l' stem: %.4f x per y", shear))
}
