/*
 * Copyright 2000-2018 JetBrains s.r.o.
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
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;

/**
 * @test
 * @summary Regression test for JRE-1054: Weird non-modal dialog above modal dialog behaviour
 * @requires jdk.version.major >= 8
 * @run main JDialog1054
 */

/*
 * Description: Test checks that modal dialog is shown above of non-modal dialog.
 * Test opens non-modal dialog and then bigger modal dialog colored with sample color and located to cover non-modal one.
 * Test captures part of the screen where non-modal dialog is located and checks if it is colored with the sample color.
 *
 * Note: In case of running the test on remote desktop or vm use the same screen resolution as for your primary monitor.
 * MacOS accessibility permission should also be granted for the application launching this test,
 * so Robot is able to access keyboard (use System Preferences->Security&Privacy->Accessibility->Privacy).
 */

public class JDialog1054 {

    private static Robot robot;

    /*
     * Checks if all pixels of the image are colored with the same sample color
     */
    private static boolean checkImage(BufferedImage screenImage, Color expectedColor) {

        final int expectedRGB = expectedColor.getRGB();

        for (int x = 0; x < screenImage.getWidth(); x++) {
            for (int y = 0; y < screenImage.getHeight(); y++) {
                int actualRGB = screenImage.getRGB(x, y);
                if (actualRGB != expectedRGB) {
                    System.err.println("Wrong rgb value: " + Integer.toHexString(actualRGB) + " != "
                            + Integer.toHexString(expectedRGB) + " at (" + x + ", " + y + ")");
                    return false;
                }
            }
        }
        return true;
    }

