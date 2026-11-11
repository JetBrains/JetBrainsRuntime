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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * @test
 * @summary LCD text with fractional metrics renders the same in every process. The text pipes place an LCD glyph
 *          at a third of a pixel by reading its image rows from one or two bytes in (DrawGlyphList.c, "every glyph
 *          image row has 2 bytes padding on the right"), so the last row reads up to two bytes past the image: the
 *          Skia scaler's records ended there and the JDK's CoreText one (CGGlyphImages.m) left its three extra bytes
 *          unset — whatever the heap held came out as stray coloured pixels at the glyphs' bottom-right corners,
 *          different in every process (five of five runs differed, a pixel or three each). The JDK's FreeType scaler
 *          pads its LCD rows by a pixel on each side but counts the pads in the glyph's width, so its last row ended
 *          at the image too: fonts of Font.createFont (JetBrains Mono, Inter) and, under LCD, system fonts drawn
 *          through it varied by a few pixels between processes. The sheet is rendered in four fresh JVMs per scaler;
 *          their pixels must agree.
 * @requires os.family == "mac"
 * @library /test/lib
 * @run main/othervm -Djava.awt.headless=true LcdGlyphSlackTest
 */
public class LcdGlyphSlackTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.out.println("hash " + Long.toHexString(sheet()));
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (String scaler : new String[] {"jdk", "skia"}) {
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                List<String> command = new ArrayList<>(List.of("-Djava.awt.headless=true"));
                if (scaler.equals("skia")) command.add("-Dsun.java2d.font.scaler=skia");
                command.addAll(List.of(LcdGlyphSlackTest.class.getName(), "child"));
                OutputAnalyzer out = ProcessTools.executeTestJava(command.toArray(new String[0]));
                out.shouldHaveExitValue(0);
                String line = out.getStdout().lines().filter(l -> l.startsWith("hash ")).findFirst().orElseThrow();
                hashes.add(line.substring(5));
            }
            System.out.println(scaler + " scaler: " + hashes);
            if (hashes.stream().distinct().count() > 1) {
                failures.append(scaler).append(" scaler: the LCD sheet differs between processes ").append(hashes).append("; ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    /** A hash of an LCD, fractional-metrics sheet at fractional positions (every third-pixel phase). */
    static long sheet() throws Exception {
        BufferedImage img = new BufferedImage(900, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 1100);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int y = 20;
        java.io.File fonts = new java.io.File(System.getProperty("java.home"), "lib/fonts");
        Font jetBrainsMono = Font.createFont(Font.TRUETYPE_FONT, new java.io.File(fonts, "JetBrainsMono-Regular.ttf"));
        Font inter = Font.createFont(Font.TRUETYPE_FONT, new java.io.File(fonts, "Inter-Regular.otf"));
        for (String family : new String[] {"Helvetica Neue", "Menlo", "Times New Roman", "Lucida Grande", "Droid Sans", "JetBrains Mono file", "Inter file"}) {
            for (int size : new int[] {11, 13, 16}) {
                g.setFont(family.equals("JetBrains Mono file") ? jetBrainsMono.deriveFont((float) size)
                          : family.equals("Inter file") ? inter.deriveFont((float) size) : new Font(family, Font.PLAIN, size));
                for (float dx : new float[] {0.3f, 0.6f}) {
                    g.drawString("The quick brown fox jumps over the lazy dog 0123456789 il1|", 10 + dx, y + 0.4f);
                    y += size + 6;
                }
            }
        }
        g.dispose();
        long h = 1125899906842597L;
        for (int yy = 0; yy < img.getHeight(); yy++) for (int x = 0; x < img.getWidth(); x++) h = 31 * h + img.getRGB(x, yy);
        return h;
    }
}
