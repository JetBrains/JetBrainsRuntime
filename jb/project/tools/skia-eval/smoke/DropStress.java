import java.awt.*;
import java.awt.image.VolatileImage;
import java.util.Random;

/** Images dropped while drawing goes on, collections forced meanwhile: both Disposer orders, under load. */
public class DropStress {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage keep = gc.createCompatibleVolatileImage(300, 300, Transparency.TRANSLUCENT);
        Random rnd = new Random(1);
        Thread gcThread = new Thread(() -> { while (true) { System.gc(); try { Thread.sleep(15); } catch (InterruptedException e) { return; } } });
        gcThread.setDaemon(true); gcThread.start();
        long end = System.currentTimeMillis() + 20000; int n = 0;
        while (System.currentTimeMillis() < end) {
            VolatileImage v = gc.createCompatibleVolatileImage(64 + rnd.nextInt(200), 64 + rnd.nextInt(200), Transparency.TRANSLUCENT);
            v.validate(gc);
            Graphics2D g = v.createGraphics(); g.setColor(Color.RED); g.fillOval(0, 0, 50, 50); g.dispose();
            keep.validate(gc);
            Graphics2D k = keep.createGraphics(); k.drawImage(v, rnd.nextInt(200), rnd.nextInt(200), null); k.dispose();
            if (n++ % 50 == 0) keep.getSnapshot();
        }
        System.out.println(n + " images drawn, blitted and dropped under continuous collections: no crash; last pixel " + Integer.toHexString(keep.getSnapshot().getRGB(150, 150)));
        System.exit(0);
    }
}
