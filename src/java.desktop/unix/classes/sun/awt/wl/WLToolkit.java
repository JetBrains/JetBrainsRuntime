/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.awt.wl;

import sun.awt.AWTAccessor;
import sun.awt.AppContext;
import sun.awt.LightweightFrame;
import sun.awt.PeerEvent;
import sun.awt.SunToolkit;
import sun.awt.UNIXToolkit;
import sun.awt.datatransfer.DataTransferer;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.dnd.peer.DragSourceContextPeer;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.awt.im.spi.InputMethodDescriptor;
import java.awt.peer.ButtonPeer;
import java.awt.peer.CanvasPeer;
import java.awt.peer.CheckboxMenuItemPeer;
import java.awt.peer.CheckboxPeer;
import java.awt.peer.ChoicePeer;
import java.awt.peer.DesktopPeer;
import java.awt.peer.DialogPeer;
import java.awt.peer.FileDialogPeer;
import java.awt.peer.FontPeer;
import java.awt.peer.FramePeer;
import java.awt.peer.KeyboardFocusManagerPeer;
import java.awt.peer.LabelPeer;
import java.awt.peer.ListPeer;
import java.awt.peer.MenuBarPeer;
import java.awt.peer.MenuItemPeer;
import java.awt.peer.MenuPeer;
import java.awt.peer.MouseInfoPeer;
import java.awt.peer.PanelPeer;
import java.awt.peer.PopupMenuPeer;
import java.awt.peer.RobotPeer;
import java.awt.peer.ScrollPanePeer;
import java.awt.peer.ScrollbarPeer;
import java.awt.peer.SystemTrayPeer;
import java.awt.peer.TaskbarPeer;
import java.awt.peer.TextAreaPeer;
import java.awt.peer.TextFieldPeer;
import java.awt.peer.TrayIconPeer;
import java.awt.peer.WindowPeer;
import java.beans.PropertyChangeListener;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * On events handling: the WLToolkit class creates a thread named "AWT-Wayland"
 * where the communication with the Wayland server is chiefly carried on such
 * as sending requests over and receiving events from the server.
 * For "system" events that are not meant to trigger any user code, a separate
 * thread is utilized called "AWT-Wayland-system-dispatcher". It is the only
 * thread where such events are handled. For other events, such as mouse click
 * events, the Wayland handlers are supposed to "transfer" themselves to
 * "AWT-EventThread" by means of SunToolkit.postEvent(). See the implementation
 * of run() method for more comments.
 */
public class WLToolkit extends UNIXToolkit implements Runnable {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLToolkit");
    private static final PlatformLogger logKeys = PlatformLogger.getLogger("sun.awt.wl.WLToolkit.keys");

    /**
     * Maximum wait time in ms between attempts to receive more data (events)
     * from the Wayland server on the toolkit thread.
     */
    private static final int WAYLAND_DISPLAY_INTERACTION_TIMEOUT_MS = 50;

    /**
     * Returned by readEvents() to signify the presence of new events
     * on the default Wayland display queue that have not been dispatched yet.
     */
    private static final int READ_RESULT_FINISHED_WITH_EVENTS = 0;

    /**
     * Returned by readEvents() to signify the total absence of events
     * on any of the Wayland display queues.
     */
    private static final int READ_RESULT_FINISHED_NO_EVENTS = 1;

    /**
     * Returned by readEvents() to signify the absence of *new* events
     * on the default Wayland display queue. The existing events
     * should better be dispatched prior to calling readEvents() again.
     */
    private static final int READ_RESULT_NO_NEW_EVENTS = 2;

    /**
     * Returned by readEvents() in case of an error condition like
     * disappearing of the Wayland display. Errors not specifically
     * related to Wayland are reported via an exception.
     */
    private static final int READ_RESULT_ERROR = 3;
    
    private static final int MOUSE_BUTTONS_COUNT = 3;
    private static final int AWT_MULTICLICK_DEFAULT_TIME_MS = 500;

