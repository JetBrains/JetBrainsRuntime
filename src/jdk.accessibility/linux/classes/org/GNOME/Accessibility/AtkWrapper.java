/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 * Copyright (C) 2015 Magdalen Berns <m.berns@thismagpie.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.GNOME.Accessibility;

import java.awt.*;
import java.awt.event.*;
import java.beans.*;
import java.io.*;
import javax.accessibility.*;
import java.awt.Toolkit;
import javax.swing.JComboBox;
import java.util.*;

import sun.util.logging.PlatformLogger;

public class AtkWrapper {
    private static final PlatformLogger log = PlatformLogger.getLogger("org.GNOME.Accessibility.AtkWrapper");
    private static boolean accessibilityEnabled = false;

    private static void initAtk() {
        System.loadLibrary("atk-wrapper");
        if (!AtkWrapper.initNativeLibrary()) {
            throw new IllegalStateException("Accessibility is disabled due to an error in initNativeLibrary.");
        }
        if (!AtkWrapper.loadAtkBridge()) {
            throw new IllegalStateException("Accessibility is disabled due to an error in loadAtkBridge.");

        }
        accessibilityEnabled = true;
    }

    private static String findXPropPath() {
        String pathEnv = System.getenv().get("PATH");
        if (pathEnv != null) {
            String pathSeparator = File.pathSeparator;
            java.util.List<String> paths = Arrays.asList(pathEnv.split(File.pathSeparator));
            for (String path : paths) {
                File xpropFile = new File(path, "xprop");
                if (xpropFile.exists()) {
                    return xpropFile.getAbsolutePath();
                }
            }
        }
        return null;
    }

    static {
        try {
            String xpropPath = findXPropPath();
            if (xpropPath == null) {
                throw new RuntimeException("No xprop found");
            }
            ProcessBuilder builder = new ProcessBuilder(xpropPath, "-root");
            Process p = builder.start();
            BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String result;
            while ((result = b.readLine()) != null) {
                if (result.indexOf("AT_SPI_IOR") >= 0 || result.indexOf("AT_SPI_BUS") >= 0) {
                    initAtk();
                    break;
                }
            }

            if (!accessibilityEnabled) {
                builder = new ProcessBuilder("dbus-send", "--session", "--dest=org.a11y.Bus", "--print-reply", "/org/a11y/bus", "org.a11y.Bus.GetAddress");
                p = builder.start();
                var ignoredOutput = p.getInputStream();
                while (ignoredOutput.skip(Long.MAX_VALUE) == Long.MAX_VALUE) ;
                p.waitFor();
                if (p.exitValue() == 0)
                    initAtk();
            }
        } catch (Exception e) {
            accessibilityEnabled = false;
            if (log.isLoggable(PlatformLogger.Level.SEVERE)) {
                log.severe("Error during ATK accessibility initialization: ", e);
            }
        }
    }

    /**
     * winAdapter
     * <p>
     * <http://docs.oracle.com/javase/8/docs/api/java/awt/event/WindowAdapter.html>
     * <http://docs.oracle.com/javase/8/docs/api/java/awt/event/WindowEvent.html>
     */
    private final WindowAdapter winAdapter = new WindowAdapter() {

        /**
         * windowActivated:
         *                    Invoked when a Window becomes the active Window.
         *                    Only a Frame or a Dialog can be the active Window. The
         *                    native windowing system may denote the active Window
         *                    or its children with special decorations, such as a
         *                    highlighted title bar. The active Window is always either
         *                    the focused Window, or the first Frame or Dialog that is
         *                    an owner of the focused Window.
         * @param e A WindowEvent object
         */
        public void windowActivated(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowActivate(ac);
            }
        }

