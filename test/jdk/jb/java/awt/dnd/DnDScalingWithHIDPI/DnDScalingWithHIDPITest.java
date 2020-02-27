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

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.*;
import java.awt.event.InputEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-1414. DnD on linux (XToolkit) does not honor HIDPI scale
 * @requires (os.family == "linux") & (jdk.version.major >= 11)
 * @author mikhail.grishch@gmail.com
 * @run main/othervm -Dsun.java2d.uiScale=2 DnDScalingWithHIDPITest 1
 * @run main/othervm -Dsun.java2d.uiScale=1 DnDScalingWithHIDPITest 2
 * @run main/othervm -Dsun.java2d.uiScale.enabled=false DnDScalingWithHIDPITest DISABLE_SCALING
 * @run main/othervm/fail -Dsun.java2d.uiScale=2.5 -Dsun.java2d.uiScale.enabled=true DnDScalingWithHIDPITest 0.5
 * @comment remove /fail option when JBR-1365 will be fixed.
 */

/**
 * Description
 * Dnd works incorrect in the case if Source and Target components have different HiDPI scaling.
 * Scaling is controlled by jvm option (-Dsun.java2d.uiScale) instead of GDK_SCALE env var.
 *
 * Test creates frame with transferable panel.
 * Then it executes another process (DropTargetRunner) which creates frame with Target panel.
 * Scaling of Target Frame is also controlled by jvm option (-Dsun.java2d.uiScale) which is got from
 * cmd argument of parent process:
 * * DISABLE_SCALING          - disable scaling (set -Dsun.java2d.uiScale.enabled=false)
 * * another value (except 0) - scaling factor for Target Frame
 * Test checks correctness of the dragExit, dragEnter, dragOver and drop actions.
 * Test runs 4 times with various configurations of source and target scaling.
 */

public class DnDScalingWithHIDPITest {
    //Timeout of child process must be smaller than timeout of test (default - 120s)
    private static final int CHILD_PROCESS_TIMEOUT = 60;
    /**
     * @param args
     * arg[0] - scaling factor of DropTargetFrame (must be set!)
     * * DISABLE_SCALING - disable scaling (set -Dsun.java2d.uiScale.enabled=false)
     * * another value (except 0)  - scaling factor for Target Frame
     */
    public static void main(String[] args) throws Exception {
        DragSourceFrame sourceFrame = new DragSourceFrame("DRAG");
        Process process = null;
        boolean processExit = false;
        try {
            var r = new Robot();
            SwingUtilities.invokeAndWait(sourceFrame::initUI);
            r.waitForIdle();

            String sourceScaleStr = System.getProperty("sun.java2d.uiScale");
            float sourceScale = 1;
            if ("false".equals(System.getProperty("sun.java2d.uiScale.enabled"))) {
                System.out.println("Scaling disabled on DRAG Frame (-Dsun.java2d.uiScale.enabled=false)");
            } else if(sourceScaleStr == null) {
                throw new RuntimeException("Error: Property sun.java2d.uiScale did not set");
            } else {
                sourceScale = Float.parseFloat(sourceScaleStr);
            }

            String vmScalingOption;
            float targetScale = 1;
            if (args.length == 0) {
                throw new RuntimeException("Error: Program argument did not set (Scaling value for DROP Frame can not be specified).");
            } else if ("DISABLE_SCALING".equals(args[0])) {
                System.out.println("Scaling disabled on DROP Frame (-Dsun.java2d.uiScale.enabled=false)");
                vmScalingOption = "-Dsun.java2d.uiScale.enabled=false";
            } else {
                vmScalingOption = "-Dsun.java2d.uiScale=" + args[0];
                String targetScaleStr = args[0];
                targetScale =  "0".equals(targetScaleStr) ? 1 :  Float.parseFloat(targetScaleStr);
            }

            float scalingFactor = sourceScale / targetScale;

            var processBuilder =  new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    vmScalingOption,
                    "-cp",
                    System.getProperty("java.class.path", "."),
                    "DropTargetRunner",
                    String.valueOf((int) (sourceFrame.getNextLocationX() * scalingFactor)),
                    String.valueOf((int) (sourceFrame.getNextLocationY() * scalingFactor)),
                    String.valueOf((int) (sourceFrame.getDragSourcePointX() * scalingFactor)),
                    String.valueOf((int) (sourceFrame.getDragSourcePointY() * scalingFactor))
            );

            System.out.println("Starting DropTargetRunner...");
            processBuilder.command().forEach(s -> System.out.print(s + " "));
            System.out.println("\n");

            process = processBuilder.start();
            processExit = process.waitFor(CHILD_PROCESS_TIMEOUT, TimeUnit.SECONDS);

            var inContent = new StringBuilder();
            var errContent = new StringBuilder();
            getStreamContent(process.getInputStream(), inContent);
            getStreamContent(process.getErrorStream(), errContent);

            System.out.println(inContent.toString());

            if(!processExit) {
                process.destroy();
                processExit = true;
                throw new RuntimeException("Error: Timeout. The sub-process hangs.");
            }

            String errorContentStr = errContent.toString();
            if (!errorContentStr.isEmpty()) {
                throw new RuntimeException(errorContentStr);
            }

        } finally {
            SwingUtilities.invokeAndWait(sourceFrame::dispose);
            Thread.sleep(1000);
            if(process != null && !processExit) {
                process.destroy();
            }
        }
    }

    public static void getStreamContent(InputStream in, StringBuilder content) throws IOException {
        String tempString;
        int count = in.available();
        while (count > 0) {
            byte[] b = new byte[count];
            in.read(b);
            tempString = new String(b);
            content.append(tempString).append("\n");
            count = in.available();
        }
    }

}

