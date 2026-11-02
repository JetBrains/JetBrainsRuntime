import java.awt.*; import java.awt.font.*; import java.awt.image.BufferedImage; import java.lang.reflect.*;
import sun.font.*; import sun.java2d.SunGraphics2D; import sun.java2d.loops.FontInfo;
public class CompositeProbe2 {
    public static void main(String[] a) throws Exception {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        BufferedImage img = new BufferedImage(400, 50, BufferedImage.TYPE_INT_ARGB_PRE);
        SunGraphics2D g = (SunGraphics2D) img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setFont(font); g.setColor(Color.BLACK);
        Method counter = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterGlyphsBySkia");
        long c0 = (long) counter.invoke(null);
        g.drawString("Hamburg 123", 10, 30); long c1 = (long) counter.invoke(null);
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), "Hamburg 123");
        g.drawGlyphVector(gv, 10, 30); long c2 = (long) counter.invoke(null);
        System.out.println("textpipe " + g.textpipe.getClass().getName() + "; string +" + (c1 - c0) + ", glyph vector +" + (c2 - c1));
        // what SKGlyphContexts.of says for the glyph vector's list
        FontInfo info = g.getGVFontInfo(gv.getFont(), gv.getFontRenderContext());
        GlyphList gl = GlyphList.getInstance(); gl.setFromGlyphVector(info, gv, 10, 30);
        Class<?> ctx = Class.forName("sun.java2d.skia.SKGlyphContexts"); Method of = ctx.getDeclaredMethod("of", GlyphList.class); of.setAccessible(true);
        Object runs = of.invoke(null, gl);
        System.out.println("of(gl) = " + runs + (runs != null ? " contexts " + java.util.Arrays.toString((long[]) runs.getClass().getMethod("contexts").invoke(runs)) : ""));
        gl.dispose();
    }
}
