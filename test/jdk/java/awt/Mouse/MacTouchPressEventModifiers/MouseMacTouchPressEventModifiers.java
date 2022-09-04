/**
 * @test
 * bug JBR-4765 TODO: place OpenJDK bug id here
 * @summary The test verifies that a press {@link java.awt.event.MouseEvent} contains correct modifiers
 *          although the according native mouse event is accompanied by no mouse modifiers
 *          ({@link sun.lwawt.macosx.NSEvent#nsToJavaModifiers} returns 0 inside
 *           {@link sun.lwawt.macosx.CPlatformResponder#handleMouseEvent(int, int, int, int, int, int, int, int)}).
 *          The situation above happens when a user taps (NOT clicks) a trackpad on a M2 MacBooks.
 *          The test emulates the situation via a direct invocation of
 *          {@link sun.lwawt.macosx.CPlatformResponder#handleMouseEvent(int, int, int, int, int, int, int, int)};
 *          unfortunately it's impossible to use {@link java.awt.Robot} because its mouse press events ARE accompanied
 *          by correct modifiers ({@link sun.lwawt.macosx.NSEvent#nsToJavaModifiers} returns correct values).
 * @author Nikita Provotorov
 *
 * @key headful
 * @requires (os.family == "mac")
 *
 * @compile --add-exports java.desktop/sun.lwawt.macosx=ALL-UNNAMED -g MouseMacTouchPressEventModifiers.java
 * @run main/othervm --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.desktop/sun.lwawt=ALL-UNNAMED --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED MouseMacTouchPressEventModifiers
 */

import sun.lwawt.macosx.CocoaConstants;
import sun.lwawt.macosx.LWCToolkit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


public class MouseMacTouchPressEventModifiers
{
    /**
     * How it works:
     * 1. Send a native mouse press to {@code frame} via
     *    {@link sun.lwawt.macosx.CPlatformResponder#handleMouseEvent(int, int, int, int, int, int, int, int)}
     *    (using reflection).
     * 2. Wait (via {@link Future#get()}) until it generates a usual java MouseEvent
     *    and dispatches it to the MouseListener of the {@code frame}.
     * 3. Verify the dispatched MouseEvent contains correct modifiers, modifiersEx and button number.
     * 4. Do all the steps above but for a corresponding mouse release.
     */
    public static void main(String[] args) {
        try {
            // TreeMap to preserve the testing order
            final var testCases = new TreeMap<>(Map.of(
                CocoaConstants.kCGMouseButtonLeft, new MouseEventFieldsToTest(MouseEvent.BUTTON1_MASK, MouseEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1),
                CocoaConstants.kCGMouseButtonRight, new MouseEventFieldsToTest(MouseEvent.BUTTON3_MASK, MouseEvent.BUTTON3_DOWN_MASK, MouseEvent.BUTTON3),
                CocoaConstants.kCGMouseButtonCenter, new MouseEventFieldsToTest(MouseEvent.BUTTON2_MASK, MouseEvent.BUTTON2_DOWN_MASK, MouseEvent.BUTTON2)
            ));

            SwingUtilities.invokeAndWait(MouseMacTouchPressEventModifiers::createAndShowGUI);

            try {
                for (var testCase : testCases.entrySet()) {
                    final var fieldsToTest = testCase.getValue();

                    final int mouseX = (frame.getWidth() - 1) / 2;
                    final int mouseY = (frame.getHeight() - 1) / 2;

                    // press

                    MouseEvent event = frame.sendNativeMousePress(
                        0,
                        testCase.getKey(),
                        1,
                        mouseX,
                        mouseY
                    ).get(500, TimeUnit.MILLISECONDS);
                    System.out.println("A mouse press turned into: " + event);

                    frame.checkInternalErrors();

                    checkMouseEvent(event,
                        MouseEvent.MOUSE_PRESSED, fieldsToTest.modifiers, fieldsToTest.pressModifiersEx, fieldsToTest.button);

                    // release

                    event = frame.sendNativeMouseRelease(
                        0,
                        testCase.getKey(),
                        1,
                        mouseX,
                        mouseY
                    ).get(500, TimeUnit.MILLISECONDS);
                    System.out.println("A mouse release turned into: " + event);

                    frame.checkInternalErrors();

                    checkMouseEvent(event,
                        MouseEvent.MOUSE_RELEASED, fieldsToTest.modifiers, 0, fieldsToTest.button);

                    System.out.println();
                }
            } finally {
                SwingUtilities.invokeAndWait(MouseMacTouchPressEventModifiers::disposeGUI);
                System.out.flush();
            }
        } catch (Throwable err) {
            throw new RuntimeException("Test failed", err);
        }
    }


