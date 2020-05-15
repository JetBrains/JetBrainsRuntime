/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.Font;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;

import static java.awt.RenderingHints.*;

public class JFrameEmojiDrawingTest {
    private static final String EMOJI = "😃👍🐊🦃🤡🔥";
    private static final Font FONT_BIG = new Font(
            System.getProperty("os.name").toLowerCase().contains("mac") ?"Menlo" : "Noto Color Emoji", Font.PLAIN, 50);
    private static final Font FONT_SMALL = FONT_BIG.deriveFont(20F);
    private static final int IMAGE_WIDTH = 450;
    private static final int IMAGE_HEIGHT = 300;
    private static final int GLYPH_X = 20;
    private static final int GLYPH_Y = 200;

    private static final List<Map<Key, Object>> PRESETS;
    static {
        Map<Key, Object[]> hints = Map.of(
                KEY_FRACTIONALMETRICS, new Object[] {VALUE_FRACTIONALMETRICS_OFF, VALUE_FRACTIONALMETRICS_ON},
                KEY_TEXT_ANTIALIASING, new Object[] {VALUE_TEXT_ANTIALIAS_OFF, VALUE_TEXT_ANTIALIAS_ON, VALUE_TEXT_ANTIALIAS_LCD_HBGR}
        );
        int totalPresets = hints.values().stream().mapToInt(a -> a.length).reduce(1, (a, b) -> a * b);
        PRESETS = Stream.generate(HashMap<Key, Object>::new).limit(totalPresets).collect(Collectors.toList());
        int period = 1;
        for(Map.Entry<Key, Object[]> hint : hints.entrySet()) {
            for (int i = 0; i < totalPresets; i+= period) {
                for (int j = 0; j < period; j++) {
                    PRESETS.get(i + j).put(hint.getKey(), hint.getValue()[i % hint.getValue().length]);
                }
            }
            period *= hint.getValue().length;
        }
    }

    private static int preset;


    public static void main(String[] args) {
        JFrame frame = new JFrame();
        frame.setBounds(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        frame.setLayout(new BorderLayout());
        frame.add(new JPanel() {
            public void paint(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics;
                g.setColor(Color.white);
                g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
                PRESETS.get(preset).forEach(g::setRenderingHint);
                g.setColor(Color.RED);
                g.drawLine(GLYPH_X, GLYPH_Y, GLYPH_X + 1000, GLYPH_Y);
                g.setFont(FONT_BIG);
                g.drawString(EMOJI, GLYPH_X, GLYPH_Y);
                {
                    AffineTransform transform = g.getTransform();
                    transform.rotate(-Math.PI * 0.25);
                    g.setTransform(transform);
                }
                g.drawLine(GLYPH_X, GLYPH_Y, GLYPH_X + 1000, GLYPH_Y);
                g.setFont(FONT_SMALL);
                g.drawString(EMOJI, GLYPH_X, GLYPH_Y);
            }
        }, BorderLayout.CENTER);
        frame.setVisible(true);
        LockSupport.parkNanos(2000_000000L);
        for (int i = 0; i < PRESETS.size(); i++) {
            preset = i;
            frame.repaint();
            LockSupport.parkNanos(1000_000000L);
        }
        System.exit(0);
    }


}
