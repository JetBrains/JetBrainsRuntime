package quality.util;

import java.awt.*;
import java.io.IOException;

public class WindowsTouchRobot extends TouchRobot {
    static {
        System.loadLibrary("windows_touch_robot");
    }

    public WindowsTouchRobot() throws AWTException, IOException {
        super();
    }

    public WindowsTouchRobot(GraphicsDevice screen) throws AWTException, IOException {
        super(screen);
    }

    @Override
    public void touchClick(int fingerId, int x, int y) throws IOException {
        // TODO unused finger id cause windows use different finger id model
        clickImpl(x, y);
    }

    @Override
    public void touchMove(int fingerId, int fromX, int fromY, int toX, int toY) throws IOException {
        // TODO unused finger id cause windows use different finger id model
        moveImpl(fromX, fromY, toX, toY);
    }

    private native void clickImpl(int x, int y);

    private native void moveImpl(int fromX, int fromY, int toX, int toY);
}
