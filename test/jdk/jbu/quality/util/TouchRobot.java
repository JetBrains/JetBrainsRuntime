package quality.util;

import java.awt.*;
import java.io.IOException;

public abstract class TouchRobot extends Robot {
    public TouchRobot() throws AWTException {
    }

    public TouchRobot(GraphicsDevice screen) throws AWTException {
        super(screen);
    }

    public abstract void touchClick(int fingerId, int x, int y) throws IOException;

    public abstract void touchMove(int fingerId, int fromX, int fromY, int toX, int toY) throws IOException;
}
