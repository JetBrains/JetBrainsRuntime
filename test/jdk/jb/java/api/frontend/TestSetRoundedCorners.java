/*
 * Copyright 2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import com.jetbrains.JBR;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

/**
 * @test
 * @summary JBR-4563 Rounded corners for native Window on Mac OS
 * @summary JBR-4787 Rounded corners for native Window on Windows 11
 * @author Alexander Lobas
 * @requires (os.family == "mac" | os.family == "windows")
 * @run main/manual TestSetRoundedCorners
 */
public class TestSetRoundedCorners {
    private static final int TD_RED = 50;
    private static final int TD = 0;
    private static final int DELAY = 1000;

    private static TestSetRoundedCorners theTest;

    private final Robot robot;
    private JFrame frame;
    private JFrame testFrame;

    public TestSetRoundedCorners() {
        try {
            robot = new Robot();
        } catch (AWTException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void performTest(Object roundParams) {
        if (!JBR.isRoundedCornersManagerSupported()) {
            throw new RuntimeException("JBR Rounded API is not available");
        }

        runSwing(() -> {
            frame = new JFrame("");
            frame.setUndecorated(true);
            JPanel panel = new JPanel();
            panel.setOpaque(true);
            panel.setBackground(Color.RED);
            frame.setContentPane(panel);
            frame.setBounds(100, 100, 300, 300);
            frame.setVisible(true);

            testFrame = new JFrame("");
            testFrame.setUndecorated(true);
            JPanel testPanel = new JPanel();
            testPanel.setOpaque(true);
            testPanel.setBackground(Color.GREEN);
            testFrame.setContentPane(testPanel);
            testFrame.setBounds(150, 150, 50, 50);
            testFrame.setVisible(true);
        });

        robot.delay(DELAY);

        // check that window without rounded corners
        validateColor(51, 51, Color.GREEN, "0");
        validateColor(51, 99, Color.GREEN, "0");
        validateColor(99, 51, Color.GREEN, "0");
        validateColor(99, 99, Color.GREEN, "0");

        runSwing(() -> testFrame.setVisible(false));
        robot.delay(DELAY);

        runSwing(() -> {
            JBR.getRoundedCornersManager().setRoundedCorners(testFrame, roundParams);
            testFrame.setVisible(true);
        });
        robot.delay(DELAY);

        // check that window with rounded corners
        validateColor(51, 51, Color.RED, "1");
        validateColor(51, 99, Color.RED, "1");
        validateColor(99, 51, Color.RED, "1");
        validateColor(99, 99, Color.RED, "1");

        runSwing(() -> dispose());
    }

    private Color getTestPixel(int x, int y) {
        Rectangle bounds = frame.getBounds();
        BufferedImage screenImage = robot.createScreenCapture(bounds);
        int rgb = screenImage.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return new Color(red, green, blue);
    }

    private static boolean validateColor(Color actual, Color expected) {
        return Math.abs(actual.getRed() - expected.getRed()) <= TD_RED &&
                Math.abs(actual.getGreen() - expected.getGreen()) <= TD &&
                Math.abs(actual.getBlue() - expected.getBlue()) <= TD;
    }

    private void validateColor(int x, int y, Color expected, String place) {
        Color actual = getTestPixel(x, y);
        if (!validateColor(actual, expected)) {
            throw new RuntimeException(
                    "Test failed [" + place + "]. Incorrect color " + actual + " instead " + expected + " at (" + x + "," + y + ")");
        }
    }

    private static void runSwing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public void dispose() {
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
        if (testFrame != null) {
            testFrame.dispose();
            testFrame = null;
        }
    }

    public static void runTest(Object roundParams) {
        try {
            runSwing(() -> theTest = new TestSetRoundedCorners());
            theTest.performTest(roundParams);
        } finally {
            if (theTest != null) {
                runSwing(() -> theTest.dispose());
            }
        }
    }

    public static void main(String[] args) {
        String osName = System.getProperty("os.name");
        if (osName.contains("Windows 11")) {
            runTest("full");
        } else if (osName.contains("OS X")) {
            runTest(10F);
        } else {
            System.out.println("This test is for MacOS or Windows 11 only. Automatically passed on other platforms.");
        }
    }
}
