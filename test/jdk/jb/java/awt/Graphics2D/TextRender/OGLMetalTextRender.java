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
/**
 * @test
 * @key headful
 * @summary Test runs two times to generate and compare images with accelerated OGL and Metal text rendering.
 * @run main/othervm -Dsun.java2d.metal=False OGLMetalTextRender
 * @run main/othervm -Dsun.java2d.metal=True OGLMetalTextRender
 */

public class OGLMetalTextRender {
  final static int WIDTH = 210;
  final static int HEIGHT = 120;
  final static int TD = 3;
  final static File mtlImg = new File("t-metal.png");
  final static File oglImg = new File("t-ogl.png");
  final static File cmpImg = new File("t-mtlogl.png");

  public static void main(final String[] args) throws IOException {

    GraphicsEnvironment ge =
        GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsConfiguration gc =
        ge.getDefaultScreenDevice().getDefaultConfiguration();

    VolatileImage vi = gc.createCompatibleVolatileImage(WIDTH, 2*HEIGHT);
    BufferedImage bi = gc.createCompatibleImage(WIDTH, 2*HEIGHT);
    String text = "The quick brown fox jumps over the lazy dog";
    int c = 10;
    do {
      Graphics2D g2d = vi.createGraphics();
      Font font = new Font("Serif", Font.PLAIN, 10);
      g2d.setFont(font);
      g2d.setColor(Color.WHITE);
      g2d.fillRect(0, 0, WIDTH, HEIGHT);
      for (int i = 0; i <= 10; i++) {
        g2d.setColor(new Color(0, 0, 0, i/10.0f));
        g2d.drawString(text, 10, 10*(i + 1));
      }
      g2d.setColor(Color.BLACK);
      g2d.fillRect(0, HEIGHT, WIDTH, HEIGHT);
      for (int i = 0; i <= 10; i++) {
        g2d.setColor(new Color(1.0f, 1.0f, 1.0f, i/10.0f));
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC));
        g2d.drawString(text, 10, 10*(i + 1) + HEIGHT);
      }
      g2d.dispose();
    } while (vi.contentsLost() && (--c > 0));

    Graphics2D g2d = bi.createGraphics();
    g2d.drawImage(vi, 0, 0, null);
    g2d.dispose();
    int errors = 0;
    BufferedImage result = new BufferedImage(WIDTH, 2*HEIGHT, BufferedImage.TYPE_INT_ARGB);

    String opt = System.getProperty("sun.java2d.metal");
    if (opt != null && ("true".equals(opt) || "True".equals(opt))) {
      ImageIO.write(bi, "png", mtlImg);
      if (oglImg.exists()) {
        errors = compare(bi, ImageIO.read(oglImg), result);
      }
    } else {
      ImageIO.write(bi, "png", oglImg);
      if (mtlImg.exists()) {
        errors = compare(bi, ImageIO.read(mtlImg), result);
      }
    }

    if (errors > 0) {
      ImageIO.write(result, "png", cmpImg);
      throw new RuntimeException("Metal vs OGL rendering mismatch, errors found: " + errors);
    }
  }

  private static int compare(BufferedImage bi1, BufferedImage bi2,
                             BufferedImage result)
  {
    int errors = 0;
    for (int i = 0; i < WIDTH; i++) {
      for (int j = 0; j < 2*HEIGHT; j++) {
        Color c1 = new Color(bi1.getRGB(i, j));
        Color c2 = new Color(bi2.getRGB(i, j));
        int dr = Math.abs(c1.getRed() - c2.getRed());
        int dg = Math.abs(c1.getGreen() - c2.getGreen());
        int db = Math.abs(c1.getBlue() - c2.getBlue());
        if (dr+dg+db > TD) {
          errors++;
          result.setRGB(i, j, Color.RED.getRGB());
        }
      }
    }
    return errors;
  }
}