    /*
     * Tests that modal dialog is shown above of non-modal dialog.
     * Checks two cases:
     *     1) non-modal dialog is focused when opening modal dialog
     *     2) owner frame is focused when opening modal dialog
     */
    private static boolean test(boolean nonModalDialogIsFocused) throws InvocationTargetException, InterruptedException {

        System.out.println("Test for the case when \"non-modal dialog is focused\" = " + nonModalDialogIsFocused + ":");

        final int size = 100;
        final int pause = 2000;
        final int indent = size/4;
        // Use black color trying to avoid transparency effects
        final Color sampleColor = Color.BLACK;

        final JFrame owner = new JFrame("Owner");
        final JDialog nonModalDialog = new JDialog(owner, "Non-modal Dialog");
        final JDialog modalDialog = new JDialog(owner, "Modal Dialog", true);
        final JButton nonModalDialogButton = new JButton("screen area to capture");

        // Non-modal dialog is smaller than modal dialog,
        // So non-modal is completely covered by modal when it appears
        final Dimension nonModalDim = new Dimension(2*size, size);
        final Dimension modalDim = new Dimension(4*size, 2*size);
        final Dimension ownerDim = new Dimension(6*size, 4*size);

        final Point nonModalLoc = new Point(size + 2*indent, size + 2*indent);
        final Point modalLoc = new Point(size + indent, size + indent);
        final Point ownerLoc = new Point(size, size);

        final CountDownLatch nonModalDialogGainedFocus = new CountDownLatch(1);
        final CountDownLatch modalDialogGainedFocus = new CountDownLatch(1);

        final WindowAdapter dialogListener = new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                if (e.getWindow() == nonModalDialog) {
                    nonModalDialogGainedFocus.countDown();
                }
                if (e.getWindow() == modalDialog) {
                    modalDialogGainedFocus.countDown();
                }
            }
        };

        final Runnable frameRunner = () -> {
            owner.setLocation(ownerLoc);
            owner.setSize(ownerDim);
            owner.setVisible(true);
            nonModalDialog.setLocation(nonModalLoc);
            nonModalDialog.setSize(nonModalDim);
            nonModalDialog.getContentPane().add(nonModalDialogButton,  BorderLayout.CENTER);
            nonModalDialog.addWindowFocusListener(dialogListener);
            nonModalDialog.setVisible(true);
        };

        final Runnable modalDialogRunner = () -> {
            modalDialog.setLocation(modalLoc);
            modalDialog.setSize(modalDim);
            // Paint the whole non-modal dialog pane over with the same sample color
            modalDialog.getContentPane().setBackground(sampleColor);
            modalDialog.addWindowFocusListener(dialogListener);
            modalDialog.setVisible(true);
        };

        final Runnable disposeRunner = () -> {
            modalDialog.removeWindowListener(dialogListener);
            nonModalDialog.removeWindowFocusListener(dialogListener);
            modalDialog.dispose();
            nonModalDialog.dispose();
            owner.dispose();
        };

        try {
            System.out.println("Open owner frame and non-modal dialog");
            SwingUtilities.invokeLater(frameRunner);
            nonModalDialogGainedFocus.await();
            System.out.println("Non-modal dialog gained focus");
            // Wait for a while to improve the visibility of the test run
            Thread.sleep(pause);

            if (!nonModalDialogIsFocused) {
                System.out.println("Request focus to the owner frame");
                SwingUtilities.invokeLater(owner::requestFocus);
                System.out.println("Owner frame gained focus");
                // Wait for a while to improve the visibility of the test run
                Thread.sleep(pause);
            }

            System.out.println("Open modal dialog");
            SwingUtilities.invokeLater(modalDialogRunner);
            modalDialogGainedFocus.await();
            System.out.println("Modal dialog gained focus");
            // Wait for a while before screen capture so any graphic effects appear
            Thread.sleep(pause);

            // Capture only non-modal dialog button area
            Rectangle capture = new Rectangle(nonModalDialogButton.getLocationOnScreen(), nonModalDialogButton.getSize());
            BufferedImage sampleImage = robot.createScreenCapture(capture);

            // Wait for robot to complete image buffering
            robot.waitForIdle();

            System.out.println("Check if modal dialog is shown on top of other windows");
            // Non-modal dialog button area should be all colored with the sample color,
            // As non-modal dialog should be completely covered by modal one
            boolean result = checkImage(sampleImage, sampleColor);
            if (result) {
                System.out.println("Test case PASSED\n");
            } else {
                Rectangle ownerArea = new Rectangle(owner.getLocationOnScreen(), owner.getSize());
                Rectangle nonModalArea = new Rectangle(nonModalDialog.getLocationOnScreen(), nonModalDialog.getSize());
                Rectangle modalArea = new Rectangle(modalDialog.getLocationOnScreen(), modalDialog.getSize());
                System.out.println("Captured screen area: " + capture);
                System.out.println("Non-modal dialog location: " + nonModalArea);
                System.out.println("Modal dialog location: " + modalArea);
                System.out.println("Owner frame location: " + ownerArea);

                // Capture the whole picture: owner frame and both dialogs
                BufferedImage fullImage = robot.createScreenCapture(ownerArea);
                String imageName = JDialog1054.class.getName()
                        + ((nonModalDialogIsFocused) ? "_dialogFocused" : "_frameFocused");
                String format = "bmp";
                try {
                    ImageIO.write(sampleImage, format, new File(imageName + "." + format));
                    ImageIO.write(fullImage, format, new File(imageName + "_full." + format));
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
                System.err.println("Test case FAILED: Modal dialog is not on the top of other windows\n");
            }
            return result;

        } finally {
            SwingUtilities.invokeAndWait(disposeRunner);
            // Waiting for EDT auto-shutdown
            Thread.sleep(pause);
        }
    }

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {

        if (GraphicsEnvironment.isHeadless()) {
            throw new RuntimeException("ERROR: Cannot execute the test in headless environment");
        }

        try {
            robot = new Robot();
        } catch (AWTException awtException) {
            throw new RuntimeException("ERROR: Cannot create Robot", awtException);
        }

        boolean nonModalDialogFocusedRes = test(true);
        boolean frameFocusedRes = test(false);

        if (nonModalDialogFocusedRes && frameFocusedRes) {
            System.out.println("Test PASSED");
        } else {
            throw new RuntimeException("Test FAILED: Some test cases have not passed");
        }
    }
}
