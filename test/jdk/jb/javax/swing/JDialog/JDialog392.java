/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/* @test
 * @summary regression test on JRE-392 Tip of the day is not hidden while another modal window is shown
 * @run main/othervm JDialog392
 */


// The test displays two modal dialogs one by one
// and checks that the latest modal dialog would be on top of all windows
public class JDialog392 implements Runnable {

    private static JFrame frame = new JFrame("JDialog392");

    private static boolean verbose = false;
    private static boolean passed = true;

    static DialogThread modalDialogThread1;

    static DialogThread modalDialogThread2;

    static class DialogThread {

        JDialog dialog;

        private String dialogTitle;
        private Point location;
        private int width;
        private int height;
        private DialogListener eventListener;


        DialogThread(String dialogTitle, Point location, int width, int height, DialogListener eventListener) {
            this.dialogTitle = dialogTitle;
            this.location = location;
            this.width = width;
            this.height = height;
            this.eventListener = eventListener;
        }

        void run() {
            dialog = new JDialog(frame, true);
            dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setTitle(dialogTitle);

            dialog.setLocation(location);
            dialog.setSize(width, height);

            if (eventListener != null)
                dialog.addWindowListener(eventListener);

            dialog.setVisible(true);
        }

        void removeWindowListener() {
            dialog.removeWindowListener(eventListener);
        }
    }

    static abstract class DialogListener implements WindowListener {

        @Override
        public void windowClosing(WindowEvent e) {

        }

        @Override
        public void windowClosed(WindowEvent e) {

        }

        @Override
        public void windowIconified(WindowEvent e) {

        }

        @Override
        public void windowDeiconified(WindowEvent e) {

        }

        @Override
        public void windowActivated(WindowEvent e) {

        }

        @Override
        public void windowDeactivated(WindowEvent e) {

        }
    }

    static class FirstDialogListener extends DialogListener {
        @Override
        public void windowOpened(WindowEvent windowEvent) {
            modalDialogThread1.removeWindowListener();
            modalDialogThread2 = new DialogThread(
                    "Modal input 2",
                    new Point(5, 50),
                    300, 200,
                    new SecondDialogListener());
            modalDialogThread2.run();
        }
    }

    static class SecondDialogListener extends DialogListener {
        @Override
        public void windowOpened(WindowEvent windowEvent) {
            try {
                Robot robot = new Robot();
                Dimension shotSize = modalDialogThread2.dialog.getContentPane().getSize();
                Rectangle captureRect = new Rectangle(modalDialogThread2.dialog.getContentPane().getLocationOnScreen(), shotSize);

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                BufferedImage screenImage = robot.createScreenCapture(captureRect);

                int rgb;
                int expectedRGB = screenImage.getRGB((int) (shotSize.getWidth() / 2), (int) (shotSize.getHeight() / 2)) & 0x00FFFFFF;

                for (int col = 1; col < shotSize.getWidth(); col++) {
                    for (int row = 1; row < shotSize.getHeight()/2; row++) {
                        try {
                            // remove transparance
                            rgb = screenImage.getRGB(col, row) & 0x00FFFFFF;

                            if (verbose)
                                System.out.print((rgb == expectedRGB) ? " ." : " " + Integer.toHexString(rgb));

                            passed = passed & (expectedRGB == rgb);

                        } catch (ArrayIndexOutOfBoundsException e) {
                            throw new RuntimeException(e);
                        }

                    }
                    if (verbose)
                        System.out.println();
                }
                ImageIO.write(screenImage, "bmp", new File("test392.bmp"));

                if (!passed)
                    throw new RuntimeException("The second dialog window was not on top");

            } catch (AWTException | IOException e) {
                throw new RuntimeException(e);
            }
            modalDialogThread2.dialog.dispose();
            modalDialogThread1.dialog.dispose();
            frame.dispose();
        }
    }

    public void run() {
        frame.setSize(350, 300);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setVisible(true);

        modalDialogThread1 = new DialogThread(
                "Modal input 1",
                new Point(10, 75),
                250, 150,
                new FirstDialogListener());
        modalDialogThread1.run();
    }

    public static void main(String[] args) throws Exception {
        JDialog392.verbose = Arrays.asList(args).contains("-verbose");
        try {
            SwingUtilities.invokeAndWait(new JDialog392());
        } catch (InterruptedException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
