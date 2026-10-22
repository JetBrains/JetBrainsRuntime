import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Rendering comparison matrix: paint x shape (x composite, transform, clip, AA) cells, rendered
 * identically through the software loops (BufferedImage), Metal and Skia (VolatileImage) and
 * written as one sheet per backend, plus a cell index. MatrixDiff compares the sheets per cell.
 *
 *   java RenderMatrix software|metal|skia      (skia: -Dsun.java2d.skia=true)
 */
public class RenderMatrix {
    static final int CELL = 64, COLS = 16;

    enum Paint { SOLID, SOLID_A50, GRADIENT, GRADIENT_CYCLIC, LIN3_NOCYCLE, LIN3_REFLECT, LIN3_REPEAT, LIN3_PARTIAL, LIN_ALPHA, RADIAL, RADIAL_FOCUS, RADIAL_REPEAT, TEXTURE }
    enum Shape { FILL_RECT, DRAW_RECT, DRAW_LINE, POLYLINE, FILL_OVAL, DRAW_OVAL, FILL_RRECT, DRAW_RRECT_W3, STROKE_W5, DASHED, FILL_PATH, TEXT_GRAY, TEXT_LCD, TEXT_NOAA, IMAGE, IMAGE_SCALED, IMAGE_ROT, COPY_AREA, CLEAR_RECT }
    enum Comp { SRC_OVER, ALPHA50, SRC, XOR, CLEAR, SRC_IN }
    enum Xform { ID, TRANSLATE_HALF, SCALE_2, SCALE_1_5, ROTATE_30, SHEAR }
    enum Clip { NONE, RECT, ELLIPSE, TWO_RECTS }

    record Cell(String name, Paint paint, Shape shape, Comp comp, Xform xform, Clip clip, boolean aa) {}

    static List<Cell> cells() {
        List<Cell> c = new ArrayList<>();
        // every paint x every shape, AA off and on, plain state
        for (Paint p : Paint.values()) for (Shape s : Shape.values()) for (boolean aa : new boolean[]{false, true})
            c.add(new Cell(p + "/" + s + (aa ? "/AA" : ""), p, s, Comp.SRC_OVER, Xform.ID, Clip.NONE, aa));
        // composites x shapes (solid paint), AA both
        for (Comp k : Comp.values()) if (k != Comp.SRC_OVER) for (Shape s : Shape.values()) for (boolean aa : new boolean[]{false, true}) {
            if (s == Shape.COPY_AREA) continue;   // Metal's AnyComposite copyArea throws RasterFormatException (pre-existing)
            c.add(new Cell("comp:" + k + "/" + s + (aa ? "/AA" : ""), Paint.SOLID_A50, s, k, Xform.ID, Clip.NONE, aa));
        }
        // composites x paints (fill rect + fill oval AA)
        for (Comp k : Comp.values()) for (Paint p : Paint.values())
            c.add(new Cell("comp:" + k + "/" + p + "/FILL_OVAL/AA", p, Shape.FILL_OVAL, k, Xform.ID, Clip.NONE, true));
        // transforms x shapes (solid), and x paints (fill rect)
        for (Xform x : Xform.values()) if (x != Xform.ID) {
            for (Shape s : Shape.values()) for (boolean aa : new boolean[]{false, true}) {
                if (s == Shape.COPY_AREA && x != Xform.TRANSLATE_HALF) continue;   // Java2D: "transformed copyArea not implemented"
                c.add(new Cell("xf:" + x + "/" + s + (aa ? "/AA" : ""), Paint.SOLID, s, Comp.SRC_OVER, x, Clip.NONE, aa));
            }
            for (Paint p : Paint.values())
                c.add(new Cell("xf:" + x + "/" + p + "/FILL_RECT", p, Shape.FILL_RECT, Comp.SRC_OVER, x, Clip.NONE, false));
        }
        // clips x shapes (solid) and x paints
        for (Clip cl : Clip.values()) if (cl != Clip.NONE) {
            for (Shape s : Shape.values()) for (boolean aa : new boolean[]{false, true})
                c.add(new Cell("clip:" + cl + "/" + s + (aa ? "/AA" : ""), Paint.SOLID, s, Comp.SRC_OVER, Xform.ID, cl, aa));
            for (Paint p : Paint.values())
                c.add(new Cell("clip:" + cl + "/" + p + "/FILL_RECT", p, Shape.FILL_RECT, Comp.SRC_OVER, Xform.ID, cl, false));
        }
        return c;
    }

    static BufferedImage srcImage;

