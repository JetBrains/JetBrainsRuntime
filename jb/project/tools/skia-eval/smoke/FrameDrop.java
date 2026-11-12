import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** Frames shown, painted, disposed and dropped: are they collected? And VolatileImages dropped without flush. */
public class FrameDrop {
    public static void main(String[] args) throws Exception {
        List<WeakReference<Object>> frames = new ArrayList<>(), images = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            JFrame[] holder = new JFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("drop " + System.nanoTime());
                f.add(new JLabel("some text") { { setPreferredSize(new Dimension(400, 300)); } });
                f.pack();
                f.setVisible(true);
                holder[0] = f;
            });
            Thread.sleep(400);
            SwingUtilities.invokeAndWait(() -> holder[0].dispose());
            frames.add(new WeakReference<>(holder[0]));
            holder[0] = null;
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            java.awt.image.VolatileImage v = gc.createCompatibleVolatileImage(64, 64);
            v.validate(gc); Graphics2D g = v.createGraphics(); g.fillRect(0, 0, 8, 8); g.dispose(); v.getSnapshot();
            images.add(new WeakReference<>(v));
        }
        for (int i = 0; i < 20; i++) { System.gc(); Thread.sleep(200); }
        long frameLeft = frames.stream().filter(r -> r.get() != null).count(), imageLeft = images.stream().filter(r -> r.get() != null).count();
        System.out.println((Boolean.getBoolean("sun.java2d.skia") ? "skia" : "metal") + ": " + frameLeft + " of 5 disposed frames and " + imageLeft + " of 5 dropped VolatileImages still reachable");
        System.exit(0);
    }
}
