import javax.swing.*;
import java.awt.*;

/** Which surface a shown frame's peer paints with, and the screen's scale. */
public class WindowSurfaceProbe {
    public static void main(String[] args) throws Exception {
        JFrame f = new JFrame("probe");
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        f.add(new JPanel() { protected void paintComponent(Graphics g) { g.setColor(Color.ORANGE); g.fillRect(0, 0, getWidth(), getHeight()); } });
        f.setSize(400, 300); f.setLocation(100, 100); f.setVisible(true);
        Thread.sleep(1500);
        Object peer = sun.awt.AWTAccessor.getComponentAccessor().getPeer(f);
        sun.java2d.SurfaceData sd = ((sun.awt.windows.WComponentPeer) peer).getSurfaceData();
        System.out.println("gc=" + f.getGraphicsConfiguration().getClass().getName()
                + " scale=" + f.getGraphicsConfiguration().getDefaultTransform().getScaleX()
                + " surface=" + sd.getClass().getName() + " lost=" + sd.isSurfaceLost()
                + " manager=" + sun.java2d.ScreenUpdateManager.getInstance().getClass().getName());
        System.exit(0);
    }
}
