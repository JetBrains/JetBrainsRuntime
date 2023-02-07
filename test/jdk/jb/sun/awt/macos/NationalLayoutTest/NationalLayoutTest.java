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

import sun.lwawt.macosx.LWCToolkit;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Label;
import java.awt.Panel;
import java.awt.Robot;
import java.awt.TextArea;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.text.CharacterIterator;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
 * Description: Tests national keyboard layouts on macOS.
 *
 * Test goes over existing Layout and Modifier enums.
 * Each layout key will be tested with all possible modifier combinations.
 * Test switches to the requested layout (or user changes it by hands).
 * Then Robot keeps pressing modifier + key, while test keeps tracking of keyPressed, charsTyped and inputMethod events.
 * Finally, test compares the resulting key presses and typed chars with the expected ones using the assumptions below.
 *
 * Test based on the following assumptions:
 * TODO check if these assumptions are correct
 * - Pressing "modifier + key" always generates
 *            "modifier key code + low registry key code" keyPressed events for any key and modifier.
 * - No keyTyped event is expected as the result of pressing dead key + key.
 * - Pressing "dead key + space" generates corresponding diacritic character,
 *   which may be obtained using inputMethodTextChanged event.
 * - Ctrl can be used to type the "control characters" (0th to 31th character inclusively in the ASCII table),
 *   so Ctrl + key can generate keyTyped event.
 * - Cmd and its combinations with other modifiers are considered as a "shortcut",
 *   no keyTyped event is expected as the result of pressing a shortcut,
 *   no attempts are made to check inputMethodTextChanged event result for a "shortcut".
 *
 * WARNING: Test sends many key presses which may match system and IDE shortcuts.
 *
 *         !!! DISABLE MACOS SYSTEM SHORTCUTS BEFORE RUNNING THIS TEST !!!
 *         !!!             DO NOT RUN THIS TEST FROM IDE               !!!
 *
 * MacOS accessibility permission should be granted on macOS >= 10.14 for the application launching this test, so
 * Java Robot is able to access keyboard (use System Preferences -> Security&Privacy -> Privacy tab -> Accessibility).
 *
 * Test can only be compiled by JBRSDK as it uses private LWCToolkit API for switching system keyboard layout.
 * Test may be run by any JRE, but in case of non-JBR, one needs to manually switch the keyboard layout during testing.
 *
 * Compilation:
 * <javac-1.8.*> -bootclasspath <jbrsdk8>/Contents/Home/jre/lib/rt.jar \
 *               ./test/jdk/jb/sun/awt/macos/NationalLayoutTest/*.java
 * <javac-11.*> --add-exports java.desktop/sun.awt.event=ALL-UNNAMED \
 *              --add-exports java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
 *               test/jdk/jb/sun/awt/macos/NationalLayoutTest/*.java
 * Usage:
 * <java> -cp test/jdk/jb/sun/awt/macos/NationalLayoutTest NationalLayoutTest [manual] [layout1] [layout2] ... ,
 * where layoutN is one of the existing Layout enum values
 *
 * Examples:
 * > java NationalLayoutTest
 * > java NationalLayoutTest ABC SPANISH_ISO
 * > java NationalLayoutTest manual US_INTERNATIONAL_PC
 *
 */

public class NationalLayoutTest {

    private static final String MANUAL= "manual";

    private static final int PAUSE = 2000;

    // Test control keys
    private static final int NEXT_KEY = KeyEvent.VK_ENTER;
    private static final int NEXT_MODIFIER = KeyEvent.VK_ESCAPE;
    private static final int TERMINATE_KEY = KeyEvent.VK_SPACE;

    private static CountDownLatch nextKey;
    private static CountDownLatch nextModifierSet;

    private static boolean jbr = true;
    private static boolean autoTest = true;
    private static boolean showKeyCodeHex = false;

    private static Frame mainFrame;

    private static TextArea inputArea;
    private static TextArea pressArea;
    private static TextArea typeArea;

    private static Robot robot;

    private static final Set<String> addedLayouts = new HashSet<>();

    // Synchronized lists for storing results of different key events
    private static CopyOnWriteArrayList<Integer> keysPressed = new CopyOnWriteArrayList();
    private static CopyOnWriteArrayList<Character> charsTyped = new CopyOnWriteArrayList();
    private static CopyOnWriteArrayList<Character> inputMethodChars = new CopyOnWriteArrayList();

