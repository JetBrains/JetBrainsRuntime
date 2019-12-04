package quality.event;

import org.apache.commons.lang3.SystemUtils;
import org.junit.Test;
import quality.util.UnixTouchRobot;
import quality.util.TouchRobot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TouchScreenEventsTest {
    static final int TIMEOUT = 2;

    @Test
    public void testTouchClick() throws Exception {
        runTest(new TouchClickSuite());
    }

    @Test
    public void testTouchMove() throws Exception {
        runTest(new TouchMoveSuite());
    }

    @Test
    public void testTouchTinyMove() throws Exception {
        runTest(new TouchTinyMoveSuite());
    }

    @Test
    public void testTouchHorizontalScroll() throws Exception {
        runTest(new TouchAxesScrollSuite(TouchAxesScrollSuite.AXIS.X));
    }

    @Test
    public void testTouchVerticalScroll() throws Exception {
        runTest(new TouchAxesScrollSuite(TouchAxesScrollSuite.AXIS.Y));
    }

    private static void runTest(TouchTestSuite suite) throws Exception {
        GUI gui = new GUI();
        try {
            TouchRobot robot = getTouchRobot();
            SwingUtilities.invokeAndWait(gui::createAndShow);
            suite.addListener(gui.frame);
            robot.waitForIdle();

            suite.perform(gui, robot);
            robot.waitForIdle();
            suite.passed();
        } finally {
            SwingUtilities.invokeLater(() -> {
                if (gui.frame != null) {
                    gui.frame.dispose();
                }
            });
        }
    }

    private static TouchRobot getTouchRobot() throws IOException, AWTException {
        if (SystemUtils.IS_OS_UNIX) {
            return new UnixTouchRobot();
        }
        throw new RuntimeException("Touch robot for this platform isn't implemented");
    }
}

class GUI {
    JFrame frame;
    Rectangle frameBounds;

    void createAndShow() {
        frame = new JFrame();
        frame.setSize(400, 400);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
        frameBounds = frame.getBounds();
    }
}

interface TouchTestSuite {
    void perform(GUI gui, TouchRobot robot) throws IOException;

    void passed() throws InterruptedException;

    void addListener(JFrame frame);
}

class TouchClickSuite implements MouseListener, TouchTestSuite {
    private volatile boolean pressed;
    private volatile boolean released;
    private volatile boolean clicked;
    private CountDownLatch latch;

    TouchClickSuite() {
        latch = new CountDownLatch(3);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (!clicked) {
            // TODO race
            clicked = true;
            latch.countDown();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        pressed = true;
        if (!pressed) {
            // TODO race
            pressed = true;
            latch.countDown();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!released) {
            // TODO race
            released = true;
            latch.countDown();
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void perform(GUI gui, TouchRobot robot) throws IOException {
        int x = gui.frameBounds.x + gui.frameBounds.width / 2;
        int y = gui.frameBounds.y + gui.frameBounds.height / 2;
        robot.touchClick(42, x, y);
    }

    @Override
    public void passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        if (!pressed || !released || !clicked) {
            String error = (pressed ? "" : "pressed: " + pressed) +
                    (released ? "" : ", released: " + released) +
                    (clicked ? "" : ", clicked: " + clicked);
            throw new RuntimeException("Touch click failed: " + error);
        }
    }

    @Override
    public void addListener(JFrame frame) {
        frame.addMouseListener(this);
    }
}

class TouchMoveSuite implements MouseWheelListener, TouchTestSuite {
    private volatile boolean begin;
    private volatile boolean update;
    private volatile boolean end;
    private CountDownLatch latch;

    TouchMoveSuite() {
        latch = new CountDownLatch(3);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        // TODO 3 race

        if (e.getScrollType() == 2) {
            if (!begin) {
                begin = true;
                latch.countDown();
            }
        } else if (e.getScrollType() == 3) {
            if (!update) {
                update = true;
                latch.countDown();
            }
        } else if (e.getScrollType() == 4) {
            if (!end) {
                end = true;
                latch.countDown();
            }
        }
    }

    @Override
    public void perform(GUI gui, TouchRobot robot) throws IOException {
        int x1 = gui.frameBounds.x + gui.frameBounds.width / 4;
        int y1 = gui.frameBounds.y + gui.frameBounds.height / 4;
        int x2 = gui.frameBounds.x + gui.frameBounds.width / 2;
        int y2 = gui.frameBounds.y + gui.frameBounds.height / 2;
        robot.touchMove(42, x1, y1, x2, y2);
    }

    @Override
    public void passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        if (!begin || !update || !end) {
            String error = (begin ? "" : "begin: " + begin) +
                    (update ? "" : ", update: " + update) +
                    (end ? "" : ", end: " + end);
            throw new RuntimeException("Touch move failed: " + error);
        }
    }

    @Override
    public void addListener(JFrame frame) {
        frame.addMouseWheelListener(this);
    }
}

class TouchTinyMoveSuite implements MouseWheelListener, MouseListener, TouchTestSuite {
    private volatile boolean scroll;
    private volatile boolean click;
    private CountDownLatch latch;

