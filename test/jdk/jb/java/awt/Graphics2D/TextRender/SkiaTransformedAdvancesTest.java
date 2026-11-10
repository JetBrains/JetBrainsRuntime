/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

/**
 * @test
 * @summary Whole-pixel advances (fractional metrics off) on macOS under a transform: the advance in user space —
 *          under the font's own transform, not the graphics' — rounded to whole user pixels per component, then
 *          transformed; the JDK's CoreText strike's rule (CStrike, measured 2026-11-10: "x           x" in Helvetica
 *          Neue 9 is 43.00 long under every rotation, uneven scale and shear of the graphics; a font rotated by 30
 *          degrees advances a space by (2, 1) where the exact vector is (2.17, 1.25)), which the Skia scaler's strike
 *          (SKFontStrike) keeps so text keeps its metrics when the scaler is switched. The Skia scaler had taken the
 *          JDK's FreeType rule instead — the exact vector on an oblique baseline or under an uneven scale: 36.85 at
 *          5 to 45 degrees, 36.74 at 1.5 times in x, (2.167, 1.251) for the rotated font. Glyph vectors and
 *          TextLayout, the system font and a bundled one, on both scalers.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true SkiaTransformedAdvancesTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia SkiaTransformedAdvancesTest
 */
public class SkiaTransformedAdvancesTest {
    static final String TEXT = "x           xHamburgefonstiv";

    public static void main(String[] args) {
        StringBuilder failures = new StringBuilder();
        for (String family : new String[] {"Helvetica Neue", "Inter"}) {
            for (float size : new float[] {9, 13}) {
                Font font = new Font(family, Font.PLAIN, 1).deriveFont(size);
                // the graphics' transforms: the untransformed whole-pixel positions, whatever the transform
                GlyphVector plain = font.createGlyphVector(new FontRenderContext(null, true, false), TEXT);
                float plainAdvance = new TextLayout(TEXT, font, new FontRenderContext(null, true, false)).getAdvance();
                Object[][] transforms = {
                        {"rotated 5", rotate(5, 1, 1)}, {"rotated 30", rotate(30, 1, 1)}, {"rotated 45", rotate(45, 1, 1)},
                        {"rotated 90", rotate(90, 1, 1)}, {"2x", rotate(0, 2, 2)}, {"rotated 30 at 2x", rotate(30, 2, 2)},
                        {"1.5x in x", rotate(0, 1.5, 1)}, {"1.5x in y", rotate(0, 1, 1.5)}, {"sheared", AffineTransform.getShearInstance(-0.25, 0)},
                        {"rotated 30 at 1.5x in x", rotate(30, 1.5, 1)}};
                for (Object[] t : transforms) {
                    FontRenderContext frc = new FontRenderContext((AffineTransform) t[1], true, false);
                    GlyphVector gv = font.createGlyphVector(frc, TEXT);
                    double worst = 0;
                    for (int i = 0; i <= gv.getNumGlyphs(); i++) {
                        worst = Math.max(worst, gv.getGlyphPosition(i).distance(plain.getGlyphPosition(i)));
                    }
                    float advance = new TextLayout(TEXT, font, frc).getAdvance();
                    if (worst > 1e-3 || Math.abs(advance - plainAdvance) > 1e-3) {
                        failures.append(String.format("%s %.0f, %s: glyph positions up to %.2f from the untransformed whole-pixel ones, TextLayout's advance %.2f against %.2f; ",
                                family, size, t[0], worst, advance, plainAdvance));
                    }
                }
                // the font's own transform: each advance the font-transformed exact vector rounded per component
                GlyphVector exact = font.createGlyphVector(new FontRenderContext(null, true, true), TEXT);
                for (Object[] t : new Object[][] {{"a font rotated by 30", AffineTransform.getRotateInstance(Math.toRadians(30))},
                                                  {"a font 1.5x wide", AffineTransform.getScaleInstance(1.5, 1)}}) {
                    AffineTransform fontTransform = (AffineTransform) t[1];
                    GlyphVector gv = font.deriveFont(fontTransform).createGlyphVector(new FontRenderContext(null, true, false), TEXT);
                    double worst = 0;
                    Point2D.Double expected = new Point2D.Double();
                    for (int i = 0; i < gv.getNumGlyphs(); i++) {
                        Point2D a = exact.getGlyphPosition(i), b = exact.getGlyphPosition(i + 1);
                        Point2D v = fontTransform.deltaTransform(new Point2D.Double(b.getX() - a.getX(), b.getY() - a.getY()), null);
                        expected.setLocation(expected.x + Math.rint(v.getX()), expected.y + Math.rint(v.getY()));
                        worst = Math.max(worst, gv.getGlyphPosition(i + 1).distance(expected));
                    }
                    if (worst > 0.01) {
                        failures.append(String.format("%s %.0f, %s: glyph positions up to %.2f from the advances rounded per component; ", family, size, t[0], worst));
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    static AffineTransform rotate(double degrees, double sx, double sy) {
        AffineTransform at = AffineTransform.getRotateInstance(Math.toRadians(degrees));
        at.scale(sx, sy);
        return at;
    }
}
