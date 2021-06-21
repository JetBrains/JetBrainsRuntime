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


import sun.lwawt.macosx.CPlatformWindow;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


/**
 * @test
 * @summary Regression test for JBR-3544 ensures that pressing Cmd+Opt+N / Ctrl+Opt+N sends precisely one event for
 *          pressing N and exactly with the Cmd+Opt / Ctrl+Opt modifiers respectively and regardless of whether
 *          macOS tabbing mode is enabled (-Djdk.allowMacOSTabbedWindows=true + java.awt.Window.setTabbingMode) or not
 * @author Nikita Provotorov
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/java.awt:open java.desktop/sun.lwawt.macosx
 * @run main/othervm/timeout=5 -Djdk.allowMacOSTabbedWindows=false CmdOptNMustNotGenerateCmdN3544
 * @run main/othervm/timeout=5 -Djdk.allowMacOSTabbedWindows=true  CmdOptNMustNotGenerateCmdN3544
 */
public class CmdOptNMustNotGenerateCmdN3544 extends JFrame {
    public static void main(String[] args) throws Exception {
        System.out.println("Checking that the key of sun.lwawt.macosx.MAC_OS_TABBED_WINDOW is still \"jdk.allowMacOSTabbedWindows\"..."); System.out.flush();
        if (!Objects.equals(System.getProperty("jdk.allowMacOSTabbedWindows"), CPlatformWindow.MAC_OS_TABBED_WINDOW)) {
            throw new IllegalStateException(
                "System.getProperty(\"jdk.allowMacOSTabbedWindows\") != sun.lwawt.macosx.MAC_OS_TABBED_WINDOW. Most likely sun.lwawt.macosx.MAC_OS_TABBED_WINDOW uses a different key."
            );
        }

        System.out.println("Ok. Initializing..."); System.out.flush();

        // Setup key event listener
        Toolkit.getDefaultToolkit().addAWTEventListener(
            keyEvent -> awaitableEvents.add(new AwaitableEvent((KeyEvent)keyEvent)),
            AWTEvent.KEY_EVENT_MASK
        );

        final Robot robot = createRobot();

        createAndShowGUI();

        try {
            robot.waitForIdle();

            { // Option+Cmd+N
                System.out.println("Done. Pressing Option+Cmd+N..."); System.out.flush();
                pressModifierOptNVia(robot, KeyEvent.VK_META);

                System.out.println("Done. Awaiting events..."); System.out.flush();
                robot.waitForIdle();

                System.out.println("Done. Verifying the events..."); System.out.flush();
                ensureGeneratedEventsAreOk(KeyEvent.VK_META, KeyEvent.META_DOWN_MASK, "Cmd");

                System.out.println("Ok."); System.out.flush();
            }

            robot.delay(200); // just in case

            { // Option+Ctrl+N
                System.out.println("Pressing Option+Ctrl+N..."); System.out.flush();
                pressModifierOptNVia(robot, KeyEvent.VK_CONTROL);

                System.out.println("Done. Awaiting events..."); System.out.flush();
                robot.waitForIdle();

                System.out.println("Done. Verifying the events..."); System.out.flush();
                ensureGeneratedEventsAreOk(KeyEvent.VK_CONTROL, KeyEvent.CTRL_DOWN_MASK, "Ctrl");

                System.out.println("Ok."); System.out.flush();
            }
        } finally {
            disposeGUI();
        }
    }


    // GUI

    private static volatile CmdOptNMustNotGenerateCmdN3544 mainWindow;
    private static volatile JMenuItem optionCmdNMenuItem;
    private static volatile JMenuItem optionCtrlNMenuItem;

    private static volatile FocusListener focusListener;

    private CmdOptNMustNotGenerateCmdN3544() {
        super("JBR-3544");
    }

