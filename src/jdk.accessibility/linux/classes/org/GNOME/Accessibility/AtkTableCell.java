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

/**
 * The ATK TableCell interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleTable interface
 * and the ATK (Accessibility Toolkit) table cell interface, representing
 * individual cells within an accessible table.
 */
public class AtkTableCell {
    private final WeakReference<AccessibleTable> accessibleTableWeakRef;
    private final int row; // the row index of this table cell, used by native code
    private final int rowSpan; // the number of rows occupied by this table cell, used by native code
    private final int column; // the column index of this table cell, used by native code
    private final int columnSpan; // the number of columns occupied by this table cell, used by native code

    private AtkTableCell(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        Accessible accessibleParent = ac.getAccessibleParent();
        if (accessibleParent == null) {
            throw new IllegalArgumentException("AccessibleContext must have accessibleParent");
        }

        AccessibleContext parentAccessibleContext = accessibleParent.getAccessibleContext();
        if (parentAccessibleContext == null) {
            throw new IllegalArgumentException("AccessibleContext must have accessibleParent with AccessibleContext");
        }

        AccessibleTable accessibleTable = parentAccessibleContext.getAccessibleTable();
        if (accessibleTable == null) {
            throw new IllegalArgumentException("AccessibleContext must have accessibleParent with AccessibleTable");
        }
        accessibleTableWeakRef = new WeakReference<AccessibleTable>(accessibleTable);

        if (accessibleTable instanceof AccessibleExtendedTable accessibleExtendedTable) {
            int index = ac.getAccessibleIndexInParent();
            row = accessibleExtendedTable.getAccessibleRow(index);
            column = accessibleExtendedTable.getAccessibleColumn(index);
        } else {
            row = -1;
            column = -1;
        }

        rowSpan = accessibleTable.getAccessibleRowExtentAt(row, column);
        columnSpan = accessibleTable.getAccessibleColumnExtentAt(row, column);
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkTableCell instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext representing a table cell
     * @return a new AtkTableCell instance, or null if creation fails
     */
    private static AtkTableCell create_atk_table_cell(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkTableCell(ac);
        }, null);
    }

    /**
     * Gets the table containing this cell.
     * Called from native code via JNI.
     *
     * @return the AccessibleTable containing this cell, or null if unavailable
     */
    private AccessibleTable get_table() {
        if (accessibleTableWeakRef == null) {
            return null;
        }
        return accessibleTableWeakRef.get();
    }

    /**
     * Returns the column headers as an array of AccessibleContext objects.
     * Called from native code via JNI.
     *
     * @return an array of AccessibleContext objects representing the column headers,
     * or null if column headers are not available
     */
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

    /**
     * Returns the row headers as an array of AccessibleContext objects.
     * Called from native code via JNI.
     *
     * @return an array of AccessibleContext objects representing the row headers,
     * or null if row headers are not available
     */
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
