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
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.text.AttributedString;
import java.util.Arrays;

/**
 * @test
 * @summary A font of Font.createFont that is no longer referenced releases what the text routes made for it: its Skia
 *          font scaler (which registered itself as its own Disposer record and so was never collected, its native
 *          typeface with it; and a strike's scaler context was free()d, its typeface reference never released), the
 *          luminance proxies of Skia's text path for its typeface, and the CTFont macOS' cascade made from its file
 *          (kept per file path, and every Font.createFont of a stream has a new file).
 *          Rounds of fonts created from the same bytes, drawn, and dropped: what the routes hold after the last round
 *          must not have grown over what they held after the first (soft references cleared at every collection, so
 *          that the strikes the JDK keeps softly do not count).
 * @requires os.family == "mac"
 * @modules java.desktop/sun.java2d.skia java.desktop/sun.font:+open
 * @run main/othervm -XX:SoftRefLRUPolicyMSPerMB=0 -Djava.awt.headless=true TypefaceLifetimeTest
 * @run main/othervm -XX:SoftRefLRUPolicyMSPerMB=0 -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia TypefaceLifetimeTest
 * @run main/othervm -XX:SoftRefLRUPolicyMSPerMB=0 -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia TypefaceLifetimeTest
 */
public class TypefaceLifetimeTest {
    static final int ROUNDS = 4, FONTS_PER_ROUND = 20, ALLOWED_GROWTH = 5;
    static final String[] NAMES = {"Skia font scalers", "luminance proxies", "cascade CTFonts", "layout aliases", "paragraph registrations"};

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(new File(System.getProperty("java.home"), "lib/fonts/JetBrainsMono-Regular.ttf").toPath());
        boolean skiaScaler = "skia".equals(System.getProperty("sun.java2d.font.scaler"));
        boolean raster = Boolean.getBoolean("sun.java2d.skia.raster");
        boolean textPath = raster && "skia".equals(System.getProperty("sun.java2d.skia.text.strike"));
        boolean layout = raster && "skia".equals(System.getProperty("sun.java2d.skia.text.layout"));
        BufferedImage image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_RGB);
        long[] first = null, last = null, peak = new long[NAMES.length];
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < FONTS_PER_ROUND; i++) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(data)).deriveFont(13f);
                Graphics2D g = image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 300, 40);
                g.setColor(Color.BLACK);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(font);
                g.drawString("Hello ★ 中", 5, 15);   // ★ 中: the bundled font lacks them, the cascade draws them
                AttributedString styled = new AttributedString("Hello ★ x");
                styled.addAttribute(TextAttribute.FONT, font);
                styled.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, 0, 5);
                g.drawString(styled.getIterator(), 5, 32);
                g.dispose();
                long[] now = held();
                for (int k = 0; k < now.length; k++) peak[k] = Math.max(peak[k], now[k]);
            }
            last = settled();
            if (first == null) first = last;
            System.out.println("after round " + round + ": " + describe(last));
        }
        if (peak[2] == 0) throw new RuntimeException("the cascade made no CTFont: the test does not reach it");
        if (skiaScaler && peak[0] == 0) throw new RuntimeException("no Skia font scaler was made: the test does not reach it");
        if (textPath && peak[1] == 0) throw new RuntimeException("Skia's text path made no luminance proxy: the test does not reach it");
        if (layout && (peak[3] == 0 || peak[4] == 0)) throw new RuntimeException("Skia's layout route registered no alias or paragraph font: the test does not reach it");
        StringBuilder failures = new StringBuilder();
        for (int k = 0; k < NAMES.length; k++) {
            if (last[k] - first[k] > ALLOWED_GROWTH) {
                failures.append(NAMES[k]).append(" grew from ").append(first[k]).append(" to ").append(last[k]).append(" over ")
                        .append((ROUNDS - 1) * FONTS_PER_ROUND).append(" dropped fonts; ");
            }
        }
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    /** What the routes hold once the collector and the Disposer are done with the dropped fonts. */
    static long[] settled() throws Exception {
        long[] previous = null;
        for (int i = 0; i < 60; i++) {
            System.gc();
            Thread.sleep(50);
            long[] now = held();
            if (i >= 10 && Arrays.equals(now, previous)) return now;
            previous = now;
        }
        return previous;
    }

    static long[] held() throws Exception {
        Method typefaces = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("typefacesHeld");
        long[] skia = (long[]) typefaces.invoke(null);
        Method bases = Class.forName("sun.font.CCascadeGlyphMapper").getDeclaredMethod("basesHeld");
        bases.setAccessible(true);
        return new long[] {skia[0], skia[1], ((Number) bases.invoke(null)).longValue(), skia[2], skia[3]};
    }

    static String describe(long[] held) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < NAMES.length; k++) s.append(k > 0 ? ", " : "").append(NAMES[k]).append(' ').append(held[k]);
        return s.toString();
    }
}
