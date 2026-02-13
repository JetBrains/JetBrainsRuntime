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

import javax.accessibility.*;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Table interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleTable interface
 * and the ATK (Accessibility Toolkit) table interface.
 */
public class AtkTable {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleTable> accessibleTableWeakRef;

    private AtkTable(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        AccessibleTable accessibleTable = ac.getAccessibleTable();
        if (accessibleTable == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleTable");
        }

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleTableWeakRef = new WeakReference<AccessibleTable>(ac.getAccessibleTable());
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkTable instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkTable instance, or null if creation fails
     */
    private static AtkTable create_atk_table(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkTable(ac);
        }, null);
    }

    /**
     * Gets an accessible context at the specified row and column in the table.
     * Called from native code via JNI.
     *
     * @param row    the row index
     * @param column the column index
     * @return the AccessibleContext of the cell at the specified position, or null if none
     */
    private AccessibleContext ref_at(int row, int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible accessible = accessibleTable.getAccessibleAt(row, column);
            if (accessible == null) {
                return null;
            }
            AccessibleContext accessibleContext = accessible.getAccessibleContext();
            if (accessibleContext != null) {
                AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
            }
            return accessibleContext;
        }, null);
    }

    /**
     * Gets the index of the accessible child at the specified row and column.
     * Called from native code via JNI.
     *
     * @param row    the row index
     * @param column the column index
     * @return the child index, or -1 if no child exists at that position
     */
    private int get_index_at(int row, int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleTable instanceof AccessibleExtendedTable accessibleExtendedTable) {
                return accessibleExtendedTable.getAccessibleIndex(row, column);
            }
            Accessible child = accessibleTable.getAccessibleAt(row, column);
            if (child == null) {
                return -1;
            }
            AccessibleContext childAccessibleContext = child.getAccessibleContext();
            if (childAccessibleContext == null) {
                return -1;
            }
            return childAccessibleContext.getAccessibleIndexInParent();
        }, -1);
    }

    /**
     * Gets the column index at the specified child index.
     * Called from native code via JNI.
     *
     * @param index the child index
     * @return the column index, or -1 if not available
     */
    private int get_column_at_index(int index) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int column = -1;
            if (accessibleTable instanceof AccessibleExtendedTable accessibleExtendedTable) {
                column = accessibleExtendedTable.getAccessibleColumn(index);
            }
            return column;
        }, -1);
    }

    /**
     * Gets the row index at the specified child index.
     * Called from native code via JNI.
     *
     * @param index the child index
     * @return the row index, or -1 if not available
     */
    private int get_row_at_index(int index) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int row = -1;
            if (accessibleTable instanceof AccessibleExtendedTable accessibleExtendedTable) {
                row = accessibleExtendedTable.getAccessibleRow(index);
            }
            return row;
        }, -1);
    }

    /**
     * Gets the number of columns in the table.
     * Called from native code via JNI.
     *
     * @return the number of columns, or 0 if the table is not available
     */
    private int get_n_columns() {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.getAccessibleColumnCount();
        }, 0);
    }

    /**
     * Gets the number of rows in the table.
     * Called from native code via JNI.
     *
     * @return the number of rows, or 0 if the table is not available
     */
    private int get_n_rows() {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.getAccessibleRowCount();
        }, 0);
    }

    /**
     * Gets the number of columns occupied by the accessible object at the specified row and column.
     * Called from native code via JNI.
     *
     * @param row    the row index
     * @param column the column index
     * @return the column extent (colspan), or 0 if not available
     */
    private int get_column_extent_at(int row, int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.getAccessibleColumnExtentAt(row, column);
        }, 0);
    }

    /**
     * Gets the number of rows occupied by the accessible object at the specified row and column.
     * Called from native code via JNI.
     *
     * @param row    the row index
     * @param column the column index
     * @return the row extent (rowspan), or 0 if not available
     */
    private int get_row_extent_at(int row, int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.getAccessibleRowExtentAt(row, column);
        }, 0);
    }

    /**
     * Gets the caption for the table.
     * Called from native code via JNI.
     *
     * @return the AccessibleContext of the table caption, or null if none
     */
    private AccessibleContext get_caption() {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible accessible = accessibleTable.getAccessibleCaption();
            if (accessible == null) {
                return null;
            }
            AccessibleContext accessibleContext = accessible.getAccessibleContext();
            if (accessibleContext != null) {
                AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
            }
            return accessibleContext;
        }, null);
    }

    /**
     * Sets the caption for the table.
     * Called from native code via JNI.
     *
     * @param a the Accessible to use as the table caption
     */
    private void set_caption(Accessible a) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleTable.setAccessibleCaption(a);
        });
    }

    /**
     * Gets the description text of the specified column in the table.
     * Called from native code via JNI.
     *
     * @param column an int representing a column in the table
     * @return a String representing the column description, or null if the table doesn't implement
     * this interface or if no description is available for the specified column
     */
    private String get_column_description(int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible accessible = accessibleTable.getAccessibleColumnDescription(column);
            if (accessible != null) {
                AccessibleContext ac = accessible.getAccessibleContext();
                if (ac != null) {
                    return ac.getAccessibleDescription();
                }
            }
            return null;
        }, null);
    }

    /**
     * Sets the description text of the specified column in the table.
     * Called from native code via JNI.
     *
     * @param column      an int representing a column in table
     * @param description a String object representing the description text to set for the
     *                    specified column of the table
     */
    private void set_column_description(int column, String description) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            Accessible accessible = accessibleTable.getAccessibleColumnDescription(column);
            if (accessible != null) {
                AccessibleContext ac = accessible.getAccessibleContext();
                if (ac != null) {
                    ac.setAccessibleDescription(description);
                }
            }
        });
    }

    /**
     * Gets the description text of the specified row in the table.
     * Called from native code via JNI.
     *
     * @param row an int representing a row in the table
     * @return a String representing the row description, or null if the table doesn't implement
     * this interface or if no description is available for the specified row
     */
    private String get_row_description(int row) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible accessible = accessibleTable.getAccessibleRowDescription(row);
            if (accessible != null) {
                AccessibleContext ac = accessible.getAccessibleContext();
                if (ac != null) {
                    return ac.getAccessibleDescription();
                }
            }
            return null;
        }, null);
    }

    /**
     * Sets the description text of the specified row in the table.
     * Called from native code via JNI.
     *
     * @param row         an int representing a row in table
     * @param description a String object representing the description text to set for the
     *                    specified row of the table
     */
    private void set_row_description(int row, String description) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            Accessible accessible = accessibleTable.getAccessibleRowDescription(row);
            if (accessible != null) {
                AccessibleContext ac = accessible.getAccessibleContext();
                if (ac != null) {
                    ac.setAccessibleDescription(description);
                }
            }
        });
    }

    /**
     * Gets the column header at the specified column index.
     * Called from native code via JNI.
     *
     * @param column the column index
     * @return the AccessibleContext of the column header, or null if none
     */
    private AccessibleContext get_column_header(int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleTable accessibleColumnHeader = accessibleTable.getAccessibleColumnHeader();
            if (accessibleColumnHeader != null) {
                Accessible accessible = accessibleColumnHeader.getAccessibleAt(0, column);
                if (accessible == null) {
                    return null;
                }
                AccessibleContext accessibleContext = accessible.getAccessibleContext();
                if (accessibleContext != null) {
                    AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
                }
                return accessibleContext;
            }
            return null;
        }, null);
    }

    /**
     * Gets the row header at the specified row index.
     * Called from native code via JNI.
     *
     * @param row the row index
     * @return the AccessibleContext of the row header, or null if none
     */
    private AccessibleContext get_row_header(int row) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleTable accessibleRowHeader = accessibleTable.getAccessibleRowHeader();
            if (accessibleRowHeader != null) {
                Accessible accessible = accessibleRowHeader.getAccessibleAt(row, 0);
                if (accessible == null) {
                    return null;
                }
                AccessibleContext accessibleContext = accessible.getAccessibleContext();
                if (accessibleContext != null) {
                    AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
                }
                return accessibleContext;
            }
            return null;
        }, null);
    }

    /**
     * Gets the summary description of the table.
     * Called from native code via JNI.
     *
     * @return the AccessibleContext of the table summary, or null if none
     */
    private AccessibleContext get_summary() {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible accessibleSummary = accessibleTable.getAccessibleSummary();
            if (accessibleSummary == null) {
                return null;
            }
            AccessibleContext accessibleContext = accessibleSummary.getAccessibleContext();
            if (accessibleContext != null) {
                AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
            }
            return accessibleContext;
        }, null);
    }

    /**
     * Sets the summary description of the table.
     * Called from native code via JNI.
     *
     * @param a the Accessible to use as the table summary
     */
    private void set_summary(Accessible a) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleTable.setAccessibleSummary(a);
        });
    }

    /**
     * Gets the selected columns in the table.
     * Called from native code via JNI.
     *
     * @return an array of column indices that are selected, or null if none are selected
     */
    private int[] get_selected_columns() {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.getSelectedAccessibleColumns();
        }, null);
    }

    /**
     * Gets the selected rows in the table.
     * Called from native code via JNI.
     *
     * @return an array of row indices that are selected, or null if none are selected
     */
    private int[] get_selected_rows() {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.getSelectedAccessibleRows();
        }, null);
    }

    /**
     * Determines whether the specified column is selected.
     * Called from native code via JNI.
     *
     * @param column the column index
     * @return true if the column is selected, false otherwise
     */
    private boolean is_column_selected(int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.isAccessibleColumnSelected(column);
        }, false);
    }

    /**
     * Determines whether the specified row is selected.
     * Called from native code via JNI.
     *
     * @param row the row index
     * @return true if the row is selected, false otherwise
     */
    private boolean is_row_selected(int row) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.isAccessibleRowSelected(row);
        }, false);
    }

    /**
     * Determines whether the accessible object at the specified row and column is selected.
     * Called from native code via JNI.
     *
     * @param row    the row index
     * @param column the column index
     * @return true if the cell at the specified position is selected, false otherwise
     */
    private boolean is_selected(int row, int column) {
        AccessibleTable accessibleTable = accessibleTableWeakRef.get();
        if (accessibleTable == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleTable.isAccessibleSelected(row, column);
        }, false);
    }
}
