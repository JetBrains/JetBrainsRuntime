// CoreGraphics' sub-pixel quantization grid vs scale, with the context's defaults, and AppKit's
// own string drawing: distinct renderings of 'l' at 64 horizontal offsets across a device pixel.
import Foundation
import CoreText
import CoreGraphics
import AppKit

let font13 = CTFontCreateWithName("HelveticaNeue" as CFString, 1.0, nil)
var u = [UniChar]("l".utf16); var gl = [CGGlyph](repeating: 0, count: 1)
CTFontGetGlyphsForCharacters(font13, &u, &gl, 1)

enum Quant { case off, on, defaults }
func render(scale: CGFloat, dx: CGFloat, q: Quant, vertical: Bool = false) -> [UInt8] {
    let w = Int(32 * scale) + 1, h = Int(32 * scale) + 1
    var px = [UInt8](repeating: 0, count: w * h)
    let cg = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    cg.setFillColor(gray: 1, alpha: 1); cg.fill(CGRect(x: 0, y: 0, width: w, height: h))
    cg.setShouldAntialias(true); cg.setAllowsFontSmoothing(true); cg.setShouldSmoothFonts(true)
    cg.setAllowsFontSubpixelPositioning(true); cg.setShouldSubpixelPositionFonts(true)
    switch q {
    case .off: cg.setAllowsFontSubpixelQuantization(false); cg.setShouldSubpixelQuantizeFonts(false)
    case .on: cg.setAllowsFontSubpixelQuantization(true); cg.setShouldSubpixelQuantizeFonts(true)
    case .defaults: break
    }
    cg.setFillColor(gray: 0, alpha: 1)
    cg.textMatrix = CGAffineTransform(scaleX: 13 * scale, y: 13 * scale)
    cg.textPosition = CGPoint(x: 8 * scale + (vertical ? 0 : dx), y: 10 * scale + (vertical ? dx : 0))
    var g = gl[0]; var p = CGPoint.zero
    CTFontDrawGlyphs(font13, &g, &p, 1, cg)
    return px
}
func count(_ f: (CGFloat) -> [UInt8]) -> (Int, [Int]) {
    var seen: [[UInt8]] = []; var changes: [Int] = []; var last: [UInt8]? = nil
    for k in 0..<64 { let img = f(CGFloat(k) / 64); if !seen.contains(img) { seen.append(img) }; if let l = last, l != img { changes.append(k) }; last = img }
    return (seen.count, changes)
}
for scale: CGFloat in [1, 1.25, 1.5, 2, 3] {
    let (nOn, cOn) = count { render(scale: scale, dx: $0, q: .on) }
    let (nDef, _) = count { render(scale: scale, dx: $0, q: .defaults) }
    let (nOff, _) = count { render(scale: scale, dx: $0, q: .off) }
    let (nVOn, cVOn) = count { render(scale: scale, dx: $0, q: .on, vertical: true) }
    print(String(format: "CTFontDrawGlyphs, Helvetica Neue 13 at %gx: quantize on %2d (changes /64: %@), context defaults %2d, quantize off %2d; vertical quantize on %2d (%@)",
                 scale, nOn, cOn.map(String.init).joined(separator: ",") as NSString, nDef, nOff, nVOn, cVOn.map(String.init).joined(separator: ",") as NSString))
}
// AppKit: NSAttributedString.draw(at:) into a bitmap rep, at 1x and 2x (rep with pixelsWide = 2 * points)
for scale: CGFloat in [1, 2] {
    let (n, changes) = count { dx in
        let w = 32, h = 32
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(CGFloat(w) * scale), pixelsHigh: Int(CGFloat(h) * scale), bitsPerSample: 8, samplesPerPixel: 1, hasAlpha: false, isPlanar: false, colorSpaceName: .deviceWhite, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: w, height: h)   // points: the rep is scale x
        let ctx = NSGraphicsContext(bitmapImageRep: rep)!
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = ctx
        NSColor.white.setFill(); NSRect(x: 0, y: 0, width: w, height: h).fill()
        let attrs: [NSAttributedString.Key: Any] = [.font: NSFont(name: "HelveticaNeue", size: 13)!, .foregroundColor: NSColor.black]
        NSAttributedString(string: "l", attributes: attrs).draw(at: NSPoint(x: 8 + dx / scale, y: 10))   // dx in device pixels
        ctx.flushGraphics(); NSGraphicsContext.restoreGraphicsState()
        let data = rep.bitmapData!; let bytes = rep.bytesPerRow * rep.pixelsHigh
        return Array(UnsafeBufferPointer(start: data, count: bytes))
    }
    print(String(format: "AppKit NSAttributedString.draw at %gx: %2d distinct across a device pixel (changes /64: %@)", scale, n, changes.map(String.init).joined(separator: ",") as NSString))
}
