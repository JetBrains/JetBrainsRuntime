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
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.concurrent.*;

/**

*/
public class WindowPopupApp {

    private static final String FOCUS_TRACKING_APP_CLASS_NAME = "FocusTrackingApp.java";

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private static void scheduleTask(Runnable r) {
        EXECUTOR.schedule(() -> SwingUtilities.invokeLater(r), 5, TimeUnit.SECONDS);
    }

    public static void main(String... args) {
        final String javaHome = System.getenv("TESTJAVA");
        final String testDir = System.getenv("TESTSRCPATH");
        final boolean requestFocus = "true".equalsIgnoreCase(System.getProperty("requestFocus"));

        SwingUtilities.invokeLater(() -> {
            JFrame parentFrame = new JFrame("WindowPopupApp");

            JPanel p = new JPanel();
            JButton openNonFocusedWindow = new JButton("Open non-focused window");
            openNonFocusedWindow.addActionListener(e -> scheduleTask(new OpenNewWindow(false)));
            p.add(openNonFocusedWindow);
            JButton openFocusedWindow = new JButton("Open focused window");
            openFocusedWindow.addActionListener(e -> scheduleTask(new OpenNewWindow(true)));

            p.add(openFocusedWindow);
            parentFrame.add(p);
            parentFrame.pack();
            parentFrame.setLocationRelativeTo(null);
            parentFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            parentFrame.setVisible(true);

            try {
                clickToButton(requestFocus ? openFocusedWindow : openNonFocusedWindow);
            } catch (AWTException e) {
                System.out.println("ERROR: unable to click to button");
                e.printStackTrace();
                System.exit(1);
            }

            runFocusTrackingApp(javaHome, testDir, requestFocus);
        });
    }

    private static void clickToButton(JButton button) throws AWTException {
        Robot robot = new Robot();
        Point location = button.getLocationOnScreen();
        robot.delay(1000);
        robot.mouseMove(location.x + button.getWidth() / 2, location.y + button.getHeight() / 2);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void runFocusTrackingApp(String javaHome, String testDir, boolean focusWasRequested) {
        ExecutorService service = Executors.newSingleThreadScheduledExecutor();
        service.submit(() -> {
            ProcessBuilder builder = new ProcessBuilder();
                builder.command(Paths.get(javaHome, "bin", "java").toString(),
                        String.format("-DfocusWasRequested=%b", focusWasRequested),
                        Paths.get(testDir, FOCUS_TRACKING_APP_CLASS_NAME).toString());

            try {
                Process process = builder.start();
                System.out.println("FocusTrackingApp has started");
                try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                int code = process.waitFor();

                if (code == 0) {
                    System.out.println("Focus tracking app has been successfully finished");
                } else if (code == 1) {
                    System.out.println("ERROR: focus tracking app have been lost the focus during the execution");
                } else {
                    System.out.println("ERROR: FocusTrackingApp internal error");
                }

                boolean result = isScreenshotHasArtifacts(focusWasRequested);
                if (code == 0 && result) {
                    System.out.println("TEST PASSED");
                    System.exit(0);
                } else {
                    System.out.println("TEST FAILED");
                    System.exit(1);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                System.exit(1);
            }
        });
    }

    private static boolean isScreenshotHasArtifacts(boolean focusWasRequested) throws IOException {
        final String workingDir = System.getenv("PWD");
        final String nameSuffix = focusWasRequested ? "with-focus-requesting" : "without-focus-requesting";
        final String fileName = String.format("FocusTrackingApp_screenshot_%s.bmp", nameSuffix);

        BufferedImage image = ImageIO.read(Paths.get(workingDir, fileName).toFile());

        // FocusTrackingApp has cyan background color and shouldn't have any other artifacts
        // Popup window by this application has green panel which shouldn't appear in the front
        int h = image.getHeight();
        int w = image.getWidth();
        int minX = (w / 2) - 200;
        int maxX = (w / 2) + 200;
        int minY = (h / 2) - 200;
        int maxY = (h / 2) + 200;

        int greenCounter = 0;
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                int pix = image.getRGB(x, y);
                int r = (pix >> 16) & 0xFF;
                int g = (pix >> 8) & 0xFF;
                int b = pix & 0xFF;

                if (r == 0 && g == 255 && b == 0) {
                    greenCounter++;
                }
            }
        }

        if (greenCounter > 0) {
            System.out.println("ERROR: Artifacts that's not related to FocusTrackingApp has found");
            return false;
        }
        return true;
    }

    private static class OpenNewWindow implements Runnable {
        private final boolean requestFocus;

        private OpenNewWindow(boolean requestFocus) {
            this.requestFocus = requestFocus;
        }

        @Override
        public void run() {
            JFrame f = new JFrame("child");
            f.setAutoRequestFocus(requestFocus);
            f.setSize(200, 100);
            f.getContentPane().setBackground(Color.green);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        }
    }

}
