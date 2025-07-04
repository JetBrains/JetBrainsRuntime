/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.font;

import java.awt.*;
import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.plaf.FontUIResource;

import com.jetbrains.exported.JBRApi;
import sun.awt.OSInfo;
import sun.util.logging.PlatformLogger;

/**
 * A collection of utility methods.
 */
public final class FontUtilities {

    public static boolean isLinux;

    public static boolean isMacOSX;
    public static boolean isMacOSX14;

    public static boolean useJDKScaler;

    public static boolean isWindows;

    static Dimension subpixelResolution;

    private static boolean debugFonts = false;
    private static PlatformLogger logger = null;
    private static boolean logging;

    // This static initializer block figures out the OS constants.
    static {
        initStatic();
    }

    @SuppressWarnings("deprecation") // PlatformLogger.setLevel is deprecated.
    private static void initStatic() {

        isLinux = OSInfo.getOSType() == OSInfo.OSType.LINUX;

        isMacOSX = OSInfo.getOSType() == OSInfo.OSType.MACOSX;
        if (isMacOSX) {
            // os.version has values like 10.13.6, 10.14.6
            // If it is not positively recognised as 10.13 or less,
            // assume it means 10.14 or some later version.
            isMacOSX14 = true;
            String version = System.getProperty("os.version", "");
            if (version.startsWith("10.")) {
                version = version.substring(3);
                int periodIndex = version.indexOf('.');
                if (periodIndex != -1) {
                    version = version.substring(0, periodIndex);
                }
                try {
                    int v = Integer.parseInt(version);
                    isMacOSX14 = (v >= 14);
                } catch (NumberFormatException e) {
                }
             }
         }
        /* If set to "jdk", use the JDK's scaler rather than
         * the platform one. This may be a no-op on platforms where
         * JDK has been configured so that it always relies on the
         * platform scaler. The principal case where it has an
         * effect is that on Windows, 2D will never use GDI.
         */
        String scalerStr = System.getProperty("sun.java2d.font.scaler");
        if (scalerStr != null) {
            useJDKScaler = "jdk".equals(scalerStr);
        } else {
            useJDKScaler = false;
        }
        isWindows = OSInfo.getOSType() == OSInfo.OSType.WINDOWS;
        String debugLevel =
            System.getProperty("sun.java2d.debugfonts");

        if (debugLevel != null && !debugLevel.equals("false")) {
            debugFonts = true;
            logger = PlatformLogger.getLogger("sun.java2d");
            if (debugLevel.equals("warning")) {
                logger.setLevel(PlatformLogger.Level.WARNING);
            } else if (debugLevel.equals("severe")) {
                logger.setLevel(PlatformLogger.Level.SEVERE);
            }
            logging = logger.isEnabled();
        }

        try {
            String property = System.getProperty("java2d.font.subpixelResolution", "");
            int separatorIndex = property.indexOf('x');
            final int MAX_RESOLUTION = 16;
            subpixelResolution = new Dimension(
                    Math.max(Math.min(Integer.parseUnsignedInt(
                            property.substring(0, separatorIndex)), MAX_RESOLUTION), 1),
                    Math.max(Math.min(Integer.parseUnsignedInt(
                            property.substring(separatorIndex + 1)), MAX_RESOLUTION), 1)
            );
        } catch (Exception ignore) {
            subpixelResolution = new Dimension(4, 1);
        }
    }

    @JBRApi.Provides("FontExtensions")
    private static Dimension getSubpixelResolution() {
        return subpixelResolution;
    }

    /**
     * Referenced by code in the JDK which wants to test for the
     * minimum char code for which layout may be required.
     * Note that even basic latin text can benefit from ligatures,
     * eg "ffi" but we presently apply those only if explicitly
     * requested with TextAttribute.LIGATURES_ON.
     * The value here indicates the lowest char code for which failing
     * to invoke layout would prevent acceptable rendering.
     */
    public static final int MIN_LAYOUT_CHARCODE = 0x0300;

