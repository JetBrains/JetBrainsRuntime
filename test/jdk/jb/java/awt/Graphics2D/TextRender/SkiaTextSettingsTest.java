/*
 * Copyright 2026 JetBrains s.r.o.
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
 */

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary The Skia backend's text settings are six independent dimensions, each a
 *          property under sun.java2d.skia.text: strike, shaping and paragraphs
 *          (java2d or skia; a skia shaping or paragraphs value implies the skia
 *          strike), hinting (system, none, slight, normal, full), darkening (system
 *          or none), blend (system, plain, corrected) and decorations (system, java2d). The retired spellings
 *          still resolve: sun.java2d.skia.text=skia|shaper|paragraph sets the three
 *          route choices, text.gray=cg|platform is blend system, coverage is blend
 *          corrected with darkening none, platformcoverage blend plain with
 *          darkening none, and text.smoothing=true is darkening system; the two
 *          emulations (smoothed, darkened) are gone and read as coverage. A new
 *          spelling wins over an old one.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true SkiaTextSettingsTest java2d java2d java2d SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text=paragraph SkiaTextSettingsTest skia skia skia SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text=skia SkiaTextSettingsTest skia java2d java2d SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.strike=skia -Dsun.java2d.skia.text.shaping=skia SkiaTextSettingsTest skia skia java2d SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.paragraphs=skia SkiaTextSettingsTest skia java2d skia SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.gray=cg SkiaTextSettingsTest java2d java2d java2d SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.gray=coverage SkiaTextSettingsTest java2d java2d java2d SYSTEM NONE CORRECTED SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.gray=platformcoverage SkiaTextSettingsTest java2d java2d java2d SYSTEM NONE PLAIN SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.gray=darkened SkiaTextSettingsTest java2d java2d java2d SYSTEM NONE CORRECTED SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.smoothing=true -Dsun.java2d.skia.text.gray=coverage SkiaTextSettingsTest java2d java2d java2d SYSTEM SYSTEM CORRECTED SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.hinting=slight -Dsun.java2d.skia.text.darkening=none -Dsun.java2d.skia.text.blend=plain SkiaTextSettingsTest java2d java2d java2d SLIGHT NONE PLAIN SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.blend=corrected SkiaTextSettingsTest java2d java2d java2d SYSTEM SYSTEM CORRECTED SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text=paragraph -Dsun.java2d.skia.text.paragraphs=java2d SkiaTextSettingsTest skia skia java2d SYSTEM SYSTEM SYSTEM SYSTEM
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.text.decorations=java2d SkiaTextSettingsTest java2d java2d java2d SYSTEM SYSTEM SYSTEM JAVA2D
 */
public class SkiaTextSettingsTest {
    public static void main(String[] args) {
        String strike = SKEnvironment.textBySkia() ? "skia" : "java2d";
        String shaping = SKEnvironment.textShapedBySkia() ? "skia" : "java2d";
        String paragraphs = SKEnvironment.textParagraphBySkia() ? "skia" : "java2d";
        String hinting = SKEnvironment.textHinting().name();
        String darkening = SKEnvironment.textDarkening().name();
        String blend = SKEnvironment.textBlend().name();
        String decorations = SKEnvironment.textDecorations().name();
        String got = String.join(" ", strike, shaping, paragraphs, hinting, darkening, blend, decorations);
        String expected = String.join(" ", args);
        System.out.println("strike shaping paragraphs hinting darkening blend decorations: " + got);
        if (!got.equals(expected)) {
            throw new RuntimeException("expected " + expected + ", got " + got);
        }
        System.out.println("PASSED");
    }
}
