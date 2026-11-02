import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.io.File;

/**
 * String widths in device pixels at a display scale, with whole-pixel (fm off) and fractional
 * metrics, for the configured scaler: how far layouts drift between scalers.
 *   java [scaler properties] Widths <scale> <font file> <size> <text>...
 */
public class Widths {
    public static void main(String[] a) throws Exception {
        double scale = Double.parseDouble(a[0]);
        int style = (Boolean.getBoolean("probe.bold") ? Font.BOLD : 0) | (Boolean.getBoolean("probe.italic") ? Font.ITALIC : 0);
        Font font = (new File(a[1]).isFile() ? Font.createFont(Font.TRUETYPE_FONT, new File(a[1])) : new Font(a[1], Font.PLAIN, 12)).deriveFont(style, Float.parseFloat(a[2]));
        System.out.println("scaler " + System.getProperty("sun.java2d.font.scaler", "freetype") + ", " + new File(a[1]).getName() + " " + a[2] + " style " + style + " at scale " + scale);
        for (int i = 3; i < a.length; i++) {
            FontRenderContext whole = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, false);
            FontRenderContext frac = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, true);
            double w1 = font.getStringBounds(a[i], whole).getWidth() * scale, w2 = font.getStringBounds(a[i], frac).getWidth() * scale;
            System.out.printf("  %-28s whole-pixel %8.2f px   fractional %8.2f px%n", a[i], w1, w2);
        }
    }
}