class DragSourceFrame extends JFrame implements DragGestureListener {
    private JPanel sourcePanel;

    DragSourceFrame(String frameName) {
        super(frameName);
        this.setUndecorated(true);
    }

    void initUI() {
        this.setLocation(0, 0);
        JPanel contentPane = new JPanel(null);
        contentPane.setPreferredSize(new Dimension(50, 50));

        sourcePanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(this.getBackground());
                g2.fillRect(0, 0, 50, 50);
            }
        };
        sourcePanel.setBounds(0, 0, 50,50);
        sourcePanel.setEnabled(true);
        sourcePanel.setBackground(Color.white);

        var ds = new DragSource();
        ds.createDefaultDragGestureRecognizer(sourcePanel,
                DnDConstants.ACTION_COPY, this);

        contentPane.add(sourcePanel);
        this.setContentPane(contentPane);

        this.pack();
        this.setVisible(true);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    // returns the X coordinate on which DropTargetFrame will be opened
    int getNextLocationX() {
        return getX() + getWidth();
    }

    // returns the X coordinate on which DropTargetFrame will be opened
    int getNextLocationY() {
        return getY();
    }

    //returns the X coordinate to drag Source panel
    int getDragSourcePointX() {
        return (int) sourcePanel.getLocationOnScreen().getX() + (sourcePanel.getWidth() / 2);
    }

    //returns the Y coordinate to drag Source panel
    int getDragSourcePointY() {
        return (int) sourcePanel.getLocationOnScreen().getY() + (sourcePanel.getHeight() / 2);
    }

    @Override
    public void dragGestureRecognized(DragGestureEvent event) {
        var cursor = Cursor.getDefaultCursor();
        if (event.getDragAction() == DnDConstants.ACTION_COPY) {
            cursor = DragSource.DefaultCopyDrop;
        }
        event.startDrag(cursor, new TransferableElement());
    }
}

class TransferableElement implements Transferable {
    private static final DataFlavor[] supportedFlavors = {DataFlavor.stringFlavor};

    public TransferableElement() {
    }

    public DataFlavor[] getTransferDataFlavors() {
        return supportedFlavors;
    }

    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.equals(DataFlavor.stringFlavor);
    }

    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (flavor.equals(DataFlavor.stringFlavor)) {
            return "Transferable text";
        } else {
            throw new UnsupportedFlavorException(flavor);
        }
    }
}


class DropTargetRunner {

    /**
     * @param args
     * arg[0] - the X coordinate of DropTargetFrame
     * arg[1] - the Y coordinate of DropTargetFrame
     * arg[2] - the X coordinate of source panel which will be dragged
     * arg[2] - the Y coordinate of source panel which will be dragged
     */
    public static void main(String[] args) throws Exception {
        Robot r = new Robot();
        DropTargetFrame targetFrame = new DropTargetFrame("DROP", r);
        try {
            int dragSourceX = Integer.parseInt(args[2]);
            int dragSourceY = Integer.parseInt(args[3]);
            var targetFrameLocation = new Point(Integer.parseInt(args[0]), Integer.parseInt(args[1]));
            SwingUtilities.invokeAndWait(() -> targetFrame.initUI(targetFrameLocation));
            r.waitForIdle();
            targetFrame.doTest(dragSourceX, dragSourceY);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            SwingUtilities.invokeAndWait(targetFrame::dispose);
            r.waitForIdle();
        }
    }
}

class DropTargetFrame extends JFrame {
    private Robot robot;
    private JPanel dtPanel;
    private boolean dropWasSuccessful = false;
    private boolean dragEnterWasSuccessful = false;
    private boolean dragOverWasSuccessful = false;
    private boolean dragExitWasSuccessful = false;

    DropTargetFrame(String frameName, Robot r) {
        super(frameName);
        robot = r;
    }

