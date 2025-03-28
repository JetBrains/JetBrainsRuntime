/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
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

package sun.lwawt.macosx;

import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.accessibility.*;
import javax.swing.*;

import static javax.accessibility.AccessibleContext.ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_CARET_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_STATE_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_TABLE_MODEL_CHANGED;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_NAME_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_VALUE_PROPERTY;

import sun.awt.AWTAccessor;


final class CAccessible extends CFRetainedResource implements Accessible {
    private static Timer timer = null;
    private final static int SELECTED_CHILDREN_MILLISECONDS_DEFAULT = 100;
    private static int SELECTED_CHILDREN_MILLISECONDS;

    static {
        @SuppressWarnings("removal") int scms = java.security.AccessController.doPrivileged(new PrivilegedAction<Integer>() {
            @Override
            public Integer run() {
                return Integer.getInteger("sun.lwawt.macosx.CAccessible.selectedChildrenMilliSeconds",
                        SELECTED_CHILDREN_MILLISECONDS_DEFAULT);
            }
        });
        SELECTED_CHILDREN_MILLISECONDS = scms >= 0 ? scms : SELECTED_CHILDREN_MILLISECONDS_DEFAULT;

        EVENTS_COALESCING_ENABLED = Boolean.parseBoolean(
            System.getProperty("sun.lwawt.macosx.CAccessible.eventsCoalescingEnabled", "true")
        );
    }

    public static CAccessible getCAccessible(final Accessible a) {
        if (a == null) return null;
        AccessibleContext context = a.getAccessibleContext();
        AWTAccessor.AccessibleContextAccessor accessor
                = AWTAccessor.getAccessibleContextAccessor();
        final CAccessible cachedCAX = (CAccessible) accessor.getNativeAXResource(context);
        if (cachedCAX != null) {
            return cachedCAX;
        }
        final CAccessible newCAX = new CAccessible(a);
        accessor.setNativeAXResource(context, newCAX);
        return newCAX;
    }

    private static native void unregisterFromCocoaAXSystem(long ptr);
    private static native void valueChanged(long ptr);
    private static native void selectedTextChanged(long ptr);
    private static native void selectionChanged(long ptr);
    private static native void titleChanged(long ptr);
    private static native void menuOpened(long ptr);
    private static native void menuClosed(long ptr);
    private static native void menuItemSelected(long ptr);
    // JBR-7659: don't use this method directly; use {@link #postCoalescedTreeNodeExpanded()} instead
    private native void treeNodeExpanded(long ptr);
    // JBR-7659: don't use this method directly; use {@link #postCoalescedTreeNodeCollapsed()} instead
    private native void treeNodeCollapsed(long ptr);
    private static native void selectedCellsChanged(long ptr);
    private static native void tableContentCacheClear(long ptr);
    private static native void updateZoomCaretFocus(long ptr);
    private static native void updateZoomFocus(long ptr);

    private Accessible accessible;

    private AccessibleContext activeDescendant;

    private CAccessible(final Accessible accessible) {
        super(0L, true); // real pointer will be poked in by native

        if (accessible == null) throw new NullPointerException();
        this.accessible = accessible;

        if (accessible instanceof Component) {
            addNotificationListeners((Component)accessible);
        }
    }

    @Override
    protected synchronized void dispose() {
        if (ptr != 0) unregisterFromCocoaAXSystem(ptr);
        super.dispose();
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        return accessible != null ? accessible.getAccessibleContext() : null;
    }

    public void addNotificationListeners(Component c) {
        if (c instanceof Accessible) {
            AccessibleContext ac = ((Accessible)c).getAccessibleContext();
            ac.addPropertyChangeListener(new AXChangeNotifier());
        }
    }


    // =================================================== JBR-7659 ===================================================

