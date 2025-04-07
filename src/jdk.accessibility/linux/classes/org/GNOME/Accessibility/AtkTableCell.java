/*
 * Java ATK Wrapper for GNOME
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

import javax.accessibility.*;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

public class AtkTableCell {

    private WeakReference<AccessibleContext> _ac;
    private WeakReference<AccessibleTable> _acc_pt;
    private int row, rowSpan, column, columnSpan;

    private AtkTableCell(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        _ac = new WeakReference<AccessibleContext>(ac);
        _acc_pt = null;
        row = -1;
        rowSpan = -1;
        column = -1;
        columnSpan = -1;
        Accessible parent = ac.getAccessibleParent();
        if (parent == null) {
            return;
        }
        AccessibleContext pc = parent.getAccessibleContext();
        if (pc == null) {
            return;
        }
        AccessibleTable pt = pc.getAccessibleTable();
        if (pt == null) {
            return;
        }
        _acc_pt = new WeakReference<AccessibleTable>(pt);
        int index = ac.getAccessibleIndexInParent();
        if (!(pt instanceof AccessibleExtendedTable)) {
            return;
        }
        AccessibleExtendedTable aet = (AccessibleExtendedTable) pt;
        row = aet.getAccessibleRow(index);
        column = aet.getAccessibleColumn(index);
        rowSpan = pt.getAccessibleRowExtentAt(row, column);
        columnSpan = pt.getAccessibleColumnExtentAt(row, column);
    }

    // JNI upcalls section

    private static AtkTableCell create_atk_table_cell(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkTableCell(ac);
        }, null);
    }

    private AccessibleTable get_table() {
        if (_acc_pt == null)
            return null;
        return _acc_pt.get();
    }

    private AccessibleContext[] get_accessible_column_header() {
        if (_acc_pt == null)
            return null;
        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleTable accessibleTable = _acc_pt.get();
            if (accessibleTable == null) {
                return null;
            }
            AccessibleTable iteration = accessibleTable.getAccessibleColumnHeader();
            if (iteration != null) {
                int length = iteration.getAccessibleColumnCount();
                AccessibleContext[] result = new AccessibleContext[length];
                for (int i = 0; i < length; i++) {
                    result[i] = iteration.getAccessibleAt(0, i).getAccessibleContext();
                }
                return result;
            }
            return null;
        }, null);
    }

    private AccessibleContext[] get_accessible_row_header() {
        if (_acc_pt == null)
            return null;
        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleTable accessibleTable = _acc_pt.get();
            if (accessibleTable == null) {
                return null;
            }
            AccessibleTable iteration = accessibleTable.getAccessibleRowHeader();
            if (iteration != null) {
                int length = iteration.getAccessibleRowCount();
                AccessibleContext[] result = new AccessibleContext[length];
                for (int i = 0; i < length; i++) {
                    result[i] = iteration.getAccessibleAt(i, 0).getAccessibleContext();
                }
                return result;
            }
            return null;
        }, null);
    }

}
