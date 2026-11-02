import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.io.File;

/**
 * The device x of every glyph origin of the sheet text as Java lays it out at a display scale
 * (fractional metrics, the strike's advances), to compare with dwref --positions.
 *   java [scaler properties] Positions <scale> <font file>...
 */
public class Positions {
    static final String TEXT = "Quick fox jumps 1234567890 {}[]();:,. ∑∂Δ → ≠ ≤ ≥ äöü";

    public static void main(String[] a) throws Exception {
        double scale = Double.parseDouble(a[0]);
        for (int i = 1; i < a.length; i++) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, new File(a[i])).deriveFont(13f);
            FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, true);
            GlyphVector gv = font.createGlyphVector(frc, TEXT);
            float[] pos = gv.getGlyphPositions(0, gv.getNumGlyphs(), null);
            System.out.println("  " + new File(a[i]).getName() + " at scale " + scale + ", device x of each glyph origin:");
            StringBuilder sb = new StringBuilder("   ");
            for (int g = 0; g < gv.getNumGlyphs(); g++) {
                sb.append(String.format(" %.4f", (4 + pos[2 * g]) * scale));
            }
            System.out.println(sb);
        }
    }
}
