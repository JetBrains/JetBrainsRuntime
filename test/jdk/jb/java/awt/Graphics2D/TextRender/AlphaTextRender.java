/*
 * Copyright 2021 JetBrains s.r.o.
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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import static java.awt.Color.*;

/**
 * @test
 * @key headful
 * @summary Check that increasing alpha of text on white background produces letters with decreasing brightness.
 *  Test runs two times to test accelerated OGL and Metal text rendering.
 * @run main/othervm -Dsun.java2d.metal=False AlphaTextRender
 * @run main/othervm -Dsun.java2d.metal=True AlphaTextRender
 */

public class AlphaTextRender {
  final static int WIDTH = 150;
  final static int HEIGHT = 200;
  final static File mtlImg = new File("t-alpha-metal.png");
  final static File oglImg = new File("t-alpha-ogl.png");

  public static void main(final String[] args) throws IOException {

    GraphicsEnvironment ge =
        GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsConfiguration gc =
        ge.getDefaultScreenDevice().getDefaultConfiguration();

    VolatileImage vi = gc.createCompatibleVolatileImage(WIDTH, HEIGHT);
    BufferedImage bi = gc.createCompatibleImage(WIDTH, HEIGHT);
    BufferedImage br = gc.createCompatibleImage(WIDTH, HEIGHT);
    Color[] colors = {
            WHITE, LIGHT_GRAY, GRAY, DARK_GRAY, BLACK, RED, PINK, ORANGE, YELLOW, MAGENTA,
            CYAN, BLUE
    };

    int c = 10;
    int maxAscent;
    do {
      Graphics2D g2d = vi.createGraphics();
      maxAscent = g2d.getFontMetrics().getMaxAscent();
      Font font = new Font("Serif", Font.PLAIN, 10);
      g2d.setFont(font);
      g2d.setColor(Color.WHITE);
      g2d.fillRect(0, 0, WIDTH, HEIGHT);
      int posy = maxAscent;
      for (Color col : colors) {
        g2d.setColor(col);
        int posx = 10;
        for (int j = 0; j < 20; j++) {
          g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, j/(float)20));
          g2d.drawString(String.valueOf('|'), posx, posy);
          posx += g2d.getFontMetrics().charWidth('|') + 1;
        }
        posy += maxAscent;
      }
      g2d.dispose();
    } while (vi.contentsLost() && (--c > 0));

    Graphics2D g2d = bi.createGraphics();
    g2d.drawImage(vi, 0, 0, null);
    g2d.dispose();

    g2d = br.createGraphics();
    g2d.drawImage(vi, 0, 0, null);
    g2d.dispose();

    int posy = 0;
    int errors = 0;
    for (Color color : colors) {
      int r0 = 255;
      int g0 = 255;
      int b0 = 255;
      int lmr = 0;
      int lmg = 0;
      int lmb = 0;

      int curErrors = 0;
      boolean spike = false;
      for (int j = 0; j < WIDTH; j++) {
        Color c1 = new Color(bi.getRGB(j, posy + maxAscent / 2));
        int dr = Math.abs(c1.getRed() - WHITE.getRed());
        int dg = Math.abs(c1.getGreen() - WHITE.getGreen());
        int db = Math.abs(c1.getBlue() - WHITE.getBlue());

        // Skip background and pick
        if (dr + dg + db > 0) {
          if (!spike) {
            lmr = c1.getRed();
            lmg = c1.getGreen();
            lmb = c1.getBlue();
            spike = true;
          } else {
            lmr = Math.max(lmr, c1.getRed());
            lmg = Math.max(lmg, c1.getGreen());
            lmb = Math.max(lmb, c1.getBlue());
          }
        } else if (spike) { // Compare current spike with the previous one
          if (getColorValue(r0, g0, b0) < getColorValue(lmr, lmg, lmb)) {
            curErrors++;
            br.setRGB(j, posy + maxAscent / 2 - 2, BLACK.getRGB());
            br.setRGB(j, posy + maxAscent / 2 - 1, BLACK.getRGB());
            br.setRGB(j, posy + maxAscent / 2, BLACK.getRGB());
            br.setRGB(j, posy + maxAscent / 2 + 1, BLACK.getRGB());
            br.setRGB(j, posy + maxAscent / 2 + 2, BLACK.getRGB());
          }
          r0 = lmr;
          g0 = lmg;
          b0 = lmb;
          spike = false;
        }
      }
      if (curErrors > 0) {
        System.err.println("Error with color:" + color);
        errors += curErrors;
      }

      posy += maxAscent;
    }

    if (errors > 0) {
      String opt = System.getProperty("sun.java2d.metal");
      if (("true".equals(opt) || "True".equals(opt))) {
        ImageIO.write(br, "png", mtlImg);
      } else {
        ImageIO.write(br, "png", oglImg);
      }
      throw new RuntimeException("Translucent text errors found: " + errors);
    }
  }

  private static double getColorValue(int r0, int g0, int b0) {
    // Use simple addition for normalized color components
    return r0 / 255.0 + g0 / 255.0 + b0 / 255.0;
  }
}
