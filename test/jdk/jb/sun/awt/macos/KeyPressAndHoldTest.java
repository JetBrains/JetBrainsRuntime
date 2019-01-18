/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JRE-998: Input freezes after MacOS key-selector on Mojave
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @run main KeyPressAndHoldTest
 */

/*
 * Description: Tests that user input continues normally after using Press&Hold feature of maxOS.
 * Robot holds down sample key so accent popup menu may appear and then types sample text.
 * Test passes if the sample text was typed correctly.
 *
 * Note: Test works with English keyboard layout.
 * Test requires Press&Hold feature of maxOS (enabled by default for macOS >= 10.7).
 * MacOS accessibility permission should also be granted for the application launching this test, so
 * Java Robot is able to access keyboard (use System Preferences -> Security & Privacy -> Privacy tab -> Accessibility).
 */

public class KeyPressAndHoldTest {

    private static final int SAMPLE_KEY = KeyEvent.VK_E;

    private static final String SAMPLE = "échantillon";
    private static final String SAMPLE_BS = "chantillon";
    private static final String SAMPLE_NO_ACCENT = "echantillon";
    private static final String SAMPLE_MISPRINT = "e0chantillon";

    private static final String PRESS_AND_HOLD_IS_DISABLED = "eeeeeeeeee";

    private static final int PAUSE = 2000;
    private static final int TIMEOUT = PAUSE*10;

    private static volatile String result="";

    private static Robot robot;

    private static int exitValue = 0;

    /*
     * Returns macOS major and minor version as an integer
     */
    private static int getMajorMinorMacOsVersion() {
        int version = 0;
        String versionProp = System.getProperty("os.version");
        if (versionProp != null && !versionProp.isEmpty()) {
            String[] versionComponents = versionProp.split("\\.");
            String majorMinor =  versionComponents[0];
            if (versionComponents.length > 1) {
                majorMinor += versionComponents[1];
            }
            try {
                version = Integer.parseInt(majorMinor);
            } catch (NumberFormatException nfexception) {
                // Do nothing
            }
        }
        return version;
    }

    /*
     * Hold down sample key so accents popup menu may appear
     */
    private static void holdDownSampleKey() {
        robot.waitForIdle();
        for (int i = 0; i < 10; i++) {
            robot.keyPress(SAMPLE_KEY);
        }
        robot.keyRelease(SAMPLE_KEY);
    }

    /*
     * Type sample text except the first sample character
     */
    private static void typeSampleBody() {
        robot.delay(PAUSE);
        for (int utfCode : SAMPLE.substring(1).toCharArray()) {
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(utfCode);
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        }
        robot.delay(PAUSE);
        robot.waitForIdle();
    }

    /*
     * Just check if accent popup appears, select no accent
     */
    private static void checkAccentPopup() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_KP_DOWN);
        robot.keyRelease(KeyEvent.VK_KP_DOWN);
        robot.delay(PAUSE);
        robot.waitForIdle();
    }

    /*
     * Type sample by selecting accent for the sample key from the popup dialog
     */
    private static void sample() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_2);
        robot.keyRelease(KeyEvent.VK_2);
        typeSampleBody();
    }

    /*
     * Do not select any accent for the sample key but press Backspace to delete it
     */
    private static void sampleBS() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_BACK_SPACE);
        robot.keyRelease(KeyEvent.VK_BACK_SPACE);
        typeSampleBody();
    }

    /*
     * Do not select any accent for the sample key from the popup dialog just press Esc
     */
    private static void sampleNoAccent() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        typeSampleBody();
    }

    /*
     * Miss to select any accent for the sample key by pressing 0
     */
    private static void sampleMisprint() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_0);
        robot.keyRelease(KeyEvent.VK_0);
        typeSampleBody();
    }

    private static void checkResult(String testName, String expected) {
        if (expected.equals(result)) {
            System.out.println(testName + ": ok");
        } else {
            System.err.println(testName + ": failed, expected \"" + expected + "\", but received \"" + result + "\"");
            exitValue = 1;
        }
    }

    public static void main(String[] args) throws InterruptedException, InvocationTargetException {

        if (GraphicsEnvironment.isHeadless()) {
            throw new RuntimeException("ERROR: Cannot execute the test in headless environment");
        }

        int osVersion  = getMajorMinorMacOsVersion();
        if (osVersion == 0) {
            throw new RuntimeException("ERROR: Cannot determine MacOS version");
        } else if (osVersion < 107) {
            System.out.println("TEST SKIPPED: No Press&Hold feature for Snow Leopard or lower MacOS version");
            return;
        }

        final JFrame frame = new JFrame(KeyPressAndHoldTest.class.getName());
        final CountDownLatch frameGainedFocus = new CountDownLatch(1);
        final JTextArea textArea = new JTextArea();

        final WindowAdapter frameFocusListener = new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                frameGainedFocus.countDown();
            }
        };

        final DocumentListener documentListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                result = textArea.getText();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                result = textArea.getText();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                // No such events for plain text components
            }
        };

        final Runnable frameRunner = () -> {
            textArea.getDocument().addDocumentListener(documentListener);
            frame.getContentPane().add(textArea);
            frame.setSize(400, 200);
            frame.setLocation(100, 100);
            frame.addWindowFocusListener(frameFocusListener);
            frame.setVisible(true);
        };

        final Runnable cleanTextArea = () -> textArea.setText("");

        final Runnable disposeRunner = () -> {
            textArea.getDocument().removeDocumentListener(documentListener);
            frame.removeWindowFocusListener(frameFocusListener);
            frame.dispose();
        };

        try {
            robot = new Robot();
            robot.setAutoDelay(50);

            SwingUtilities.invokeLater(frameRunner);
            if(!frameGainedFocus.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Test ERROR: Cannot focus on the test frame");
            }

            checkAccentPopup();
            if (PRESS_AND_HOLD_IS_DISABLED.equals(result)) {
                throw new RuntimeException("ERROR: Holding a key down causes the key repeat instead of " +
                        "accent menu popup, please check if Press&Hold feature of maxOS >= 10.7 is enabled");
            }
            SwingUtilities.invokeLater(cleanTextArea);

            sample();
            checkResult("AccentChar", SAMPLE);
            SwingUtilities.invokeLater(cleanTextArea);

            sampleBS();
            checkResult("BackspaceAccentChar", SAMPLE_BS);
            SwingUtilities.invokeLater(cleanTextArea);

            sampleNoAccent();
            checkResult("NoAccentChar", SAMPLE_NO_ACCENT);
            SwingUtilities.invokeLater(cleanTextArea);

            sampleMisprint();
            checkResult("MisprintAccentChar", SAMPLE_MISPRINT);
            SwingUtilities.invokeLater(cleanTextArea);

            if (exitValue == 0) {
                System.out.println("TEST PASSED");
            } else {
                throw new RuntimeException("TEST FAILED: Some samples were typed incorrectly");
            }

        } catch (AWTException awtException) {
            throw new RuntimeException("ERROR: Cannot create Robot", awtException);
        } finally {
            SwingUtilities.invokeAndWait(disposeRunner);
            /* Waiting for EDT auto-shutdown */
            Thread.sleep(PAUSE);
        }
    }
}
