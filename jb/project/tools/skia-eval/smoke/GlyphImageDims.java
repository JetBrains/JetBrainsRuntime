import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.lang.reflect.*;
import java.util.*;

/** GlyphInfo image dimensions (width, height, rowBytes, sub-pixel resolution) of a strike's glyphs, as the glyph caches see them. */
public class GlyphImageDims {
    public static void main(String[] args) throws Exception {
        Class<?> fu = Class.forName("sun.font.FontUtilities"); Method getFont2D = fu.getMethod("getFont2D", Font.class);
        getFont2D.invoke(null, new Font("Dialog", Font.PLAIN, 12));   // loads the font manager and libfontmanager before StrikeCache initializes
        Class<?> sc = Class.forName("sun.font.StrikeCache");
        Method gw = sc.getDeclaredMethod("getGlyphWidth", long.class), gh = sc.getDeclaredMethod("getGlyphHeight", long.class), grb = sc.getDeclaredMethod("getGlyphRowBytes", long.class);
        Method gsx = sc.getDeclaredMethod("getGlyphSubpixelResolutionX", long.class), gsy = sc.getDeclaredMethod("getGlyphSubpixelResolutionY", long.class);
        Method gfmt = sc.getDeclaredMethod("getGlyphFormat", long.class);
        for (Method m : new Method[]{gw, gh, grb, gsx, gsy, gfmt}) m.setAccessible(true);
        final boolean verbose = System.getProperty("verbose") != null;
        String text = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (String spec : args) {   // family:pt:scale
            String[] p = spec.split(":"); Font font = new Font(p[0], Font.PLAIN, Integer.parseInt(p[1])); int scale = Integer.parseInt(p[2]);
            FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            Object font2D = getFont2D.invoke(null, font);
            Method getStrike = font2D.getClass().getMethod("getStrike", Font.class, FontRenderContext.class);
            Object strike = getStrike.invoke(font2D, font, frc);
            Method ptr = null; for (Class<?> c = strike.getClass(); c != null && ptr == null; c = c.getSuperclass()) try { ptr = c.getDeclaredMethod("getGlyphImagePtr", int.class); } catch (NoSuchMethodException e) {}
            ptr.setAccessible(true);
            Method charToGlyph = null; for (Class<?> c = font2D.getClass(); c != null && charToGlyph == null; c = c.getSuperclass()) try { charToGlyph = c.getDeclaredMethod("charToGlyph", int.class); } catch (NoSuchMethodException e) {}
            charToGlyph.setAccessible(true);
            int maxW = 0, maxH = 0, maxRB = 0, over = 0; String rx = "?", ry = "?";
            for (char ch : text.toCharArray()) {
                int code = (Integer) charToGlyph.invoke(font2D, (int) ch);
                long gi = (Long) ptr.invoke(strike, code);
                int w = (Character) gw.invoke(null, gi), h = (Character) gh.invoke(null, gi), rb = (Character) grb.invoke(null, gi);
                rx = "" + gsx.invoke(null, gi); ry = "" + gsy.invoke(null, gi);
                int fmt = (Byte) gfmt.invoke(null, gi);
                if (verbose) System.out.printf("   '%c' %dx%d rowBytes %d format %d%n", ch, w, h, rb, fmt);
                maxW = Math.max(maxW, w); maxH = Math.max(maxH, h); maxRB = Math.max(maxRB, rb); if (w > 32 || h > 32) over++;
            }
            System.out.printf("%-26s strike %-22s maxW %3d maxH %3d maxRowBytes %3d subpixel %sx%s  over32: %d/%d%n", spec, strike.getClass().getSimpleName(), maxW, maxH, maxRB, rx, ry, over, text.length());
        }
        
    }
}
