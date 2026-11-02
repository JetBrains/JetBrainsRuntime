import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.io.File;

/**
 * Line metrics (ascent, descent, leading, height) in device pixels at a display scale for the
 * configured scaler, whole-pixel and fractional, for file fonts and installed families.
 *   java [scaler properties] Metrics <scale> <font file or family> <size>...
 */
public class Metrics {
    public static void main(String[] a) throws Exception {
        double scale = Double.parseDouble(a[0]);
        Font base = new File(a[1]).isFile() ? Font.createFont(Font.TRUETYPE_FONT, new File(a[1])) : new Font(a[1], Font.PLAIN, 12);
        System.out.println("scaler " + System.getProperty("sun.java2d.font.scaler", "freetype") + ", " + a[1] + " at scale " + scale);
        for (int i = 2; i < a.length; i++) {
            Font font = base.deriveFont(Float.parseFloat(a[i]));
            for (boolean frac : new boolean[] {false, true}) {
                FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, frac);
                LineMetrics lm = font.getLineMetrics("Hg", frc);
                System.out.printf("  size %s %-11s ascent %7.3f  descent %7.3f  leading %6.3f  height %7.3f  (user px)%n", a[i], frac ? "fractional" : "whole-pixel",
                                  lm.getAscent(), lm.getDescent(), lm.getLeading(), lm.getHeight());
            }
        }
    }
}
