/*
 * Copyright 2000-2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/*
 * @test
 * @summary JBR-10245 CJK font fallback regression on macOS — after registerFont
 *          of a Latin-only font (JetBrains Mono), resolving the same font by
 *          name creates a composite font with CJK cascade. When
 *          createGlyphVector is called for a CJK character, the returned glyph
 *          code must encode the composite slot in the high byte (SLOTMASK
 *          0xff000000) so the IDE can detect that the glyph comes from a
 *          cascaded font, not the base font. If the slot is zero, the IDE
 *          incorrectly thinks the base font can display CJK and skips fallback.
 * @requires os.family == "mac"
 * @run main/othervm CJKFallbackTest
 */
public class CJKFallbackTest {

    // Masks used by the IDE (FontInfo.canDisplay) to distinguish base-font
    // glyphs (slot 0) from composite/cascaded glyphs (slot > 0).
    private static final int SLOTMASK  = 0xff000000;
    private static final int GLYPHMASK = 0x00ffffff;

    private static final int CJK_CP = 0x4F60; // 你

    public static void main(String[] args) throws Exception {
        FontRenderContext frc = new FontRenderContext(null, true, true);

        // Load JetBrains Mono via createFont (physical font, no composite).
        Font physical = loadJetBrainsMono();

        // Verify the physical font itself cannot display CJK.
        if (physical.canDisplay(CJK_CP)) {
            throw new RuntimeException(
                "Precondition failed: physical JetBrains Mono reports " +
                "canDisplay=true for U+4F60");
        }

        // Register the font — this makes it available by name.
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(physical);

        // Resolve by name. On macOS, this typically creates a CCompositeFont
        // with CJK fonts in the cascade list.
        Font resolved = new Font("JetBrains Mono", Font.PLAIN, 48);
        System.out.println("Physical fontName: " + physical.getFontName());
        System.out.println("Resolved fontName: " + resolved.getFontName());

        // createGlyphVector for CJK on the resolved (composite) font.
        String s = new String(new int[]{CJK_CP}, 0, 1);
        GlyphVector gv = resolved.createGlyphVector(frc, s);
        int glyphCode = gv.getGlyphCode(0);
        int slot  = (glyphCode & SLOTMASK) >>> 24;
        int glyph = glyphCode & GLYPHMASK;

        System.out.printf("U+%04X glyphCode=0x%08x slot=0x%02x glyph=0x%06x%n",
                          CJK_CP, glyphCode, slot, glyph);

        // Render CJK text to an image for visual verification (always, even on failure).
        saveGlyphImage(resolved, frc, s, glyphCode, slot);

        // The glyph for CJK must come from a non-zero slot (cascaded font).
        // If slot=0 and glyph!=0, the IDE's disableFontFallback check
        // (FontInfo.canDisplay) will incorrectly conclude the base font can
        // display CJK, preventing font fallback.
        if (glyph != 0 && slot == 0) {
            throw new RuntimeException(
                "FAILED: createGlyphVector for U+4F60 on composite JetBrains " +
                "Mono returned glyphCode=0x" + Integer.toHexString(glyphCode) +
                " with slot=0. The CJK glyph comes from a cascaded font but " +
                "is not marked with a non-zero slot. This breaks the IDE's " +
                "font fallback detection (SLOTMASK=0xff000000 check).");
        }

        if (glyph == 0) {
            System.out.println("Note: composite font returned .notdef for CJK");
        }

        System.out.println("PASSED: CJK glyph correctly reports slot=" + slot +
                           " (cascaded font), IDE fallback detection works");
    }

    private static void saveGlyphImage(Font font, FontRenderContext frc,
                                        String cjkStr, int glyphCode, int slot)
            throws Exception {
        int width = 300;
        int height = 120;
        BufferedImage img = new BufferedImage(width, height,
                                             BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        // Draw CJK character with the composite font.
        g.setFont(font);
        g.setColor(Color.BLACK);
        g.drawString(cjkStr, 20, 60);

        // Draw diagnostic label.
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(Color.DARK_GRAY);
        g.drawString(String.format("glyphCode=0x%08x  slot=%d", glyphCode, slot),
                     20, 85);
        g.drawString("font: " + font.getFontName(), 20, 100);
        g.dispose();

        String scratchDir = System.getProperty("user.dir", ".");
        File outFile = new File(scratchDir, "cjk_fallback_glyph.png");
        ImageIO.write(img, "png", outFile);
        System.out.println("Saved visual check image: " + outFile.getAbsolutePath());
    }

    private static Font loadJetBrainsMono() throws Exception {
        String testSrc = System.getProperty("test.src", ".");
        File repoRoot = new File(testSrc, "../../../../../..").getCanonicalFile();
        File jbMonoFile = new File(repoRoot,
            "src/java.desktop/share/fonts/JetBrainsMono-Regular.ttf");

        if (!jbMonoFile.exists()) {
            throw new RuntimeException(
                "JetBrains Mono not found at: " + jbMonoFile +
                " (test.src=" + testSrc + ", repoRoot=" + repoRoot + ")");
        }

        System.out.println("Loading via createFont: " + jbMonoFile);
        return Font.createFont(Font.TRUETYPE_FONT, jbMonoFile)
                    .deriveFont(Font.PLAIN, 48f);
    }
}
