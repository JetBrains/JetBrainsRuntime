import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;

/**
 * The usable screen area in logical pixels (without menu bar, dock or taskbar): "x y w h".
 * With {@code --require <scale> <w> <h>} it instead waits until some attached display runs at that
 * scale with at least w x h usable — a native mode: a 16-inch MacBook panel's default 2x mode
 * (1728 wide; the "more space" modes are downsampled, not pixel-exact), 1920 x 1080 at 2x on a 4K
 * display, 1x on a 1080p or larger one — telling the user what to set every 10 s;
 * exit 1 after 15 minutes. The scenes are captured at the display's own scale since 2026-11-02
 * (native decorations, the OS's own text rendering settings), not through sun.java2d.uiScale.
 */
public class ScreenBounds {
    /** The first display at the scale with the room, as "x y w h" of its usable area, or null. */
    static Rectangle suitable(double scale, int w, int h, StringBuilder report) {
        Rectangle found = null;
        int n = 0;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            double s = gc.getDefaultTransform().getScaleX();
            Rectangle usable = usable(gc);
            if (report != null) report.append(String.format("%sdisplay %d: %.2fx, %dx%d usable", n > 0 ? "; " : "", n, s, usable.width, usable.height));
            if (found == null && Math.abs(s - scale) < 0.01 && usable.width >= w && usable.height >= h) found = usable;
            n++;
        }
        return found;
    }

    static Rectangle usable(GraphicsConfiguration gc) {
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--require")) {
            double scale = Double.parseDouble(args[1]);
            int w = Integer.parseInt(args[2]), h = Integer.parseInt(args[3]);
            long start = System.currentTimeMillis();
            long lastTold = -10;
            while (true) {
                StringBuilder report = new StringBuilder();
                Rectangle r = suitable(scale, w, h, report);
                if (r != null) {
                    System.out.println("display at " + scale + "x with " + r.width + "x" + r.height + " usable at " + r.x + "," + r.y + " (" + report + ")");
                    return;
                }
                long waited = (System.currentTimeMillis() - start) / 1000;
                if (waited - lastTold >= 10) {
                    System.err.printf("No display runs at %.0fx with at least %dx%d usable (%s). Set one in the OS display settings — %s — "
                                      + "or hide the dock / taskbar; waiting (%d s).%n", scale, w, h, report,
                                      System.getProperty("os.name").toLowerCase().contains("mac")
                                          ? "System Settings > Displays: the default (2x) mode of a 16-inch MacBook panel or 1920 x 1080 on a 4K display for 2x, the native resolution for 1x; not a downsampled 'more space' mode"
                                          : "Settings > System > Display: Scale 200 % for 2x, 100 % for 1x",
                                      waited);
                    lastTold = waited;
                }
                if (waited > 900) {
                    System.err.println("gave up waiting for the display settings");
                    System.exit(1);
                }
                Thread.sleep(2000);
            }
        }
        Rectangle r = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        System.out.println(r.x + " " + r.y + " " + r.width + " " + r.height);
    }
}
