package quality.util;

import java.awt.*;
import java.io.IOException;

public class UnixTouchRobot extends TouchRobot {
    private UnixTouchScreenDevice device;

    public UnixTouchRobot() throws AWTException, IOException {
        super();
        device = new UnixTouchScreenDevice();
    }

    public UnixTouchRobot(GraphicsDevice screen) throws AWTException, IOException {
        super(screen);
        device = new UnixTouchScreenDevice();
    }

    public void touchClick(int fingerId, int x, int y) throws IOException {
        device.click(fingerId, x, y);
    }

    public void touchMove(int fingerId, int fromX, int fromY, int toX, int toY) throws IOException {
        device.move(fingerId, fromX, fromY, toX, toY);
    }
}
