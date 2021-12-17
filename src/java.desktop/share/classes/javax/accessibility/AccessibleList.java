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
 *
 * @param <E> the type of the elements of this a11y list
 */
public abstract class AccessibleList<E> implements ListModel<E>, ListSelectionModel, AccessibleTable {
    private ListModel<E> listModel;
    private ListSelectionModel listSelectionModel;

    /**
     * Defalt constructor
     */
    public AccessibleList() {
        this(null, null);
    }

    /**
     * This constructor collect oll parms
     *
     * @param listModel          wrapping list model
     * @param listSelectionModel wrapping list selection model
     */
    public AccessibleList(ListModel<E> listModel, ListSelectionModel listSelectionModel) {
        this.listModel = listModel;
        this.listSelectionModel = listSelectionModel;
    }

    /**
     * This methond set list model feald
     *
     * @param lm  elements of this a11y list
     */
    public void setListModel(ListModel<E> lm) {
        this.listModel = lm;
    }

    /**
     * This method set list selection model feald
     *
     * @param lsm wrapping list selection model
     */
    public void setListSelectionModel(ListSelectionModel lsm) {
        this.listSelectionModel = lsm;
    }

    // ListModel implementation

    @Override
    public int getSize() {
        if (listModel != null) {
            return listModel.getSize();
        }
        return 0;
    }

    @Override
    public E getElementAt(int index) {
        if (listModel != null) {
            return listModel.getElementAt(index);
        }
        return null;
    }

    @Override
    public void addListDataListener(ListDataListener l) {
        if (listModel != null) {
            listModel.addListDataListener(l);
        }
    }

    @Override
    public void removeListDataListener(ListDataListener l) {
        if (listModel != null) {
            listModel.removeListDataListener(l);
        }
    }

    // ListSelectedModel implementation

    @Override
    public void setSelectionInterval(int index0, int index1) {
        if (listSelectionModel != null) {
            listSelectionModel.setSelectionInterval(index0, index1);
        }
    }

    @Override
    public void addSelectionInterval(int index0, int index1) {
        if (listSelectionModel != null) {
            listSelectionModel.addSelectionInterval(index0, index1);
        }
    }

    @Override
    public void removeSelectionInterval(int index0, int index1) {
        if (listSelectionModel != null) {
            listSelectionModel.removeSelectionInterval(index0, index1);
        }
    }

    @Override
    public int getMinSelectionIndex() {
        if (listSelectionModel != null) {
            return listSelectionModel.getMinSelectionIndex();
        }
        return 0;
    }

    @Override
    public int getMaxSelectionIndex() {
        if (listSelectionModel != null) {
            return listSelectionModel.getMaxSelectionIndex();
        }
        return 0;
    }

    @Override
    public boolean isSelectedIndex(int index) {
        if (listSelectionModel != null) {
            return listSelectionModel.isSelectedIndex(index);
        }
        return false;
    }

    @Override
    public int getAnchorSelectionIndex() {
        if (listSelectionModel != null) {
            return listSelectionModel.getAnchorSelectionIndex();
        }
        return 0;
    }

    @Override
    public void setAnchorSelectionIndex(int index) {
        if (listSelectionModel != null) {
            listSelectionModel.setAnchorSelectionIndex(index);
        }
    }

    @Override
    public int getLeadSelectionIndex() {
        if (listSelectionModel != null) {
            return listSelectionModel.getLeadSelectionIndex();
        }
        return 0;
    }

    @Override
    public void setLeadSelectionIndex(int index) {
        if (listSelectionModel != null) {
            listSelectionModel.setLeadSelectionIndex(index);
        }
    }

    @Override
    public void clearSelection() {
        if (listSelectionModel != null) {
            listSelectionModel.clearSelection();
        }
    }

    @Override
    public boolean isSelectionEmpty() {
        if (listSelectionModel != null) {
            return listSelectionModel.isSelectionEmpty();
        }
        return true;
    }

    @Override
    public void insertIndexInterval(int index, int length, boolean before) {
        if (listSelectionModel != null) {
            listSelectionModel.insertIndexInterval(index, length, before);
        }
    }

