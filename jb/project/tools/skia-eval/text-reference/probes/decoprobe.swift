// The platform's text decorations under transforms (2026-11-10): underline and strikethrough as CoreText
// (CTLineDraw, kCTUnderlineStyleAttributeName) and AppKit (NSLayoutManager, underline and strikethrough styles)
// draw them under the cases of decocases.txt — translations with fractional baselines, uniform and uneven
// scales, rotations, a shear — for the fonts of decofonts.txt. One gray PNG per route and decoration, plus the
// plain line, so DecoProbe.java can take the decoration's pixels apart from the glyphs' and measure the band in
// the text's own frame. Black on white, a gray context (no LCD), the line's baseline origin at user (0, 0).
//
//   swift decoprobe.swift <probes dir> <out dir>
import AppKit
import CoreText
import CoreGraphics
import UniformTypeIdentifiers

let args = CommandLine.arguments
let probes = args[1], out = args[2]
let SIZE = 640, TEXT = "x          x"   // spaces between: the middle of the line holds only the decorations
func lines(_ file: String) -> [[String]] {
    try! String(contentsOfFile: "\(probes)/\(file)", encoding: .utf8).split(separator: "\n")
        .filter { !$0.hasPrefix("#") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        .map { $0.split(separator: " ", omittingEmptySubsequences: true).map(String.init) }
}
let cases = lines("decocases.txt"), fonts = lines("decofonts.txt")
try! FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

func render(_ t: CGAffineTransform, _ name: String, _ draw: (CGContext) -> Void) {
    let gray = CGColorSpaceCreateDeviceGray()
    let ctx = CGContext(data: nil, width: SIZE, height: SIZE, bitsPerComponent: 8, bytesPerRow: SIZE, space: gray, bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    ctx.setFillColor(gray: 1, alpha: 1); ctx.fill(CGRect(x: 0, y: 0, width: SIZE, height: SIZE))
    ctx.translateBy(x: 0, y: CGFloat(SIZE)); ctx.scaleBy(x: 1, y: -1)   // y down, as Java's device space
    ctx.concatenate(t)
    ctx.setShouldAntialias(true); ctx.setShouldSmoothFonts(false)
    ctx.setAllowsFontSubpixelPositioning(true); ctx.setShouldSubpixelPositionFonts(true)
    ctx.setFillColor(gray: 0, alpha: 1)
    draw(ctx)
    let image = ctx.makeImage()!
    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: "\(out)/\(name).png") as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil); CGImageDestinationFinalize(dest)
}

func coreText(_ font: CTFont, underline: Bool) -> (CGContext) -> Void {
    return { ctx in
        let black = CGColor(gray: 0, alpha: 1)
        var attrs: [CFString: Any] = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: black]
        if underline { attrs[kCTUnderlineStyleAttributeName] = CTUnderlineStyle.single.rawValue }
        let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, TEXT as CFString, attrs as CFDictionary))
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = .zero
        CTLineDraw(line, ctx)
    }
}

func appKit(_ font: NSFont, _ decoration: String) -> (CGContext) -> Void {
    return { ctx in
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
        if decoration == "underline" { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if decoration == "strike" { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        let storage = NSTextStorage(string: TEXT, attributes: attrs)
        let layout = NSLayoutManager(), container = NSTextContainer(size: CGSize(width: 10000, height: 10000))
        container.lineFragmentPadding = 0
        layout.addTextContainer(container); storage.addLayoutManager(layout)
        let range = layout.glyphRange(for: container)
        let fragment = layout.lineFragmentRect(forGlyphAt: 0, effectiveRange: nil)
        let baseline = fragment.minY + layout.location(forGlyphAt: 0).y
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
        layout.drawGlyphs(forGlyphRange: range, at: CGPoint(x: 0, y: -baseline))
        NSGraphicsContext.restoreGraphicsState()
    }
}

var metrics = ""
for f in fonts {
    let size = CGFloat(Double(f[2])!)
    let ct = CTFontCreateWithName(f[0] as CFString, size, nil)
    let ns = NSFont(name: f[0], size: size)!
    var os2Strike = "none"
    if let os2 = CTFontCopyTable(ct, CTFontTableTag(kCTFontTableOS2), []) as Data?, os2.count >= 30 {
        let units = CGFloat(CTFontGetUnitsPerEm(ct))
        func s16(_ o: Int) -> CGFloat { CGFloat(Int16(bitPattern: UInt16(os2[o]) << 8 | UInt16(os2[o + 1]))) }
        os2Strike = "\(s16(28) * size / units) \(s16(26) * size / units)"
    }
    metrics += "\(f[1]) \(f[2]) xHeight \(CTFontGetXHeight(ct)) os2StrikeoutPositionSize \(os2Strike) underlinePosition \(-CTFontGetUnderlinePosition(ct)) underlineThickness \(CTFontGetUnderlineThickness(ct)) advance \(CTLineGetTypographicBounds(CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, TEXT as CFString, [kCTFontAttributeName: ct] as CFDictionary)), nil, nil, nil))\n"
    for c in cases {
        let v = c[1...6].map { CGFloat(Double($0)!) }
        let t = CGAffineTransform(a: v[0], b: v[1], c: v[2], d: v[3], tx: v[4], ty: v[5])
        let stem = "\(f[1])-\(f[2])-\(c[0])"
        render(t, "\(stem)-ct-plain", coreText(ct, underline: false))
        render(t, "\(stem)-ct-underline", coreText(ct, underline: true))
        render(t, "\(stem)-ak-plain", appKit(ns, "plain"))
        render(t, "\(stem)-ak-underline", appKit(ns, "underline"))
        render(t, "\(stem)-ak-strike", appKit(ns, "strike"))
    }
}
try! metrics.write(toFile: "\(out)/metrics.txt", atomically: true, encoding: .utf8)
print("wrote \(fonts.count * cases.count * 5) images under \(out)")
