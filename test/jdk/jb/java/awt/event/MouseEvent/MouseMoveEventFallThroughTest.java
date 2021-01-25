/*
 * Copyright 2021 JetBrains s.r.o.
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
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @test
 * @summary Regression test for JBR-2702 Tooltips display through other applications on hover
 * @key headful
 */

public class MouseMoveEventFallThroughTest {
    private static JFrame topFrame;
    private static JFrame bottomFrame;
    private static final AtomicBoolean bottomFrameReceivedMouseMoved = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                bottomFrame = new JFrame("Bottom frame");
                bottomFrame.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        bottomFrameReceivedMouseMoved.set(true);
                    }
                });
                bottomFrame.setSize(200, 100);
                bottomFrame.setLocation(250, 250);
                bottomFrame.setVisible(true);
                topFrame = new JFrame("Top frame");
                topFrame.setSize(300, 200);
                topFrame.setLocation(200, 200);
                topFrame.setVisible(true);
            });
            Robot robot = new Robot();
            robot.delay(1000);
            robot.mouseMove(350, 300); // position mouse cursor over both frames
            robot.delay(1000);
            SwingUtilities.invokeAndWait(() -> {
                bottomFrame.setSize(210, 110);
            });
            robot.delay(1000);
            robot.mouseMove(351, 301); // move mouse cursor a bit
            robot.delay(1000);
            if (bottomFrameReceivedMouseMoved.get()) {
                throw new RuntimeException("Mouse moved event dispatched to invisible window");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (topFrame != null) topFrame.dispose();
                if (bottomFrame != null) bottomFrame.dispose();
            });
        }
    }
}
