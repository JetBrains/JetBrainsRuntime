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
import sun.awt.AWTAutoShutdown;
import sun.awt.AppContext;
import sun.awt.LightweightFrame;
import sun.awt.PeerEvent;
import sun.awt.SunToolkit;
import sun.awt.UNIXToolkit;
import sun.awt.datatransfer.DataTransferer;
import sun.awt.wl.im.WLInputMethodMetaDescriptor;
import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKRenderQueue;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.dnd.peer.DragSourceContextPeer;
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
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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

    private static final int MOUSE_BUTTONS_COUNT = 7;
    private static final int AWT_MULTICLICK_DEFAULT_TIME_MS = 500;

    private static boolean initialized = false;
    private static Thread toolkitThread;
    private final WLDataDevice dataDevice;

    private static Boolean sunAwtDisableGtkFileDialogs = null;

    private static final boolean isKDE;

    // NOTE: xdg_toplevel_icon_v1 is pretty much only supported on KDE, and KWin always sends 96px as the icon size,
    // regardless of the display resolution, scale, or anything else.
    // TODO: this is currently unused
    private static final java.util.List<Integer> preferredIconSizes = new ArrayList<>();

    private static native void initIDs(long displayPtr);

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            keyboard = new WLKeyboard();
            long display = WLDisplay.getInstance().getDisplayPtr();
            VKEnv.init(display);
            initIDs(display);
        }
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        isKDE = desktop != null && desktop.toLowerCase().contains("kde");
        initialized = true;
    }

    @SuppressWarnings("removal")
    public WLToolkit() {
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            initSystemProperties();
            return null;
        });

        if (!GraphicsEnvironment.isHeadless()) {
            toolkitThread = InnocuousThread.newThread("AWT-Wayland", this);
            toolkitThread.setDaemon(true);
            toolkitThread.start();

            final Thread toolkitSystemThread = InnocuousThread.newThread("AWT-Wayland-system-dispatcher", this::dispatchNonDefaultQueues);
            toolkitSystemThread.setDaemon(true);
            toolkitSystemThread.start();

            dataDevice = new WLDataDevice(0); // TODO: for multiseat support pass wl_seat pointer here

            registerShutdownHook();
        } else {
            dataDevice = null;
        }
    }

    private void registerShutdownHook() {
        Runnable r = () -> {
            ArrayList<WLWindowPeer> livePeers;
            synchronized (wlSurfaceToPeerMap) {
                livePeers = new ArrayList<>(wlSurfaceToPeerMap.values());
            }
            livePeers.forEach(p -> {
                Component target = p.getTarget();
                if (target.isDisplayable()) {
                    target.removeNotify();
                }
            });
        };
        Thread shutdownThread = InnocuousThread.newSystemThread("WLToolkit-Shutdown-Thread", r);
        shutdownThread.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(shutdownThread);
    }

    public static synchronized boolean getSunAwtDisableGtkFileDialogs() {
        if (sunAwtDisableGtkFileDialogs == null) {
            sunAwtDisableGtkFileDialogs = Boolean.getBoolean("sun.awt.disableGtkFileDialogs");
        }
        return sunAwtDisableGtkFileDialogs;
    }

    private static void initSystemProperties() {
        final String extraButtons = "sun.awt.enableExtraMouseButtons";
        areExtraMouseButtonsEnabled =
                Boolean.parseBoolean(System.getProperty(extraButtons, "true"));
        System.setProperty(extraButtons, String.valueOf(areExtraMouseButtonsEnabled));
    }

    static String getApplicationID() {
        // Do not cache the properties as the application might want to change
        // them at run time. Might also consider using JComponent's client property
        // for per-window app ID
        String appID = System.getProperty("awt.app.id");
        return appID != null ? appID : System.getProperty("sun.java.command");
    }

    public static boolean isToolkitThread() {
        return Thread.currentThread() == toolkitThread;
    }

    @Override
    public boolean needUpdateWindowAfterPaint() {
        return true;
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
            AWTAutoShutdown.notifyToolkitThreadFree(); // will now wait for events
            int result = readEvents();
            if (result == READ_RESULT_ERROR) {
                log.severe("Wayland protocol I/O error");
                shutDownAfterServerError();
                break;
            } else if (result == READ_RESULT_FINISHED_WITH_EVENTS) {
                AWTAutoShutdown.notifyToolkitThreadBusy(); // busy processing events
                SunToolkit.postEvent(AppContext.getAppContext(), new PeerEvent(this, () -> {
                    WLToolkit.awtLock();
                    try {
                        dispatchEventsOnEDT();
                        if (dataDevice != null) {
                            dataDevice.performDeletionsOnEDT();
                        }
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

    private static void shutDownAfterServerError() {
        EventQueue.invokeLater(() -> {
            var frames = Arrays.asList(Frame.getFrames());
            Collections.reverse(frames);
            frames.forEach(frame -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));

            // They've had their chance to exit from the "window closing" handler code. If we have gotten here,
            // that chance wasn't taken. But we cannot continue because the connection with the server is
            // no longer available, so let's exit forcibly.
            System.exit(0);
        });
    }

    /**
     * If more than this amount milliseconds has passed since the same mouse button click,
     * the next click is considered separate and not part of multi-click event.
     * @return maximum milliseconds between same mouse button clicks for them to be a multiclick
     */
    static int getMulticlickTime() {
        /* TODO: get from the system somehow */
        return AWT_MULTICLICK_DEFAULT_TIME_MS;
    }

    private static WLInputState inputState = WLInputState.initialState();
    private static WLKeyboard keyboard;

    private static void dispatchRelativePointerEvent(double dx, double dy) {
        WLMouseInfoPeer.getInstance().accumulatePointerDelta(dx, dy);
    }

    private static void dispatchPointerEvent(WLPointerEvent e) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINE)) log.fine("dispatchPointerEvent: " + e);

        final WLInputState oldInputState = inputState;
        final WLInputState newInputState = oldInputState.updatedFromPointerEvent(e);
        inputState = newInputState;
        if (e.hasLeaveEvent() || e.hasEnterEvent()) {
            // We've lost the control over the cursor, assume no knowledge about it
            getCursorManager().reset();
        }
        final WLComponentPeer peer = newInputState.peerForPointerEvents();
        if (peer == null) {
            // We don't know whom to notify of the event; may happen when
            // the surface has recently disappeared, in which case
            // e.getSurface() is likely 0.
        } else {
            peer.dispatchPointerEventInContext(e, oldInputState, newInputState);
        }
    }

    private static void dispatchKeyboardKeyEvent(long serial,
                                                 long timestamp,
                                                 int id,
                                                 int keyCode,
                                                 int keyLocation,
                                                 int rawCode,
                                                 int extendedKeyCode,
                                                 char keyChar,
                                                 int modifiers) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        inputState = inputState.updatedFromKeyEvent(serial);

        if (timestamp == 0) {
            // Happens when a surface was focused with keys already pressed.
            // Fake the timestamp by peeking at the last known event.
            timestamp = inputState.getTimestamp();
        }

        final long surfacePtr = inputState.surfaceForKeyboardInput();
        final WLComponentPeer peer = peerFromSurface(surfacePtr);
        if (peer != null) {
            if (extendedKeyCode >= 0x1000000) {
                int ch = extendedKeyCode - 0x1000000;
                int correctCode = KeyEvent.getExtendedKeyCodeForChar(ch);
                if (extendedKeyCode == keyCode) {
                    keyCode = correctCode;
                }
                extendedKeyCode = correctCode;
            }

            postKeyEvent(
                    peer.getTarget(),
                    id,
                    timestamp,
                    keyCode,
                    keyChar,
                    keyLocation,
                    rawCode,
                    extendedKeyCode,
                    modifiers
            );
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
                                     int extendedKeyCode,
                                     int modifiers) {
        int mergedModifiers = inputState.getNonKeyboardModifiers() | modifiers;
        final KeyEvent keyEvent = new KeyEvent(source, id, timestamp,
                mergedModifiers, keyCode, keyChar, keyLocation);

        AWTAccessor.KeyEventAccessor kea = AWTAccessor.getKeyEventAccessor();
        kea.setRawCode(keyEvent, rawCode);
        kea.setExtendedKeyCode(keyEvent, (long) extendedKeyCode);
        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine(String.valueOf(keyEvent));
        }
        postEvent(keyEvent);
    }

    private static void dispatchKeyboardModifiersEvent(long serial) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";
        inputState = inputState.updatedFromKeyboardModifiersEvent(serial, keyboard.getModifiers());
        WLDropTargetContextPeer.getInstance().handleModifiersUpdate();
    }

    private static void dispatchKeyboardEnterEvent(long serial, long surfacePtr) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine("dispatchKeyboardEnterEvent: " + serial + ", surface 0x"
                    + Long.toHexString(surfacePtr));
        }

        final WLInputState newInputState = inputState.updatedFromKeyboardEnterEvent(serial, surfacePtr);
        final WLWindowPeer peer = peerFromSurface(surfacePtr);
        if (peer != null) {
            Window window = (Window) peer.getTarget();
            Window winToFocus = window;

            Component s = peer.getSyntheticFocusOwner();
            if (s instanceof Window synthWindow) {
                if (synthWindow.isVisible() && synthWindow.isFocusableWindow()) {
                    winToFocus = synthWindow;
                }
            }

            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusedWindow(window);
            WindowEvent windowEnterEvent = new WindowEvent(winToFocus, WindowEvent.WINDOW_GAINED_FOCUS);
            postPriorityEvent(windowEnterEvent);
        }
        inputState = newInputState;
    }

    private static void dispatchKeyboardLeaveEvent(long serial, long surfacePtr) {
        // Invoked from the native code
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (logKeys.isLoggable(PlatformLogger.Level.FINE)) {
            logKeys.fine("dispatchKeyboardLeaveEvent: " + serial + ", surface 0x"
                    + Long.toHexString(surfacePtr));
        }

        keyboard.onLostFocus();

        final WLInputState newInputState = inputState.updatedFromKeyboardLeaveEvent(serial, surfacePtr);
        final WLWindowPeer peer = peerFromSurface(surfacePtr);
        if (peer != null && peer.getTarget() instanceof Window window) {
            final WindowEvent winLostFocusEvent = new WindowEvent(window, WindowEvent.WINDOW_LOST_FOCUS);
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusedWindow(null);
            WLKeyboardFocusManagerPeer.getInstance().setCurrentFocusOwner(null);
            postPriorityEvent(winLostFocusEvent);
        }
        inputState = newInputState;
    }

    /**
     * Maps 'struct wl_surface*' to WLComponentPeer that owns the Wayland surface.
     */
    private static final Map<Long, WLWindowPeer> wlSurfaceToPeerMap = new HashMap<>();

    static void registerWLSurface(long wlSurfacePtr, WLWindowPeer peer) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("registerWLSurface: 0x" + Long.toHexString(wlSurfacePtr) + "->" + peer);
        }
        synchronized (wlSurfaceToPeerMap) {
            wlSurfaceToPeerMap.put(wlSurfacePtr, peer);
        }
    }

    static void unregisterWLSurface(long wlSurfacePtr) {
        synchronized (wlSurfaceToPeerMap) {
            wlSurfaceToPeerMap.remove(wlSurfacePtr);
        }

        inputState = inputState.updatedFromUnregisteredSurface(wlSurfacePtr);
    }

    static WLWindowPeer peerFromSurface(long wlSurfacePtr) {
        synchronized (wlSurfaceToPeerMap) {
            return wlSurfaceToPeerMap.get(wlSurfacePtr);
        }
    }

    /**
     * If there's exactly one Wayland surface present, return the native peer
     * associated with that surface.
     * Otherwise, throw UOE.
     */
    static WLWindowPeer getSingularWindowPeer() {
        synchronized (wlSurfaceToPeerMap) {
            if (wlSurfaceToPeerMap.size() > 1) {
                throw new UnsupportedOperationException("More than one native window");
            } else if (wlSurfaceToPeerMap.isEmpty()) {
                throw new UnsupportedOperationException("No native windows");
            }

            return wlSurfaceToPeerMap.values().iterator().next();
        }
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
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.setDynamicLayout()");
        }
    }

    @Override
    public boolean isRunningOnXWayland() {
        return false;
    }

    @Override
    protected boolean isDynamicLayoutSet() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.isDynamicLayoutSet()");
        }
        return false;
    }

    protected boolean isDynamicLayoutSupported() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.isDynamicLayoutSupported()");
        }
        return false;
    }

    @Override
    public boolean isDynamicLayoutActive() {
        return isDynamicLayoutSupported();
    }

    @Override
    public FontPeer getFontPeer(String name, int style) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.getFontPeer()");
        }
        return null;
    }

    @Override
    public DragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge) throws InvalidDnDOperationException {
        return new WLDragSourceContextPeer(dge, dataDevice);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DragGestureRecognizer> T
    createDragGestureRecognizer(Class<T> recognizerClass,
                    DragSource ds,
                    Component c,
                    int srcActions,
                    DragGestureListener dgl)
    {
        final LightweightFrame f = SunToolkit.getLightweightFrame(c);
        if (f != null) {
            return f.createDragGestureRecognizer(recognizerClass, ds, c, srcActions, dgl);
        }

        if (recognizerClass.isAssignableFrom(WLMouseDragGestureRecognizer.class)) {
            return (T)new WLMouseDragGestureRecognizer(ds, c, srcActions, dgl);
        }

        return null;
    }

    @Override
    public CheckboxMenuItemPeer createCheckboxMenuItem(CheckboxMenuItem target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createCheckboxMenuItem()");
        }
        return null;
    }

    @Override
    public MenuItemPeer createMenuItem(MenuItem target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createMenuItem()");
        }
        return null;
    }

    @Override
    public TextFieldPeer createTextField(TextField target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createTextField()");
        }
        return null;
    }

    @Override
    public LabelPeer createLabel(Label target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createLabel()");
        }
        return null;
    }

    @Override
    public ListPeer createList(java.awt.List target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createList()");
        }
        return null;
    }

    @Override
    public CheckboxPeer createCheckbox(Checkbox target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createCheckbox()");
        }
        return null;
    }

    @Override
    public ScrollbarPeer createScrollbar(Scrollbar target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createScrollbar()");
        }
        return null;
    }

    @Override
    public ScrollPanePeer createScrollPane(ScrollPane target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createScrollPane()");
        }
        return null;
    }

    @Override
    public TextAreaPeer createTextArea(TextArea target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createTextArea()");
        }
        return null;
    }

    @Override
    public ChoicePeer createChoice(Choice target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createChoice()");
        }
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
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createPanel()");
        }
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
        FileDialogPeer peer = null;
        if (!getSunAwtDisableGtkFileDialogs() && checkGtkVersion(3, 0, 0)) {
            peer = new GtkFileDialogPeer(target);
            targetCreatedPeer(target, peer);
            return peer;
        } else {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Not implemented: WLToolkit.createFileDialog()");
            }
            return null;
        }
    }

    @Override
    public MenuBarPeer createMenuBar(MenuBar target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createMenuBar()");
        }
        return null;
    }

    @Override
    public MenuPeer createMenu(Menu target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createMenu()");
        }
        return null;
    }

    @Override
    public PopupMenuPeer createPopupMenu(PopupMenu target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createPopupMenu()");
        }
        return null;
    }

    @Override
    public synchronized MouseInfoPeer getMouseInfoPeer() {
        return WLMouseInfoPeer.getInstance();
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
    public TrayIconPeer createTrayIcon(TrayIcon target) throws HeadlessException {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createTrayIcon()");
        }
        return null;
    }

    @Override
    public SystemTrayPeer createSystemTray(SystemTray target) throws HeadlessException {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createSystemTray()");
        }
        return null;
    }

    @Override
    public boolean isTraySupported() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.isTraySupported()");
        }
        return false;
    }

    @Override
    public DataTransferer getDataTransferer() {
        return WLDataTransferer.getInstanceImpl();
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

    /**
     * {@link java.awt.event.InputMethodEvent#getText()} can contain attributes which provide AWT/Swing with additional useful information
     *   (e.g. a language of the text).
     *
     * One kind of the possible attributes is {@link InputMethodHighlight}. It informs AWT/Swing that some parts of
     *   the text are in different states of the text composing process, hence they should look differently from the others.
     *   However, it doesn't tell how exactly they should look; this choice is left to Toolkit's implementations,
     *   or more precisely implementations of this method.
     *
     * @param highlight a state of a part of InputMethodEvent's text
     *
     * @return a collection of {@link TextAttribute}s (with their corresponding values as documented) informing how exactly
     *         such text should look or {@code null} if a mapping can't be provided.
     *
     * @see Toolkit#mapInputMethodHighlight(InputMethodHighlight)
     */
    @Override
    public Map<TextAttribute, ?> mapInputMethodHighlight(InputMethodHighlight highlight) {
        return WLInputMethodMetaDescriptor.mapInputMethodHighlight(highlight);
    }

    @Override
    public boolean getLockingKeyState(int key) {
        return switch (key) {
            case KeyEvent.VK_CAPS_LOCK -> keyboard.isCapsLockPressed();
            case KeyEvent.VK_NUM_LOCK -> keyboard.isNumLockPressed();
            case KeyEvent.VK_SCROLL_LOCK, KeyEvent.VK_KANA_LOCK ->
                    throw new UnsupportedOperationException("getting locking key state is not supported for this key");
            default -> throw new IllegalArgumentException("invalid key for Toolkit.getLockingKeyState");
        };
    }

    @Override
    public  Clipboard getSystemClipboard() {
        return dataDevice.getSystemClipboard();
    }

    @Override
    public Clipboard getSystemSelection() {
        return dataDevice.getPrimarySelectionClipboard();
    }

    @Override
    public void beep() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.beep()");
        }
    }

    @Override
    public PrintJob getPrintJob(final Frame frame, final String doctitle,
                                final Properties props) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.getPrintJob()");
        }
        return null;
    }

    @Override
    public PrintJob getPrintJob(final Frame frame, final String doctitle,
                final JobAttributes jobAttributes,
                final PageAttributes pageAttributes)
    {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.getPrintJob()");
        }
        return null;
    }

    @Override
    public int getScreenResolution() {
        var defaultScreen = (WLGraphicsDevice)WLGraphicsEnvironment.getSingleInstance().getDefaultScreenDevice();
        return defaultScreen.getResolution();
    }

    /**
     * Returns a new input method adapter descriptor for native input methods.
     */
    @Override
    public InputMethodDescriptor getInputMethodAdapterDescriptor() {
        return WLInputMethodMetaDescriptor.getInstanceIfAvailableOnPlatform();
    }

    /**
     * Returns whether enableInputMethods should be set to true for peered
     * TextComponent instances on this platform. True by default.
     */
    @Override
    public boolean enableInputMethodsForTextComponent() {
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

        desktopProperties.put("DnD.Autoscroll.initialDelay", 50);
        desktopProperties.put("DnD.Autoscroll.interval", 50);
        desktopProperties.put("DnD.Autoscroll.cursorHysteresis", 5);
        desktopProperties.put("Shell.shellFolderManager", "sun.awt.shell.ShellFolderManager");

        if (!GraphicsEnvironment.isHeadless()) {
            desktopProperties.put("awt.multiClickInterval", getMulticlickTime());
            desktopProperties.put("awt.mouse.numButtons", getNumberOfButtons());
        }
    }

    @Override
    public int getNumberOfButtons(){
        return MOUSE_BUTTONS_COUNT;
    }

    @Override
    protected Object lazilyLoadDesktopProperty(String name) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.lazilyLoadDesktopProperty()");
        }
        return null;
    }

    /**
     * @see SunToolkit#needsXEmbedImpl
     */
    @Override
    protected boolean needsXEmbedImpl() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.needsXEmbedImpl()");
        }
        return false;
    }

    @Override
    public boolean isModalityTypeSupported(Dialog.ModalityType modalityType) {
        return (modalityType == Dialog.ModalityType.MODELESS) ||
                (modalityType == Dialog.ModalityType.APPLICATION_MODAL);
    }

    @Override
    public boolean isModalExclusionTypeSupported(Dialog.ModalExclusionType exclusionType) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.isModalExclusionTypeSupported()");
        }
        return false;
    }

    @Override
    public boolean isAlwaysOnTopSupported() {
        return false;
    }

    @Override
    public boolean useBufferPerWindow() {
        // TODO: this may depend on the rendering engine used.
        // When rendering is performed into memory buffers shared with Wayland,
        // there's no sense in having additional buffers in AWT/Swing.
        return false;
    }

    /**
     * @inheritDoc
     */
    @Override
    protected boolean syncNativeQueue(long timeout) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.syncNativeQueue()");
        }
        return false;
    }

    @Override
    public void grab(Window w) {
        // There is no input grab in Wayland for client applications, only
        // the compositor can control grabs. But we need UngrabEvent
        // for popup/tooltip management, so we do input grab accounting here
        // and in ungrab() below.
        Objects.requireNonNull(w);

        var peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer instanceof WLWindowPeer windowPeer) {
            windowPeer.grab();
        }
    }

    @Override
    public void ungrab(Window w) {
        Objects.requireNonNull(w);

        var peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer instanceof WLWindowPeer windowPeer) {
            windowPeer.ungrab(false);
        }
    }

    /**
     * Returns if the java.awt.Desktop class is supported on the current
     * desktop.
     * <p>
     * The methods of java.awt.Desktop class are supported on the Gnome desktop.
     * Check if the running desktop is Gnome by checking the window manager.
     */
    @Override
    public boolean isDesktopSupported() {
        return WLDesktopPeer.isDesktopSupported();
    }

    @Override
    public DesktopPeer createDesktopPeer(Desktop target) {
        return new WLDesktopPeer();
    }

    @Override
    public boolean isTaskbarSupported() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.isTaskbarSupported()");
        }
        return false;
    }

    @Override
    public TaskbarPeer createTaskbarPeer(Taskbar target) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLToolkit.createTaskbarPeer()");
        }
        return null;
    }

    private static boolean areExtraMouseButtonsEnabled = true;
    @Override
    public boolean areExtraMouseButtonsEnabled() throws HeadlessException {
        return areExtraMouseButtonsEnabled;
    }

    @Override
    public boolean isWindowOpacitySupported() {
        return false;
    }

    @Override
    public boolean isWindowShapingSupported() {
        return false;
    }

    @Override
    public boolean isWindowTranslucencySupported() {
        return true;
    }

    @Override
    public boolean isTranslucencyCapable(GraphicsConfiguration gc) {
        if (gc instanceof WLGraphicsConfig wlGraphicsConfig) {
            return wlGraphicsConfig.isTranslucencyCapable();
        }
        return false;
    }

    @Override
    public void sync() {
        if(VKEnv.isPresentationEnabled()) {
            VKRenderQueue.sync();
        }
        flushImpl();
    }

    public void flush() {
        flushImpl();
    }

    @Override
    public Insets getScreenInsets(final GraphicsConfiguration gc) {
        final GraphicsDevice gd = gc.getDevice();
        if (gd instanceof WLGraphicsDevice device) {
            Insets insets = device.getInsets();
            return (Insets) insets.clone();
        } else {
            return super.getScreenInsets(gc);
        }
    }

    /**
     * @return true if xdg-decoration-unstable-v1 is supported and false otherwise.
     */
    public static boolean isSSDAvailable() {
        return isSSDAvailableImpl();
    }

    private static native boolean isSSDAvailableImpl();

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

    static boolean isInitialized() {
        return initialized;
    }

    public static WLCursorManager getCursorManager() {
        return WLCursorManager.getInstance();
    }

    public static boolean isKDE() {
        return isKDE;
    }

    // called from native
    private static void handleToplevelIconSize(int size) {
        preferredIconSizes.add(size);
    }
}
