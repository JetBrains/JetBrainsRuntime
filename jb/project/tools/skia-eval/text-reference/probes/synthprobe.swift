import Foundation
import CoreText
import AppKit
// What does macOS do for a style a font lacks? CoreText symbolic traits, AppKit's font manager, and the
// JDK's own CStrike conventions are three different answers; print the first two.
for name in ["Monaco", "Chalkduster", "Helvetica Neue", "Menlo"] {
    let base = CTFontCreateWithName(name as CFString, 48, nil)
    let italic = CTFontCreateCopyWithSymbolicTraits(base, 48, nil, .italicTrait, .italicTrait)
    let bold = CTFontCreateCopyWithSymbolicTraits(base, 48, nil, .boldTrait, .boldTrait)
    let ps = CTFontCopyPostScriptName(base) as String
    print("\(name): base \(ps); italic via CoreText: \(italic.map { CTFontCopyPostScriptName($0) as String } ?? "nil (no synthesis)"); bold via CoreText: \(bold.map { CTFontCopyPostScriptName($0) as String } ?? "nil (no synthesis)")")
    if let ns = NSFont(name: ps, size: 48) {
        let fm = NSFontManager.shared
        let it = fm.convert(ns, toHaveTrait: .italicFontMask), bd = fm.convert(ns, toHaveTrait: .boldFontMask)
        func m(_ f: NSFont) -> String { (0..<6).map { String(format: "%.4f", f.matrix[$0]) }.joined(separator: " ") }
        print("   AppKit font manager: italic -> \(it.fontName) matrix [\(m(it))], bold -> \(bd.fontName) matrix [\(m(bd))]")
    }
}