    /*
     * Test entry point
     */
    public static void main(String[] args) throws InterruptedException {

        // Check if the testing could be performed using Java Robot
        try {
            robot = new Robot();
            robot.setAutoDelay(50);
        } catch (AWTException e) {
            throw new RuntimeException("TEST ERROR: Cannot create Java Robot " + e);
        }

        // Check if program arguments contain known layouts to test
        List<Layout> layoutList = new ArrayList();
        for (String arg : args) {
            if(!arg.equals(MANUAL)) {
                try {
                    layoutList.add(Layout.valueOf(arg));
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("ERROR: Unexpected argument: " + arg + "\n"
                            + "Possible test arguments are: " + MANUAL + ", "
                            + Arrays.stream(Layout.values()).map(Enum::name).collect(Collectors.joining(", ")));
                }
            }
        }

        // JBR internal API from LWCToolkit is used to switch system keyboard layout.
        // So running the test for other java implementations is only possible in manual mode.
        // During the test run one should switch to the requested keyboard layout by hands
        // and then proceed with testing by pressing NEXT_MODIFIER.
        if (!System.getProperty("java.vm.vendor").toLowerCase().contains("jetbrains")) {
            System.out.println("WARNING - Not JBR mode: Cannot automatically switch keyboard layout");
            jbr = false;
        }

        // One may want to use MANUAL mode to simply improve visibility.
        // Please proceed with layout testing by pressing NEXT_MODIFIER.
        if(Arrays.asList(args).contains(MANUAL) || !(jbr)) {
            System.out.println("WARNING - Manual mode: Press " + KeyEvent.getKeyText(NEXT_MODIFIER)
                    + " to start testing keyboard layout with the modifier set");
            autoTest = false;
        }

        String initialLayoutName = null;
        try {
            // Create test GUI
            createGUI();

            // Save initial keyboard layout
            if(jbr) {
                initialLayoutName = LWCToolkit.getKeyboardLayoutId();
            }

            boolean failed = false;
            // Test layouts defined in the command line or all enumerated test layouts in case command line is empty
            for(Layout layout : (layoutList.isEmpty()
                    ? Layout.values() : layoutList.toArray(new Layout[layoutList.size()]))) {
                // Test layout with all enumerated modifier combinations
                for (Modifier modifier : Modifier.values()) {
                    if(!testLayout(layout, modifier)) {
                        failed = true;
                    }
                }
            }
            // Provide the test result
            if(failed) {
                throw new RuntimeException("TEST FAILED: Some keyboard layout tests failed, check the test output");
            } else {
                System.out.println("TEST PASSED");
            }

        } finally {
            for (String layoutId : addedLayouts) {
                try {
                    LWCToolkit.disableKeyboardLayout(layoutId);
                } catch (Exception ignored) {}
            }

            // Restore initial keyboard layout
            if(initialLayoutName != null && !initialLayoutName.isEmpty()) {
                LWCToolkit.switchKeyboardLayout(initialLayoutName);
            }

            // Destroy test GUI
            destroyGUI();
            // Wait for EDT auto-shutdown
            Thread.sleep(PAUSE);
        }
    }

