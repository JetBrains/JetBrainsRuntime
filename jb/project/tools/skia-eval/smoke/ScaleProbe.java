import java.awt.*;
public class ScaleProbe {
    public static void main(String[] a) {
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            System.out.println(d.getIDstring() + " " + gc.getBounds() + " scale=" + gc.getDefaultTransform().getScaleX() + " mode=" + d.getDisplayMode().getWidth() + "x" + d.getDisplayMode().getHeight());
        }
    }
}
