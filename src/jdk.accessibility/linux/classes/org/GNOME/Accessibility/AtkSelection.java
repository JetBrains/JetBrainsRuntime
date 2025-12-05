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

public class AtkSelection {

    private WeakReference<AccessibleContext> accessibleContextWeakRef;
    private WeakReference<AccessibleSelection> accessibleSelectionWeakRef;

    private AtkSelection(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleSelectionWeakRef = new WeakReference<AccessibleSelection>(ac.getAccessibleSelection());
    }

    // JNI upcalls section

    private static AtkSelection create_atk_selection(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkSelection(ac);
        }, null);
    }

    private boolean add_selection(int i) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            accessibleSelection.addAccessibleSelection(i);
            return is_child_selected(i);
        }, false);
    }

    private boolean clear_selection() {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleSelection.clearAccessibleSelection();
        });
        return true;
    }

    private AccessibleContext ref_selection(int i) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible selection = accessibleSelection.getAccessibleSelection(i);
            if (selection == null) {
                return null;
            }
            AccessibleContext accessibleContext = selection.getAccessibleContext();
            AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
            return accessibleContext;
        }, null);
    }

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
                if (accessibleSelection.isAccessibleChildSelected(i))
                    count++;
            }
            return count;
        }, 0);
        //A bug in AccessibleJMenu??
        //return acc_selection.getAccessibleSelectionCount();
    }

    private boolean is_child_selected(int i) {
        AccessibleSelection accessibleSelection = accessibleSelectionWeakRef.get();
        if (accessibleSelection == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleSelection.isAccessibleChildSelected(i);
        }, false);
    }

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
