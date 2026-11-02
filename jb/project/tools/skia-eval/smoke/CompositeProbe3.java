import java.awt.*; import java.awt.font.*; import java.awt.image.BufferedImage;
import sun.font.*; import sun.java2d.SunGraphics2D; import sun.java2d.loops.FontInfo;
public class CompositeProbe3 {
    public static void main(String[] a) throws Exception {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        BufferedImage img = new BufferedImage(400, 50, BufferedImage.TYPE_INT_ARGB_PRE);
        SunGraphics2D g = (SunGraphics2D) img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setFont(font);
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), "Hamburg 123");
        FontInfo info = g.getGVFontInfo(gv.getFont(), gv.getFontRenderContext());
        GlyphList gl = GlyphList.getInstance(); gl.setFromGlyphVector(info, gv, 10, 30);
        Object strike = gl.getFontStrike(); int[] codes = gl.getGlyphCodes(); int n = gl.getNumGlyphs();
        System.out.println("strike " + strike.getClass().getName() + " n " + n + " codes.length " + codes.length + " INVISIBLE " + Integer.toHexString(CharToGlyphMapper.INVISIBLE_GLYPH_ID));
        CompositeStrike cs = (CompositeStrike) strike;
        long[] contexts = new long[cs.numSlots()];
        for (int i = 0; i < n; i++) {
            int code = codes[i];
            if (code == CharToGlyphMapper.INVISIBLE_GLYPH_ID) { System.out.println(i + ": invisible"); continue; }
            int slot = cs.slotOf(code), glyph = cs.glyphOf(code);
            if (slot < 0 || slot >= contexts.length || glyph < 0 || glyph > 0xffffff) { System.out.println(i + ": REJECT slot " + slot + " glyph " + glyph + " of " + contexts.length); return; }
            if (contexts[slot] == 0) {
                FontStrike fs = cs.strikeForSlot(slot);
                if (!(fs instanceof SKDrawableStrike s)) { System.out.println(i + ": REJECT not drawable " + fs.getClass()); return; }
                contexts[slot] = s.drawsBySkia() ? s.skiaScalerContext() : 0;
                System.out.println(i + ": slot " + slot + " context " + contexts[slot] + " drawsBySkia " + s.drawsBySkia());
                if (contexts[slot] == 0) { System.out.println("REJECT zero context"); return; }
            }
        }
        System.out.println("would return runs; contexts " + java.util.Arrays.toString(contexts));
        gl.dispose();
    }
}
