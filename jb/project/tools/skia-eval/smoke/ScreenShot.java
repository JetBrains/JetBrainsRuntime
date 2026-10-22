import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Captures the primary screen to the given PNG after an optional delay in ms. */
public class ScreenShot {
    public static void main(String[] args) throws Exception {
        long delay = args.length > 1 ? Long.parseLong(args[1]) : 0;
        Thread.sleep(delay);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        BufferedImage shot = new Robot().createScreenCapture(screen);
        ImageIO.write(shot, "png", new File(args[0]));
        System.out.println("wrote " + args[0] + " " + screen);
    }
}