        /**
         * windowDeactivated:
         *                    Invoked when a Window is no longer the active Window.
         *                    Only a Frame or a Dialog can be the active Window. The
         *                    native windowing system may denote the active Window
         *                    or its children with special decorations, such as a
         *                    highlighted title bar. The active Window is always either
         *                    the focused Window, or the first Frame or Dialog that is
         *                    an owner of the focused Window.
         * @param e A WindowEvent object
         */
        public void windowDeactivated(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowDeactivate(ac);
            }
        }

        /**
         * windowStateChanged:
         *                     Invoked when a window state is changed.
         *
         * @param e A WindowEvent object
         */
        public void windowStateChanged(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowStateChange(ac);

                if ((e.getNewState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                    AtkWrapperDisposer.addRecord(ac);
                    AtkWrapper.windowMaximize(ac);
                }
            }
        }

        /**
         * windowDeiconified:
         *                   Invoked when a window is deiconified.
         * @param e A WindowEvent instance
         */
        public void windowDeiconified(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowRestore(ac);
            }
        }

        /**
         *  windowIconified:
         *                  Invoked when a window is iconified.
         * @param e A WindowEvent instance
         */
        public void windowIconified(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowMinimize(ac);
            }
        }

        /**
         *  windowOpened
         * @param e A WindowEvent object
         */
        public void windowOpened(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                boolean isToplevel = isToplevel(o);
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowOpen(ac, isToplevel);
            }
        }

        /**
         * windowClosed:
         *              Invoked when a window has been closed.
         * @param e A WindowEvent object
         */
        public void windowClosed(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                boolean isToplevel = isToplevel(o);
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowClose(ac, isToplevel);
            }
        }

        /**
         * windowClosing:
         *              Invoked when a window is in the process of being closed.
         * @param e A WindowEvent object
         */
        public void windowClosing(WindowEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                boolean isToplevel = isToplevel(o);
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.windowClose(ac, isToplevel);
            }
        }

        /**
         * windowGainedFocus:
         *                   Invoked when the Window is set to be the focused Window,
         *                   which means that the Window, or one of its subcomponents,
         *                   will receive keyboard events.
         * @param e A WindowEvent object
         */
        public void windowGainedFocus(WindowEvent e) {
        }

        public void windowLostFocus(WindowEvent e) {
        }
    }; // Close WindowAdapter brace


    private final ComponentAdapter componentAdapter = new ComponentAdapter() {

        /**
         *  componentResized
         *   *             Invoked when the component's size changes and emits
         *                  a "bounds-changed" signal.
         * @param e a ComponentEvent object
         */
        public void componentResized(ComponentEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.boundsChanged(ac);
            }
        }

        /**
         * componentMoved:
         *                Invoked when the component's position changes and emits
         *                a "bounds-changed" signal.
         *
         * @param e a ComponentEvent object
         */
        public void componentMoved(ComponentEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.boundsChanged(ac);
            }
        }

        /**
         *  componentShown
         * @param e A ComponentEvent object
         */
        public void componentShown(ComponentEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.componentAdded(ac);
            }
        }

        /**
         * componentHidden:
         *                 Invoked when the component has been made invisible.
         * @param e A ComponentEvent object
         */
        public void componentHidden(ComponentEvent e) {
            Object o = e.getSource();
            if (o instanceof Accessible accessible) {
                AccessibleContext ac = accessible.getAccessibleContext();
                AtkWrapperDisposer.addRecord(ac);
                AtkWrapper.componentRemoved(ac);
            }
        }
    };


    /**
     * isToplevel
     *
     * @param o an instance
     * @return true if instance is of a window, frame or dialogue object
     * false otherwise.
     */
    private static boolean isToplevel(Object o) {
        boolean isToplevel = false;
        if (o instanceof java.awt.Window ||
                o instanceof java.awt.Frame ||
                o instanceof java.awt.Dialog) {
            isToplevel = true;
        }
        return isToplevel;
    }

    private final AWTEventListener globalListener = new AWTEventListener() {
        private boolean firstEvent = true;

        public void eventDispatched(AWTEvent e) {
            if (e instanceof WindowEvent windowEvent) {
                switch (e.getID()) {
                    case WindowEvent.WINDOW_OPENED:
                        Window win = windowEvent.getWindow();
                        win.addWindowListener(winAdapter);
                        win.addWindowStateListener(winAdapter);
                        win.addWindowFocusListener(winAdapter);
                        break;
                    case WindowEvent.WINDOW_LOST_FOCUS:
                        AtkWrapper.dispatchFocusEvent(null);
                        break;
                    default:
                        break;
                }
            } else if (e instanceof ContainerEvent containerEvent) {
                switch (e.getID()) {
                    case ContainerEvent.COMPONENT_ADDED: {
                        Component c = containerEvent.getChild();
                        c.addComponentListener(componentAdapter);
                        break;
                    }
                    case ContainerEvent.COMPONENT_REMOVED: {
                        Component c = containerEvent.getChild();
                        c.removeComponentListener(componentAdapter);
                        break;
                    }

                    default:
                        break;
                }
            } else if (e instanceof FocusEvent) {
                switch (e.getID()) {
                    case FocusEvent.FOCUS_GAINED:
                        AtkWrapper.dispatchFocusEvent(e.getSource());
                        break;
                    default:
                        break;
                }
            }
        }
    };

    private static AccessibleContext oldSourceContext = null;
    private static AccessibleContext savedSourceContext = null;
    private static AccessibleContext oldPaneContext = null;

    /**
     * dispatchFocusEvent
     *
     * @param eventSource An instance of the event source object.
     */
    private static void dispatchFocusEvent(Object eventSource) {
        if (eventSource == null) {
            oldSourceContext = null;
            return;
        }

        AccessibleContext ctx;

        try {
            if (eventSource instanceof AccessibleContext accessibleContext) {
                ctx = accessibleContext;
            } else if (eventSource instanceof Accessible accessible) {
                ctx = accessible.getAccessibleContext();
            } else {
                return;
            }

            if (ctx == oldSourceContext) {
                return;
            }

            if (oldSourceContext != null) {
                AccessibleRole role = oldSourceContext.getAccessibleRole();
                if (role == AccessibleRole.MENU || role == AccessibleRole.MENU_ITEM) {
                    String jrootpane = "javax.swing.JRootPane$AccessibleJRootPane";
                    String name = ctx.getClass().getName();

                    if (jrootpane.compareTo(name) == 0) {
                        oldPaneContext = ctx;
                        return;
                    }
                }
                savedSourceContext = ctx;
            } else if (oldPaneContext == ctx) {
                ctx = savedSourceContext;
            } else {
                savedSourceContext = ctx;
            }

            oldSourceContext = ctx;
            AccessibleRole role = ctx.getAccessibleRole();
            if (role == AccessibleRole.PAGE_TAB_LIST) {
                AccessibleSelection accSelection = ctx.getAccessibleSelection();

                if (accSelection != null && accSelection.getAccessibleSelectionCount() > 0) {
                    Object child = accSelection.getAccessibleSelection(0);
                    if (child instanceof AccessibleContext accessibleContext) {
                        ctx = accessibleContext;
                    } else if (child instanceof Accessible accessible) {
                        ctx = accessible.getAccessibleContext();
                    } else {
                        return;
                    }
                }
            }
            AtkWrapperDisposer.addRecord(ctx);
            focusNotify(ctx);
        } catch (Exception e) {
            if (log.isLoggable(PlatformLogger.Level.SEVERE)) {
                log.severe("Error in dispatchFocusEvent: ", e);
            }
        }
    }

    private final Toolkit toolkit = Toolkit.getDefaultToolkit();

    private static PropertyChangeListener propertyChangeListener = new PropertyChangeListener() {

        /**
         * propertyChange:
         * @param e An instance of the PropertyChangeEvent object.
         */
        public void propertyChange(PropertyChangeEvent e) {
            Object o = e.getSource();
            AccessibleContext ac;
            if (o instanceof AccessibleContext accessibleContext) {
                ac = accessibleContext;
            } else if (o instanceof Accessible accessible) {
                ac = accessible.getAccessibleContext();
            } else {
                return;
            }

            Object oldValue = e.getOldValue();
            Object newValue = e.getNewValue();
            String propertyName = e.getPropertyName();

            AtkWrapperDisposer.addRecord(ac);

            if (propertyName.equals(AccessibleContext.ACCESSIBLE_CARET_PROPERTY)) {
                Object[] args = new Object[1];
                args[0] = newValue;
                emitSignal(ac, AtkSignal.TEXT_CARET_MOVED, args);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY)) {
                /**
                 * The documentation of AccessibleContext.ACCESSIBLE_TEXT_PROPERTY states that newValue
                 * is an instance of AccessibleTextSequence in an insertion event, and oldValue is an
                 * instance of AccessibleTextSequence in a deletion event. However, Swing components
                 * send a signal where oldValue == null and newValue is an instance of Integer for both
                 * insertion and deletion events.
                 */
                if (oldValue == null && newValue != null) {
                    if (newValue instanceof AccessibleTextSequence) {
                        AccessibleTextSequence newSeq = (AccessibleTextSequence) newValue;
                        Object[] args = new Object[3];
                        args[0] = Integer.valueOf(newSeq.startIndex);
                        args[1] = Integer.valueOf(newSeq.endIndex - newSeq.startIndex);
                        args[2] = newSeq.text;
                        emitSignal(ac, AtkSignal.TEXT_PROPERTY_CHANGED_INSERT, args); // insertion event
                        return;
                    } else if (newValue instanceof Integer) {
                        Object[] args = new Object[1];
                        args[0] = newValue;
                        emitSignal(ac, AtkSignal.TEXT_PROPERTY_CHANGED, args); // insertion or deletion event
                    }
                } else if (oldValue != null && newValue == null) {
                    if (oldValue instanceof AccessibleTextSequence) {
                        AccessibleTextSequence oldSeq = (AccessibleTextSequence) oldValue;
                        Object[] args = new Object[3];
                        args[0] = Integer.valueOf(oldSeq.startIndex);
                        args[1] = Integer.valueOf(oldSeq.endIndex - oldSeq.startIndex);
                        args[2] = oldSeq.text;
                        emitSignal(ac, AtkSignal.TEXT_PROPERTY_CHANGED_DELETE, args); // deletion event
                    }
                }
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_CHILD_PROPERTY)) {
                if (oldValue == null && newValue != null) { //child added
                    AccessibleContext child_ac;
                    if (newValue instanceof Accessible accessible) {
                        child_ac = accessible.getAccessibleContext();
                    } else {
                        return;
                    }
                    Object[] args = new Object[2];
                    args[0] = Integer.valueOf(child_ac.getAccessibleIndexInParent());
                    args[1] = child_ac;
                    emitSignal(ac, AtkSignal.OBJECT_CHILDREN_CHANGED_ADD, args);
                } else if (oldValue != null && newValue == null) { //child removed
                    AccessibleContext child_ac;
                    if (oldValue instanceof Accessible accessible) {
                        child_ac = accessible.getAccessibleContext();
                    } else {
                        return;
                    }
                    Object[] args = new Object[2];
                    args[0] = Integer.valueOf(child_ac.getAccessibleIndexInParent());
                    args[1] = child_ac;
                    emitSignal(ac, AtkSignal.OBJECT_CHILDREN_CHANGED_REMOVE, args);
                }
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY)) {
                AccessibleContext child_ac;
                if (newValue instanceof Accessible accessible) {
                    child_ac = accessible.getAccessibleContext();
                } else {
                    return;
                }

                /*
                 * JComboBox sends two signals: object:active-descendant-changed and object:selection-changed.
                 * Information about the component is announced when processing the object:selection-changed signal.
                 * The object:active-descendant-changed signal interrupts this announcement in earlier versions of GNOME.
                 * Skipping the emission of object:active-descendant-changed signal resolves this issue.
                 * See: https://gitlab.gnome.org/GNOME/java-atk-wrapper/-/issues/29
                 */
                if (ac.getAccessibleRole() == AccessibleRole.COMBO_BOX) {
                    return;
                }
                Object[] args = new Object[1];
                args[0] = child_ac;
                emitSignal(ac, AtkSignal.OBJECT_ACTIVE_DESCENDANT_CHANGED, args);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY)) {
                boolean isTextEvent = false;
                AccessibleRole role = ac.getAccessibleRole();
                if ((role == AccessibleRole.TEXT) ||
                        role.toDisplayString(java.util.Locale.US).equalsIgnoreCase("paragraph")) {
                    isTextEvent = true;
                } else if (role == AccessibleRole.MENU_BAR) {
                    dispatchFocusEvent(o);
                } else if (role == AccessibleRole.PAGE_TAB_LIST) {
                    AccessibleSelection selection = ac.getAccessibleSelection();
                    if (selection != null &&
                            selection.getAccessibleSelectionCount() > 0) {
                        java.lang.Object child = selection.getAccessibleSelection(0);
                        dispatchFocusEvent(child);
                    }
                }

                /*
                 * When navigating through nodes, JTree sends two signals: object:selection-changed and object:active-descendant-changed.
                 * The object:selection-changed signal is emitted before object:active-descendant-changed, leading to the following issues:
                 * 1. When focusing on the root of the tree, object:active-descendant-changed interrupts the announcement and does not
                 *    provide any output because the FOCUS MANAGER doesn't update the focus - it is already set to the same object.
                 * 2. For other nodes, the correct behavior happens because of the bug in JTree:
                 *    AccessibleJTree incorrectly reports the selected children and object:selection-changed sets focus on the tree.
                 *
                 * Removing the object:selection-changed signal ensures that the locus of focus is updated during object:active-descendant-changed,
                 * allowing elements to be announced correctly.
                 * See: https://gitlab.gnome.org/GNOME/orca/-/issues/552
                 */
                if (ac.getAccessibleRole() == AccessibleRole.TREE) {
                    return;
                }

                if (!isTextEvent) {
                    emitSignal(ac, AtkSignal.OBJECT_SELECTION_CHANGED, null);
                }

            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY)) {
                emitSignal(ac, AtkSignal.OBJECT_VISIBLE_DATA_CHANGED, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_ACTION_PROPERTY)) {
                Object[] args = new Object[2];
                args[0] = oldValue;
                args[1] = newValue;
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_ACTIONS, args);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_VALUE_PROPERTY)) {
                if (oldValue instanceof Number oldValueNamber && newValue instanceof Number newValueNumber) {
                    Object[] args = new Object[2];
                    args[0] = Double.valueOf(oldValueNamber.doubleValue());
                    args[1] = Double.valueOf(newValueNumber.doubleValue());
                    emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_VALUE, args);
                }
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_DESCRIPTION, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_NAME_PROPERTY)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_NAME, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_HYPERTEXT_OFFSET)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_HYPERTEXT_OFFSET, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_MODEL_CHANGED)) {
                emitSignal(ac, AtkSignal.TABLE_MODEL_CHANGED, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_CAPTION_CHANGED)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_CAPTION, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_SUMMARY_CHANGED)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_SUMMARY, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_COLUMN_HEADER_CHANGED)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_HEADER, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_COLUMN_DESCRIPTION_CHANGED)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_DESCRIPTION, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_ROW_HEADER_CHANGED)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_HEADER, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_TABLE_ROW_DESCRIPTION_CHANGED)) {
                emitSignal(ac, AtkSignal.OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_DESCRIPTION, null);
            } else if (propertyName.equals(AccessibleContext.ACCESSIBLE_STATE_PROPERTY)) {
                Accessible parent = ac.getAccessibleParent();
                AccessibleRole role = ac.getAccessibleRole();
                if (role != null) {
                    if (newValue == AccessibleState.FOCUSED ||
                            newValue == AccessibleState.SELECTED) {
                        dispatchFocusEvent(o);
                    }
                }
                AccessibleState state;
                boolean value = false;
                if (newValue != null) {
                    state = (AccessibleState) newValue;
                    value = true;
                } else {
                    state = (AccessibleState) oldValue;
                    value = false;
                }
                if (state == AccessibleState.COLLAPSED) {
                    state = AccessibleState.EXPANDED;
                    value = false;
                }
                if (parent instanceof JComboBox && oldValue ==
                        AccessibleState.VISIBLE) {
                    objectStateChange(ac, AccessibleState.SHOWING, value);
                }
                objectStateChange(ac, state, value);
                if (parent instanceof JComboBox && newValue ==
                        AccessibleState.VISIBLE && oldValue == null) {
                    objectStateChange(ac, AccessibleState.SHOWING, value);
                }
            }
        }
    };

    private static void registerPropertyChangeListener(AccessibleContext ac) {
        if (ac != null) {
            AtkUtil.invokeInSwing(() -> {
                ac.addPropertyChangeListener(propertyChangeListener);
            });
        }
    }

    private native static boolean initNativeLibrary();

    private native static boolean loadAtkBridge();

    native static long createNativeResources(AccessibleContext ac);

    native static void releaseNativeResources(long ref);

    private native static void focusNotify(AccessibleContext ac);

    private native static void windowOpen(AccessibleContext ac,
                                         boolean isToplevel);

    private native static void windowClose(AccessibleContext ac,
                                          boolean isToplevel);

    private native static void windowMinimize(AccessibleContext ac);

    private native static void windowMaximize(AccessibleContext ac);

    private native static void windowRestore(AccessibleContext ac);

    private native static void windowActivate(AccessibleContext ac);

    private native static void windowDeactivate(AccessibleContext ac);

    private native static void windowStateChange(AccessibleContext ac);

    private native static void emitSignal(AccessibleContext ac, int id, Object[] args);

    private native static void objectStateChange(AccessibleContext ac,
                                                Object state, boolean value);

    private native static void componentAdded(AccessibleContext ac);

    private native static void componentRemoved(AccessibleContext ac);

    private native static void boundsChanged(AccessibleContext ac);

    private native static void dispatchKeyEvent(AtkKeyEvent e);

    private native static long getInstance(AccessibleContext ac);

    // FIXME: no usages
    private static void printLog(String msg) {
        if (log.isLoggable(PlatformLogger.Level.INFO)) {
            log.info(msg);
        }
    }

    /** AtkWrapper is service provider that adds an assistive technology feature
     * using assistive_technologies property, so constructor is public
     */
    public AtkWrapper() {
        if (!accessibilityEnabled) {
            throw new IllegalStateException("AtkWrapper not initialized due to disabled accessibility.");
        }

        toolkit.addAWTEventListener(globalListener,
                AWTEvent.WINDOW_EVENT_MASK |
                        AWTEvent.FOCUS_EVENT_MASK |
                        AWTEvent.CONTAINER_EVENT_MASK);
        if (toolkit.getSystemEventQueue() != null) {
            // TODO: Remove the replacement of the existing EventQueue
            toolkit.getSystemEventQueue().push(new EventQueue() {
                public void dispatchEvent(AWTEvent e) {
                    if (e instanceof KeyEvent keyEvent) {
                        AtkWrapper.dispatchKeyEvent(new AtkKeyEvent((KeyEvent) e));
                    }
                    super.dispatchEvent(e);
                }
            });
        }
    }

    public static void main(String args[]) {
        new AtkWrapper();
    }

    // JNI upcalls section

    private static long get_instance_from_swing(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return getInstance(ac);
        }, 0l);
    }

    /**
     * returns -1 if it was not difined
     * @param ac
     * @return
     */
    private static long get_native_resources(AccessibleContext ac) {
        return AtkWrapperDisposer.getRecord(ac);
    }
}