    void initUI(Point frameLocation) {
        this.setLocation(frameLocation);
        JPanel contentPane = new JPanel(null);
        contentPane.setPreferredSize(new Dimension(200,200));

        dtPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(this.getBackground());
                g2.fillRect(0, 0, 100,100);
            }
        };

        dtPanel.setBounds(50,50, 100, 100);
        dtPanel.setEnabled(true);
        dtPanel.setBackground(Color.white);
        dtPanel.setDropTarget(new DropTarget(dtPanel, DnDConstants.ACTION_COPY_OR_MOVE, new DropTargetAdapter() {
            public void dragOver(DropTargetDragEvent dtde) {
                dragOverWasSuccessful = true;
            }
            public void dragExit(DropTargetEvent dte) {
                dragExitWasSuccessful = true;
            }
            public void dragEnter(DropTargetDragEvent dtde) {
                dragEnterWasSuccessful = true;
            }
            @Override
            public void drop(DropTargetDropEvent dtde) {
                dropWasSuccessful = true;
            }

        }, true));

        contentPane.add(dtPanel);
        this.setContentPane(contentPane);
        this.pack();
        this.setVisible(true);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    void doTest(int dragSourceX, int dragSourceY) {
        int dtPanelX = (int) dtPanel.getLocationOnScreen().getX();
        int dtPanelY = (int) dtPanel.getLocationOnScreen().getY();

        System.out.print("Check the inner border in the top left corner...\t");
        dragAndDrop(dragSourceX, dragSourceY, dtPanelX, dtPanelY);
        checkDragEnter();
        checkDragOver();
        checkNotDragExit();
        checkDropInsidePanel();
        System.out.println("OK");

        System.out.print("Check the outer border in the top left corner...\t");
        drag(dragSourceX, dragSourceY, dtPanelX, dtPanelY);
        mouseMove(dtPanelX, dtPanelY,dtPanelX - 1, dtPanelY - 1);
        checkDragExit();
        drop();
        checkDropOutsidePanel();
        System.out.println("OK");

        System.out.print("Check the inner border in the bottom right corner...\t");
        dragAndDrop(dragSourceX, dragSourceY, dtPanelX + dtPanel.getWidth() - 1, dtPanelY + dtPanel.getHeight() - 1);
        checkDragEnter();
        checkDragOver();
        checkNotDragExit();
        checkDropInsidePanel();
        System.out.println("OK");

        System.out.print("Check the outer border in the bottom right corner...\t");
        drag(dragSourceX, dragSourceY, dtPanelX + dtPanel.getWidth() - 1, dtPanelY + dtPanel.getHeight() - 1);
        mouseMove(dtPanelX + dtPanel.getWidth() - 1, dtPanelY + dtPanel.getHeight() - 1,
                dtPanelX + dtPanel.getWidth(), dtPanelY + dtPanel.getHeight());
        checkDragExit();
        drop();
        checkDropOutsidePanel();
        System.out.println("OK");
    }

    private void drag(int startX, int startY, int finishX, int finishY) {
        robot.mouseMove(startX, startY);
        robot.delay(50);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Point p = MouseInfo.getPointerInfo().getLocation();
        mouseMove(p.x, p.y, finishX, finishY);
        robot.delay(500);
    }

    private void drop() {
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(500);
    }

    private void dragAndDrop(int startX, int startY, int finishX, int finishY) {
        drag(startX, startY, finishX, finishY);
        drop();
    }

    private void checkDropInsidePanel() {
        if (!dropWasSuccessful) {
            System.out.println("FAILED");
            throw new RuntimeException("DnD failed: Drop was outside Target panel, but inside is expected");
        }
        dropWasSuccessful = false;
    }

    private void checkDropOutsidePanel() {
        if (dropWasSuccessful) {
            System.out.println("FAILED");
            throw new RuntimeException("DnD failed: Drop was inside Target panel, but outside is expected");
        }
    }

    private void checkDragExit() {
        if (!dragExitWasSuccessful) {
            System.out.println("FAILED");
            throw new RuntimeException("DnD failed: Drag exit was expected, but did not happened");
        }
        dragExitWasSuccessful = false;
    }

    private void checkNotDragExit() {
        if (dragExitWasSuccessful) {
            System.out.println("FAILED");
            throw new RuntimeException("DnD failed: Unexpected Drag exit");
        }
    }


    private void checkDragEnter() {
        if (!dragEnterWasSuccessful) {
            System.out.println("FAILED");
            throw new RuntimeException("DnD failed: Drag enter was expected, but did not happened");
        }
        dragEnterWasSuccessful = false;
    }

    private void checkDragOver() {
        if (!dragOverWasSuccessful) {
            System.out.println("FAILED");
            throw new RuntimeException("DnD failed: Drag over was expected, but did not happened");
        }
        dragOverWasSuccessful = false;
    }

    private void mouseMove(int startX, int startY, int finishX, int finishY) {
        mouseMove(new Point(startX, startY), new Point(finishX, finishY));
    }

    private void mouseMove(Point start, Point end) {
        robot.mouseMove(start.x, start.y);
        for (Point p = new Point(start); !p.equals(end);
             p.translate(Integer.compare(end.x - p.x, 0),
                     Integer.compare(end.y - p.y, 0))) {
            robot.mouseMove(p.x, p.y);
            robot.delay(10);
        }
        robot.mouseMove(end.x, end.y);
        robot.delay(100);
    }
}