import Foundation
import CoreGraphics
import CoreText
// Does CoreGraphics ever emit per-channel (sub-pixel) smoothing here, and does the polarity (black on white vs white on black) matter?
let font = CTFontCreateWithName("HelveticaNeue" as CFString, 13, nil)
let cs = CGColorSpaceCreateDeviceRGB()
let formats: [(String, UInt32, Int)] = [   // name, bitmapInfo, index of the pad/alpha byte
  ("noneSkipLast", CGImageAlphaInfo.noneSkipLast.rawValue, 3),
  ("noneSkipFirst|32Little (Skia)", CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue, 3),
  ("premultipliedFirst|32Little", CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue, 3),
]
for (name, info, pad) in formats { for whiteOnBlack in [false, true] { for smooth in [false, true] {
    let w = 120, h = 24
    var buf = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: cs, bitmapInfo: info) else { continue }
    let bgv: CGFloat = whiteOnBlack ? 0 : 1, fgv: CGFloat = whiteOnBlack ? 1 : 0
    ctx.setFillColor(CGColor(colorSpace: cs, components: [bgv, bgv, bgv, 1])!); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    ctx.setShouldAntialias(true); ctx.setShouldSmoothFonts(smooth); ctx.setAllowsFontSmoothing(true)
    let fg = CGColor(colorSpace: cs, components: [fgv, fgv, fgv, 1])!
    ctx.setFillColor(fg)
    let attrs: [CFString: Any] = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: fg]
    let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, "Hamburgefonts" as CFString, attrs as CFDictionary))
    ctx.textPosition = CGPoint(x: 2, y: 6); CTLineDraw(line, ctx)
    var fringe = 0, ink = 0, maxSpread = 0
    let bgByte = Int(bgv * 255)
    for i in stride(from: 0, to: buf.count, by: 4) {
      var c: [Int] = []
      for k in 0..<4 where k != pad { c.append(Int(buf[i + k])) }
      let s = c.max()! - c.min()!
      maxSpread = max(maxSpread, s)
      if s > 48 { fringe += 1 }
      ink += c.reduce(0) { $0 + abs($1 - bgByte) }
    }
    print(String(format: "%-30@ %@ smooth=%@  ink %7d  fringe px %4d  max spread %d", name as NSString, whiteOnBlack ? "white on black" : "black on white", smooth ? "on " : "off", ink / 3, fringe, maxSpread))
} } }
