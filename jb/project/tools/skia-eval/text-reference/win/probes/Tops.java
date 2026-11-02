import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.io.File;

/**
 * Per glyph of the sheet text at a display scale: the device x of the origin, the glyph image's top
 * row (device pixels, from the pixel bounds) and whether that top is odd — i.e. whether the mask's
 * baseline sits on a half DIP at 200 %.
 *   java [scaler properties] Tops <scale> <font file>...
 */
public class Tops {
    static final String TEXT = "Quick fox jumps 1234567890 {}[]();:,. ∑∂Δ → ≠ ≤ ≥ äöü";

    public static void main(String[] a) throws Exception {
        double scale = Double.parseDouble(a[0]);
        for (int i = 1; i < a.length; i++) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, new File(a[i])).deriveFont(13f);
            FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, true);
            GlyphVector gv = font.createGlyphVector(frc, TEXT);
            System.out.println("  " + new File(a[i]).getName() + " at scale " + scale + " (char: device x, top row, odd?)");
            StringBuilder sb = new StringBuilder("   ");
            for (int g = 0; g < gv.getNumGlyphs(); g++) {
                Rectangle b = gv.getGlyphPixelBounds(g, frc, 4f * (float) scale, 19f * (float) scale);
                char ch = TEXT.charAt(gv.getGlyphCharIndex(g));
                sb.append(String.format(" %c:%.1f/%d%s", ch == ' ' ? '_' : ch, gv.getGlyphPosition(g).getX() * scale + 4 * scale, b.y, (b.y & 1) != 0 ? "*" : ""));
            }
            System.out.println(sb);
        }
    }
}
