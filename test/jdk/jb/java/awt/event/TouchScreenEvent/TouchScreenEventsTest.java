/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2041: Touchscreen devices support
 * @requires (jdk.version.major >= 11) & (os.family == "windows")
 * @build WindowsTouchRobot TouchScreenEventsTest
 * @run main/othervm/native TouchScreenEventsTest
 */

public class TouchScreenEventsTest {

    static final int TIMEOUT = 2;
    static final int PAUSE = 1000;

    // TODO make this constants accessible within jdk
    static final int TOUCH_BEGIN = 2;
    static final int TOUCH_UPDATE = 3;
    static final int TOUCH_END = 4;

    public static void main(String[] args) throws Exception {
        if(runTest(new TouchClickSuite())
                & runTest(new TouchMoveSuite())
                & runTest(new TouchTinyMoveSuite())
                & runTest(new TouchAxesScrollSuite(TouchAxesScrollSuite.AXIS.X))
                & runTest(new TouchAxesScrollSuite(TouchAxesScrollSuite.AXIS.Y))) {
            System.out.println("TEST PASSED");
        } else {
            throw new RuntimeException("TEST FAILED");
        }
    }

    private static boolean runTest(TouchTestSuite suite) throws Exception {
        GUI gui = new GUI();
        try {
            TouchRobot robot = getTouchRobot();
            SwingUtilities.invokeAndWait(gui::createAndShow);
            suite.addListener(gui.frame);
            robot.waitForIdle();
            Thread.sleep(PAUSE);
            suite.perform(gui, robot);
            robot.waitForIdle();
            return suite.passed();
        } finally {
            SwingUtilities.invokeLater(() -> {
                if (gui.frame != null) {
                    gui.frame.dispose();
                }
            });
            Thread.sleep(PAUSE);
        }
    }

    private static TouchRobot getTouchRobot() throws IOException, AWTException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux"))  {
            return new LinuxTouchRobot();
        } else if (osName.contains("windows")) {
            return new WindowsTouchRobot();
        }
        throw new RuntimeException("Touch robot for this platform isn't implemented");
    }
}

class GUI {
    JFrame frame;
    Rectangle frameBounds;

    void createAndShow() {
        frame = new JFrame();
        frame.setSize(640, 480);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
        frameBounds = frame.getBounds();
    }
}

interface TouchTestSuite {

    void addListener(JFrame frame);

    void perform(GUI gui, TouchRobot robot) throws IOException;

    boolean passed() throws InterruptedException;

}

class TouchClickSuite implements MouseListener, TouchTestSuite {

    private volatile boolean pressed;
    private volatile boolean released;
    private volatile boolean clicked;
    private final CountDownLatch latch = new CountDownLatch(3);

    @Override
    public void mouseClicked(MouseEvent e) {
        if (!clicked) {
            clicked = true;
            latch.countDown();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        pressed = true;
        if (!pressed) {
            pressed = true;
            latch.countDown();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!released) {
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
    public boolean passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        if (!pressed || !released || !clicked) {
            String error = (pressed ? "" : "pressed: " + pressed) +
                    (released ? "" : ", released: " + released) +
                    (clicked ? "" : ", clicked: " + clicked);
            System.out.println("Touch click failed: " + error);
            return false;
        }
        System.out.println("Touch click passed");
        return true;
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
    private final CountDownLatch latch = new CountDownLatch(3);

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getScrollType() == TouchScreenEventsTest.TOUCH_BEGIN) {
            if (!begin) {
                begin = true;
                latch.countDown();
            }
        } else if (e.getScrollType() == TouchScreenEventsTest.TOUCH_UPDATE) {
            if (!update) {
                update = true;
                latch.countDown();
            }
        } else if (e.getScrollType() == TouchScreenEventsTest.TOUCH_END) {
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
    public boolean passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        if (!begin || !update || !end) {
            String error = (begin ? "" : "begin: " + begin) +
                    (update ? "" : ", update: " + update) +
                    (end ? "" : ", end: " + end);
            System.out.println("Touch move failed: " + error);
            return false;
        }
        System.out.println("Touch move passed");
        return true;
    }

    @Override
    public void addListener(JFrame frame) {
        frame.addMouseWheelListener(this);
    }
}

class TouchTinyMoveSuite implements MouseWheelListener, MouseListener, TouchTestSuite {

    private volatile boolean scroll;
    private volatile boolean click;
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getScrollType() == TouchScreenEventsTest.TOUCH_UPDATE) {
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
    public boolean passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        if (scroll || !click) {
            String error = (scroll ? "scroll " + scroll : "") +
                    (click ? "" : ", click: " + click);
            System.out.println("Tiny touch move failed: " + error);
            return false;
        }
        System.out.println("Tiny touch move passed");
        return true;
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
    private final CountDownLatch latch = new CountDownLatch(1);

    TouchAxesScrollSuite(AXIS axis) {
        this.axis = axis;
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getScrollType() == TouchScreenEventsTest.TOUCH_UPDATE) {
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
                robot.touchMove(42, x1, y1, x2, y1);
                break;
            case Y:
                int y2 = gui.frameBounds.y + gui.frameBounds.height / 2;
                robot.touchMove(42, x1, y1, x1, y2);
                break;
        }
    }

    @Override
    public boolean passed() throws InterruptedException {
        latch.await(TouchScreenEventsTest.TIMEOUT, TimeUnit.SECONDS);
        String info = "horizontal: " + horizontal + ", vertical: " + vertical;
        switch (axis) {
            case X:
                if (!horizontal || vertical) {
                    System.out.println("Touch axes failed: " + info);
                    return false;
                }
                break;
            case Y:
                if (horizontal || !vertical) {
                    System.out.println("Touch axes failed: " + info);
                    return false;
                }
                break;
        }
        System.out.println("Touch axes passed: " + info);
        return true;
    }

    @Override
    public void addListener(JFrame frame) {
        frame.addMouseWheelListener(this);
    }
}
