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
    private WeakReference<AccessibleTable> accessibleTableWeakRef = null;
    private int row        = -1; // used by native code
    private int rowSpan    = 0; // number of rows occupied by this table cell, used by native code
    private int column     = -1; // used by native code
    private int columnSpan = 0; // number of columns occupied by this table cell, used by native code

    private AtkTableCell(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        Accessible accessibleParent = ac.getAccessibleParent();
        if (accessibleParent == null) {
            return;
        }
        AccessibleContext parentAccessibleContext = accessibleParent.getAccessibleContext();
        if (parentAccessibleContext == null) {
            return;
        }
        AccessibleTable accessibleTable = parentAccessibleContext.getAccessibleTable();
        if (accessibleTable == null) {
            return;
        }
        accessibleTableWeakRef = new WeakReference<AccessibleTable>(accessibleTable);
        if (!(accessibleTable instanceof AccessibleExtendedTable)) {
            return;
        }
        AccessibleExtendedTable accessibleExtendedTable = (AccessibleExtendedTable) accessibleTable;

        int index = ac.getAccessibleIndexInParent();
        row = accessibleExtendedTable.getAccessibleRow(index);
        column = accessibleExtendedTable.getAccessibleColumn(index);
        rowSpan = accessibleTable.getAccessibleRowExtentAt(row, column);
        columnSpan = accessibleTable.getAccessibleColumnExtentAt(row, column);
    }

    // JNI upcalls section

    private static AtkTableCell create_atk_table_cell(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkTableCell(ac);
        }, null);
    }

    private AccessibleTable get_table() {
        if (accessibleTableWeakRef == null) {
            return null;
        }
        return accessibleTableWeakRef.get();
    }

    private AccessibleContext[] get_accessible_column_header() {
        if (accessibleTableWeakRef == null) {
            return null;
        }
        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleTable accessibleTable = accessibleTableWeakRef.get();
            if (accessibleTable == null) {
                return null;
            }
            AccessibleTable iteration = accessibleTable.getAccessibleColumnHeader();
            if (iteration != null) {
                int length = iteration.getAccessibleColumnCount();
                AccessibleContext[] result = new AccessibleContext[length];
                for (int i = 0; i < length; i++) {
                    AccessibleContext accessibleContext = iteration.getAccessibleAt(0, i).getAccessibleContext();
                    AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
                    result[i] = accessibleContext;
                }
                return result;
            }
            return null;
        }, null);
    }

    private AccessibleContext[] get_accessible_row_header() {
        if (accessibleTableWeakRef == null) {
            return null;
        }
        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleTable accessibleTable = accessibleTableWeakRef.get();
            if (accessibleTable == null) {
                return null;
            }
            AccessibleTable iteration = accessibleTable.getAccessibleRowHeader();
            if (iteration != null) {
                int length = iteration.getAccessibleRowCount();
                AccessibleContext[] result = new AccessibleContext[length];
                for (int i = 0; i < length; i++) {
                    AccessibleContext accessibleContext = iteration.getAccessibleAt(0, i).getAccessibleContext();
                    AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
                    result[i] = accessibleContext;
                }
                return result;
            }
            return null;
        }, null);
    }
}