    TouchTinyMoveSuite() {
        latch = new CountDownLatch(1);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getScrollType() == 3) {
            scroll = true;
            latch.countDown();
        }
    }

    @Override
    public void perform(GUI gui, TouchRobot robot) throws IOException {
        int x1 = gui.frameBounds.x + gui.frameBounds.width / 4;
        int y1 = gui.frameBounds.y + gui.frameBounds.height / 4;
        // move inside tiny area
        int x2 = x1 + 1;
        int y2 = y1 + 1;
        robot.touchMove(42, x1, y1, x2, y2);
    }

    @Override
    public void passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        if (scroll || !click) {
            String error = (scroll ? "scroll " + scroll : "") +
                    (click ? "" : ", click: " + click);
            throw new RuntimeException("Tiny touch move failed: " + error);
        }
    }

    @Override
    public void addListener(JFrame frame) {
        frame.addMouseWheelListener(this);
        frame.addMouseListener(this);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        click = true;
        latch.countDown();
    }

    @Override
    public void mousePressed(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }
}

class TouchAxesScrollSuite implements MouseWheelListener, TouchTestSuite {
    enum AXIS {X, Y}

    private final AXIS axis;
    private volatile boolean vertical;
    private volatile boolean horizontal;
    private CountDownLatch latch;

    TouchAxesScrollSuite(AXIS axis) {
        this.axis = axis;
        latch = new CountDownLatch(1);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getScrollType() == 3) {
            if (e.isShiftDown()) {
                horizontal = true;
            } else {
                vertical = true;
            }
            latch.countDown();
        }
    }

    @Override
    public void perform(GUI gui, TouchRobot robot) throws IOException {
        int x1 = gui.frameBounds.x + gui.frameBounds.width / 4;
        int y1 = gui.frameBounds.y + gui.frameBounds.height / 4;
        switch (axis) {
            case X:
                int x2 = gui.frameBounds.x + gui.frameBounds.width / 2;
                device.move(42, x1, y1, x2, y1);
            case Y:
                int y2 = gui.frameBounds.y + gui.frameBounds.height / 4;
                device.move(42, x1, y1, x1, y2);
        }
    }

    @Override
    public void passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        String error = "horizontal " + horizontal + ", vertical: " + vertical;
        switch (axis) {
            case X:
                if (!horizontal || vertical) {
                    throw new RuntimeException("Touch axes failed: " + error);
                }
            case Y:
                if (horizontal || !vertical) {
                    throw new RuntimeException("Touch axes failed: " + error);
                }
        }
    }

    @Override
    public void addListener(JFrame frame) {
        frame.addMouseWheelListener(this);
    }
}