    static java.awt.Paint makePaint(Paint p) {
        Color a = new Color(0xE05020), b = new Color(0x2050E0), cc = new Color(0x20C040);
        switch (p) {
            case SOLID: return new Color(0xC03030);
            case SOLID_A50: return new Color(0x30, 0x60, 0xC0, 128);
            case GRADIENT: return new GradientPaint(8, 8, a, 56, 56, b);
            case GRADIENT_CYCLIC: return new GradientPaint(16, 16, a, 32, 24, b, true);
            case LIN3_NOCYCLE: return new LinearGradientPaint(8, 0, 56, 0, new float[]{0f, 0.5f, 1f}, new Color[]{a, cc, b});
            case LIN3_REFLECT: return new LinearGradientPaint(20, 0, 36, 0, new float[]{0f, 0.5f, 1f}, new Color[]{a, cc, b}, MultipleGradientPaint.CycleMethod.REFLECT);
            case LIN3_REPEAT: return new LinearGradientPaint(20, 0, 36, 0, new float[]{0f, 0.5f, 1f}, new Color[]{a, cc, b}, MultipleGradientPaint.CycleMethod.REPEAT);
            case LIN3_PARTIAL: return new LinearGradientPaint(8, 0, 56, 0, new float[]{0.2f, 0.4f, 0.9f}, new Color[]{a, cc, b});
            case LIN_ALPHA: return new LinearGradientPaint(8, 0, 56, 0, new float[]{0f, 1f}, new Color[]{new Color(0xE0, 0x50, 0x20, 0), new Color(0xE0, 0x50, 0x20, 255)});
            case RADIAL: return new RadialGradientPaint(32, 32, 24, new float[]{0f, 0.6f, 1f}, new Color[]{Color.WHITE, a, b});
            case RADIAL_FOCUS: return new RadialGradientPaint(new Point2D.Float(32, 32), 24, new Point2D.Float(20, 24), new float[]{0f, 1f}, new Color[]{Color.YELLOW, b}, MultipleGradientPaint.CycleMethod.NO_CYCLE);
            case RADIAL_REPEAT: return new RadialGradientPaint(32, 32, 10, new float[]{0f, 1f}, new Color[]{a, b}, MultipleGradientPaint.CycleMethod.REPEAT);
            case TEXTURE: return new TexturePaint(srcImage, new Rectangle(4, 4, 16, 16));
        }
        throw new IllegalStateException();
    }