    @Override
    public void removeIndexInterval(int index0, int index1) {
        if (listSelectionModel != null) {
            listSelectionModel.removeIndexInterval(index0, index1);
        }
    }

    @Override
    public void setValueIsAdjusting(boolean valueIsAdjusting) {
        if (listSelectionModel != null) {
            listSelectionModel.setValueIsAdjusting(valueIsAdjusting);
        }
    }

    @Override
    public boolean getValueIsAdjusting() {
        if (listSelectionModel != null) {
            return  listSelectionModel.getValueIsAdjusting();
        }
        return false;
    }

    @Override
    public void setSelectionMode(int selectionMode) {
        if (listSelectionModel != null) {
            listSelectionModel.setSelectionMode(selectionMode);
        }
    }

    @Override
    public int getSelectionMode() {
        if (listSelectionModel != null) {
            return listSelectionModel.getSelectionMode();
        }
        return 0;
    }

    @Override
    public void addListSelectionListener(ListSelectionListener x) {
        if (listSelectionModel != null) {
            listSelectionModel.addListSelectionListener(x);
        }
    }

    @Override
    public void removeListSelectionListener(ListSelectionListener x) {
        if (listSelectionModel != null) {
            listSelectionModel.removeListSelectionListener(x);
        }
    }

    @Override
    public int[] getSelectedIndices() {
        if (listSelectionModel != null) {
            return listSelectionModel.getSelectedIndices();
        }
        return ListSelectionModel.super.getSelectedIndices();
    }

    @Override
    public int getSelectedItemsCount() {
        if (listSelectionModel != null) {
            return listSelectionModel.getSelectedItemsCount();
        }
        return ListSelectionModel.super.getSelectedItemsCount();
    }

    // AccessibleTable Implementation

    @Override
    public Accessible getAccessibleCaption() {
        return null;
    }

    @Override
    public void setAccessibleCaption(Accessible a) {

    }

    @Override
    public Accessible getAccessibleSummary() {
        return null;
    }

    @Override
    public void setAccessibleSummary(Accessible a) {

    }

    @Override
    public int getAccessibleRowCount() {
        if (listModel != null) {
            return listModel.getSize();
        }
        return -1;
    }

    @Override
    public int getAccessibleColumnCount() {
        return 1;
    }

    @Override
    public abstract Accessible getAccessibleAt(int r, int c);

    @Override
    public int getAccessibleRowExtentAt(int r, int c) {
        return 1;
    }

    @Override
    public int getAccessibleColumnExtentAt(int r, int c) {
        return 1;
    }

    @Override
    public AccessibleTable getAccessibleRowHeader() {
        return null;
    }

    @Override
    public void setAccessibleRowHeader(AccessibleTable table) {

    }

    @Override
    public AccessibleTable getAccessibleColumnHeader() {
        return null;
    }

    @Override
    public void setAccessibleColumnHeader(AccessibleTable table) {

    }

    @Override
    public Accessible getAccessibleRowDescription(int r) {
        return null;
    }

    @Override
    public void setAccessibleRowDescription(int r, Accessible a) {

    }

    @Override
    public Accessible getAccessibleColumnDescription(int c) {
        return null;
    }

    @Override
    public void setAccessibleColumnDescription(int c, Accessible a) {

    }

    @Override
    public boolean isAccessibleSelected(int r, int c) {
        if ((listSelectionModel != null) && (r >= 0) && (r< this.getAccessibleRowCount())) {
            return listSelectionModel.isSelectedIndex(r);
        }
        return false;
    }

    @Override
    public boolean isAccessibleRowSelected(int r) {
        if (listSelectionModel != null) {
            return listSelectionModel.isSelectedIndex(r);
        }
        return false;
    }

    @Override
    public boolean isAccessibleColumnSelected(int c) {
        return c== 0;
    }

    @Override
    public int[] getSelectedAccessibleRows() {
        if (listSelectionModel != null) {
            return listSelectionModel.getSelectedIndices();
        }
        return null;
    }

    @Override
    public int[] getSelectedAccessibleColumns() {
        return null;
    }
}
