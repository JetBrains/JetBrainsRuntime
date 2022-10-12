/*
 * Copyright 2000-2022 JetBrains s.r.o.
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
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FocusTrackingApp {

    private static boolean focusLost = false;

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private static void scheduleTask(Runnable r, int delayInSeconds) {
        EXECUTOR.schedule(() -> SwingUtilities.invokeLater(r), delayInSeconds, TimeUnit.SECONDS);
    }

    public static void main(String... args) {
        SwingUtilities.invokeLater(() -> {
            JFrame parentFrame = new JFrame("FocusTrackingApp");
            parentFrame.setBackground(Color.CYAN);

            JPanel panel = new JPanel();
            panel.setBackground(Color.CYAN);
            JTextField textField = new JTextField(100);
            textField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    System.out.println("ERROR: Focus was lost.");
                    focusLost = true;
                }
            });

            panel.add(textField);
            parentFrame.add(panel);

            parentFrame.pack();
            parentFrame.setLocationRelativeTo(null);
            parentFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            parentFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            parentFrame.setVisible(true);

            parentFrame.requestFocus();

            try {
                textField.requestFocus();
                initTypingRobot(textField);
            } catch (AWTException e) {
                e.printStackTrace();
                System.exit(3);
            }

            scheduleTask(() -> {
                takeScreenshot();
                int exitCode = focusLost ? 1 : 0;
                System.exit(exitCode);
            }, 10);
        });
    }

    private static void takeScreenshot() {
        try {
            Robot robot = new Robot();
            BufferedImage screenShot = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));

            final String workingDir = System.getenv("PWD");
            final boolean focusWasRequestByAnotherApp = "true".equalsIgnoreCase(System.getProperty("focusWasRequested"));
            final String nameSuffix = focusWasRequestByAnotherApp ? "with-focus-requesting" : "without-focus-requesting";
            final String fileName = String.format("FocusTrackingApp_screenshot_%s.bmp", nameSuffix);

            ImageIO.write(screenShot, "BMP", Paths.get(workingDir, fileName).toFile());
        } catch (AWTException | IOException e) {
            System.out.println("ERROR: Unable to take screenshot");
            e.printStackTrace(System.out);
            System.exit(2);
        }
    }

    private static void initTypingRobot(JTextField textField) throws AWTException {
        Robot robot = new Robot();
        textField.requestFocus();

        for (int c = 0x30; c <= 0x60; c++) {
            robot.keyPress(c);
            robot.delay(50);
            robot.keyRelease(c);
            robot.delay(50);
        }
    }

}
