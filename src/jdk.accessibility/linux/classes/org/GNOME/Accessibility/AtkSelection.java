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

    private WeakReference<AccessibleContext> _ac;
    private WeakReference<AccessibleSelection> _acc_selection;

    private AtkSelection(AccessibleContext ac) {
        super();

        assert EventQueue.isDispatchThread();

        this._ac = new WeakReference<AccessibleContext>(ac);
        this._acc_selection = new WeakReference<AccessibleSelection>(ac.getAccessibleSelection());
    }

    // JNI upcalls section

    private static AtkSelection create_atk_selection(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkSelection(ac);
        }, null);
    }

    private boolean add_selection(int i) {
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return false;

        return AtkUtil.invokeInSwingAndWait(() -> {
            acc_selection.addAccessibleSelection(i);
            return is_child_selected(i);
        }, false);
    }

    private boolean clear_selection() {
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return false;

        AtkUtil.invokeInSwing(() -> {
            acc_selection.clearAccessibleSelection();
        });
        return true;
    }

    private AccessibleContext ref_selection(int i) {
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return null;

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible sel = acc_selection.getAccessibleSelection(i);
            if (sel == null)
                return null;
            return sel.getAccessibleContext();
        }, null);
    }

    private int get_selection_count() {
        AccessibleContext ac = _ac.get();
        if (ac == null)
            return 0;
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return 0;

        return AtkUtil.invokeInSwingAndWait(() -> {
            int count = 0;
            for (int i = 0; i < ac.getAccessibleChildrenCount(); i++) {
                if (acc_selection.isAccessibleChildSelected(i))
                    count++;
            }
            return count;
        }, 0);
        //A bug in AccessibleJMenu??
        //return acc_selection.getAccessibleSelectionCount();
    }

    private boolean is_child_selected(int i) {
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return false;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_selection.isAccessibleChildSelected(i);
        }, false);
    }

    private boolean remove_selection(int i) {
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return false;

        return AtkUtil.invokeInSwingAndWait(() -> {
            acc_selection.removeAccessibleSelection(i);
            return !is_child_selected(i);
        }, false);
    }

    private boolean select_all_selection() {
        AccessibleContext ac = _ac.get();
        if (ac == null)
            return false;
        AccessibleSelection acc_selection = _acc_selection.get();
        if (acc_selection == null)
            return false;

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleStateSet stateSet = ac.getAccessibleStateSet();
            if (stateSet.contains(AccessibleState.MULTISELECTABLE)) {
                acc_selection.selectAllAccessibleSelection();
                return true;
            }
            return false;
        }, false);
    }
}
