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

/*
 * @test
 * @summary Checks that variation selectors work.
 */

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static java.awt.RenderingHints.KEY_TEXT_ANTIALIASING;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;

public class EmojiVariation {
    private static final int IMG_WIDTH = 100;
    private static final int IMG_HEIGHT = 50;

    private static final Color SYMBOL_COLOR = Color.MAGENTA;

    private static final String[] SYMBOLS = {
            "\u0023", // #︎#️
            "\u002a", // *︎*️
            "\u0030", // 0︎0️
            "\u0031", // 1︎1️
            "\u0032", // 2︎2️
            "\u0033", // 3︎3️
            "\u0034", // 4︎4️
            "\u0035", // 5︎5️
            "\u0036", // 6︎6️
            "\u0037", // 7︎7️
            "\u0038", // 8︎8️
            "\u0039", // 9︎9️
            "\u00a9", // ©︎©️
            "\u00ae", // ®︎®️
            "\u203c", // ‼︎‼️
            "\u2049", // ⁉︎⁉️
            "\u2122", // ™︎™️
            "\u2139", // ℹ︎ℹ️
            "\u2194", // ↔︎↔️
            "\u2195", // ↕︎↕️
            "\u2196", // ↖︎↖️
            "\u2197", // ↗︎↗️
            "\u2198", // ↘︎↘️
            "\u2199", // ↙︎↙️
            "\u21a9", // ↩︎↩️
            "\u21aa", // ↪︎↪️
            "\u2328", // ⌨︎⌨️
            "\u23cf", // ⏏︎⏏️
            "\u23ed", // ⏭︎⏭️
            "\u23ee", // ⏮︎⏮️
            "\u23ef", // ⏯︎⏯️
            "\u23f1", // ⏱︎⏱️
            "\u23f2", // ⏲︎⏲️
            "\u23f8", // ⏸︎⏸️
            "\u23f9", // ⏹︎⏹️
            "\u23fa", // ⏺︎⏺️
            "\u24c2", // Ⓜ︎Ⓜ️
            "\u25aa", // ▪︎▪️
            "\u25ab", // ▫︎▫️
            "\u25b6", // ▶︎▶️
            "\u25c0", // ◀︎◀️
            "\u25fb", // ◻︎◻️
            "\u25fc", // ◼︎◼️
            "\u2600", // ☀︎☀️
            "\u2601", // ☁︎☁️
            "\u2602", // ☂︎☂️
            "\u2603", // ☃︎☃️
            "\u2604", // ☄︎☄️
            "\u260e", // ☎︎☎️
            "\u2611", // ☑︎☑️
            "\u2618", // ☘︎☘️
            "\u261d", // ☝︎☝️
            "\u2620", // ☠︎☠️
            "\u2622", // ☢︎☢️
            "\u2623", // ☣︎☣️
            "\u2626", // ☦︎☦️
            "\u262a", // ☪︎☪️
            "\u262e", // ☮︎☮️
            "\u262f", // ☯︎☯️
            "\u2638", // ☸︎☸️
            "\u2639", // ☹︎☹️
            "\u263a", // ☺︎☺️
            "\u2640", // ♀︎♀️
            "\u2642", // ♂︎♂️
            "\u265f", // ♟︎♟️
            "\u2660", // ♠︎♠️
            "\u2663", // ♣︎♣️
            "\u2665", // ♥︎♥️
            "\u2666", // ♦︎♦️
            "\u2668", // ♨︎♨️
            "\u267b", // ♻︎♻️
            "\u267e", // ♾︎♾️
            "\u2692", // ⚒︎⚒️
            "\u2694", // ⚔︎⚔️
            "\u2695", // ⚕︎⚕️
            "\u2696", // ⚖︎⚖️
            "\u2697", // ⚗︎⚗️
            "\u2699", // ⚙︎⚙️
            "\u269b", // ⚛︎⚛️
            "\u269c", // ⚜︎⚜️
            "\u26a0", // ⚠︎⚠️
            "\u26a7", // ⚧︎⚧️
            "\u26b0", // ⚰︎⚰️
            "\u26b1", // ⚱︎⚱️
            "\u26c8", // ⛈︎⛈️
            "\u26cf", // ⛏︎⛏️
            "\u26d1", // ⛑︎⛑️
            "\u26d3", // ⛓︎⛓️
            "\u26e9", // ⛩︎⛩️
            "\u26f0", // ⛰︎⛰️
            "\u26f1", // ⛱︎⛱️
            "\u26f4", // ⛴︎⛴️
            "\u26f7", // ⛷︎⛷️
            "\u26f8", // ⛸︎⛸️
            "\u26f9", // ⛹︎⛹️
            "\u2702", // ✂︎✂️
            "\u2708", // ✈︎✈️
            "\u2709", // ✉︎✉️
            "\u270c", // ✌︎✌️
            "\u270d", // ✍︎✍️
            "\u270f", // ✏︎✏️
            "\u2712", // ✒︎✒️
            "\u2714", // ✔︎✔️
            "\u2716", // ✖︎✖️
            "\u271d", // ✝︎✝️
            "\u2721", // ✡︎✡️
            "\u2733", // ✳︎✳️
            "\u2734", // ✴︎✴️
            "\u2744", // ❄︎❄️
            "\u2747", // ❇︎❇️
            "\u2763", // ❣︎❣️
            "\u2764", // ❤︎❤️
            "\u27a1", // ➡︎➡️
            "\u2934", // ⤴︎⤴️
            "\u2935", // ⤵︎⤵️
            "\u2b05", // ⬅︎⬅️
            "\u2b06", // ⬆︎⬆️
            "\u2b07", // ⬇︎⬇️
            "\u3030", // 〰︎〰️
            "\u303d", // 〽︎〽️
            "\u3297", // ㊗︎㊗️
            "\u3299", // ㊙︎㊙️
            "\ud83c\udd70", // 🅰︎🅰️
            "\ud83c\udd71", // 🅱︎🅱️
            "\ud83c\udd7e", // 🅾︎🅾️
            "\ud83c\udd7f", // 🅿︎🅿️
            "\ud83c\ude02", // 🈂︎🈂️
            "\ud83c\ude37", // 🈷︎🈷️
            "\ud83c\udf21", // 🌡︎🌡️
            "\ud83c\udf24", // 🌤︎🌤️
            "\ud83c\udf25", // 🌥︎🌥️
            "\ud83c\udf26", // 🌦︎🌦️
            "\ud83c\udf27", // 🌧︎🌧️
            "\ud83c\udf28", // 🌨︎🌨️
            "\ud83c\udf29", // 🌩︎🌩️
            "\ud83c\udf2a", // 🌪︎🌪️
            "\ud83c\udf2b", // 🌫︎🌫️
            "\ud83c\udf2c", // 🌬︎🌬️
            "\ud83c\udf36", // 🌶︎🌶️
            "\ud83c\udf7d", // 🍽︎🍽️
            "\ud83c\udf96", // 🎖︎🎖️
            "\ud83c\udf97", // 🎗︎🎗️
            "\ud83c\udf99", // 🎙︎🎙️
            "\ud83c\udf9a", // 🎚︎🎚️
            "\ud83c\udf9b", // 🎛︎🎛️
            "\ud83c\udf9e", // 🎞︎🎞️
            "\ud83c\udf9f", // 🎟︎🎟️
            "\ud83c\udfcb", // 🏋︎🏋️
            "\ud83c\udfcc", // 🏌︎🏌️
            "\ud83c\udfcd", // 🏍︎🏍️
            "\ud83c\udfce", // 🏎︎🏎️
            "\ud83c\udfd4", // 🏔︎🏔️
            "\ud83c\udfd5", // 🏕︎🏕️
            "\ud83c\udfd6", // 🏖︎🏖️
            "\ud83c\udfd7", // 🏗︎🏗️
            "\ud83c\udfd8", // 🏘︎🏘️
            "\ud83c\udfd9", // 🏙︎🏙️
            "\ud83c\udfda", // 🏚︎🏚️
            "\ud83c\udfdb", // 🏛︎🏛️
            "\ud83c\udfdc", // 🏜︎🏜️
            "\ud83c\udfdd", // 🏝︎🏝️
            "\ud83c\udfde", // 🏞︎🏞️
            "\ud83c\udfdf", // 🏟︎🏟️
            "\ud83c\udff3", // 🏳︎🏳️
            "\ud83c\udff5", // 🏵︎🏵️
            "\ud83c\udff7", // 🏷︎🏷️
            "\ud83d\udc3f", // 🐿︎🐿️
            "\ud83d\udc41", // 👁︎👁️
            "\ud83d\udcfd", // 📽︎📽️
            "\ud83d\udd49", // 🕉︎🕉️
            "\ud83d\udd4a", // 🕊︎🕊️
            "\ud83d\udd6f", // 🕯︎🕯️
            "\ud83d\udd70", // 🕰︎🕰️
            "\ud83d\udd73", // 🕳︎🕳️
            "\ud83d\udd74", // 🕴︎🕴️
            "\ud83d\udd75", // 🕵︎🕵️
            "\ud83d\udd76", // 🕶︎🕶️
            "\ud83d\udd77", // 🕷︎🕷️
            "\ud83d\udd78", // 🕸︎🕸️
            "\ud83d\udd79", // 🕹︎🕹️
            "\ud83d\udd87", // 🖇︎🖇️
            "\ud83d\udd8a", // 🖊︎🖊️
            "\ud83d\udd8b", // 🖋︎🖋️
            "\ud83d\udd8c", // 🖌︎🖌️
            "\ud83d\udd8d", // 🖍︎🖍️
            "\ud83d\udd90", // 🖐︎🖐️
            "\ud83d\udda5", // 🖥︎🖥️
            "\ud83d\udda8", // 🖨︎🖨️
            "\ud83d\uddb1", // 🖱︎🖱️
            "\ud83d\uddb2", // 🖲︎🖲️
            "\ud83d\uddbc", // 🖼︎🖼️
            "\ud83d\uddc2", // 🗂︎🗂️
            "\ud83d\uddc3", // 🗃︎🗃️
            "\ud83d\uddc4", // 🗄︎🗄️
            "\ud83d\uddd1", // 🗑︎🗑️
            "\ud83d\uddd2", // 🗒︎🗒️
            "\ud83d\uddd3", // 🗓︎🗓️
            "\ud83d\udddc", // 🗜︎🗜️
            "\ud83d\udddd", // 🗝︎🗝️
            "\ud83d\uddde", // 🗞︎🗞️
            "\ud83d\udde1", // 🗡︎🗡️
            "\ud83d\udde3", // 🗣︎🗣️
            "\ud83d\udde8", // 🗨︎🗨️
            "\ud83d\uddef", // 🗯︎🗯️
            "\ud83d\uddf3", // 🗳︎🗳️
            "\ud83d\uddfa", // 🗺︎🗺️
            "\ud83d\udecb", // 🛋︎🛋️
            "\ud83d\udecd", // 🛍︎🛍️
            "\ud83d\udece", // 🛎︎🛎️
            "\ud83d\udecf", // 🛏︎🛏️
            "\ud83d\udee0", // 🛠︎🛠️
            "\ud83d\udee1", // 🛡︎🛡️
            "\ud83d\udee2", // 🛢︎🛢️
            "\ud83d\udee3", // 🛣︎🛣️
            "\ud83d\udee4", // 🛤︎🛤️
            "\ud83d\udee5", // 🛥︎🛥️
            "\ud83d\udee9", // 🛩︎🛩️
            "\ud83d\udef0", // 🛰︎🛰️
            "\ud83d\udef3", // 🛳︎🛳️
    };

