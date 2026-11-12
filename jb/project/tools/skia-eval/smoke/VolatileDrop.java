import java.awt.*;
import java.awt.image.VolatileImage;

/** VolatileImages drawn into and dropped: is their GPU memory given back once they are collected? -Dgpumem.probe=<a dylib
 *  with Java_VolatileDrop_metalAllocated returning MTLDevice.currentAllocatedSize, as bench/run-gpumem-mac.sh builds for GpuMemDriver>. */
public class VolatileDrop {
    static native long metalAllocated();
    static double mb() { return metalAllocated() / 1048576.0; }

    public static void main(String[] args) throws Exception {
        System.load(System.getProperty("gpumem.probe"));
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        boolean flush = args.length > 0 && args[0].equals("flush");
        warm(gc);
        System.gc(); Thread.sleep(500);
        double before = mb();
        for (int i = 0; i < 40; i++) {
            VolatileImage img = gc.createCompatibleVolatileImage(850, 850, Transparency.TRANSLUCENT);
            for (int f = 0; f < 3; f++) {
                img.validate(gc);
                Graphics2D g = img.createGraphics();
                g.setColor(Color.BLACK); g.fillRect(0, 0, 850, 850);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.ORANGE); for (int k = 0; k < 200; k++) g.fillOval(k * 4, k * 4, 30, 30);
                g.dispose();
            }
            img.getSnapshot();
            if (flush) img.flush();
        }
        double drawn = mb();
        for (int i = 0; i < 10; i++) { System.gc(); Thread.sleep(300); }
        warm(gc);   // a frame after the collection: the pipe runs its disposals with its next work
        Thread.sleep(1500);
        double after = mb();
        System.out.printf("%s%s: Metal allocated %.1f MB before, %.1f MB after 40 dropped 850x850 images, %.1f MB after GC%n",
                System.getProperty("sun.java2d.skia") != null ? "skia" : "metal", flush ? " (flushed)" : "", before, drawn, after);
        System.exit(0);
    }

    static void warm(GraphicsConfiguration gc) {
        VolatileImage w = gc.createCompatibleVolatileImage(16, 16);
        w.validate(gc); Graphics2D g = w.createGraphics(); g.fillRect(0, 0, 16, 16); g.dispose(); w.getSnapshot();
    }
}
