import java.awt.*;
import java.awt.font.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** Glyph image sizes (pixel bounds of each glyph at the device transform) for IDE fonts and sizes at 1x and 2x: share over 32 and over 64 px in either dimension. */
public class GlyphSizeCensus {
    public static void main(String[] args) {
        String text = "";
        for (char c = 33; c < 127; c++) text += c;
        text += "äöüÄÖÜßéèêñçø→←≠≤≥∑∆ЖЯФ日本語中文가";
        String[] families = {"Inter", "JetBrains Mono", "Helvetica Neue", ".AppleSystemUIFont"};
        int[] sizes = {11, 12, 13, 14, 16, 18, 20, 24};
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        System.out.printf("%-20s %4s %5s | %6s %6s %6s | %7s %7s%n", "font", "pt", "scale", "maxW", "maxH", "medH", ">32px", ">64px");
        for (String fam : families) for (int scale : new int[]{1, 2}) for (int pt : sizes) {
            Font f = new Font(fam, Font.PLAIN, pt);
            FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, true);
            GlyphVector gv = f.createGlyphVector(frc, text);
            int n = gv.getNumGlyphs(), maxW = 0, maxH = 0, over32 = 0, over64 = 0; int[] hs = new int[n];
            for (int i = 0; i < n; i++) {
                Rectangle r = gv.getGlyphPixelBounds(i, frc, 0, 0);
                maxW = Math.max(maxW, r.width); maxH = Math.max(maxH, r.height); hs[i] = r.height;
                if (r.width > 32 || r.height > 32) over32++;
                if (r.width > 64 || r.height > 64) over64++;
            }
            java.util.Arrays.sort(hs);
            System.out.printf("%-20s %4d %5d | %6d %6d %6d | %6.0f%% %6.0f%%%n", fam, pt, scale, maxW, maxH, hs[n / 2], 100.0 * over32 / n, 100.0 * over64 / n);
        }
    }
}
