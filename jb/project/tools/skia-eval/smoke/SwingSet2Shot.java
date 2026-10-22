import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Starts SwingSet2 (on the classpath), brings its frame to the front, captures
 * the screen to the given PNG and exits. Optional second argument: a tab index
 * to click through before the capture is left for later.
 */
public class SwingSet2Shot {
    public static void main(String[] args) throws Exception {
        Method main = Class.forName("SwingSet2").getMethod("main", String[].class);
        main.invoke(null, (Object) new String[0]);
        Thread.sleep(8000);
        Frame[] frames = Frame.getFrames();
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : frames) {
                if (f.isShowing()) {
                    f.setAlwaysOnTop(true);
                    f.toFront();
                }
            }
        });
        Thread.sleep(3000);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        BufferedImage shot = new Robot().createScreenCapture(screen);
        ImageIO.write(shot, "png", new File(args[0]));
        System.out.println("wrote " + args[0] + " frames=" + frames.length);
        System.exit(0);
    }
}
