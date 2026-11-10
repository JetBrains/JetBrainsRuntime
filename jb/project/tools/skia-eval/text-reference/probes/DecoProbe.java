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
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The decorations' bands under transforms, the counterpart of decoprobe.swift (2026-11-10).
 *
 *   java DecoProbe render <probes dir> <out dir>         TextLayout's underline and strikethrough, as decoprobe.swift's cases
 *   java DecoProbe analyze <probes dir> <dir> <route>... each route's decoration measured in the text's frame
 *
 * Measured per case: the decoration's pixels (the decorated line minus the plain one) mapped back to the text's
 * frame (u along the baseline, v below it, in user units), over the middle 80 % of the line: the band's centre
 * (coverage-weighted v), its thickness (the coverage's area over the length, in user units), and its profile
 * across (coverage density per sixteenth of a user unit — 1.0 a fully covered band, the edges show the snapping).
 */
public class DecoProbe {
    static final int SIZE = 640;
    static final String TEXT = "x          x";   // spaces between: the middle of the line holds only the decorations

    record Case(String name, AffineTransform transform) {}
    record FontCase(String coreTextName, String family, float size) {}

    public static void main(String[] args) throws Exception {
        Path probes = Path.of(args[1]);
        List<Case> cases = new ArrayList<>();
        for (String[] f : lines(probes.resolve("decocases.txt"))) {
            double[] v = new double[6];
            for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(f[i + 1]);
            cases.add(new Case(f[0], new AffineTransform(v[0], v[1], v[2], v[3], v[4], v[5])));
        }
        List<FontCase> fonts = new ArrayList<>();
        for (String[] f : lines(probes.resolve("decofonts.txt"))) {
            fonts.add(new FontCase(f[0], f[1], Float.parseFloat(f[2])));
        }
        if (args[0].equals("render")) {
            render(cases, fonts, Path.of(args[2]));
        } else {
            analyze(cases, fonts, Path.of(args[2]), List.of(args).subList(3, args.length));
        }
    }

    static List<String[]> lines(Path file) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String l : Files.readAllLines(file)) {
            if (!l.isBlank() && !l.startsWith("#")) out.add(l.trim().split("\\s+"));
        }
        return out;
    }

    static void render(List<Case> cases, List<FontCase> fonts, Path out) throws IOException {
        Files.createDirectories(out);
        StringBuilder advances = new StringBuilder();
        for (FontCase f : fonts) {
            for (Case c : cases) {
                String stem = f.family + "-" + fmt(f.size) + "-" + c.name;
                for (String decoration : new String[] {"plain", "underline", "strike"}) {
                    Map<TextAttribute, Object> attributes = new HashMap<>();
                    attributes.put(TextAttribute.FAMILY, f.family.replace('_', ' '));
                    attributes.put(TextAttribute.SIZE, f.size);
                    if (decoration.equals("underline")) attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
                    if (decoration.equals("strike")) attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
                    BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, SIZE, SIZE);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    g.setColor(Color.BLACK);
                    g.setTransform(c.transform);
                    if (Boolean.getBoolean("decoprobe.iterator")) {   // the attributed pipe: SkParagraph under text.layout=skia
                        java.text.AttributedString as = new java.text.AttributedString(TEXT, attributes);
                        g.drawString(as.getIterator(), 0f, 0f);
                    } else {
                        new TextLayout(TEXT, new Font(attributes), g.getFontRenderContext()).draw(g, 0, 0);
                    }
                    g.dispose();
                    ImageIO.write(image, "png", out.resolve(stem + "-java-" + decoration + ".png").toFile());
                }
            }
        }
    }

    static void analyze(List<Case> cases, List<FontCase> fonts, Path dir, List<String> routes) throws Exception {
        Map<String, Double> advance = new HashMap<>();
        for (String[] m : lines(dir.resolve("metrics.txt"))) {
            // family size xHeight x os2StrikeoutPositionSize p s underlinePosition p underlineThickness t advance a
            int o = m[5].equals("none") ? -1 : 0;
            advance.put(m[0] + "-" + m[1], Double.parseDouble(m[12 + o]));
            System.out.printf(Locale.ROOT, "%s %s: x-height %.3f, OS/2 strikeout %s, CoreText's underline position %.3f, thickness %.3f%n", m[0], m[1],
                    Double.parseDouble(m[3]), o < 0 ? "none" : m[5] + " size " + m[6], Double.parseDouble(m[8 + o]), Double.parseDouble(m[10 + o]));
        }
        for (FontCase f : fonts) {
            String key = f.family + "-" + fmt(f.size);
            for (Case c : cases) {
                for (String route : routes) {
                    String[] parts = route.split(":");   // e.g. ct:underline, ak:strike, java:underline (in dir/<subdir>)
                    String source = parts[0], decoration = parts[1];
                    Path base = dir.resolve(parts.length > 2 ? parts[2] : ".");
                    File plain = base.resolve(key + "-" + c.name + "-" + source + "-plain.png").toFile();
                    File decorated = base.resolve(key + "-" + c.name + "-" + source + "-" + decoration + ".png").toFile();
                    if (!plain.exists() || !decorated.exists()) continue;
                    System.out.println(key + " " + c.name + " " + route + ": " + measure(ImageIO.read(plain), ImageIO.read(decorated), c.transform, advance.get(key), f.size));
                }
            }
        }
    }

    static String measure(BufferedImage plain, BufferedImage decorated, AffineTransform t, double advance, float size) throws NoninvertibleTransformException {
        AffineTransform inverse = t.createInverse();
        double det = Math.abs(t.getDeterminant()), u0 = 0.1 * advance, u1 = 0.9 * advance, length = u1 - u0;
        double sum = 0, sumV = 0, lighter = 0;
        // a baseline along device rows or columns: the mean coverage of each device line across it, counted from the
        // device baseline's own line (the snapping shows: whole lines at 1.0, or a band split by the baseline's phase)
        boolean horizontal = t.getShearY() == 0 && t.getShearX() == 0, vertical = t.getScaleX() == 0 && t.getScaleY() == 0;
        Point2D origin = t.transform(new Point2D.Double(0, 0), null);
        int baseLine = (int) Math.floor(horizontal ? origin.getY() : origin.getX());
        double[] lineSum = new double[SIZE];
        int[] lineCount = new int[SIZE];
        Point2D.Double p = new Point2D.Double();
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            p.setLocation(x + 0.5, y + 0.5);
            inverse.transform(p, p);
            if (p.x < u0 || p.x > u1 || p.y < -size || p.y >= size) continue;
            double c = ((plain.getRGB(x, y) >> 8 & 0xff) - (decorated.getRGB(x, y) >> 8 & 0xff)) / 255.0;
            int line = horizontal ? y : x;
            lineCount[line]++;
            if (c < 0) { lighter -= c; continue; }
            lineSum[line] += c;
            sum += c;
            sumV += c * p.y;
        }
        if (sum == 0) return "nothing drawn";
        double thickness = sum / det / length, centre = sumV / sum;
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "centre %+.3f thickness %.3f (band %+.3f..%+.3f)", centre, thickness, centre - thickness / 2, centre + thickness / 2));
        if (lighter > 0.01 * sum) s.append(String.format(Locale.ROOT, " lighter %.1f", lighter));
        if (horizontal || vertical) {
            s.append("  device lines from the baseline's:");
            for (int i = 0; i < SIZE; i++) {
                double mean = lineCount[i] > 0 ? lineSum[i] / lineCount[i] : 0;
                if (mean > 0.02) s.append(String.format(Locale.ROOT, " %+d=%.2f", i - baseLine, mean));
            }
        }
        return s.toString();
    }

    static String fmt(float size) {
        return size == Math.rint(size) ? Integer.toString((int) size) : Float.toString(size);
    }
}