    private record MouseEventFieldsToTest(int modifiers, int pressModifiersEx, int button) {}

    private static MyFrame frame;

    private static void createAndShowGUI() {
        frame = new MyFrame();
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        frame.pack();
        frame.setSize(800, 500);

        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);

        frame.setVisible(true);
    }

    private static void disposeGUI() {
        if (frame != null) {
            frame.dispose();
        }
    }

    private static void checkMouseEvent(MouseEvent me,
        int expectedId, int expectedModifiers, int expectedModifiersEx, int expectedButton
    ) {
        boolean wrong = false;

        final var errMsg = new StringBuilder(1024);
        errMsg.append("Wrong MouseEvent ").append(me).append(':');

        if (me.getID() != expectedId) {
            errMsg.append("\n  eventId: expected <").append(expectedId).append(">, actual <").append(me.getID()).append('>');
            wrong = true;
        }
        if (me.getModifiers() != expectedModifiers) {
            errMsg.append("\n  modifiers: expected <").append(expectedModifiers).append(">, actual <").append(me.getModifiers()).append('>');
            wrong = true;
        }
        if (me.getModifiersEx() != expectedModifiersEx) {
            errMsg.append("\n  modifiersEx: expected <").append(expectedModifiersEx).append(">, actual <").append(me.getModifiersEx()).append('>');
            wrong = true;
        }
        if (me.getButton() != expectedButton) {
            errMsg.append("\n  button: expected <").append(expectedButton).append(">, actual <").append(me.getButton()).append('>');
            wrong = true;
        }

        if (wrong) {
            throw new IllegalArgumentException(errMsg.append('\n').toString());
        }
    }
}


