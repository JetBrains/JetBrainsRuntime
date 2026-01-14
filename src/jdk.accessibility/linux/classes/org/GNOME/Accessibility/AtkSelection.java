/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
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

import javax.accessibility.*;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Selection interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleSelection interface
 * and the ATK (Accessibility Toolkit) selection interface.
 */
public class AtkSelection {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleSelection> accessibleSelectionWeakRef;

    private AtkSelection(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        AccessibleSelection accessibleSelection = ac.getAccessibleSelection();
        if (accessibleSelection == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleSelection");
        }

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleSelectionWeakRef = new WeakReference<AccessibleSelection>(accessibleSelection);
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkSelection instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkSelection instance, or null if creation fails
     */
    private static AtkSelection create_atk_selection(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkSelection(ac);
        }, null);
    }

    /**
     * Adds the specified accessible child to the object's selection.
     * Called from native code via JNI.
     *
     * @param index the index of the child in the object's list of children
     * @return true if the child was successfully selected, false otherwise
     */
    private boolean add_selection(int index) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            accessibleSelection.addAccessibleSelection(index);
            return is_child_selected(index);
        }, false);
    }

    /**
     * Clears the selection in the object so that no children in the object are selected.
     * Called from native code via JNI.
     *
     * @return true if the selection was successfully cleared, false otherwise
     */
    private boolean clear_selection() {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            accessibleSelection.clearAccessibleSelection();
            return true;
        }, false);
    }

    /**
     * Gets a reference to the accessible object representing the specified selected child of the object.
     * Called from native code via JNI.
     *
     * @param index the index of the selected child in the selection
     * @return the AccessibleContext of the selected child, or null if none
     */
    private AccessibleContext ref_selection(int index) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible selectedChild = accessibleSelection.getAccessibleSelection(index);
            if (selectedChild == null) {
                return null;
            }
            AccessibleContext selectedChildAccessibleContext = selectedChild.getAccessibleContext();
            if (selectedChildAccessibleContext != null) {
                AtkWrapperDisposer.getInstance().addRecord(selectedChildAccessibleContext);
            }
            return selectedChildAccessibleContext;
        }, null);
    }

    /**
     * Gets the number of accessible children currently selected.
     * Called from native code via JNI.
     *
     * @return the number of currently selected children, or 0 if none are selected
     */
    private int get_selection_count() {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return 0;
        }
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int count = 0;
            for (int i = 0; i < ac.getAccessibleChildrenCount(); i++) {
                if (accessibleSelection.isAccessibleChildSelected(i)) {
                    count++;
                }
            }
            return count;
        }, 0);
    }

    /**
     * Determines if the specified child of the object is included in the object's selection.
     * Called from native code via JNI.
     *
     * @param i the index of the child in the object's list of children
     * @return true if the child is selected, false otherwise
     */
    private boolean is_child_selected(int i) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleSelection.isAccessibleChildSelected(i);
        }, false);
    }

    /**
     * Removes the specified child of the object from the object's selection.
     * Called from native code via JNI.
     *
     * @param i the index of the selected child in the selection
     * @return true if the child was successfully removed from the selection, false otherwise
     */
    private boolean remove_selection(int i) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            accessibleSelection.removeAccessibleSelection(i);
            return !is_child_selected(i);
        }, false);
    }

    /**
     * Causes every child of the object to be selected if the object supports multiple selection.
     * Called from native code via JNI.
     *
     * @return true if all children were successfully selected (object supports multiple selection), false otherwise
     */
    private boolean select_all_selection() {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return false;
        }
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleStateSet stateSet = accessibleContext.getAccessibleStateSet();
            if (stateSet.contains(AccessibleState.MULTISELECTABLE)) {
                accessibleSelection.selectAllAccessibleSelection();
                return true;
            }
            return false;
        }, false);
    }
}
