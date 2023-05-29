package util;

import java.awt.*;

public class MouseUtils {

    public static void verifyLocationAndMove(Robot robot, Window window, int x, int y) {
        verifyLocation(window, x, y);

        robot.waitForIdle();
        robot.mouseMove(x, y);
        robot.delay(50);
    }

    public static void verifyLocationAndClick(Robot robot, Window window, int x, int y, int mask) {
        verifyLocation(window, x, y);

        robot.waitForIdle();
        robot.mouseMove(x, y);
        robot.delay(50);
        robot.mousePress(mask);
        robot.delay(50);
        robot.mouseRelease(mask);
    }

    private static void verifyLocation(Window window, int x, int y) {
        int x1 = window.getLocationOnScreen().x + window.getInsets().left;
        int x2 = x1 + window.getBounds().width - window.getInsets().right;
        int y1 = window.getLocationOnScreen().y + window.getInsets().top;
        int y2 = y1 + window.getBounds().height - window.getInsets().bottom;

        boolean isLocationValid = (x1 < x && x < x2 && y1 < y && y < y2);
        if (!isLocationValid) {
            throw new RuntimeException("Coordinates (" + x + ", " + y + ") is outside of clickable area");
        }
        if (!window.isVisible()) {
            throw new RuntimeException("Window isn't visible. Can't click to the area");
        }
    }

}