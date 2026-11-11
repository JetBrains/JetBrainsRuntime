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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * @test
 * @key headful
 * @summary The IDE's two sub-pixel switches get real LCD text on macOS, alike. enable.macos.ide.subpixelAA: the UI's
 *          Swing text with the LCD hint as a component client property (GraphicsUtil.setAntialiasingType, SUBPIXEL), in
 *          the UI font (SF, ".AppleSystemUIFont", a variable system font: regular and bold); and
 *          enable.macos.editor.subpixelAA: the editor's text with the hint on the graphics, in JetBrains Mono (bundled),
 *          fractional metrics off and on. On every pipe the IDE runs — the software loops, Metal, the Skia pipe — each
 *          must carry sub-pixel fringes at whole-pixel positions (none for the UI font before: CoreText's LCD masks have
 *          equal channels), in the loops' order (Metal drew BGR), within 48 levels of the loops; grayscale has none.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=true IdeSubpixelAATest metal
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true IdeSubpixelAATest skia
 */
public class IdeSubpixelAATest {
    static final int W = 460, H = 30, TOLERANCE = 48;
    static final String UI_TEXT = "File Edit View Navigate Code Refactor Build Run", EDITOR_TEXT = "public static void main(String[] args) {";

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) throw new RuntimeException("not the Metal configuration hosting " + args[0] + ": " + gc.getClass().getName());
        Font ui = new Font(".AppleSystemUIFont", Font.PLAIN, 13), uiBold = new Font(".AppleSystemUIFont", Font.BOLD, 13);
        Font editor = new Font("JetBrains Mono", Font.PLAIN, 13);
        if (!uiBold.getPSName().equals(".SFNS-Bold") || !editor.getPSName().equals("JetBrainsMono-Regular")) {
            throw new RuntimeException("fonts not found: " + uiBold.getPSName() + ", " + editor.getPSName());
        }
        StringBuilder failures = new StringBuilder();
        for (Font font : new Font[] {ui, uiBold}) {
            check(gc, "ide (Swing client property), " + font.getPSName(), g -> swingText(g, font, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB), failures);
            control(gc, "ide grayscale, " + font.getPSName(), g -> swingText(g, font, RenderingHints.VALUE_TEXT_ANTIALIAS_ON), failures);
        }
        for (boolean fractional : new boolean[] {false, true}) {
            check(gc, "editor (graphics hint), fractional metrics " + fractional, g -> editorText(g, editor, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, fractional), failures);
        }
        control(gc, "editor grayscale", g -> editorText(g, editor, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, false), failures);
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    /** The UI's text: a label painted by Swing, the LCD hint a client property of the component. */
    static void swingText(Graphics2D g, Font font, Object aa) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                JLabel label = new JLabel(UI_TEXT);
                label.setFont(font);
                label.setForeground(Color.BLACK);
                label.setBackground(Color.WHITE);
                label.setOpaque(true);
                label.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
                label.setSize(W, H);
                label.paint(g);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            g.dispose();
        }
    }

    /** The editor's text: the hints on the graphics. */
    static void editorText(Graphics2D g, Font font, Object aa, boolean fractional) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                fractional ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(EDITOR_TEXT, 4, 20);
        g.dispose();
    }

    static void check(GraphicsConfiguration gc, String what, Consumer<Graphics2D> paint, StringBuilder failures) {
        BufferedImage loops = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint.accept(loops.createGraphics());
        BufferedImage pipe = onPipe(gc, paint);
        int loopFringes = fringes(loops), pipeFringes = fringes(pipe), beyond = 0;
        long agreement = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int l = loops.getRGB(x, y), p = pipe.getRGB(x, y);
            agreement += (long) (((l >> 16) & 255) - (l & 255)) * (((p >> 16) & 255) - (p & 255));
            for (int s = 0; s < 24; s += 8) if (Math.abs(((l >> s) & 255) - ((p >> s) & 255)) > TOLERANCE) { beyond++; break; }
        }
        System.out.printf("%s: fringe pixels loops %d, pipe %d; order agreement %d; pixels beyond %d levels %d%n",
                what, loopFringes, pipeFringes, agreement, TOLERANCE, beyond);
        if (loopFringes < 300 || pipeFringes < 300 || agreement <= 0 || beyond > 0) {
            failures.append(what).append(": fringes ").append(loopFringes).append(" / ").append(pipeFringes)
                    .append(", agreement ").append(agreement).append(", beyond ").append(beyond).append("; ");
        }
    }

    static void control(GraphicsConfiguration gc, String what, Consumer<Graphics2D> paint, StringBuilder failures) {
        BufferedImage loops = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint.accept(loops.createGraphics());
        int loopFringes = fringes(loops), pipeFringes = fringes(onPipe(gc, paint));
        System.out.printf("%s: fringe pixels loops %d, pipe %d%n", what, loopFringes, pipeFringes);
        if (loopFringes > 0 || pipeFringes > 0) failures.append(what).append(": fringes ").append(loopFringes).append(" / ").append(pipeFringes).append("; ");
    }

    static BufferedImage onPipe(GraphicsConfiguration gc, Consumer<Graphics2D> paint) {
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage snapshot;
        do {
            image.validate(gc);
            paint.accept(image.createGraphics());
            snapshot = image.getSnapshot();
        } while (image.contentsLost());
        return snapshot;
    }

    static int fringes(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = image.getRGB(x, y);
            if (((p >> 16) & 255) != (p & 255)) count++;
        }
        return count;
    }
}