    // Since macOS 15 applications running a lot of nested CFRunLoop s has been crashing.
    // In AWT case, a new nested CFRunLoop is run whenever macOS asks some a11y info about a UI component.
    // macOS usually does that when AWT notifies it about changes in UI components.
    // So, if there happen N changes of UI components in a row, the AppKit thread may have N nested CFRunLoops.
    // If N is too high (>= ~700), the application will crash.
    // In JBR-7659 the problem is observed with UI tree expansion/collapsing events, but AFAIU in general it may happen
    //   with any kind of UI change events.
    // As for now we're covering only those 2 kinds of events to make sure if the solution is effective enough.
    // The proposed solution is to make sure there is not more than one event of each kind in the AppKit queue
    //   for the same UI component (i.e. for the same CAccessible).

    /** Is a way to disable the fix */
    private static final boolean EVENTS_COALESCING_ENABLED;

    /**
     * The variables indicate whether there's an "event" posted by
     *   {@link #treeNodeExpanded}/{@link #treeNodeCollapsed(long)} onto the AppKit thread, but not processed by it yet.
     */
    private final AtomicBoolean isProcessingTreeNodeExpandedEvent = new AtomicBoolean(false),
                                isProcessingTreeNodeCollapsedEvent = new AtomicBoolean(false);
    /**
     * The variables indicate whether there was an attempt to post another
     *   {@link #treeNodeExpanded}/{@link #treeNodeCollapsed(long)} while there was already one
     *   on the AppKit thread (no matter if it's still in the queue or is being processed).
     * It's necessary to make sure there won't be unobserved changes of the component.
     */
    private final AtomicBoolean hasDelayedTreeNodeExpandedEvent = new AtomicBoolean(false),
                                hasDelayedTreeNodeCollapsedEvent = new AtomicBoolean(false);

    private void postCoalescedTreeNodeExpanded() {
        postCoalescedEventImpl(
            isProcessingTreeNodeExpandedEvent,
            hasDelayedTreeNodeExpandedEvent,
            this::treeNodeExpanded,
            this
        );
    }

    private void postCoalescedTreeNodeCollapsed() {
        postCoalescedEventImpl(
            isProcessingTreeNodeCollapsedEvent,
            hasDelayedTreeNodeCollapsedEvent,
            this::treeNodeCollapsed,
            this
        );
    }

    private static void postCoalescedEventImpl(
        final AtomicBoolean isProcessingEventFlag,
        final AtomicBoolean hasDelayedEventFlag,
        final CFNativeAction eventPostingAction,
        // a reference to this is passed instead of making the method non-static to make sure the implementation
        //   doesn't accidentally touch anything of the instance by mistake
        final CAccessible self
    ) {
        if (!EVENTS_COALESCING_ENABLED) {
            self.execute(eventPostingAction);
            return;
        }

        assert EventQueue.isDispatchThread();

        final var result = self.executeGet(ptr -> {
            if (isProcessingEventFlag.compareAndSet(false, true)) {
                hasDelayedEventFlag.set(false);

                try {
                    eventPostingAction.run(ptr);
                } catch (Exception err) {
                    isProcessingEventFlag.set(false);
                    throw err;
                }
            } else {
                hasDelayedEventFlag.set(true);
            }
            return 1;
        });

        if (result != 1) {
            // the routine hasn't been executed because there was no native resource (i.e. {@link #ptr} was 0)
            isProcessingEventFlag.set(false);
            hasDelayedEventFlag.set(false);
        }
    }

    // Called from native
    private void onProcessedTreeNodeExpandedEvent() {
        onProcessedEventImpl(
            isProcessingTreeNodeExpandedEvent,
            hasDelayedTreeNodeExpandedEvent,
            this::postCoalescedTreeNodeExpanded
        );
    }

    // Called from native
    private void onProcessedTreeNodeCollapsedEvent() {
        onProcessedEventImpl(
            isProcessingTreeNodeCollapsedEvent,
            hasDelayedTreeNodeCollapsedEvent,
            this::postCoalescedTreeNodeCollapsed
        );
    }

