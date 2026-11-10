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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;

/**
 * @test
 * @summary Colour glyphs (emoji) under every AlphaComposite rule, on Skia's text routes, take their alpha as the
 *          coverage and their own colours at the composite's extra alpha as the source, as outline glyphs take
 *          theirs: dst' = mix(dst, B(s, dst), a) — the reference computed per pixel from the emoji as the software
 *          loops draw them (over SrcOver on a transparent image), a translucent background so that the rules reading
 *          the destination's alpha show. Drawn as images, a colour glyph applied the rule to its whole box (SrcIn,
 *          DstIn, SrcOut and DstAtop up to 128 levels off, Src under extra alpha 51). The GPU pipe's own configuration
 *          (Java's glyph images, "images") likewise: rules other than Src, Clear and SrcOver went to the loops'
 *          MaskFill text pipe, which skips colour glyphs (DstOver, DstOut and DstAtop 128 levels off).
 * @requires os.family == "mac"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaColourGlyphRulesTest glyphs
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaColourGlyphRulesTest shaper
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaColourGlyphRulesTest paragraph
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaColourGlyphRulesTest raster-glyphs
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaColourGlyphRulesTest raster-paragraph
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaColourGlyphRulesTest images
 */
public class SkiaColourGlyphRulesTest {
    static final int W = 200, H = 70;
    static final String TEXT = "😀👍";
    static final int BACKGROUND = 0x80f0e8d0;
    static final int TOLERANCE = 3;
    static final String[] NAMES = {"Clear", "Src", "SrcOver", "DstOver", "SrcIn", "DstIn", "SrcOut", "DstOut", "Dst", "SrcAtop", "DstAtop", "Xor"};
    static final int[] RULES = {AlphaComposite.CLEAR, AlphaComposite.SRC, AlphaComposite.SRC_OVER, AlphaComposite.DST_OVER,
            AlphaComposite.SRC_IN, AlphaComposite.DST_IN, AlphaComposite.SRC_OUT, AlphaComposite.DST_OUT, AlphaComposite.DST,
            AlphaComposite.SRC_ATOP, AlphaComposite.DST_ATOP, AlphaComposite.XOR};
    static String mode;

    public static void main(String[] args) throws Exception {
        mode = args[0];
        boolean raster = mode.startsWith("raster");
        GraphicsConfiguration gc = raster ? null : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!raster && !PipelineComparison.pipelineActive("skia", gc)) {
            return;
        }
        Font font = new Font("Helvetica Neue", Font.PLAIN, 40);
        // the emoji as the software loops draw them: premultiplied colour and coverage
        BufferedImage glyphs = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D gg = glyphs.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gg.setFont(font);
        gg.drawString(TEXT, 10, 50);
        gg.dispose();
        StringBuilder failures = new StringBuilder();
        for (float extraAlpha : new float[] {1f, 0.6f}) {
            for (int k = 0; k < RULES.length; k++) {
                String what = NAMES[k] + (extraAlpha < 1 ? " at " + extraAlpha : "");
                AlphaComposite comp = AlphaComposite.getInstance(RULES[k], extraAlpha);
                long before = counter();
                BufferedImage actual = render(gc, raster, font, comp);
                long drawn = counter() - before;
                int max = 0;
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                    float[] e = expected(glyphs.getRGB(x, y), RULES[k], extraAlpha), got = premultiplied(actual.getRGB(x, y));
                    for (int i = 0; i < 4; i++) max = Math.max(max, Math.round(Math.abs(got[i] - e[i]) * 255));
                }
                System.out.println(mode + ", " + what + ": " + drawn + " by the route, at most " + max + " levels from the reference");
                if (drawn == 0 && !mode.equals("images")) {   // Java's glyph images: no Skia text route to count
                    failures.append(what).append(": not drawn by the route; ");
                }
                if (max > TOLERANCE) {
                    failures.append(what).append(": ").append(max).append(" levels off; ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(mode + ": " + failures);
        }
        System.out.println("PASSED");
    }

    static BufferedImage render(GraphicsConfiguration gc, boolean raster, Font font, AlphaComposite comp) {
        VolatileImage vi = raster ? null : gc.createCompatibleVolatileImage(W, H, Transparency.TRANSLUCENT);
        BufferedImage bi = raster ? new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE) : null;
        Graphics2D g = raster ? bi.createGraphics() : vi.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(BACKGROUND, true));
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(new Color(0x2050c0));
        g.setComposite(comp);
        if (mode.endsWith("paragraph")) {
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            g.drawString(as.getIterator(), 10, 50);
        } else {
            g.drawString(TEXT, 10, 50);
        }
        g.dispose();
        return raster ? bi : vi.getSnapshot();
    }

    /** mix(dst, B(s, dst), a): a the glyph's coverage, s its colour at the extra alpha. */
    static float[] expected(int glyph, int rule, float extraAlpha) {
        float[] p = premultiplied(glyph), d = premultiplied(BACKGROUND);
        float a = p[3];
        if (a <= 0) {
            return d;
        }
        float[] s = {p[0] / a * extraAlpha, p[1] / a * extraAlpha, p[2] / a * extraAlpha, extraAlpha};
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) {
            float S = s[i], D = d[i], sa = s[3], da = d[3];
            float b = switch (rule) {
                case AlphaComposite.CLEAR -> 0;
                case AlphaComposite.SRC -> S;
                case AlphaComposite.DST -> D;
                case AlphaComposite.SRC_OVER -> S + D * (1 - sa);
                case AlphaComposite.DST_OVER -> D + S * (1 - da);
                case AlphaComposite.SRC_IN -> S * da;
                case AlphaComposite.DST_IN -> D * sa;
                case AlphaComposite.SRC_OUT -> S * (1 - da);
                case AlphaComposite.DST_OUT -> D * (1 - sa);
                case AlphaComposite.SRC_ATOP -> S * da + D * (1 - sa);
                case AlphaComposite.DST_ATOP -> D * sa + S * (1 - da);
                default -> S * (1 - da) + D * (1 - sa);
            };
            r[i] = D + (b - D) * a;
        }
        return r;
    }

    static float[] premultiplied(int argb) {
        float a = (argb >>> 24) / 255f;
        return new float[] {((argb >> 16) & 0xff) / 255f * a, ((argb >> 8) & 0xff) / 255f * a, (argb & 0xff) / 255f * a, a};
    }

    static long counter() throws Exception {
        if (mode.equals("images")) {
            return 0;
        }
        String name = mode.endsWith("paragraph") ? "paragraphsBySkia" : mode.equals("shaper") ? "glyphsShapedBySkia"
                : mode.startsWith("raster") ? "rasterGlyphsBySkia" : "glyphsDrawnBySkia";
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod(name).invoke(null);
    }
}
