import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.File;
import java.lang.reflect.Method;

/** Renders a primitive mix off-screen (VolatileImage -> snapshot -> PNG) and in a window. */
public class SkiaRender {
    static void paintScene(Graphics2D g, int w, int h) {
        g.setColor(new Color(0xF0F0F0)); g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x3574F0)); g.fillRect(10, 10, 100, 60);
        g.setColor(new Color(0xE55353)); g.drawRect(120, 10, 100, 60);
        g.setColor(Color.DARK_GRAY);
        for (int i = 0; i < 6; i++) g.drawLine(10, 90 + i * 4, 220, 80 + i * 4);
        Graphics2D aa = (Graphics2D) g.create();
        aa.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        aa.setColor(new Color(0x208A3C)); aa.fill(new RoundRectangle2D.Float(240.5f, 10.5f, 120, 60, 16, 16));
        aa.setColor(new Color(0x7B3FC4)); aa.setStroke(new BasicStroke(3f));
        aa.draw(new RoundRectangle2D.Float(240.5f, 80.5f, 120, 40, 12, 12));
        aa.setPaint(new GradientPaint(380, 10, Color.ORANGE, 500, 70, Color.MAGENTA));
        aa.fill(new Ellipse2D.Float(380, 10, 120, 60));
        aa.setPaint(new LinearGradientPaint(380, 80, 500, 80, new float[]{0f, 0.5f, 1f}, new Color[]{Color.RED, Color.YELLOW, Color.BLUE}));
        aa.fillRect(380, 80, 120, 30);
        aa.setPaint(new RadialGradientPaint(560, 50, 40, new float[]{0f, 1f}, new Color[]{Color.WHITE, Color.BLACK}));
        aa.fillOval(520, 10, 80, 80);
        aa.dispose();
        BufferedImage img = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        ig.setColor(new Color(0, 128, 255, 160)); ig.fillOval(0, 0, 48, 48); ig.setColor(Color.BLACK); ig.drawRect(0, 0, 47, 47); ig.dispose();
        g.drawImage(img, 10, 130, null);
        g.drawImage(img, 70, 130, 96, 96, null);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Dialog", Font.PLAIN, 14));
        g.drawString("Skia backend test — JBR-10654 (plain)", 180, 150);
        Graphics2D ta = (Graphics2D) g.create();
        ta.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        ta.setFont(new Font("Dialog", Font.BOLD, 18));
        ta.drawString("Antialiased text 0123456789", 180, 180);
        ta.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        ta.setColor(Color.RED); ta.fillRect(180, 190, 200, 20);
        ta.dispose();
        Graphics2D lcd = (Graphics2D) g.create();
        lcd.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        lcd.setColor(new Color(0x202020)); lcd.setFont(new Font("Dialog", Font.PLAIN, 13));
        lcd.drawString("LCD subpixel text 0123456789 iIlL", 180, 262);
        lcd.dispose();
        g.setClip(new Ellipse2D.Float(420, 120, 160, 100));
        g.setColor(new Color(0x00A0A0)); g.fillRect(400, 100, 200, 140);
        g.setClip(null);
        g.copyArea(10, 10, 100, 60, 0, 220);
    }

    public static void main(String[] args) throws Exception {
        String tag = args.length > 0 ? args[0] : "run";
        boolean window = args.length > 1 && args[1].equals("window");
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        System.out.println("skia enabled: " + env.getMethod("isSkiaEnabled").invoke(null)
                + " / " + env.getMethod("getDescription").invoke(null));
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        final int W = 620, H = 300;

        if (tag.equals("software")) {
            // canonical Java2D: the software loops
            BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D sg = bi.createGraphics();
            paintScene(sg, W, H);
            sg.dispose();
            ImageIO.write(bi, "png", new File("skia-offscreen-software.png"));
            System.out.println("wrote skia-offscreen-software.png");
            if (!window) return;
        }
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H);
        Graphics2D g = vi.createGraphics();
        paintScene(g, W, H);
        g.dispose();
        Object sd = Class.forName("sun.awt.image.SurfaceManager").getMethod("getManager", Image.class).invoke(null, vi);
        sd = sd.getClass().getMethod("getPrimarySurfaceData").invoke(sd);
        System.out.println("offscreen surface: " + sd.getClass().getName());
        BufferedImage snap = vi.getSnapshot();
        ImageIO.write(snap, "png", new File("skia-offscreen-" + tag + ".png"));
        System.out.println("wrote skia-offscreen-" + tag + ".png (contentsLost=" + vi.contentsLost() + ")");

        if (window) {
            if (Boolean.getBoolean("skia.test.nodb")) javax.swing.RepaintManager.currentManager(null).setDoubleBufferingEnabled(false);
            JFrame f = Boolean.getBoolean("skia.test.gcframe")
                    ? new JFrame("SkiaRender " + tag, GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration())
                    : new JFrame("SkiaRender " + tag);
            JPanel panel = new JPanel() {
                @Override protected void paintComponent(Graphics gg) {
                    paintScene((Graphics2D) gg, getWidth(), getHeight());
                    if (Boolean.getBoolean("skia.test.sync")) Toolkit.getDefaultToolkit().sync();
                }
            };
            panel.setPreferredSize(new Dimension(W, H));
            JPanel south = new JPanel(); south.add(new JLabel("JLabel")); south.add(new JButton("JButton")); south.add(new JCheckBox("JCheckBox", true));
            f.getContentPane().add(panel, BorderLayout.CENTER); f.getContentPane().add(south, BorderLayout.SOUTH);
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.pack(); f.setLocation(100, 100); f.setAlwaysOnTop(Boolean.getBoolean("skia.test.ontop")); f.setVisible(true); if (Boolean.getBoolean("skia.test.ontop")) f.toFront();
            if (Boolean.getBoolean("skia.test.resize")) {
                Thread.sleep(800);
                javax.swing.SwingUtilities.invokeAndWait(() -> f.setSize(f.getWidth() + 40, f.getHeight() + 40));
                System.out.println("resized");
            }
            if (Boolean.getBoolean("skia.test.repaintloop")) {
                for (int i = 0; i < 40; i++) { Thread.sleep(50); javax.swing.SwingUtilities.invokeAndWait(panel::repaint); }
                System.out.println("repaint loop done");
            }
            long sleepMs = Long.getLong("skia.test.sleep", 2500);
            Thread.sleep(sleepMs);
            System.out.println("frame showing=" + f.isShowing() + " bounds=" + f.getBounds() + " panelOnScreen=" + panel.getLocationOnScreen());
            Robot r = new Robot();
            Point p = panel.getLocationOnScreen();
            BufferedImage shot = r.createScreenCapture(new Rectangle(p.x, p.y, W, H));
            ImageIO.write(shot, "png", new File("skia-window-" + tag + ".png"));
            System.out.println("wrote skia-window-" + tag + ".png");
            System.exit(0);
        }
    }
}