    // Helpers for checking whether type area got focus
    private static final CountDownLatch typeAreaGainedFocus = new CountDownLatch(1);
    private static final FocusListener typeAreaFocusListener = new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            typeAreaGainedFocus.countDown();
        }
    };

    /*
     * Create the test GUI - main frame with three text areas:
     * 1) input area shows the current test key combination pressed by Java Robot
     * 2) press area shows what key pressed events was received as the result of 1
     * 3) type area (which is initially focused) shows symbols typed as the result of 1
     */
    private static void createGUI() throws InterruptedException {

        mainFrame = new Frame("Test Frame");
        inputArea = new TextArea("",50, 30);
        pressArea = new TextArea("", 50, 30);
        typeArea = new TextArea("",50, 30);

        // Add listeners to track keyboard events
        typeArea.addFocusListener(typeAreaFocusListener);
        // TODO Currently dead key presses do not gererate corresponding keyPressed events to TextComponents,
        // but such events are generated normally for Frames or other non-text Components
        typeArea.addKeyListener(keyListener);
        typeArea.addInputMethodListener(inputMethodListener);

        Panel labelPanel = new Panel();
        labelPanel.add(new Label("Input Keys  --->  Pressed Keys  --->  Typed Chars"));

        Panel textPanel = new Panel();
        textPanel.setLayout(new FlowLayout());
        textPanel.add(inputArea);
        textPanel.add(pressArea);
        textPanel.add(typeArea);

        mainFrame.setLocation(200, 200);
        mainFrame.add(labelPanel, BorderLayout.NORTH);
        mainFrame.add(textPanel, BorderLayout.SOUTH);
        mainFrame.pack();
        mainFrame.setVisible(true);

        typeArea.requestFocusInWindow();
        // Check if type area got focus.
        // Corresponding latch is released in the typeAreaFocusListener.
        if(!typeAreaGainedFocus.await(PAUSE, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("TEST ERROR: Failed to request focus in the text area for typing");
        }
    }

    /*
     * Destroy the test GUI
     */
    private static void destroyGUI() {
        if(typeArea != null) {
            typeArea.removeFocusListener(typeAreaFocusListener);
            typeArea.removeKeyListener(keyListener);
            typeArea.removeInputMethodListener(inputMethodListener);
        }
        if(mainFrame != null) {
            mainFrame.dispose();
        }
    }

    /*
     * Listener for keyPressed and keyTyped events
     */
    private static final KeyListener keyListener = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
            // Obtain pressed key code
            int keyCode = e.getKeyCode();
            if (keyCode == KeyEvent.VK_UNDEFINED) {
                keyCode = e.getExtendedKeyCode();
            }
            if (keyCode == NEXT_MODIFIER) {
                // Support for manual mode: notify main thread that testing may be continued
                nextModifierSet.countDown();
            } else if (keyCode == NEXT_KEY) {
                // Update press area with the next line
                pressArea.append("\n");
                // Notify main thread that all events related to the key testing should already have been received
                nextKey.countDown();
            } else if (keyCode == TERMINATE_KEY) {
                // Do nothing, press TERMINATE_KEY_SEQUENCE to support dead key testing
            } else {
                String keyText = KeyEvent.getKeyText(keyCode);
                // Update press area with the pressed key text
                pressArea.append(keysPressed.isEmpty() ? keyText : " " + keyText);
                // Store pressed key code to the corresponding list
                keysPressed.add(keyCode);
            }
        }

        @Override
        public void keyTyped(KeyEvent e) {
            // Obtain typed char
            char keyChar = e.getKeyChar();
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(keyChar);

            switch (keyCode) {
                case NEXT_MODIFIER:
                case NEXT_KEY:
                case TERMINATE_KEY:
                    if ((e.getModifiers() & KeyEvent.CTRL_MASK) == 0) {
                        // Do not store the typed char only if it is NEXT_MODIFIER, NEXT_KEY, TERMINATE_KEY generated
                        //  without Control modifier: the Control allows to "type" the "control characters"
                        //  (0th to 31th character inclusively in the ASCII table).
                        break;
                    }
                default:
                    // Store typed char to the corresponding list
                    charsTyped.add(keyChar);
                    break;
            }
        }
    };

    /*
     * Test listener for InputMethod events.
     * Such events may occur for a special case when a character is generated by pressing more than one key,
     * for example, when attaching specific diacritic to a base letter, by pressing dead key + base key.
     */
    static final InputMethodListener inputMethodListener = new InputMethodListener() {
        @Override
        public void inputMethodTextChanged(InputMethodEvent e) {
            // Store generated chars to the corresponding list
            if(e.getCommittedCharacterCount() > 0) {
                CharacterIterator text = e.getText();
                for (char ch = text.first(); ch != CharacterIterator.DONE; ch = text.next()) {
                    inputMethodChars.add(ch);
                }
            }
        }

        @Override
        public void caretPositionChanged(InputMethodEvent event) {
            // Do nothing
        }
    };

    /*
     * Main method for testing defined layout keys with the specific modifier set
     */
    private static boolean testLayout(Layout layout, Modifier modifier) throws InterruptedException {
        boolean result = true;
        System.out.println("\nStart  testing " + layout + " layout with " + modifier + " modifier(s):");

        // Switch current keyboard layout to the test one
        if(jbr) {
            String layoutId = layout.toString();
            if (!LWCToolkit.isKeyboardLayoutEnabled(layoutId)) {
                LWCToolkit.enableKeyboardLayout(layoutId);
                addedLayouts.add(layoutId);
            }
            LWCToolkit.switchKeyboardLayout(layoutId);
        }

        // Support for manual mode: wait while user switches the keyboard layout (if needed)
        // and proceed with layout + modifier testing by pressing NEXT_MODIFIER.
        // Corresponding latch is released in the keyListener when NEXT_MODIFIER key event is received.
        nextModifierSet = new CountDownLatch(1);
        if(autoTest) {
            pressKey(NEXT_MODIFIER);
        }
        if(!nextModifierSet.await(PAUSE*10, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("TEST ERROR: User has not proceeded with manual testing");
        }

        // Clean up the test text areas
        inputArea.setText("");
        pressArea.setText("");
        // Workaround jdk8 setText() issue
        typeArea.setText(" ");

        // Store modifier keys to array
        int[] modifiers = modifier.getModifiers();

        // Go over all keys defined for the layout
        for(LayoutKey layoutKey : layout.getLayoutKeys()) {
            System.err.printf("KEYPRESS: key=%s, modifier=%s\n", layoutKey.getKey(), modifier.toPlaintextString());

            // Clean up synchronized lists which store pressed key codes and typed chars
            keysPressed = new CopyOnWriteArrayList();
            charsTyped = new CopyOnWriteArrayList();
            inputMethodChars = new CopyOnWriteArrayList();

            // Get Key object from LayoutKey enum
            Key key = layoutKey.getKey();

            // Obtain the key code for the current layout
            int keyCode = key.getKeyCode();

            // Append input area with the modifiers + key under test
            if(!modifier.isEmpty()) {
                inputArea.append(modifier + " ");
            }
            inputArea.append(KeyEvent.getKeyText(keyCode) + "\n");

            // Robot only knows US keyboard layout.
            // So, to press modifier + key under test on the current layout,
            // one need to pass corresponding US layout key code to Robot.
            pressKey(key.getKeyCode_US(), modifiers);

            // The key under test may be a dead key which does not generate a character by itself.
            // But it modifies the character generated by the key pressed immediately after.
            // So need to press some TERMINATE_KEY key to avoid dead key influence on the next key under test.
            // Corresponding diacritic character by itself can be generated by pressing dead key followed by space.
            // That is why pressing TERMINATE_KEY=space after a dead key resets the dead key modifier
            // and allows testing the generated character which may be useful for some layouts.
            // Test does two checks: if modifier + key is a dead key and if key with no modifier is a dead key.
            // Second check is needed for shortcuts as key.isDead(modifier) is always false for a shortcut.
            if(key.isDead(modifier) || key.isDead()) {
                pressKey(TERMINATE_KEY);
            }

            // Wait while NEXT_KEY is pressed, which identifies that modifier + key testing is finished.
            // Corresponding latch is released in the keyListener when NEXT_KEY key event is received.
            nextKey = new CountDownLatch(1);
            pressKey(NEXT_KEY);
            if(!nextKey.await(PAUSE, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("TEST ERROR: "
                        + KeyEvent.getKeyText(NEXT_KEY) + " key pressed event was not received");
            }

            // Define array of key codes expected to be pressed as the result of modifiers + key
            int[] keysPattern = Arrays.copyOf(modifiers, modifiers.length + 1);
            keysPattern[modifiers.length] = keyCode;

            // Define array of key codes which were really pressed as the result of modifiers + key
            int[] keysResult = listToInts(keysPressed);

            // Do not check the resulting character in case it is null,
            // i.e. no char is mapped to the modifier or there is no need to test it.
            if (key.isCharNull(modifier)) {
                // Check if the pressed key codes are equal to the expected ones
                if(!checkResult(keysPattern, keysResult, null,null)) {
                    result = false;
                }
            } else {
                // Define array of chars expected to be typed as the result of modifiers + key
                // Do not expect any char typed as the result of shortcut (char is undefined in this case)
                char[] charsPattern = key.isCharUndefined(modifier)
                        ? new char[0] : new char[] { key.getChar(modifier) };
                // Define array of chars which were really typed as the result of modifiers + key
                // No keyTyped events may be generated as the result of pressing a dead key + space,
                // so check if input method text was updated in this case.
                char[] charsResult = (key.isDead(modifier) && charsTyped.isEmpty())
                        ? listToChars(inputMethodChars) : listToChars(charsTyped);
                // Check if pressed key codes and typed chars are equal to the expected ones
                if(!checkResult(keysPattern, keysResult, charsPattern, charsResult)) {
                    result = false;
                }
            }
        }
        // Provide layout + modifier testing result
        System.out.println("Finish testing " + layout + " layout with " + modifier + " modifier(s): "
                + (result ? "PASSED" : "FAILED"));

        return result;
    }

    /*
     * Use Java Robot to press the key with specific modifiers
     */
    private static void pressKey(int keyCode, int... modifiers) {
        robot.waitForIdle();
        for (int modifier : modifiers) {
            robot.keyPress(modifier);
        }
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        for (int modifier : modifiers) {
            robot.keyRelease(modifier);
        }
    }

    /*
     * Check if keys pressed and char typed are equal to the expected ones
     */
    private static boolean checkResult(int[] patternKeys, int[] resultKeys, char[] patternChars, char[] resultChars) {

        boolean checkKeys = Arrays.equals(patternKeys, resultKeys);
        boolean checkChars = Arrays.equals(patternChars, resultChars);

        boolean result = (checkKeys && checkChars);

        if(!result) {
            String[] patternStr = (patternKeys != null) ? intsToStrings(patternKeys) : null;
            String[] resultStr = (resultKeys != null) ? intsToStrings(resultKeys) : null;
            String eqKeys = checkKeys ? " == " : " != ";
            System.err.println("keyText   : " + Arrays.toString(patternStr)  + eqKeys + Arrays.toString(resultStr));
            System.err.println("keyCode   : " + Arrays.toString(patternKeys) + eqKeys + Arrays.toString(resultKeys));
            if(showKeyCodeHex) {
                String[] patternHex = intsToHexStrings(patternKeys);
                String[] resultHex = intsToHexStrings(resultKeys);
                System.err.println("keyCodeHex: " + Arrays.toString(patternHex)  + eqKeys + Arrays.toString(resultHex));
            }
        }
        if(!checkChars) {
            String[] patternHex = (patternChars != null) ? charsToHexStrings(patternChars) : null;
            String[] resultHex = (resultChars != null) ? charsToHexStrings(resultChars) : null;
            String eqChars = checkChars ? " == " : " != ";
            System.err.println("keyChar   : " + Arrays.toString(patternChars) + eqChars + Arrays.toString(resultChars));
            System.err.println("keyCharHex: " + Arrays.toString(patternHex)   + eqChars + Arrays.toString(resultHex));
        }
        if(!result) {
            System.err.println();
        }
        return result;
    }

    /*
     * Transform list of Integers to int array
     */
    private static int[] listToInts(List<Integer> list) {
        return list.stream().mapToInt(i->i).toArray();
    }

    /*
     * Transform list of Characters to char array
     */
    private static char[] listToChars(List<Character> list) {
        return list.stream().map(c -> c.toString()).collect(Collectors.joining()).toCharArray();
    }

    /*
     * Transform array of int keys to array of Strings, mapping an int key to its text representation
     */
    private static String[] intsToStrings(int[] ints) {
        return Arrays.stream(ints).boxed().map(i -> KeyEvent.getKeyText(i)).toArray(String[]::new);
    }

    /*
     * Transform array of int keys to array of Strings, mapping an int key to its hex representation
     */
    private static String[] intsToHexStrings(int[] ints) {
        return Arrays.stream(ints).boxed().map(i -> String.format("0x%1$04X", i)).toArray(String[]::new);
    }

    /*
     * Transform array of char to array of Strings, mapping a char to its hex representation
     */
    private static String[] charsToHexStrings(char[] chars) {
        int[] result = new int[chars.length];
        Arrays.setAll(result, i -> (int) chars[i]);
        return intsToHexStrings(result);
    }
}