    /**
     * Referenced by code in the JDK which wants to test for the
     * maximum char code for which layout may be required.
     * Note this does not account for supplementary characters
     * where the caller interprets 'layout' to mean any case where
     * one 'char' (ie the java type char) does not map to one glyph
     */
    public static final int MAX_LAYOUT_CHARCODE = CharToGlyphMapper.VSS_END;

    /**
     * Calls the private getFont2D() method in java.awt.Font objects.
     *
     * @param font the font object to call
     *
     * @return the Font2D object returned by Font.getFont2D()
     */
    public static Font2D getFont2D(Font font) {
        return FontAccess.getFontAccess().getFont2D(font);
    }

    /**
     * Return true if there any characters which would trigger layout.
     * This method considers supplementary characters to be simple,
     * since we do not presently invoke layout on any code points in
     * outside the BMP.
     */
    public static boolean isComplexScript(char [] chs, int start, int limit) {

        for (int i = start; i < limit; i++) {
            if (chs[i] < MIN_LAYOUT_CHARCODE) {
                continue;
            }
            else if (isComplexCharCode(chs[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * If there is anything in the text which triggers a case
     * where char->glyph does not map 1:1 in straightforward
     * left->right ordering, then this method returns true.
     * Scripts which might require it but are not treated as such
     * due to JDK implementations will not return true.
     * ie a 'true' return is an indication of the treatment by
     * the implementation.
     * Whether supplementary characters should be considered is dependent
     * on the needs of the caller. Since this method accepts the 'char' type
     * then such chars are always represented by a pair. From a rendering
     * perspective these will all (in the cases I know of) still be one
     * unicode character -> one glyph. But if a caller is using this to
     * discover any case where it cannot make naive assumptions about
     * the number of chars, and how to index through them, then it may
     * need the option to have a 'true' return in such a case.
     */
    public static boolean isComplexText(char [] chs, int start, int limit) {

        for (int i = start; i < limit; i++) {
            if (chs[i] < MIN_LAYOUT_CHARCODE) {
                continue;
            }
            else if (isNonSimpleChar(chs[i])) {
                return true;
            }
        }
        return false;
    }

    /* This is almost the same as the method above, except it takes a
     * char which means it may include undecoded surrogate pairs.
     * The distinction is made so that code which needs to identify all
     * cases in which we do not have a simple mapping from
     * char->unicode character->glyph can be identified.
     * For example measurement cannot simply sum advances of 'chars',
     * the caret in editable text cannot advance one 'char' at a time, etc.
     * These callers really are asking for more than whether 'layout'
     * needs to be run, they need to know if they can assume 1->1
     * char->glyph mapping.
     */
    public static boolean isNonSimpleChar(char ch) {
        return
            isComplexCharCode(ch) ||
            (ch >= CharToGlyphMapper.HI_SURROGATE_START &&
             ch <= CharToGlyphMapper.LO_SURROGATE_END);
    }

    /* If the character code falls into any of a number of unicode ranges
     * where we know that simple left->right layout mapping chars to glyphs
     * 1:1 and accumulating advances is going to produce incorrect results,
     * we want to know this so the caller can use a more intelligent layout
     * approach. A caller who cares about optimum performance may want to
     * check the first case and skip the method call if its in that range.
     * Although there's a lot of tests in here, knowing you can skip
     * CTL saves a great deal more. The rest of the checks are ordered
     * so that rather than checking explicitly if (>= start & <= end)
     * which would mean all ranges would need to be checked so be sure
     * CTL is not needed, the method returns as soon as it recognises
     * the code point is outside of a CTL ranges.
     * NOTE: Since this method accepts an 'int' it is assumed to properly
     * represent a CHARACTER. ie it assumes the caller has already
     * converted surrogate pairs into supplementary characters, and so
     * can handle this case and doesn't need to be told such a case is
     * 'complex'.
     */
    public static boolean isComplexCharCode(int code) {

        if (code < MIN_LAYOUT_CHARCODE || code > MAX_LAYOUT_CHARCODE) {
            return false;
        }
        else if (code <= 0x036f) {
            // Trigger layout for combining diacriticals 0x0300->0x036f
            return true;
        }
        else if (code < 0x0590) {
            // No automatic layout for Greek, Cyrillic, Armenian.
             return false;
        }
        else if (code <= 0x06ff) {
            // Hebrew 0590 - 05ff
            // Arabic 0600 - 06ff
            return true;
        }
        else if (code < 0x0900) {
            return false; // Syriac and Thaana
        }
        else if (code <= 0x0e7f) {
            // if Indic, assume shaping for conjuncts, reordering:
            // 0900 - 097F Devanagari
            // 0980 - 09FF Bengali
            // 0A00 - 0A7F Gurmukhi
            // 0A80 - 0AFF Gujarati
            // 0B00 - 0B7F Oriya
            // 0B80 - 0BFF Tamil
            // 0C00 - 0C7F Telugu
            // 0C80 - 0CFF Kannada
            // 0D00 - 0D7F Malayalam
            // 0D80 - 0DFF Sinhala
            // 0E00 - 0E7F if Thai, assume shaping for vowel, tone marks
            return true;
        }
        else if (code <  0x0f00) {
            return false;
        }
        else if (code <= 0x0fff) { // U+0F00 - U+0FFF Tibetan
            return true;
        }
        else if (code < 0x10A0) {  // U+1000 - U+109F Myanmar
            return true;
        }
        else if (code < 0x1100) {
            return false;
        }
        else if (code < 0x11ff) { // U+1100 - U+11FF Old Hangul
            return true;
        }
        else if (code < 0x1780) {
            return false;
        }
        else if (code <= 0x17ff) { // 1780 - 17FF Khmer
            return true;
        }
        else if (code < 0x200c) {
            return false;
        }
        else if (code <= 0x200d) { //  zwj or zwnj
            return true;
        }
        else if (code >= 0x202a && code <= 0x202e) { // directional control
            return true;
        }
        else if (code >= 0x206a && code <= 0x206f) { // directional control
            return true;
        }
        else if (code >= 0x20d0 && code <= 0x20f0) { // U+20D0 - U+20F0 combining diacritical marks for symbols
            return true;
        }
        else if (code >= 0x1f1e6 && code <= 0x1f1ff) { // U+1F1E6 - U+1F1FF flag letters https://emojipedia.org/emoji-flag-sequence/
            return true;
        }
        else if (code == 0x1f3f4) { // black flag https://emojipedia.org/emoji-tag-sequence/
            return true;
        }
        else if (code >= 0x1f3fb && code <= 0x1f3ff) { // U+1F3FB - U+1F3FF emoji modifiers
            return true;
        }
        else if (CharToGlyphMapper.isVariationSelector(code)) {
            return true;
        }
        return false;
    }

    /**
     * <p>Checks whether or not the specified codepoint is ignorable by default, per the
     * Unicode standard (with exceptions for HarfBuzz and Uniscribe compatibility).
     * The codepoints categorized as ignorable should remain in sync with HarfBuzz's
     * is_default_ignorable() in hb-unicode.hh.
     *
     * <p>See the original Unicode list of default-ignorable codepoints
     * <a href="https://www.unicode.org/Public/16.0.0/ucd/DerivedCoreProperties.txt">here</a>:
     *
     * <pre>
     * 00AD         # Cf       SOFT HYPHEN
     * 034F         # Mn       COMBINING GRAPHEME JOINER
     * 061C         # Cf       ARABIC LETTER MARK
     * 115F..1160   # Lo   [2] HANGUL CHOSEONG FILLER..HANGUL JUNGSEONG FILLER
     * 17B4..17B5   # Mn   [2] KHMER VOWEL INHERENT AQ..KHMER VOWEL INHERENT AA
     * 180B..180D   # Mn   [3] MONGOLIAN FREE VARIATION SELECTOR ONE..MONGOLIAN FREE VARIATION SELECTOR THREE
     * 180E         # Cf       MONGOLIAN VOWEL SEPARATOR
     * 180F         # Mn       MONGOLIAN FREE VARIATION SELECTOR FOUR
     * 200B..200F   # Cf   [5] ZERO WIDTH SPACE..RIGHT-TO-LEFT MARK
     * 202A..202E   # Cf   [5] LEFT-TO-RIGHT EMBEDDING..RIGHT-TO-LEFT OVERRIDE
     * 2060..2064   # Cf   [5] WORD JOINER..INVISIBLE PLUS
     * 2065         # Cn       <reserved-2065>
     * 2066..206F   # Cf  [10] LEFT-TO-RIGHT ISOLATE..NOMINAL DIGIT SHAPES
     * 3164         # Lo       HANGUL FILLER
     * FE00..FE0F   # Mn  [16] VARIATION SELECTOR-1..VARIATION SELECTOR-16
     * FEFF         # Cf       ZERO WIDTH NO-BREAK SPACE
     * FFA0         # Lo       HALFWIDTH HANGUL FILLER
     * FFF0..FFF8   # Cn   [9] <reserved-FFF0>..<reserved-FFF8>
     * 1BCA0..1BCA3 # Cf   [4] SHORTHAND FORMAT LETTER OVERLAP..SHORTHAND FORMAT UP STEP
     * 1D173..1D17A # Cf   [8] MUSICAL SYMBOL BEGIN BEAM..MUSICAL SYMBOL END PHRASE
     * E0000        # Cn       <reserved-E0000>
     * E0001        # Cf       LANGUAGE TAG
     * E0002..E001F # Cn  [30] <reserved-E0002>..<reserved-E001F>
     * E0020..E007F # Cf  [96] TAG SPACE..CANCEL TAG
     * E0080..E00FF # Cn [128] <reserved-E0080>..<reserved-E00FF>
     * E0100..E01EF # Mn [240] VARIATION SELECTOR-17..VARIATION SELECTOR-256
     * E01F0..E0FFF # Cn [3600] <reserved-E01F0>..<reserved-E0FFF>
     * </pre>
     */
    public static boolean isDefaultIgnorable(int charCode) {
        if (charCode < 0x00AD) {
            return false;
        }
        int plane = charCode >> 16;
        if (plane == 0) {
            // basic multilingual plane (BMP)
            int page = charCode >> 8;
            switch (page) {
                case 0x00: return (charCode == 0x00AD);
                case 0x03: return (charCode == 0x034F);
                case 0x06: return (charCode == 0x061C);
                case 0x17: return (charCode >= 0x17B4 && charCode <= 0x17B5);
                case 0x18: return (charCode >= 0x180B && charCode <= 0x180E);
                case 0x20: return (charCode >= 0x200B && charCode <= 0x200F) ||
                                  (charCode >= 0x202A && charCode <= 0x202E) ||
                                  (charCode >= 0x2060 && charCode <= 0x206F);
                case 0xFE: return (charCode >= 0xFE00 && charCode <= 0xFE0F) ||
                                  (charCode == 0xFEFF);
                case 0xFF: return (charCode >= 0xFFF0 && charCode <= 0xFFF8);
                default: return false;
            }
        } else {
            // other planes
            switch (plane) {
                case 0x01: return (charCode >= 0x1D173 && charCode <= 0x1D17A);
                case 0x0E: return (charCode >= 0xE0000 && charCode <= 0xE0FFF);
                default: return false;
            }
        }
    }

    /**
     * <p>Checks whether or not the specified codepoint is whitespace which is
     * ignorable at the shaping stage of text rendering. These ignorable
     * whitespace characters should be used prior to text shaping and
     * rendering to determine the position of the text, but are not themselves
     * rendered.
     *
     * <p>Includes 0x0009 (horizontal tab / TAB), 0x000A (line feed / LF),
     * 0x000B (vertical tab / VT), 0x000C (form feed / FF),
     * 0x000D (carriage return / CR), 0x0085 (next line / NEL),
     * 0x2028 (line separator / LS), 0x2029 (paragraph separator / PS).
     *
     * @param ch the codepoint to check
     * @return whether the specified codepoint is ignorable whitespace
     */
    public static boolean isIgnorableWhitespace(int ch) {
        return (ch >= 0x0009 && ch <= 0x000d)
            || ch == 0x0085
            || ch == 0x2028
            || ch == 0x2029;
    }

    public static PlatformLogger getLogger() {
        return logger;
    }

    public static boolean isLogging() {
        return logging;
    }

    public static boolean debugFonts() {
        return debugFonts;
    }

    public static void logWarning(String s) {
        getLogger().warning(s);
    }

    public static void logInfo(String s) {
        getLogger().info(s);
    }

    public static void logSevere(String s) {
        getLogger().severe(s);
    }

    // The following methods are used by Swing.

    /* Revise the implementation to in fact mean "font is a composite font.
     * This ensures that Swing components will always benefit from the
     * fall back fonts
     */
    public static boolean fontSupportsDefaultEncoding(Font font) {
        return getFont2D(font) instanceof CompositeFont;
    }

    /**
     * This method is provided for internal and exclusive use by Swing.
     *
     * It may be used in conjunction with fontSupportsDefaultEncoding(Font)
     * In the event that a desktop properties font doesn't directly
     * support the default encoding, (ie because the host OS supports
     * adding support for the current locale automatically for native apps),
     * then Swing calls this method to get a font which  uses the specified
     * font for the code points it covers, but also supports this locale
     * just as the standard composite fonts do.
     * Note: this will over-ride any setting where an application
     * specifies it prefers locale specific composite fonts.
     * The logic for this, is that this method is used only where the user or
     * application has specified that the native L&F be used, and that
     * we should honour that request to use the same font as native apps use.
     *
     * The behaviour of this method is to construct a new composite
     * Font object that uses the specified physical font as its first
     * component, and adds all the components of "dialog" as fall back
     * components.
     * The method currently assumes that only the size and style attributes
     * are set on the specified font. It doesn't copy the font transform or
     * other attributes because they aren't set on a font created from
     * the desktop. This will need to be fixed if use is broadened.
     *
     * Operations such as Font.deriveFont will work properly on the
     * font returned by this method for deriving a different point size.
     * Additionally it tries to support a different style by calling
     * getNewComposite() below. That also supports replacing slot zero
     * with a different physical font but that is expected to be "rare".
     * Deriving with a different style is needed because its been shown
     * that some applications try to do this for Swing FontUIResources.
     * Also operations such as new Font(font.getFontName(..), Font.PLAIN, 14);
     * will NOT yield the same result, as the new underlying CompositeFont
     * cannot be "looked up" in the font registry.
     * This returns a FontUIResource as that is the Font sub-class needed
     * by Swing.
     * Suggested usage is something like :
     * FontUIResource fuir;
     * Font desktopFont = getDesktopFont(..);
     * if (FontManager.fontSupportsDefaultEncoding(desktopFont)) {
     *   fuir = new FontUIResource(desktopFont);
     * } else {
     *   fuir = FontManager.getCompositeFontUIResource(desktopFont);
     * }
     * return fuir;
     */
    private static volatile
        SoftReference<ConcurrentHashMap<PhysicalFont, CompositeFont>>
        compMapRef = new SoftReference<>(null);

    public static FontUIResource getCompositeFontUIResource(Font font) {

        FontUIResource fuir = new FontUIResource(font);
        Font2D font2D = FontUtilities.getFont2D(font);

        if (!(font2D instanceof PhysicalFont)) {
            /* Swing should only be calling this when a font is obtained
             * from desktop properties, so should generally be a physical font,
             * an exception might be for names like "MS Serif" which are
             * automatically mapped to "Serif", so there's no need to do
             * anything special in that case. But note that suggested usage
             * is first to call fontSupportsDefaultEncoding(Font) and this
             * method should not be called if that were to return true.
             */
             return fuir;
        }

        FontManager fm = FontManagerFactory.getInstance();
        Font2D dialog = fm.findFont2D("dialog", font.getStyle(), FontManager.NO_FALLBACK);
        // Should never be null, but MACOSX fonts are not CompositeFonts
        if (!(dialog instanceof CompositeFont dialog2D)) {
            return fuir;
        }
        PhysicalFont physicalFont = (PhysicalFont)font2D;
        ConcurrentHashMap<PhysicalFont, CompositeFont> compMap = compMapRef.get();
        if (compMap == null) { // Its been collected.
            compMap = new ConcurrentHashMap<PhysicalFont, CompositeFont>();
            compMapRef = new SoftReference<>(compMap);
        }
        CompositeFont compFont = compMap.get(physicalFont);
        if (compFont == null) {
            compFont = new CompositeFont(physicalFont, dialog2D);
            compMap.put(physicalFont, compFont);
        }
        FontAccess.getFontAccess().setFont2D(fuir, compFont.handle);
        /* marking this as a font with fallback to make sure its
         * handle is copied to derived fonts.
         */
        FontAccess.getFontAccess().setWithFallback(fuir);
        return fuir;
    }

   /* A small "map" from GTK/fontconfig names to the equivalent JDK
    * logical font name.
    */
    private static final String[][] nameMap = {
        {"sans",       "sansserif"},
        {"sans-serif", "sansserif"},
        {"serif",      "serif"},
        {"monospace",  "monospaced"}
    };

    public static String mapFcName(String name) {
        for (int i = 0; i < nameMap.length; i++) {
            if (name.equals(nameMap[i][0])) {
                return nameMap[i][1];
            }
        }
        return null;
    }


    /* This is called by Swing passing in a fontconfig family name
     * such as "sans". In return Swing gets a FontUIResource instance
     * that has queried fontconfig to resolve the font(s) used for this.
     * Fontconfig will if asked return a list of fonts to give the largest
     * possible code point coverage.
     * For now we use only the first font returned by fontconfig, and
     * back it up with the most closely matching JDK logical font.
     * Essentially this means pre-pending what we return now with fontconfig's
     * preferred physical font. This could lead to some duplication in cases,
     * if we already included that font later. We probably should remove such
     * duplicates, but it is not a significant problem. It can be addressed
     * later as part of creating a Composite which uses more of the
     * same fonts as fontconfig. At that time we also should pay more
     * attention to the special rendering instructions fontconfig returns,
     * such as whether we should prefer embedded bitmaps over antialiasing.
     * There's no way to express that via a Font at present.
     */
    public static FontUIResource getFontConfigFUIR(String fcFamily,
                                                   int style, int size) {

        String mapped = mapFcName(fcFamily);
        if (mapped == null) {
            mapped = "sansserif";
        }

        FontUIResource fuir;
        FontManager fm = FontManagerFactory.getInstance();
        if (fm instanceof SunFontManager) {
            SunFontManager sfm = (SunFontManager) fm;
            fuir = sfm.getFontConfigFUIR(mapped, style, size);
        } else {
            fuir = new FontUIResource(mapped, style, size);
        }
        return fuir;
    }


    /**
     * Used by windows printing to assess if a font is likely to
     * be layout compatible with JDK
     * TrueType fonts should be, but if they have no GPOS table,
     * but do have a GSUB table, then they are probably older
     * fonts GDI handles differently.
     */
    public static boolean textLayoutIsCompatible(Font font) {

        Font2D font2D = getFont2D(font);
        if (font2D instanceof TrueTypeFont) {
            TrueTypeFont ttf = (TrueTypeFont) font2D;
            return
                ttf.getDirectoryEntry(TrueTypeFont.GSUBTag) == null ||
                ttf.getDirectoryEntry(TrueTypeFont.GPOSTag) != null;
        } else {
            return false;
        }
    }

}
