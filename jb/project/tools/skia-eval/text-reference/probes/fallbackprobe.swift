// The platform's fallback font per (base font, symbol), 2026-11-10: CTLine's run font (CoreText's cascade, which
// TextKit 2 follows), TextKit 2's line fragment font, CTFontCreateForString (the Skia scaler's glyph-list fallback),
// JRSFontCreateFallbackFontForCharacters (the JDK's). TSV on stdout; FallbackRoutes.java checks the routes against it.
//   swift fallbackprobe.swift > fallback.tsv
import AppKit
typealias JRSFallback = @convention(c) (CTFont, UnsafePointer<UniChar>, CFIndex) -> Unmanaged<CTFont>?
let jrsHandle = dlopen("/System/Library/Frameworks/JavaRuntimeSupport.framework/JavaRuntimeSupport", RTLD_NOW)
let jrs = jrsHandle.flatMap { dlsym($0, "JRSFontCreateFallbackFontForCharacters") }.map { unsafeBitCast($0, to: JRSFallback.self) }
print("JRS available: \(jrs != nil)")
func name(_ f: CTFont?) -> String { f.map { CTFontCopyPostScriptName($0) as String } ?? "-" }
let symbols = ["∑", "∂", "Δ", "→", "⇒", "≠", "≤", "≥", "⌘", "✓", "★", "∞", "λ", "中", "😀", "←", "↑", "⟶", "∀", "∈"]
print("base\tchar\tCTLine (platform)\tTextKit 2 fragment\tCTFontCreateForString\tJRS fallback")
for baseName in ["Menlo-Regular", "HelveticaNeue", "SFMono-Regular", "JetBrainsMono-Regular", "LucidaGrande", "Monaco"] {
    guard let base = NSFont(name: baseName, size: 13) else { print("\(baseName)\tnot installed"); continue }
    for ch in symbols {
        let s = NSAttributedString(string: ch, attributes: [.font: base])
        // CoreText's own cascade: the run fonts of a CTLine
        let line = CTLineCreateWithAttributedString(s)
        let runs = CTLineGetGlyphRuns(line) as! [CTRun]
        let runFont = runs.first.flatMap { (CTRunGetAttributes($0) as NSDictionary)[kCTFontAttributeName] as! CTFont? }
        // TextKit 2: the line fragment's attributed string after layout
        let storage = NSTextContentStorage(); let lm = NSTextLayoutManager(); storage.addTextLayoutManager(lm)
        lm.textContainer = NSTextContainer(size: CGSize(width: 1000, height: 1000)); storage.attributedString = s
        lm.ensureLayout(for: lm.documentRange)
        var tk2 = "-"
        lm.enumerateTextLayoutFragments(from: lm.documentRange.location, options: [.ensuresLayout]) { f in
            if let l = f.textLineFragments.first, let fnt = l.attributedString.attribute(.font, at: 0, effectiveRange: nil) as? NSFont { tk2 = fnt.fontName }
            return false
        }
        let chars = Array(ch.utf16)
        let cf = CTFontCreateForString(base as CTFont, ch as CFString, CFRange(location: 0, length: chars.count))
        let j = jrs.flatMap { fn in chars.withUnsafeBufferPointer { fn(base as CTFont, $0.baseAddress!, chars.count)?.takeRetainedValue() } }
        print("\(baseName)\t\(ch)\t\(name(runFont))\t\(tk2)\t\(name(cf))\t\(name(j))")
    }
}
