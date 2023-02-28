import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;
import util.ScreenShotHelpers;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;

/*
 * @test
 * @summary Verify modifying of title bar height
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 CheckFullScreen
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 CheckFullScreen
 */
public class CheckFullScreen {

    public static void main(String... args) throws AWTException, IOException {
        Frame f = new Frame(){
            @Override
            public void paint(Graphics g) {
                Rectangle r = g.getClipBounds();
                g.setColor(Color.BLUE);
                g.fillRect(r.x, r.y, r.width, 100);
                super.paint(g);
            }
        };

        WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
        titleBar.setHeight(100);
        JBR.getWindowDecorations().setCustomTitleBar(f, titleBar);

        f.setVisible(true);
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(f);

        Robot robot = new Robot();
        robot.delay(5000);

        BufferedImage image = ScreenShotHelpers.takeScreenshot(f);
        ScreenShotHelpers.storeScreenshot("fullscreen", image);
    }

}