    static void applyComposite(Graphics2D g, Comp k) {
        switch (k) {
            case SRC_OVER: break;
            case ALPHA50: g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)); break;
            case SRC: g.setComposite(AlphaComposite.Src); break;
            case XOR: g.setXORMode(new Color(0x00FF80)); break;
            case CLEAR: g.setComposite(AlphaComposite.Clear); break;
            case SRC_IN: g.setComposite(AlphaComposite.SrcIn); break;
        }
    }

    static void applyXform(Graphics2D g, Xform x) {
        switch (x) {
            case ID: break;
            case TRANSLATE_HALF: g.translate(0.5, 0.5); break;
            case SCALE_2: g.scale(2, 2); g.translate(-16, -16); break;
            case SCALE_1_5: g.scale(1.5, 1.5); g.translate(-10, -10); break;
            case ROTATE_30: g.rotate(Math.toRadians(30), 32, 32); break;
            case SHEAR: g.shear(0.3, 0); g.translate(-8, 0); break;
        }
    }

    static void applyClip(Graphics2D g, Clip c) {
        switch (c) {
            case NONE: break;
            case RECT: g.clipRect(10, 6, 40, 50); break;
            case ELLIPSE: g.clip(new Ellipse2D.Float(6, 6, 52, 52)); break;
            case TWO_RECTS: { Area a = new Area(new Rectangle(4, 4, 24, 56)); a.add(new Area(new Rectangle(36, 4, 24, 56))); g.clip(a); break; }
        }
    }

    static void drawShape(Graphics2D g, Shape s) {
        switch (s) {
            case FILL_RECT: g.fillRect(10, 10, 44, 44); break;
            case DRAW_RECT: g.drawRect(10, 10, 43, 43); break;
            case DRAW_LINE: g.drawLine(6, 58, 58, 10); g.drawLine(6, 6, 58, 20); break;
            case POLYLINE: g.drawPolyline(new int[]{6, 20, 34, 48, 58}, new int[]{50, 14, 46, 10, 40}, 5); break;
            case FILL_OVAL: g.fillOval(8, 8, 48, 40); break;
            case DRAW_OVAL: g.drawOval(8, 8, 47, 39); break;
            case FILL_RRECT: g.fill(new RoundRectangle2D.Float(8, 8, 48, 48, 16, 16)); break;
            case DRAW_RRECT_W3: g.setStroke(new BasicStroke(3f)); g.draw(new RoundRectangle2D.Float(8, 8, 48, 48, 16, 16)); break;
            case STROKE_W5: g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Line2D.Float(8, 56, 56, 8)); g.draw(new Line2D.Float(8, 8, 30, 56)); break;
            case DASHED: g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f)); g.draw(new Ellipse2D.Float(8, 8, 48, 48)); break;
            case FILL_PATH: { Path2D.Float p = new Path2D.Float(); p.moveTo(8, 56); p.curveTo(8, 0, 56, 0, 56, 56); p.lineTo(32, 36); p.closePath(); g.fill(p); break; }
            case TEXT_GRAY: g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setFont(new Font("Dialog", Font.PLAIN, 13)); g.drawString("Agile 123", 4, 26); g.setFont(new Font("Dialog", Font.BOLD, 11)); g.drawString("Wxyz iIl|", 4, 48); break;
            case TEXT_LCD: g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB); g.setFont(new Font("Dialog", Font.PLAIN, 13)); g.drawString("Agile 123", 4, 26); g.drawString("Wxyz iIl|", 4, 48); break;
            case TEXT_NOAA: g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF); g.setFont(new Font("Dialog", Font.PLAIN, 13)); g.drawString("Agile 123", 4, 26); g.drawString("Wxyz iIl|", 4, 48); break;
            case IMAGE: g.drawImage(srcImage, 12, 12, null); break;
            case IMAGE_SCALED: g.drawImage(srcImage, 6, 6, 52, 52, null); break;
            case IMAGE_ROT: { AffineTransform t = AffineTransform.getRotateInstance(Math.toRadians(25), 32, 32); t.translate(16, 16); g.drawImage(srcImage, t, null); break; }
            case COPY_AREA: g.fillRect(6, 6, 24, 24); g.copyArea(6, 6, 24, 24, 26, 26); g.copyArea(6, 6, 24, 24, 26, 0); break;
            case CLEAR_RECT: g.fillRect(6, 6, 52, 52); g.clearRect(16, 16, 32, 32); break;
        }
    }

    static void paintSheet(Graphics2D g, List<Cell> cells) {
        g.setColor(new Color(0xF0F0F0));
        g.fillRect(0, 0, 4096, 4096);
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            int cx = (i % COLS) * CELL, cy = (i / COLS) * CELL;
            Graphics2D cg = (Graphics2D) g.create(cx, cy, CELL, CELL);
            // background: light field with a dark band so alpha / XOR / CLEAR are visible
            cg.setColor(new Color(0xDDDDDD)); cg.fillRect(0, 0, CELL, CELL);
            cg.setColor(new Color(0x404040)); cg.fillRect(0, 36, CELL, 12);
            cg.setColor(new Color(0x808080)); cg.fillRect(0, 0, CELL, 1); cg.fillRect(0, 0, 1, CELL);
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, c.aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            cg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            applyClip(cg, c.clip);
            applyXform(cg, c.xform);
            applyComposite(cg, c.comp);
            if (c.comp != Comp.XOR) cg.setPaint(makePaint(c.paint)); else cg.setColor(new Color(0xC03030));
            drawShape(cg, c.shape);
            cg.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        srcImage = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = srcImage.createGraphics();
        ig.setPaint(new GradientPaint(0, 0, new Color(255, 200, 0, 255), 40, 40, new Color(0, 120, 255, 64)));
        ig.fillRect(0, 0, 40, 40); ig.setColor(Color.BLACK); ig.drawRect(0, 0, 39, 39); ig.fillOval(14, 14, 12, 12); ig.dispose();
        List<Cell> cells = cells();
        int rows = (cells.size() + COLS - 1) / COLS, W = COLS * CELL, H = rows * CELL;
        BufferedImage out;
        if (mode.equals("software")) {
            out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics(); paintSheet(g, cells); g.dispose();
        } else {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            Graphics2D g = vi.createGraphics(); paintSheet(g, cells); g.dispose();
            out = vi.getSnapshot();
            System.out.println("surface: " + sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getName() + " lost=" + vi.contentsLost());
        }
        ImageIO.write(out, "png", new File("matrix-" + mode + ".png"));
        try (java.io.PrintWriter w = new java.io.PrintWriter("matrix-cells.txt")) { for (Cell c : cells) w.println(c.name); }
        System.out.println("wrote matrix-" + mode + ".png (" + cells.size() + " cells, " + W + "x" + H + ")");
    }
}
