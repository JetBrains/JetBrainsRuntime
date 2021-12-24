/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */

package javax.accessibility;

import javax.swing.*;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionListener;

/**
 *  This is salution for 8271846 a11y API lacks setSelectedIndex method.
 *  This object provide expanded list info for a11y implementation.
 */
public interface AccessibleList<E> extends ListModel<E>, ListSelectionModel, AccessibleTable {

    /**
     * This method return list model
     * Implementate this method for this interface methods defalt implementation
     *
     * @return List model
     */
    ListModel<E> getListModel();

    /**
     *  This method return list selection model for this interface
     *  Implementate this method for use defolt interface method implementation
     *
     * @return List selection model
     */
    ListSelectionModel getListSelectionModel();

    // ListModel implementation

    @Override
    public default int getSize() {
        if (getListModel() != null) {
            return getListModel().getSize();
        }
        return 0;
    }

    @Override
    public default E getElementAt(int index) {
        if (getListModel() != null) {
            return getListModel().getElementAt(index);
        }
        return null;
    }

    @Override
    public default void addListDataListener(ListDataListener l) {
        if (getListModel() != null) {
            getListModel().addListDataListener(l);
        }
    }

    @Override
    public default void removeListDataListener(ListDataListener l) {
        if (getListModel() != null) {
            getListModel().removeListDataListener(l);
        }
    }

    // ListSelectedModel implementation

    @Override
    public default void setSelectionInterval(int index0, int index1) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().setSelectionInterval(index0, index1);
        }
    }

    @Override
    public default void addSelectionInterval(int index0, int index1) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().addSelectionInterval(index0, index1);
        }
    }

    @Override
    public default void removeSelectionInterval(int index0, int index1) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().removeSelectionInterval(index0, index1);
        }
    }

    @Override
    public default int getMinSelectionIndex() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getMinSelectionIndex();
        }
        return 0;
    }

    @Override
    public default int getMaxSelectionIndex() {
        if (getListModel() != null) {
            return getListSelectionModel().getMaxSelectionIndex();
        }
        return 0;
    }

    @Override
    public default boolean isSelectedIndex(int index) {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().isSelectedIndex(index);
        }
        return false;
    }

    @Override
    public default int getAnchorSelectionIndex() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getAnchorSelectionIndex();
        }
        return 0;
    }

    @Override
    public default void setAnchorSelectionIndex(int index) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().setAnchorSelectionIndex(index);
        }
    }

    @Override
    public default int getLeadSelectionIndex() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getLeadSelectionIndex();
        }
        return 0;
    }

    @Override
    public default void setLeadSelectionIndex(int index) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().setLeadSelectionIndex(index);
        }
    }

    @Override
    public default void clearSelection() {
        if (getListSelectionModel() != null) {
            getListSelectionModel().clearSelection();
        }
    }

    @Override
    public default boolean isSelectionEmpty() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().isSelectionEmpty();
        }
        return true;
    }

    @Override
    public default void insertIndexInterval(int index, int length, boolean before) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().insertIndexInterval(index, length, before);
        }
    }

    @Override
    public default void removeIndexInterval(int index0, int index1) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().removeIndexInterval(index0, index1);
        }
    }

    @Override
    public default void setValueIsAdjusting(boolean valueIsAdjusting) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().setValueIsAdjusting(valueIsAdjusting);
        }
    }

    @Override
    public default boolean getValueIsAdjusting() {
        if (getListSelectionModel() != null) {
            return  getListSelectionModel().getValueIsAdjusting();
        }
        return false;
    }

    @Override
    public default void setSelectionMode(int selectionMode) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().setSelectionMode(selectionMode);
        }
    }

    @Override
    public default int getSelectionMode() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getSelectionMode();
        }
        return 0;
    }

    @Override
    public default void addListSelectionListener(ListSelectionListener x) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().addListSelectionListener(x);
        }
    }

    @Override
    public default void removeListSelectionListener(ListSelectionListener x) {
        if (getListSelectionModel() != null) {
            getListSelectionModel().removeListSelectionListener(x);
        }
    }

    @Override
    public default int[] getSelectedIndices() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getSelectedIndices();
        }
        return ListSelectionModel.super.getSelectedIndices();
    }

    @Override
    public default int getSelectedItemsCount() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getSelectedItemsCount();
        }
        return ListSelectionModel.super.getSelectedItemsCount();
    }

    // AccessibleTable Implementation

    @Override
    public default Accessible getAccessibleCaption() {
        return null;
    }

    @Override
    public default void setAccessibleCaption(Accessible a) {

    }

    @Override
    public default Accessible getAccessibleSummary() {
        return null;
    }

    @Override
    public default void setAccessibleSummary(Accessible a) {

    }

    @Override
    public default int getAccessibleRowCount() {
        if (getListModel() != null) {
            return getListModel().getSize();
        }
        return -1;
    }

    @Override
    public default int getAccessibleColumnCount() {
        return 1;
    }

    @Override
    public default int getAccessibleRowExtentAt(int r, int c) {
        return 1;
    }

    @Override
    public default int getAccessibleColumnExtentAt(int r, int c) {
        return 1;
    }

    @Override
    public default AccessibleTable getAccessibleRowHeader() {
        return null;
    }

    @Override
    public default void setAccessibleRowHeader(AccessibleTable table) {

    }

    @Override
    public default AccessibleTable getAccessibleColumnHeader() {
        return null;
    }

    @Override
    public default void setAccessibleColumnHeader(AccessibleTable table) {

    }

    @Override
    public default Accessible getAccessibleRowDescription(int r) {
        return null;
    }

    @Override
    public default void setAccessibleRowDescription(int r, Accessible a) {

    }

    @Override
    public default Accessible getAccessibleColumnDescription(int c) {
        return null;
    }

    @Override
    public default void setAccessibleColumnDescription(int c, Accessible a) {

    }

    @Override
    public default boolean isAccessibleSelected(int r, int c) {
        if ((getListSelectionModel() != null) && (r >= 0) && (r< this.getAccessibleRowCount())) {
            return getListSelectionModel().isSelectedIndex(r);
        }
        return false;
    }

    @Override
    public default boolean isAccessibleRowSelected(int r) {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().isSelectedIndex(r);
        }
        return false;
    }

    @Override
    public default boolean isAccessibleColumnSelected(int c) {
        return c== 0;
    }

    @Override
    public default int[] getSelectedAccessibleRows() {
        if (getListSelectionModel() != null) {
            return getListSelectionModel().getSelectedIndices();
        }
        return null;
    }

    @Override
    public default int[] getSelectedAccessibleColumns() {
        return null;
    }
}
