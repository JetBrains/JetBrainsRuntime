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

import jdk.internal.misc.InnocuousThread;
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
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

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
     * Returned by readEvents() to signify the presence of events on the default
     * Wayland display queue that have not been dispatched yet.
     */
    private static final int READ_RESULT_FINISHED_WITH_EVENTS = 0;

    /**
     * Returned by readEvents() to signify the absence of events on the default
     * Wayland display queue.
     */
    private static final int READ_RESULT_FINISHED_NO_EVENTS = 1;

    /**
     * Returned by readEvents() in case of an error condition like
     * disappearing of the Wayland display. Errors not specifically
     * related to Wayland are reported via an exception.
     */
    private static final int READ_RESULT_ERROR = 2;
    
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

        if (!GraphicsEnvironment.isHeadless()) {
            Thread toolkitThread = InnocuousThread.newThread("AWT-Wayland", this);
            toolkitThread.setDaemon(true);
            toolkitThread.start();

            final Thread toolkitSystemThread = InnocuousThread.newThread("AWT-Wayland-system-dispatcher", this::dispatchNonDefaultQueues);
            toolkitSystemThread.setDaemon(true);
            toolkitSystemThread.start();

            // Wait here for all display sync events to have been received?
        }
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

    private final Semaphore eventsQueued = new Semaphore(0);

    @Override
    public void run() {
        while(true) {
            int result = readEvents();
            if (result == READ_RESULT_ERROR) {
                log.severe("Wayland protocol I/O error");
                // TODO: display disconnect handling here?
                break;
            } else if (result == READ_RESULT_FINISHED_WITH_EVENTS) {
                SunToolkit.postEvent(AppContext.getAppContext(), new PeerEvent(this, () -> {
                    WLToolkit.awtLock();
                    try {
                        dispatchEventsOnEDT();
                    } finally {
                        eventsQueued.release();
                        WLToolkit.awtUnlock();
                    }
                }, PeerEvent.ULTIMATE_PRIORITY_EVENT));
                try {
                    eventsQueued.acquire();
                } catch (InterruptedException e) {
                    log.severe("Wayland protocol I/O thread interrupted");
                    break;
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

    private static class KeyRepeatManager {
        private Timer keyRepeatTimer;
        private PostKeyEventTask postKeyEventTask;

        KeyRepeatManager() {
        }

        private void stopKeyRepeat() {
            assert EventQueue.isDispatchThread();

            if (postKeyEventTask != null) {
                postKeyEventTask.cancel();
                postKeyEventTask = null;
            }
        }

        private void initiateDelayedKeyRepeat(long keycode,
                                              int keyCodePoint,
                                              WLComponentPeer peer) {
            assert EventQueue.isDispatchThread();
            assert postKeyEventTask == null;

            if (keyRepeatTimer == null) {
                // The following starts a dedicated daemon thread.
                keyRepeatTimer = new Timer("WLToolkit Key Repeat", true);
            }

            postKeyEventTask = new PostKeyEventTask(keycode, keyCodePoint, peer);

            assert WLToolkit.keyRepeatRate > 0;
            assert WLToolkit.keyRepeatDelay > 0;

            keyRepeatTimer.schedule(
                    postKeyEventTask,
                    WLToolkit.keyRepeatDelay,
                    (long)(1000.0 / WLToolkit.keyRepeatRate));
        }

        static boolean xkbCodeRequiresRepeat(long code) {
            return !WLKeySym.xkbCodeIsModifier(code);
        }

        void keyboardEvent(long keycode, int keyCodePoint,
                           boolean isPressed, WLComponentPeer peer) {
            stopKeyRepeat();
            if (isPressed && KeyRepeatManager.xkbCodeRequiresRepeat(keycode)) {
                initiateDelayedKeyRepeat(keycode, keyCodePoint, peer);
            }
        }

        void windowEvent(WindowEvent event) {
            if (event.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
                stopKeyRepeat();
            }
        }

        private static class PostKeyEventTask extends TimerTask {
            final long keycode;
            final int keyCodePoint;
            final WLComponentPeer peer;

            PostKeyEventTask(long keycode,
                             int keyCodePoint,
                             WLComponentPeer peer) {
                this.keycode = keycode;
                this.keyCodePoint = keyCodePoint;
                this.peer = peer;
            }

            @Override
            public void run() {
                final long timestamp = System.currentTimeMillis();
                try {
                    EventQueue.invokeAndWait(() -> {
                        generateKeyEventFrom(timestamp, keycode, keyCodePoint, true, peer);
                    });
                } catch (InterruptedException ignored) {
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static final KeyRepeatManager keyRepeatManager = new KeyRepeatManager();

    private static WLInputState inputState = WLInputState.initialState();

    private static void dispatchPointerEvent(WLPointerEvent e) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread();

        if (log.isLoggable(PlatformLogger.Level.FINE)) log.fine("dispatchPointerEvent: " + e);

        final WLInputState oldInputState = inputState;
        final WLInputState newInputState = oldInputState.update(e);
        inputState = newInputState;
        final WLComponentPeer peer = newInputState.getPeer();
        if (peer == null) {
            // we don't know whom to notify of the event
            log.severe("Surface doesn't map to any component");
        } else {
            peer.dispatchPointerEventInContext(e, oldInputState, newInputState);
        }
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
            generateKeyEventFrom(timestamp, keycode, keyCodePoint, isPressed, peer);
            keyRepeatManager.keyboardEvent(keycode, keyCodePoint, isPressed, peer);
        }
    }

    private static void generateKeyEventFrom(long timestamp, long keycode, int keyCodePoint,
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

    private static void dispatchKeyboardEnterEvent(long serial, long surfacePtr) {
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
            final WindowEvent winLostFocusEvent = new WindowEvent(window, WindowEvent.WINDOW_LOST_FOCUS);
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusedWindow(null);
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusOwner(null);
            keyRepeatManager.windowEvent(winLostFocusEvent);
            postEvent(winLostFocusEvent);
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
        WLWindowPeer peer = new WLWindowPeer(target);
        targetCreatedPeer(target, peer);
        return peer;
    }

    @Override
    public DialogPeer createDialog(Dialog target) {
        WLDialogPeer peer = new WLDialogPeer(target);
        targetCreatedPeer(target, peer);
        return peer;
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
        return new WLCustomCursor(cursor, hotSpot, name);
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

    @Override
    public Dimension getBestCursorSize(int preferredWidth, int preferredHeight) {
        // we don't restrict the maximum size
        return new Dimension(Math.max(1, preferredWidth), Math.max(1, preferredHeight));
    }

    @Override
    public int getMaximumCursorColors() {
        return 16777216; // 24 bits per pixel, 8 bits per channel
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
        // TODO
        return 150;
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
        // TODO: set based on wm_capabilities event
        return switch (state) {
            case Frame.NORMAL -> true;
            case Frame.ICONIFIED -> true;
            case Frame.MAXIMIZED_BOTH -> true;
            default -> false;
        };
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
        return (modalityType == Dialog.ModalityType.MODELESS) ||
                (modalityType == Dialog.ModalityType.APPLICATION_MODAL);
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

    @Override
    public boolean needUpdateWindow() {
        return true;
    }

    static WLInputState getInputState() {
        return inputState;
    }

    // this emulates pointer leave event, which isn't sent sometimes by compositor
    static void resetPointerInputState() {
        inputState = inputState.resetPointerState();
    }
}
