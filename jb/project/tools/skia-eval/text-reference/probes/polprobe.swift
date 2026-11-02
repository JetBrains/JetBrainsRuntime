import Foundation
import CoreGraphics
import CoreText
// Does CoreGraphics' smoothing follow the text colour (luminance) or the contrast direction against the paper?
let font = CTFontCreateWithName("HelveticaNeue" as CFString, 13, nil)
let cs = CGColorSpaceCreateDeviceRGB()
func ink(fg: CGFloat, bg: CGFloat, smooth: Bool) -> Int {
    let w = 120, h = 24
    var buf = [UInt8](repeating: 0, count: w * h * 4)
    let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.setFillColor(CGColor(colorSpace: cs, components: [bg, bg, bg, 1])!); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    ctx.setShouldAntialias(true); ctx.setShouldSmoothFonts(smooth); ctx.setAllowsFontSmoothing(true)
    let c = CGColor(colorSpace: cs, components: [fg, fg, fg, 1])!
    ctx.setFillColor(c)
    let attrs: [CFString: Any] = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: c]
    let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, "Hamburgefonts" as CFString, attrs as CFDictionary))
    ctx.textPosition = CGPoint(x: 2, y: 6); CTLineDraw(line, ctx)
    let bgByte = Int((bg * 255).rounded()); var total = 0
    for i in stride(from: 0, to: buf.count, by: 4) { total += abs(Int(buf[i]) - bgByte) }
    return total
}
for (name, fg, bg) in [("black on white", 0.0, 1.0), ("white on black", 1.0, 0.0), ("black on mid-gray", 0.0, 0.5), ("white on mid-gray", 1.0, 0.5), ("dark gray (0.2) on light gray (0.8)", 0.2, 0.8), ("light gray (0.8) on dark gray (0.2)", 0.8, 0.2), ("mid-gray on white", 0.5, 1.0), ("mid-gray on black", 0.5, 0.0)] as [(String, CGFloat, CGFloat)] {
    let off = ink(fg: fg, bg: bg, smooth: false), on = ink(fg: fg, bg: bg, smooth: true)
    print(String(format: "%-38@ unsmoothed %7d  smoothed %7d  (%+.0f %%)", name as NSString, off, on, 100.0 * Double(on - off) / Double(off)))
}