    private static void onProcessedEventImpl(
        final AtomicBoolean isProcessingEventFlag,
        final AtomicBoolean hasDelayedEventFlag,
        final Runnable postingCoalescedEventRoutine
    ) {
        if (!EVENTS_COALESCING_ENABLED) {
            return;
        }

        isProcessingEventFlag.set(false);

        if (hasDelayedEventFlag.compareAndSet(true, false)) {
            // We shouldn't call postCoalesced<...> synchronously from here to allow the current CFRunLoop
            //   to finish, thus reducing the current number of nested CFRunLoop s.
            EventQueue.invokeLater(postingCoalescedEventRoutine);
        }
    }

    // =============================================== END of JBR-7659 ================================================


    private final class AXChangeNotifier implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent e) {
            assert EventQueue.isDispatchThread();
            String name = e.getPropertyName();
            if ( ptr != 0 ) {
                Object newValue = e.getNewValue();
                Object oldValue = e.getOldValue();
                if (name.equals(ACCESSIBLE_CARET_PROPERTY)) {
                    execute(ptr -> selectedTextChanged(ptr));
                    // Notify macOS Accessibility Zoom to move focus to the new caret location.
                    execute(ptr -> updateZoomCaretFocus(ptr));
                } else if (name.equals(ACCESSIBLE_TEXT_PROPERTY)) {
                    AccessibleContext thisAC = accessible.getAccessibleContext();
                    Accessible parentAccessible = thisAC.getAccessibleParent();
                    if (!(parentAccessible instanceof JSpinner.NumberEditor)) {
                        execute(ptr -> valueChanged(ptr));
                    }
                } else if (name.equals(ACCESSIBLE_SELECTION_PROPERTY)) {
                    if (timer != null) {
                        timer.stop();
                    }
                    timer = new Timer(SELECTED_CHILDREN_MILLISECONDS, actionEvent -> execute(ptr -> selectionChanged(ptr)));
                    timer.setRepeats(false);
                    timer.start();

                    // Notify the Accessibility Zoom to follow the new selection location.
                    execute(ptr -> updateZoomFocus(ptr));
                } else if (name.equals(ACCESSIBLE_TABLE_MODEL_CHANGED)) {
                    execute(ptr -> valueChanged(ptr));
                    if (CAccessible.getSwingAccessible(CAccessible.this) != null) {
                        Accessible a = CAccessible.getSwingAccessible(CAccessible.this);
                        AccessibleContext ac = a.getAccessibleContext();
                        if ((ac != null) && (ac.getAccessibleRole() == AccessibleRole.TABLE)) {
                            execute(ptr -> tableContentCacheClear(ptr));
                        }
                    }
                } else if (name.equals(ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY)) {
                    if (newValue == null || newValue instanceof AccessibleContext) {
                        activeDescendant = (AccessibleContext)newValue;
                        if (newValue instanceof Accessible) {
                            Accessible a = (Accessible)newValue;
                            AccessibleContext ac = a.getAccessibleContext();
                            if (ac !=  null) {
                                Accessible p = ac.getAccessibleParent();
                                if (p != null) {
                                    AccessibleContext pac = p.getAccessibleContext();
                                    if ((pac != null) && (pac.getAccessibleRole() == AccessibleRole.TABLE)) {
                                        execute(ptr -> selectedCellsChanged(ptr));
                                    }
                                }
                            }
                        }
                    }
                } else if (name.equals(ACCESSIBLE_STATE_PROPERTY)) {
                    AccessibleContext thisAC = accessible.getAccessibleContext();
                    AccessibleRole thisRole = thisAC.getAccessibleRole();
                    Accessible parentAccessible = thisAC.getAccessibleParent();
                    AccessibleRole parentRole = null;
                    if (parentAccessible != null) {
                        parentRole = parentAccessible.getAccessibleContext().getAccessibleRole();
                    }

                    if (newValue == AccessibleState.EXPANDED) {
                        if (EVENTS_COALESCING_ENABLED) {
                            postCoalescedTreeNodeExpanded();
                        } else {
                            execute(ptr -> treeNodeExpanded(ptr));
                        }
                    } else if (newValue == AccessibleState.COLLAPSED) {
                        if (EVENTS_COALESCING_ENABLED) {
                            postCoalescedTreeNodeCollapsed();
                        } else {
                            execute(ptr -> treeNodeCollapsed(ptr));
                        }
                    }

                    if (thisRole == AccessibleRole.COMBO_BOX) {
                        AccessibleStateSet as = thisAC.getAccessibleStateSet();
                        if (as != null)
                            if (as.contains(AccessibleState.EXPANDED)) {
                                if (timer != null) {
                                    timer.stop();
                                }
                                timer = new Timer(SELECTED_CHILDREN_MILLISECONDS, actionEvent -> execute(ptr -> selectionChanged(ptr)));
                                timer.setRepeats(false);
                                timer.start();
                                if (as.contains(AccessibleState.COLLAPSED)) {
                                    execute(ptr -> valueChanged(ptr));
                                }
                            }
                    }

                    if (thisRole == AccessibleRole.POPUP_MENU) {
                        if (newValue != null &&
                                ((AccessibleState) newValue) == AccessibleState.VISIBLE) {
                            execute(ptr -> menuOpened(ptr));
                        } else if (oldValue != null &&
                                ((AccessibleState) oldValue) == AccessibleState.VISIBLE) {
                            execute(ptr -> menuClosed(ptr));
                            execute(ptr -> unregisterFromCocoaAXSystem(ptr));
                        }
                    } else if (thisRole == AccessibleRole.MENU_ITEM ||
                            (thisRole == AccessibleRole.MENU)) {
                        if (newValue != null &&
                                ((AccessibleState) newValue) == AccessibleState.FOCUSED) {
                            execute(ptr -> menuItemSelected(ptr));
                        }
                    }

                    // Do send check box state changes to native side
                    if (thisRole == AccessibleRole.CHECK_BOX) {
                        if (!Objects.equals(newValue, oldValue)) {
                            execute(ptr -> valueChanged(ptr));
                        }

                        // Notify native side to handle check box style menuitem
                        if (parentRole == AccessibleRole.POPUP_MENU && newValue != null
                                && ((AccessibleState)newValue) == AccessibleState.FOCUSED) {
                            menuItemSelected(ptr);
                        }
                    }

                    // Do send radio button state changes to native side
                    if (thisRole == AccessibleRole.RADIO_BUTTON) {
                        if (newValue != null && !newValue.equals(oldValue)) {
                            valueChanged(ptr);
                        }

                        // Notify native side to handle radio button style menuitem
                        if (parentRole == AccessibleRole.POPUP_MENU && newValue != null
                            && ((AccessibleState)newValue) == AccessibleState.FOCUSED) {
                            menuItemSelected(ptr);
                        }
                    }

                    // Do send toggle button state changes to native side
                    if (thisRole == AccessibleRole.TOGGLE_BUTTON) {
                        if (!Objects.equals(newValue, oldValue)) {
                            valueChanged(ptr);
                        }
                    }
                } else if (name.equals(ACCESSIBLE_NAME_PROPERTY)) {
                    //for now trigger only for JTabbedPane.
                    if (e.getSource() instanceof JTabbedPane) {
                        execute(ptr -> titleChanged(ptr));
                    }
                } else if (name.equals(ACCESSIBLE_VALUE_PROPERTY)) {
                    AccessibleRole thisRole = accessible.getAccessibleContext()
                                                        .getAccessibleRole();
                    if (thisRole == AccessibleRole.SLIDER ||
                            thisRole == AccessibleRole.PROGRESS_BAR ||
                            thisRole == AccessibleRole.SCROLL_BAR) {
                        execute(ptr -> valueChanged(ptr));
                    }
                }
            }
        }
    }

    static Accessible getSwingAccessible(final Accessible a) {
        return (a instanceof CAccessible) ? ((CAccessible)a).accessible : a;
    }

    static AccessibleContext getActiveDescendant(final Accessible a) {
        return (a instanceof CAccessible) ? ((CAccessible)a).activeDescendant : null;
    }

}