    private enum Variation {
        DEFAULT(false, ""),
        MONO(false, "\ufe0e"),
        COLOR(true, "\ufe0f");

        final boolean colorByDefault;
        final String suffix;

        Variation(boolean colorByDefault, String suffix) {
            this.colorByDefault = colorByDefault;
            this.suffix = suffix;
        }
    }

    public static void main(String[] args) {
        // Platform-specific tricks
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            // Many emoji on Linux don't have monochrome variants
            Arrays.fill(SYMBOLS, 28, 37, null);
            Arrays.fill(SYMBOLS, 84, 95, null);
            Arrays.fill(SYMBOLS, 118, SYMBOLS.length, null);
        } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            // Many emoji on macOS don't have monochrome variants
            Arrays.fill(SYMBOLS, 28, 36, null);
            Arrays.fill(SYMBOLS, 84, 95, null);
            Arrays.fill(SYMBOLS, 128, SYMBOLS.length, null);
        }

        BufferedImage img = new BufferedImage(IMG_WIDTH, IMG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        String errors = "";
        for (int i = 0; i < SYMBOLS.length; i++) {
            if (SYMBOLS[i] == null) continue;
            errors += test(img, i, Variation.DEFAULT);
            errors += test(img, i, Variation.MONO);
            errors += test(img, i, Variation.COLOR);
        }
        if (!errors.isEmpty()) throw new RuntimeException(errors);
    }

    private static String test(BufferedImage img, int i, Variation variation) {
        String symbol = SYMBOLS[i];
        draw(img, symbol + variation.suffix);
        String error = check(img, variation.colorByDefault);
        if (error != null) {
            try {
                ImageIO.write(img, "PNG", new File("EmojiVariation" + i + "-" + variation + ".png"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            return "\n#" + i + "-" + variation + ": " + error;
        }
        return "";
    }

    private static void draw(Image img, String symbol) {
        Graphics2D g = (Graphics2D) img.getGraphics();
        g.setColor(Color.white);
        g.fillRect(0, 0, IMG_WIDTH, IMG_HEIGHT);
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 50));
        g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(SYMBOL_COLOR);
        g.drawString(symbol, 2, 42);
        g.dispose();
    }

    private static String check(BufferedImage img, boolean expectColor) {
        boolean rendered = false;
        boolean color = false;
        for (int x = 0; x < IMG_WIDTH; x++) {
            for (int y = 0; y < IMG_HEIGHT; y++) {
                int rgb = img.getRGB(x, y);
                if (rgb != Color.white.getRGB()) {
                    rendered = true;
                    if (rgb != SYMBOL_COLOR.getRGB()) color = true;
                }
            }
        }
        if (!rendered) {
            return "Empty image";
        } else if (color != expectColor) {
            return expectColor ? "Expected color but rendered mono" : "Expected mono but rendered color";
        }
        return null;
    }
}