    private static void createAndShowGUI() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            mainWindow = new CmdOptNMustNotGenerateCmdN3544();
            mainWindow.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            if (Boolean.parseBoolean(CPlatformWindow.MAC_OS_TABBED_WINDOW)) {
                // enable tabbing mode
                try {
                    final var setTabbingModeMethod = Window.class.getDeclaredMethod("setTabbingMode");
                    setTabbingModeMethod.setAccessible(true);
                    setTabbingModeMethod.invoke(mainWindow);
                } catch (Exception err) {
                    throw new RuntimeException(err);
                }
            }

            { // set up the menu
                optionCmdNMenuItem = new JMenuItem("OptionCmdN action");
                optionCmdNMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.ALT_DOWN_MASK | KeyEvent.META_DOWN_MASK, false));
                optionCmdNMenuItem.addActionListener(actionEvent -> {
                    System.out.println(optionCmdNMenuItem.getText() + " has been triggered. " + actionEvent);
                    System.out.flush();
                    awaitableEvents.add(new AwaitableEvent(actionEvent, optionCmdNMenuItem));
                });

                optionCtrlNMenuItem = new JMenuItem("OptionCtrlN action");
                optionCtrlNMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.ALT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK, false));
                optionCtrlNMenuItem.addActionListener(actionEvent -> {
                    System.out.println(optionCtrlNMenuItem.getText() + " has been triggered. " + actionEvent);
                    System.out.flush();
                    awaitableEvents.add(new AwaitableEvent(actionEvent, optionCtrlNMenuItem));
                });

                final var myMenu = new JMenu("My JMenu");
                myMenu.add(optionCtrlNMenuItem);
                myMenu.add(optionCmdNMenuItem);

                final var myMenuBar = new JMenuBar();
                myMenuBar.add(myMenu);

                mainWindow.setJMenuBar(myMenuBar);
            }

            final var myFocusListener = new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    final var errorMsg = String.format("FOCUS LOST! %s", e);
                    System.err.println(errorMsg);
                    awaitableEvents.add(new AwaitableEvent(new RuntimeException(errorMsg)));
                }
            };

            mainWindow.addFocusListener(myFocusListener);
            focusListener = myFocusListener;

            mainWindow.pack();
            mainWindow.setSize(800, 500);

            mainWindow.setLocationRelativeTo(null);

            // Don't do this, it's somehow incompatible with tabbing mode
            //mainWindow.setAlwaysOnTop(true);

            mainWindow.setVisible(true);

            mainWindow.requestFocus();
        });
    }

    private static void disposeGUI() throws InterruptedException, InvocationTargetException {
        final var localFocusListener = focusListener;
        if (localFocusListener != null) {
            focusListener = null;
        }

        final var localMainWindow = mainWindow;
        if (localMainWindow != null) {
            mainWindow = null;
            SwingUtilities.invokeAndWait(() -> {
                // To avoid false positive errors while the window is disposing
                localMainWindow.removeFocusListener(localFocusListener);

                localMainWindow.dispose();
            });
        }
    }


    // Other utilities

    private static Robot createRobot() throws AWTException {
        final Robot robot = new Robot();
        robot.setAutoWaitForIdle(false);
        robot.setAutoDelay(0);
        return robot;
    }

    private static void pressModifierOptNVia(Robot robot, int modifierVk) {
        robot.keyPress(modifierVk);
        try {
            robot.delay(80);

            robot.keyPress(KeyEvent.VK_ALT);
            try {
                robot.delay(100);

                robot.keyPress(KeyEvent.VK_N);
                try {
                    robot.delay(50);
                } finally {
                    robot.keyRelease(KeyEvent.VK_N);
                }
                robot.delay(100);

            } finally {
                robot.keyRelease(KeyEvent.VK_ALT);
            }
            robot.delay(80);
        } finally {
            robot.keyRelease(modifierVk);
        }
    }


    // Events reporting and handling

    /** Either KeyEvent or (ActionEvent, JMenuItem) or Throwable */
    private static final class AwaitableEvent {
        final KeyEvent keyEvent;

        final ActionEvent actionEvent;
        final JMenuItem triggeredMenuItem;

        final Throwable criticalError;


        AwaitableEvent(KeyEvent keyEvent) {
            this.keyEvent = Objects.requireNonNull(keyEvent, "keyEvent == null");
            this.actionEvent = null;
            this.triggeredMenuItem = null;
            this.criticalError = null;
        }

        AwaitableEvent(ActionEvent actionEvent, JMenuItem triggeredMenuItem) {
            this.keyEvent = null;
            this.actionEvent = Objects.requireNonNull(actionEvent, "actionEvent == null");
            this.triggeredMenuItem = Objects.requireNonNull(triggeredMenuItem, "triggeredMenuItem == null");
            this.criticalError = null;
        }

        AwaitableEvent(Throwable criticalError) {
            this.keyEvent = null;
            this.actionEvent = null;
            this.triggeredMenuItem = null;
            this.criticalError = Objects.requireNonNull(criticalError, "criticalError == null");
        }


        @Override
        public String toString() {
            return (keyEvent == null) ? actionEvent + " ON " + triggeredMenuItem : keyEvent.toString();
        }
    }

    private static final BlockingQueue<AwaitableEvent> awaitableEvents = new LinkedBlockingQueue<>();

    private static void ensureGeneratedEventsAreOk(int modifierVk, int modifierMaskEx, String modifierText) {
        // Ensures:
        //   1. About expected specific key events order:
        //       1. The modifier Cmd/Ctrl PRESS
        //       2. The modifier Option PRESS
        //       3. N PRESS
        //       4. N RELEASE
        //       5. The modifier Option RELEASE
        //       6. The modifier Cmd/Ctrl RELEASE
        //   2. That there are no excess key events
        //   3. That there was exactly 1 ActionEvent exactly by the corresponding menu item

        final var expectedMenuItem = (modifierVk == KeyEvent.VK_META) ? optionCmdNMenuItem : optionCtrlNMenuItem;

        boolean modifierPressedFound = false;
        boolean optionPressedFound = false;
        boolean nPressedFound = false;
        boolean nReleasedFound = false;
        boolean optionReleasedFound = false;
        boolean modifierReleasedFound = false;
        int actionEventsCount = 0;

        boolean anyErrorOccurred = false;

        while (!awaitableEvents.isEmpty()) {
            final var event = awaitableEvents.poll();

            if (event.criticalError != null) {
                throw new RuntimeException("Critical error occurred", event.criticalError);
            }

            final var keyEvent = event.keyEvent;

            if (keyEvent == null) {
                // ActionEvent
                if (event.triggeredMenuItem != expectedMenuItem) {
                    anyErrorOccurred = true;
                    System.err.printf("Error: found ActionEvent for unexpected menu item: %s\n", event);
                }

                if (++actionEventsCount != 1) {
                    anyErrorOccurred = true;
                    System.err.printf(
                        "Error: expected exactly 1 ActionEvent, but got another one (%dth): %s\n",
                        actionEventsCount,
                        event
                    );
                }

                continue;
            }

            if (!modifierPressedFound) {            // Firstly, look for Cmd/Ctrl press
                if ( (keyEvent.getID() == KeyEvent.KEY_PRESSED) &&
                        (keyEvent.getKeyCode() == modifierVk) &&
                        (keyEvent.getModifiersEx() == modifierMaskEx) ) {
                    modifierPressedFound = true;
                } else {
                    anyErrorOccurred = true;
                    System.err.printf("Error: expecting '%s' KEY_PRESSED, but got %s\n", modifierText, keyEvent);
                }
                continue;
            } else if (!optionPressedFound) {       // Secondly, look for Option press
                if ( (keyEvent.getID() == KeyEvent.KEY_PRESSED) &&
                        (keyEvent.getKeyCode() == KeyEvent.VK_ALT) &&
                        (keyEvent.getModifiersEx() == (modifierMaskEx | KeyEvent.ALT_DOWN_MASK)) ) {
                    optionPressedFound = true;
                } else {
                    anyErrorOccurred = true;
                    System.err.printf("Error: expecting 'Option' KEY_PRESSED, but got %s\n", keyEvent);
                }
                continue;
            } else if (!nPressedFound) {            // Third, look for N press
                if ( (keyEvent.getID() == KeyEvent.KEY_PRESSED) &&
                        (keyEvent.getKeyCode() == KeyEvent.VK_N) &&
                        (keyEvent.getModifiersEx() == (modifierMaskEx | KeyEvent.ALT_DOWN_MASK)) ) {
                    nPressedFound = true;
                } else {
                    anyErrorOccurred = true;
                    System.err.printf("Error: expecting 'N [Option+%s]' KEY_PRESSED, but got %s\n", modifierText, keyEvent);
                }
                continue;
            } else if (!nReleasedFound) {           // Fourth, look for N release
                if ( (modifierVk == KeyEvent.VK_CONTROL) &&
                        (keyEvent.getID() == KeyEvent.KEY_TYPED) &&
                        (keyEvent.getModifiersEx() == (modifierMaskEx | KeyEvent.ALT_DOWN_MASK)) &&
                        (keyEvent.getKeyChar() == 14) ) {
                    // Suppressing the Shift Out ASCII character that is generated by Ctrl+N
                    continue;
                }

                if ( (keyEvent.getID() == KeyEvent.KEY_RELEASED) &&
                        (keyEvent.getKeyCode() == KeyEvent.VK_N) &&
                        (keyEvent.getModifiersEx() == (modifierMaskEx | KeyEvent.ALT_DOWN_MASK)) ) {
                    nReleasedFound = true;
                } else {
                    anyErrorOccurred = true;
                    System.err.printf("Error: expecting 'N [Option+%s]' KEY_RELEASED, but got %s\n", modifierText, keyEvent);
                }
                continue;
            } else if (!optionReleasedFound) {    // Fifth, look for Option release
                if ( (keyEvent.getID() == KeyEvent.KEY_RELEASED) &&
                        (keyEvent.getKeyCode() == KeyEvent.VK_ALT) ) {
                    optionReleasedFound = true;
                } else {
                    anyErrorOccurred = true;
                    System.err.printf("Error: expecting 'Option' KEY_RELEASED, but got %s\n", keyEvent);
                }
                continue;
            } else if (!modifierReleasedFound) {    // Sixth, look for Cmd/Ctrl release
                if ( (keyEvent.getID() == KeyEvent.KEY_RELEASED) &&
                        (keyEvent.getKeyCode() == modifierVk) ) {
                    modifierReleasedFound = true;
                } else {
                    anyErrorOccurred = true;
                    System.err.printf("Error: expecting '%s' KEY_RELEASED, but got %s\n", modifierText, keyEvent);
                }
                continue;
            }

            // Finally, any other events are excess

            anyErrorOccurred = true;
            System.err.printf("Error: excess key event: %s\n", keyEvent);
        }

        if (!modifierPressedFound) {
            anyErrorOccurred = true;
            System.err.printf("Error: '%s' KEY_PRESSED hasn't been found\n", modifierText);
        }
        if (!optionPressedFound) {
            anyErrorOccurred = true;
            System.err.printf("Error: 'Option' KEY_PRESSED hasn't been found\n");
        }
        if (!nPressedFound) {
            anyErrorOccurred = true;
            System.err.printf("Error: 'N [Option+%s]' KEY_PRESSED hasn't been found\n", modifierText);
        }
        if (!nReleasedFound) {
            anyErrorOccurred = true;
            System.err.printf("Error: 'N [Option+%s]' KEY_RELEASED hasn't been found\n", modifierText);
        }
        if (!optionReleasedFound) {
            anyErrorOccurred = true;
            System.err.printf("Error: 'Option' KEY_RELEASED hasn't been found\n");
        }
        if (!modifierReleasedFound) {
            anyErrorOccurred = true;
            System.err.printf("Error: '%s' KEY_RELEASED hasn't been found\n", modifierText);
        }

        if (actionEventsCount != 1) {
            anyErrorOccurred = true;
            System.err.printf("Error: %d ActionEvents have arrived instead of 1\n", actionEventsCount);
        }

        if (anyErrorOccurred) {
            throw new RuntimeException("Test failed");
        }
    }
}
