/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2022, JetBrains s.r.o.. All rights reserved.
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

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.dnd.peer.DragSourceContextPeer;
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
import java.util.Map;
import java.util.Properties;

import sun.awt.*;
import sun.awt.datatransfer.DataTransferer;
import sun.util.logging.PlatformLogger;

public class WLToolkit extends UNIXToolkit implements Runnable {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLToolkit");

    private static final int READ_RESULT_NOT_STARTED = 0;
    private static final int READ_RESULT_FINISHED_NO_EVENTS = 1;
    private static final int READ_RESULT_FINISHED_WITH_EVENTS = 2;

    private static native void initIDs();

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            initIDs();
        }
    }

    public WLToolkit() {
        Thread toolkitThread = new Thread(this, "AWT-Wayland");
        toolkitThread.setDaemon(true);
        toolkitThread.start();
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

    @Override
    public void run() {
        while(true) {
            int result = readEvents();
            if (result == READ_RESULT_FINISHED_WITH_EVENTS) {
                SunToolkit.postEvent(AppContext.getAppContext(),
                        new PeerEvent(this, () -> {
                            dispatchEvents();
                            synchronized (WLToolkit.this) {
                                WLToolkit.this.notifyAll();
                            }
                        }, PeerEvent.ULTIMATE_PRIORITY_EVENT));
            } else if (result == READ_RESULT_NOT_STARTED) {
                synchronized (WLToolkit.this) {
                    try {
                        WLToolkit.this.wait(50);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    @Override
    public RobotPeer createRobot(GraphicsDevice screen) throws AWTException {
        log.info("Not implemented: WLToolkit.createRobot()");
        return null;
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
        log.info("Not implemented: WLToolkit.initializeDesktopProperties()");
    }

    @Override
    public int getNumberOfButtons(){
        log.info("Not implemented: WLToolkit.getNumberOfButtons()");
        return 0;
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

    @Override
    public boolean areExtraMouseButtonsEnabled() throws HeadlessException {
        log.info("Not implemented: WLToolkit.areExtraMouseButtonsEnabled()");
        return false;
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

    private native void dispatchEvents();
    private native int readEvents();

    protected static void targetDisposedPeer(Object target, Object peer) {
        SunToolkit.targetDisposedPeer(target, peer);
    }

    static void postEvent(AWTEvent event) {
        SunToolkit.postEvent(AppContext.getAppContext(), event);
    }
}
