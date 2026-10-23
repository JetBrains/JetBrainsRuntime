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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import sun.font.Font2D;
import sun.font.FontUtilities;
import sun.font.SKFontScaler;

/**
 * @test
 * @summary The Skia font scaler works without the Skia rendering backend:
 *          -Dsun.java2d.font.scaler=skia alone (software loops, or the
 *          platform's GPU pipeline) selects it, so its glyph images can be
 *          compared on every pipeline. It used to fall back to the platform
 *          scaler silently because its library was only loaded with the
 *          backend.
 * @modules java.desktop/sun.font
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia SkiaScalerWithoutBackendTest
 */
public class SkiaScalerWithoutBackendTest {
    public static void main(String[] args) {
        if (!FontUtilities.useSkiaScaler) {
            throw new RuntimeException("sun.java2d.font.scaler=skia was not picked up");
        }
        if (!SKFontScaler.isAvailable()) {
            throw new RuntimeException("the Skia font scaler is not available without the Skia backend");
        }
        // the scaler must be constructible over a font file, not just report availability
        File file = new File(System.getProperty("java.home"), "lib/fonts/Inter-Regular.otf");
        Font font;
        try {
            font = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(13f);
        } catch (IOException | FontFormatException e) {
            throw new RuntimeException(e);
        }
        Font2D font2D = FontUtilities.getFont2D(font);
        SKFontScaler scaler = SKFontScaler.forFile(font2D, file.getPath(), 0);
        if (scaler == null) {
            throw new RuntimeException("no scaler for " + file);
        }
        BufferedImage image = new BufferedImage(200, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 30);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString("Skia scaler on the software loops", 4, 20);
        g.dispose();
        int ink = 0;
        for (int y = 0; y < 30; y++) for (int x = 0; x < 200; x++) if (image.getRGB(x, y) != Color.WHITE.getRGB()) ink++;
        if (ink == 0) {
            throw new RuntimeException("no ink from the Skia scaler on a BufferedImage");
        }
    }
}