    private static native void initIDs();

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            initIDs();
        }
    }

    @SuppressWarnings("removal")
    public WLToolkit() {
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            final String extraButtons = "sun.awt.enableExtraMouseButtons";
            areExtraMouseButtonsEnabled =
                    Boolean.parseBoolean(System.getProperty(extraButtons, "true"));
            System.setProperty(extraButtons, String.valueOf(areExtraMouseButtonsEnabled));
            return null;
        });

        Thread toolkitThread = new Thread(this, "AWT-Wayland");
        toolkitThread.setDaemon(true);
        toolkitThread.start();

        final Thread toolkitSystemThread = new Thread(this::dispatchNonDefaultQueues, "AWT-Wayland-system-dispatcher");
        toolkitSystemThread.setDaemon(true);
        toolkitSystemThread.start();
    }

    @Override
    public ButtonPeer createButton(Button target) {
        ButtonPeer peer = new WLButtonPeer(target);
        targetCreatedPeer(target, peer);
        return peer;
    }

    @Override
    public FramePeer createLightweightFrame(LightweightFrame target) {
        FramePeer peer = new WLLightweightFramePeer(target);
        targetCreatedPeer(target, peer);
        return peer;
    }

    @Override
    public FramePeer createFrame(Frame target) {
        FramePeer peer = new WLFramePeer(target);
        targetCreatedPeer(target, peer);
        return peer;
    }

    /**
     * Wayland events coming to queues other that the default are handled here.
     * The code is executed on a separate thread and must not call any user code.
     */
    private void dispatchNonDefaultQueues() {
        dispatchNonDefaultQueuesImpl(); // does not return until error or server disconnect
    }

    @Override
    public void run() {
        while(true) {
            int result = readEvents();
            // TODO: the result of the very first call can theoretically be READ_RESULT_NO_NEW_EVENTS
            // and that assumes we already have a dispatcher queued, which is initially not so.
            // Can be solved by queueing dispatch once in the very beginning (?)

            // There's also a problem where we can start waiting in the READ_RESULT_NO_NEW_EVENTS branch,
            // but at the same time dispatch has just completed. So on the one hand we are ready for
            // more events, but on the other we will wait for 50ms before getting them. This may hinder
            // the responsiveness.

            if (result == READ_RESULT_ERROR) {
                // TODO: display disconnect handling here?
                break;
            } else if (result == READ_RESULT_FINISHED_WITH_EVENTS) {
                SunToolkit.postEvent(AppContext.getAppContext(),
                        new PeerEvent(this, () -> {
                            dispatchEventsOnEDT();
                            synchronized (WLToolkit.this) {
                                // Finally, processed the entire events queue, can ask for more now.
                                WLToolkit.this.notifyAll();
                            }
                        }, PeerEvent.ULTIMATE_PRIORITY_EVENT));
            } else if (result == READ_RESULT_NO_NEW_EVENTS) {
                synchronized (WLToolkit.this) {
                    try {
                        // Give the EDT an opportunity to process the current event queue
                        // (see above) before requesting yet another dispatch
                        // on the very next loop iteration. That iteration may exit with
                        // the same READ_RESULT_NO_NEW_EVENTS thus creating a busy loop
                        // until EDT finally gets around to dispatching the existing queue.
                        WLToolkit.this.wait(WAYLAND_DISPLAY_INTERACTION_TIMEOUT_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
    
    /**
     * If more than this amount milliseconds has passed since the same mouse button click,
     * the next click is considered separate and not part of multi-click event.
     * @return maximum milliseconds between same mouse button clicks for them to be a multiclick
     */
    static long getMulticlickTime() {
        /* TODO: get from the system somehow */
        return AWT_MULTICLICK_DEFAULT_TIME_MS;
    }


    /**
     * The rate of repeating keys in characters per second
     * Set from the native code  by the 'repeat_info' Wayland event (see wayland.xml).
     */
    static volatile int keyRepeatRate = 33;

    /**
     * Delay in milliseconds since key down until repeating starts.
     * Set from the native code by the 'repeat_info' Wayland event (see wayland.xml).
     */
    static volatile int keyRepeatDelay = 500;

    static int getKeyRepeatRate() {
        return keyRepeatRate;
    }

    static int getKeyRepeatDelay() {
        return keyRepeatDelay;
    }

    /**
     * Creates and posts mouse events based on the given WLPointerEvent received from Wayland,
     * the freshly updated WLInputState, and the previous WLInputState stored in inputState.
     */
    private static void dispatchPointerEventInContext(WLPointerEvent e, WLInputState newInputState) {
        final WLComponentPeer peer = newInputState.getPeer();
        if (peer == null)  {
            log.severe("Surface doesn't map to any component");
            return; // or else we don't know whom to notify of the event
        }

        final int x = newInputState.getPointerX();
        final int y = newInputState.getPointerY();
        final Point abs = peer.relativePointToAbsolute(new Point(x, y));
        int xAbsolute = abs.x;
        int yAbsolute = abs.y;
        
        final long timestamp = newInputState.getTimestamp();

        if (e.hasEnterEvent()) {
            final MouseEvent mouseEvent = new MouseEvent(peer.getTarget(), MouseEvent.MOUSE_ENTERED,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    0, false, MouseEvent.NOBUTTON);
            postEvent(mouseEvent);
        }

        int clickCount = 0;
        boolean isPopupTrigger = false;
        int buttonChanged = MouseEvent.NOBUTTON;

        if (e.hasButtonEvent()) {
            final WLPointerEvent.PointerButtonCodes buttonCode
                    = WLPointerEvent.PointerButtonCodes.recognizedOrNull(e.getButtonCode());
            if (buttonCode != null) {
                clickCount = newInputState.getClickCount();
                isPopupTrigger = buttonCode.isPopupTrigger();
                buttonChanged = buttonCode.javaCode;

                final MouseEvent mouseEvent = new MouseEvent(peer.getTarget(),
                        e.getIsButtonPressed() ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED,
                        timestamp,
                        newInputState.getModifiers(),
                        x, y,
                        xAbsolute, yAbsolute,
                        clickCount,
                        isPopupTrigger,
                        buttonChanged);
                postEvent(mouseEvent);

                final boolean isButtonReleased = !e.getIsButtonPressed();
                final boolean wasSameButtonPressed = inputState.hasThisPointerButtonPressed(e.getButtonCode());
                final boolean isButtonClicked = isButtonReleased && wasSameButtonPressed;
                if (isButtonClicked) {
                    final MouseEvent mouseClickEvent = new MouseEvent(peer.getTarget(),
                            MouseEvent.MOUSE_CLICKED,
                            timestamp,
                            newInputState.getModifiers(),
                            x, y,
                            xAbsolute, yAbsolute,
                            clickCount,
                            isPopupTrigger,
                            buttonChanged);
                    postEvent(mouseClickEvent);
                }
            }
        }
        
        if (e.hasAxisEvent() && e.getIsAxis0Valid()) {
            final MouseEvent mouseEvent = new MouseWheelEvent(peer.getTarget(),
                    MouseEvent.MOUSE_WHEEL,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    1,
                    isPopupTrigger,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    e.getAxis0Value());
            postEvent(mouseEvent);
        }
        
        if (e.hasMotionEvent()) {
            final MouseEvent mouseEvent = new MouseEvent(peer.getTarget(),
                    newInputState.hasPointerButtonPressed()
                            ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    clickCount,
                    isPopupTrigger,
                    buttonChanged);
            postEvent(mouseEvent);
        }

        if (e.hasLeaveEvent()) {
            final MouseEvent mouseEvent = new MouseEvent(peer.getTarget(),
                    MouseEvent.MOUSE_EXITED,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    clickCount,
                    isPopupTrigger,
                    buttonChanged);
            postEvent(mouseEvent);
        }
    }

    private static WLInputState inputState = WLInputState.initialState();

    private static void dispatchPointerEvent(WLPointerEvent e) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread();

        if (log.isLoggable(PlatformLogger.Level.FINE)) log.fine("dispatchPointerEvent: " + e);

        final WLInputState newInputState = inputState.update(e);
        dispatchPointerEventInContext(e, newInputState);
        inputState = newInputState;
    }

    private static void dispatchKeyboardKeyEvent(long serial,
                                                 long timestamp,
                                                 long keycode,
                                                 int keyCodePoint,  // UTF32 character
                                                 boolean isPressed) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread();

        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine("dispatchKeyboardKeyEvent: keycode " + keycode + ", code point 0x"
                    + Integer.toHexString(keyCodePoint) + ", " + (isPressed ? "pressed" : "released")
                    + ", serial " + serial + ", timestamp " + timestamp);
        }

        if (timestamp == 0) {
            // Happens when a surface was focused with keys already pressed.
            // Fake the timestamp by peeking at the last known event.
            timestamp = inputState.getTimestamp();
        }

        final long surfacePtr = inputState.getSurfaceForKeyboardInput();
        final WLComponentPeer peer = componentPeerFromSurface(surfacePtr);
        if (peer != null) {
            convertToKeyEvent(timestamp, keycode, keyCodePoint, isPressed, peer);
        }
    }

    private static void convertToKeyEvent(long timestamp, long keycode, int keyCodePoint,
                                          boolean isPressed, WLComponentPeer peer) {
        // See also XWindow.handleKeyPress()
        final char keyChar = Character.isBmpCodePoint(keyCodePoint)
                ? (char) keyCodePoint
                : KeyEvent.CHAR_UNDEFINED;
        final WLKeySym.KeyDescriptor keyDescriptor = WLKeySym.KeyDescriptor.fromXKBCode(keycode);
        final int jkeyExtended = keyDescriptor.javaKeyCode() == KeyEvent.VK_UNDEFINED
                        ? primaryUnicodeToJavaKeycode(keyCodePoint)
                        : keyDescriptor.javaKeyCode();
        postKeyEvent(peer.getTarget(),
                isPressed ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                timestamp,
                keyDescriptor.javaKeyCode(),
                keyChar,
                keyDescriptor.keyLocation(),
                keycode,
                jkeyExtended);

        if (isPressed && keyChar != 0 && keyChar != KeyEvent.CHAR_UNDEFINED) {
            postKeyEvent(peer.getTarget(),
                    KeyEvent.KEY_TYPED,
                    timestamp,
                    KeyEvent.VK_UNDEFINED,
                    keyChar,
                    KeyEvent.KEY_LOCATION_UNKNOWN,
                    keycode,
                    KeyEvent.VK_UNDEFINED);
        }
    }

    private static int primaryUnicodeToJavaKeycode(int codePoint) {
        return (codePoint > 0? sun.awt.ExtendedKeyCodes.getExtendedKeyCodeForChar(codePoint) : 0);
    }

    private static void postKeyEvent(Component source,
                                     int id,
                                     long timestamp,
                                     int keyCode,
                                     char keyChar,
                                     int keyLocation,
                                     long rawCode,
                                     int extendedKeyCode) {
        final KeyEvent keyEvent = new KeyEvent(source, id, timestamp,
                inputState.getModifiers(), keyCode, keyChar, keyLocation);

        AWTAccessor.KeyEventAccessor kea = AWTAccessor.getKeyEventAccessor();
        kea.setRawCode(keyEvent, rawCode);
        kea.setExtendedKeyCode(keyEvent, (long) extendedKeyCode);
        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine(String.valueOf(keyEvent));
        }
        postEvent(keyEvent);
    }

    private static void dispatchKeyboardModifiersEvent(long serial,
                                                       boolean isShiftActive,
                                                       boolean isAltActive,
                                                       boolean isCtrlActive,
                                                       boolean isMetaActive) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread();

        final int newModifiers =
                  (isShiftActive ? InputEvent.SHIFT_DOWN_MASK : 0)
                | (isAltActive   ? InputEvent.ALT_DOWN_MASK   : 0)
                | (isCtrlActive  ? InputEvent.CTRL_DOWN_MASK  : 0)
                | (isMetaActive  ? InputEvent.META_DOWN_MASK  : 0);
        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine("dispatchKeyboardModifiersEvent: new modifiers 0x"
                    + Integer.toHexString(newModifiers));
        }

        inputState = inputState.updatedFromKeyboardModifiersEvent(serial, newModifiers);
    }

    private static void dispatchKeyboardEnterEvent(long serial, long surfacePtr /*, TODO*/) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread();

        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine("dispatchKeyboardEnterEvent: " + serial + ", surface 0x"
                    + Long.toHexString(surfacePtr));
        }

        final WLInputState newInputState = inputState.updatedFromKeyboardEnterEvent(serial, surfacePtr);
        final WLComponentPeer peer = componentPeerFromSurface(surfacePtr);
        if (peer != null && peer.getTarget() instanceof Window window) {
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusedWindow(window);
            final WindowEvent windowEnterEvent = new WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS);
            postEvent(windowEnterEvent);
        }
        inputState = newInputState;
    }

    private static void dispatchKeyboardLeaveEvent(long serial, long surfacePtr) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread();

        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine("dispatchKeyboardLeaveEvent: " + serial + ", surface 0x"
                    + Long.toHexString(surfacePtr));
        }

        final WLInputState newInputState = inputState.updatedFromKeyboardLeaveEvent(serial, surfacePtr);
        final WLComponentPeer peer = componentPeerFromSurface(surfacePtr);
        if (peer != null && peer.getTarget() instanceof Window window) {
            final WindowEvent windowEnterEvent = new WindowEvent(window, WindowEvent.WINDOW_LOST_FOCUS);
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusedWindow(null);
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusOwner(null);
            postEvent(windowEnterEvent);
        }
        inputState = newInputState;
    }

    /**
     * Maps 'struct wl_surface*' to WLComponentPeer that owns the Wayland surface.
     */
    private static final Map<Long, WLComponentPeer> wlSurfaceToComponentMap = Collections.synchronizedMap(new HashMap<>());

    static void registerWLSurface(long wlSurfacePtr, WLComponentPeer componentPeer) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("registerWLSurface: 0x" + Long.toHexString(wlSurfacePtr) + "->" + componentPeer);
        }
        wlSurfaceToComponentMap.put(wlSurfacePtr, componentPeer);
    }

    static void unregisterWLSurface(long wlSurfacePtr) {
        wlSurfaceToComponentMap.remove(wlSurfacePtr);
    }

    static WLComponentPeer componentPeerFromSurface(long wlSurfacePtr) {
        return wlSurfaceToComponentMap.get(wlSurfacePtr);
    }

    @Override
    public RobotPeer createRobot(GraphicsDevice screen) throws AWTException {
        if (screen instanceof WLGraphicsDevice) {
            return new WLRobotPeer((WLGraphicsDevice) screen);
        }
        return super.createRobot(screen);
    }

    @Override
    public void setDynamicLayout(boolean b) {
        log.info("Not implemented: WLToolkit.setDynamicLayout()");
    }

    @Override
    protected boolean isDynamicLayoutSet() {
        log.info("Not implemented: WLToolkit.isDynamicLayoutSet()");
        return false;
    }

    protected boolean isDynamicLayoutSupported() {
        log.info("Not implemented: WLToolkit.isDynamicLayoutSupported()");
        return false;
    }

    @Override
    public boolean isDynamicLayoutActive() {
        return isDynamicLayoutSupported();
    }

    @Override
    public FontPeer getFontPeer(String name, int style){
        log.info("Not implemented: WLToolkit.getFontPeer()");
        return null;
    }

    @Override
    public DragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge) throws InvalidDnDOperationException {
        log.info("Not implemented: WLToolkit.createDragSourceContextPeer()");
        return null;
    }

    @Override
    public <T extends DragGestureRecognizer> T
    createDragGestureRecognizer(Class<T> recognizerClass,
                    DragSource ds,
                    Component c,
                    int srcActions,
                    DragGestureListener dgl)
    {
        log.info("Not implemented: WLToolkit.createDragGestureRecognizer()");
        return null;
    }

    @Override
    public CheckboxMenuItemPeer createCheckboxMenuItem(CheckboxMenuItem target) {
        log.info("Not implemented: WLToolkit.createCheckboxMenuItem()");
        return null;
    }

    @Override
    public MenuItemPeer createMenuItem(MenuItem target) {
        log.info("Not implemented: WLToolkit.createMenuItem()");
        return null;
    }

    @Override
    public TextFieldPeer createTextField(TextField target) {
        log.info("Not implemented: WLToolkit.createTextField()");
        return null;
    }

    @Override
    public LabelPeer createLabel(Label target) {
        log.info("Not implemented: WLToolkit.createLabel()");
        return null;
    }

    @Override
    public ListPeer createList(java.awt.List target) {
        log.info("Not implemented: WLToolkit.createList()");
        return null;
    }

    @Override
    public CheckboxPeer createCheckbox(Checkbox target) {
        log.info("Not implemented: WLToolkit.createCheckbox()");
        return null;
    }

    @Override
    public ScrollbarPeer createScrollbar(Scrollbar target) {
        log.info("Not implemented: WLToolkit.createScrollbar()");
        return null;
    }

    @Override
    public ScrollPanePeer createScrollPane(ScrollPane target) {
        log.info("Not implemented: WLToolkit.createScrollPane()");
        return null;
    }

    @Override
    public TextAreaPeer createTextArea(TextArea target) {
        log.info("Not implemented: WLToolkit.createTextArea()");
        return null;
    }

    @Override
    public ChoicePeer createChoice(Choice target) {
        log.info("Not implemented: WLToolkit.createChoice()");
        return null;
    }

    @Override
    public CanvasPeer createCanvas(Canvas target) {
        WLCanvasPeer peer = new WLCanvasPeer(target);
        targetCreatedPeer(target, peer);
        return peer;
    }

    @Override
    public PanelPeer createPanel(Panel target) {
        log.info("Not implemented: WLToolkit.createPanel()");
        return null;
    }

    @Override
    public WindowPeer createWindow(Window target) {
        log.info("Not implemented: WLToolkit.createWindow()");
        return null;
    }

    @Override
    public DialogPeer createDialog(Dialog target) {
        log.info("Not implemented: WLToolkit.createDialog()");
        return null;
    }

    @Override
    public FileDialogPeer createFileDialog(FileDialog target) {
        log.info("Not implemented: WLToolkit.createFileDialog()");
        return null;
    }

    @Override
    public MenuBarPeer createMenuBar(MenuBar target) {
        log.info("Not implemented: WLToolkit.createMenuBar()");
        return null;
    }

    @Override
    public MenuPeer createMenu(Menu target) {
        log.info("Not implemented: WLToolkit.createMenu()");
        return null;
    }

    @Override
    public PopupMenuPeer createPopupMenu(PopupMenu target) {
        log.info("Not implemented: WLToolkit.createPopupMenu()");
        return null;
    }

    @Override
    public synchronized MouseInfoPeer getMouseInfoPeer() {
        log.info("Not implemented: WLToolkit.getMouseInfoPeer()");
        return null;
    }

    @Override
    public KeyboardFocusManagerPeer getKeyboardFocusManagerPeer() throws HeadlessException {
        return WLKeyboardFocusManagerPeer.getInstance();
    }

    /**
     * Returns a new custom cursor.
     */
    @Override
    public Cursor createCustomCursor(Image cursor, Point hotSpot, String name)
      throws IndexOutOfBoundsException {
        log.info("Not implemented: WLToolkit.createCustomCursor()");
        return null;
    }

    @Override
    public TrayIconPeer createTrayIcon(TrayIcon target)
      throws HeadlessException
    {
        log.info("Not implemented: WLToolkit.createTrayIcon()");
        return null;
    }

    @Override
    public SystemTrayPeer createSystemTray(SystemTray target) throws HeadlessException {
        log.info("Not implemented: WLToolkit.createSystemTray()");
        return null;
    }

    @Override
    public boolean isTraySupported() {
        log.info("Not implemented: WLToolkit.isTraySupported()");
        return false;
    }

    @Override
    public DataTransferer getDataTransferer() {
        log.info("Not implemented: WLToolkit.getDataTransferer()");
        return null;
    }

    /**
     * Returns the supported cursor size
     */
    @Override
    public Dimension getBestCursorSize(int preferredWidth, int preferredHeight) {
        log.info("Not implemented: WLToolkit.getBestCursorSize()");
        return null;
    }

    @Override
    public int getMaximumCursorColors() {
        return 2;  // Black and white.
    }

    @Override
    public Map<TextAttribute, ?> mapInputMethodHighlight( InputMethodHighlight highlight) {
        log.info("Not implemented: WLToolkit.mapInputMethodHighlight()");
        return null;
    }
    @Override
    public boolean getLockingKeyState(int key) {
        log.info("Not implemented: WLToolkit.getLockingKeyState()");
        return false;
    }

    @Override
    public  Clipboard getSystemClipboard() {
        log.info("Not implemented: WLToolkit.getSystemClipboard()");
        return null;
    }

    @Override
    public Clipboard getSystemSelection() {
        log.info("Not implemented: WLToolkit.getSystemSelection()");
        return null;
    }

    @Override
    public void beep() {
        log.info("Not implemented: WLToolkit.beep()");
    }

    @Override
    public PrintJob getPrintJob(final Frame frame, final String doctitle,
                                final Properties props) {
        log.info("Not implemented: WLToolkit.getPrintJob()");
        return null;
    }

    @Override
    public PrintJob getPrintJob(final Frame frame, final String doctitle,
                final JobAttributes jobAttributes,
                final PageAttributes pageAttributes)
    {
        log.info("Not implemented: WLToolkit.getPrintJob()");
        return null;
    }

    @Override
    public int getScreenResolution() {
        log.info("Not implemented: WLToolkit.getScreenResolution()");
        return 0;
    }

    /**
     * Returns a new input method adapter descriptor for native input methods.
     */
    @Override
    public InputMethodDescriptor getInputMethodAdapterDescriptor() {
        log.info("Not implemented: WLToolkit.getInputMethodAdapterDescriptor()");
        return null;
    }

    /**
     * Returns whether enableInputMethods should be set to true for peered
     * TextComponent instances on this platform. True by default.
     */
    @Override
    public boolean enableInputMethodsForTextComponent() {
        log.info("Not implemented: WLToolkit.enableInputMethodsForTextComponent()");
        return true;
    }


    @Override
    public boolean isFrameStateSupported(int state)
      throws HeadlessException
    {
        log.info("Not implemented: WLToolkit.isFrameStateSupported()");
        return false;
    }

    @Override
    protected void initializeDesktopProperties() {
        super.initializeDesktopProperties();

        if (!GraphicsEnvironment.isHeadless()) {
            desktopProperties.put("awt.mouse.numButtons", MOUSE_BUTTONS_COUNT);
        }
    }

    @Override
    public int getNumberOfButtons(){
        return MOUSE_BUTTONS_COUNT;
    }

    @Override
    protected Object lazilyLoadDesktopProperty(String name) {
        log.info("Not implemented: WLToolkit.lazilyLoadDesktopProperty()");
        return null;
    }

    @Override
    public synchronized void addPropertyChangeListener(String name, PropertyChangeListener pcl) {
        log.info("Not implemented: WLToolkit.addPropertyChangeListener()");
    }

    /**
     * @see SunToolkit#needsXEmbedImpl
     */
    @Override
    protected boolean needsXEmbedImpl() {
        log.info("Not implemented: WLToolkit.needsXEmbedImpl()");
        return false;
    }

    @Override
    public boolean isModalityTypeSupported(Dialog.ModalityType modalityType) {
        log.info("Not implemented: WLToolkit.isModalityTypeSupported()");
        return false;
    }

    @Override
    public boolean isModalExclusionTypeSupported(Dialog.ModalExclusionType exclusionType) {
        log.info("Not implemented: WLToolkit.isModalExclusionTypeSupported()");
        return false;
    }

    @Override
    public boolean isAlwaysOnTopSupported() {
        log.info("Not implemented: WLToolkit.isAlwaysOnTopSupported()");
        return false;
    }

    @Override
    public boolean useBufferPerWindow() {
        log.info("Not implemented: WLToolkit.useBufferPerWindow()");
        return false;
    }

    /**
     * @inheritDoc
     */
    @Override
    protected boolean syncNativeQueue(long timeout) {
        log.info("Not implemented: WLToolkit.syncNativeQueue()");
        return false;
    }

    @Override
    public void grab(Window w) {
        log.info("Not implemented: WLToolkit.grab()");
    }

    @Override
    public void ungrab(Window w) {
        log.info("Not implemented: WLToolkit.ungrab()");
    }
    /**
     * Returns if the java.awt.Desktop class is supported on the current
     * desktop.
     * <p>
     * The methods of java.awt.Desktop class are supported on the Gnome desktop.
     * Check if the running desktop is Gnome by checking the window manager.
     */
    @Override
    public boolean isDesktopSupported(){
        log.info("Not implemented: WLToolkit.isDesktopSupported()");
        return false;
    }

    @Override
    public DesktopPeer createDesktopPeer(Desktop target){
        log.info("Not implemented: WLToolkit.createDesktopPeer()");
        return null;
    }

    @Override
    public boolean isTaskbarSupported(){
        log.info("Not implemented: WLToolkit.isTaskbarSupported()");
        return false;
    }

    @Override
    public TaskbarPeer createTaskbarPeer(Taskbar target){
        log.info("Not implemented: WLToolkit.createTaskbarPeer()");
        return null;
    }

    private static boolean areExtraMouseButtonsEnabled = true;
    @Override
    public boolean areExtraMouseButtonsEnabled() throws HeadlessException {
        return areExtraMouseButtonsEnabled;
    }

    @Override
    public boolean isWindowOpacitySupported() {
        log.info("Not implemented: WLToolkit.isWindowOpacitySupported()");
        return false;
    }

    @Override
    public boolean isWindowShapingSupported() {
        log.info("Not implemented: WLToolkit.isWindowShapingSupported()");
        return false;
    }

    @Override
    public boolean isWindowTranslucencySupported() {
        log.info("Not implemented: WLToolkit.isWindowTranslucencySupported()");
        return false;
    }

    @Override
    public boolean isTranslucencyCapable(GraphicsConfiguration gc) {
        log.info("Not implemented: WLToolkit.isWindowTranslucencySupported()");
        return false;
    }

    @Override
    public void sync() {
        flushImpl();
    }

    private native int readEvents();
    private native void dispatchEventsOnEDT();
    private native void flushImpl();
    private native void dispatchNonDefaultQueuesImpl();

    protected static void targetDisposedPeer(Object target, Object peer) {
        SunToolkit.targetDisposedPeer(target, peer);
    }

    static void postEvent(AWTEvent event) {
        SunToolkit.postEvent(AppContext.getAppContext(), event);
    }
}
