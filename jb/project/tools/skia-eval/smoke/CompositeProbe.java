import java.awt.*; import java.awt.font.*; import java.awt.image.BufferedImage;
import sun.font.*; import sun.java2d.SunGraphics2D; import sun.java2d.loops.FontInfo;
public class CompositeProbe {
    public static void main(String[] a) throws Exception {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB_PRE);
        SunGraphics2D g = (SunGraphics2D) img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), "Hamburg 123");
        FontInfo info = g.getGVFontInfo(gv.getFont(), gv.getFontRenderContext());
        System.out.println("string strike: " + g.getFontInfo().fontStrike.getClass().getName() + " drawable " + (g.getFontInfo().fontStrike instanceof SKDrawableStrike s ? s.drawsBySkia() + "/" + s.skiaScalerContext() : "no"));
        GlyphList gl = GlyphList.getInstance();
        gl.setFromGlyphVector(info, gv, 10, 30);
        Object strike = gl.getFontStrike();
        System.out.println("gv strike: " + strike.getClass().getName() + " (holder " + gl.getStrike().getClass().getName() + ")");
        int n = gl.getNumGlyphs(); int[] codes = gl.getGlyphCodes();
        if (strike instanceof CompositeStrike cs) {
            System.out.println("slots " + cs.numSlots());
            for (int i = 0; i < n; i++) {
                int c = codes[i]; int slot = cs.slotOf(c), glyph = cs.glyphOf(c);
                FontStrike fs = cs.strikeForSlot(slot);
                System.out.printf("  code %08x slot %d glyph %d strike %s drawable %s%n", c, slot, glyph, fs.getClass().getSimpleName(), fs instanceof SKDrawableStrike s ? s.drawsBySkia() + "/" + s.skiaScalerContext() : "no");
            }
        }
        gl.dispose();
    }
}