class MyFrame extends JFrame {
    public MyFrame() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                System.out.println("MyFrame::mousePressed: " + e);
                keepPromiseVia(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                System.out.println("MyFrame::mouseReleased: " + e);
                keepPromiseVia(e);
            }
        });
    }

    public Future<MouseEvent> sendNativeMousePress(int modifierFlags, int buttonNumber, int clickCount, int x, int y) {
        final int eventType = (buttonNumber == CocoaConstants.kCGMouseButtonLeft) ? CocoaConstants.NSLeftMouseDown
                              : (buttonNumber == CocoaConstants.kCGMouseButtonRight) ? CocoaConstants.NSRightMouseDown
                              : CocoaConstants.NSOtherMouseDown;

        return sendNativeMouseEvent(eventType, modifierFlags, buttonNumber, clickCount, x, y, getX() + x, getY() + y);
    }

    public Future<MouseEvent> sendNativeMouseRelease(int modifierFlags, int buttonNumber, int clickCount, int x, int y) {
        final int eventType = (buttonNumber == CocoaConstants.kCGMouseButtonLeft) ? CocoaConstants.NSLeftMouseUp
                              : (buttonNumber == CocoaConstants.kCGMouseButtonRight) ? CocoaConstants.NSRightMouseUp
                              : CocoaConstants.NSOtherMouseUp;

        return sendNativeMouseEvent(eventType, modifierFlags, buttonNumber, clickCount, x, y, getX() + x, getY() + y);
    }

    public void checkInternalErrors() throws Throwable {
        final Throwable result = internalError.getAndSet(null);
        if (result != null) {
            throw result;
        }
    }


    private final AtomicReference<CompletableFuture<MouseEvent>> mouseEventPromise = new AtomicReference<>(null);

    private final AtomicReference<Throwable> internalError = new AtomicReference<>(null);

    private Future<MouseEvent> sendNativeMouseEvent(
        final int eventType,
        final int modifierFlags,
        final int buttonNumber,
        final int clickCount,
        final int x,
        final int y,
        final int absX,
        final int absY
    ) {
        assert !SwingUtilities.isEventDispatchThread();
        assert mouseEventPromise.get() == null : "Trying to send a mouse event while there is already a processing one";

        final CompletableFuture<MouseEvent> result = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> {
            try {
                LWCToolkit.invokeLater(() -> {
                    try {
                        final Object thisPlatformResponder = obtainFramePlatformResponder(this);
                        final Method thisPlatformResponderHandleMouseEventMethod = obtainHandleMouseEventMethod(thisPlatformResponder);

                        if (mouseEventPromise.compareAndExchange(null, result) != null) {
                            throw new IllegalStateException("Trying to send a mouse event while there is already a processing one");
                        }

                        thisPlatformResponderHandleMouseEventMethod.invoke(thisPlatformResponder,
                            eventType, modifierFlags, buttonNumber, clickCount, x, y, absX, absY);
                    } catch (Throwable err) {
                        // Remove the promise if thisPlatformResponderHandleMouseEventMethod.invoke(...) failed
                        mouseEventPromise.compareAndExchange(result, null);
                        failPromiseDueTo(result, err);
                    }
                }, this);
            } catch (Throwable err) {
                failPromiseDueTo(result, err);
            }
        });

        return result;
    }

    /** Wraps {@link CompletableFuture#complete(Object)} */
    private void keepPromiseVia(MouseEvent mouseEvent) {
        try {
            final CompletableFuture<MouseEvent> promise = mouseEventPromise.getAndSet(null);
            if (promise == null) {
                throw new IllegalStateException("The following unexpected MouseEvent has arrived: " + mouseEvent);
            }

            if (!promise.complete(mouseEvent)) {
                throw new IllegalStateException("The promise had already been completed when the following MouseEvent arrived: " + mouseEvent);
            }
        } catch (Throwable err) {
            setInternalError(err);
        }
    }

    /** Wraps {@link CompletableFuture#completeExceptionally(Throwable)} */
    private void failPromiseDueTo(CompletableFuture<MouseEvent> promise, Throwable cause) {
        try {
            if (!promise.completeExceptionally(cause)) {
                throw new IllegalStateException("The promise had already been completed when the following error arrived: " + cause);
            }
        } catch (Throwable err) {
            setInternalError(err);
        }
    }

    private void setInternalError(Throwable err) {
        if (internalError.compareAndExchange(null, err) != null) {
            System.err.println("Failed to set the following internal error because there is another one: " + err);
        }
    }

    /** Obtains {@code component.peer.platformWindow.responder} */
    private static Object obtainFramePlatformResponder(Component component) throws NoSuchFieldException, IllegalAccessException {
        final Object framePeer;
        {
            final var frameClass = Component.class;
            final var peerField = frameClass.getDeclaredField("peer");

            peerField.setAccessible(true);

            framePeer = peerField.get(component);
        }

        final Object peerPlatformWindow;
        {
            final var peerClass = framePeer.getClass();
            final var platformWindowField = peerClass.getDeclaredField("platformWindow");

            platformWindowField.setAccessible(true);

            peerPlatformWindow = platformWindowField.get(framePeer);
        }

        final Object platformWindowResponder;
        {
            final var peerPlatformWindowClass = peerPlatformWindow.getClass();
            final var platformWindowResponderField = peerPlatformWindowClass.getDeclaredField("responder");

            platformWindowResponderField.setAccessible(true);

            platformWindowResponder = platformWindowResponderField.get(peerPlatformWindow);
        }

        return platformWindowResponder;
    }

    /** Obtains {@link sun.lwawt.macosx.CPlatformResponder#handleMouseEvent(int, int, int, int, int, int, int, int)} */
    private static Method obtainHandleMouseEventMethod(final Object platformResponder) throws NoSuchMethodException {
        final var responderClass = platformResponder.getClass();
        final var handleMouseEventMethod = responderClass.getDeclaredMethod(
            "handleMouseEvent",
            int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class
        );

        handleMouseEventMethod.setAccessible(true);

        return handleMouseEventMethod;
    }
}
