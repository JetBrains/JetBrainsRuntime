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
 *  This interface provides list specific data.
 *
 * @author Artem Semenov
 */
public interface AccessibleList<E> extends ListModel<E>, ListSelectionModel, AccessibleTable {

    // AccessibleTable

    @Override
    Accessible getAccessibleCaption();

    @Override
    void setAccessibleCaption(Accessible a);

    @Override
    Accessible getAccessibleSummary();

    @Override
    void setAccessibleSummary(Accessible a);

    @Override
    int getAccessibleRowCount();

    @Override
    int getAccessibleColumnCount();

    @Override
    Accessible getAccessibleAt(int r, int c);

    @Override
    int getAccessibleRowExtentAt(int r, int c);

    @Override
    int getAccessibleColumnExtentAt(int r, int c);

    @Override
    AccessibleTable getAccessibleRowHeader();

    @Override
    void setAccessibleRowHeader(AccessibleTable table);

    @Override
    AccessibleTable getAccessibleColumnHeader();

    @Override
    void setAccessibleColumnHeader(AccessibleTable table);

    @Override
    Accessible getAccessibleRowDescription(int r);

    @Override
    void setAccessibleRowDescription(int r, Accessible a);

    @Override
    Accessible getAccessibleColumnDescription(int c);

    @Override
    void setAccessibleColumnDescription(int c, Accessible a);

    @Override
    boolean isAccessibleSelected(int r, int c);

    @Override
    boolean isAccessibleRowSelected(int r);

    @Override
    boolean isAccessibleColumnSelected(int c);

    @Override
    int[] getSelectedAccessibleRows();

    @Override
    int[] getSelectedAccessibleColumns();

    // ListModel

    @Override
    int getSize();

    @Override
    E getElementAt(int index);

    @Override
    void addListDataListener(ListDataListener l);

    @Override
    void removeListDataListener(ListDataListener l);

    // ListSelectionModel

    @Override
    void setSelectionInterval(int index0, int index1);

    @Override
    void addSelectionInterval(int index0, int index1);

    @Override
    void removeSelectionInterval(int index0, int index1);

    @Override
    int getMinSelectionIndex();

    @Override
    int getMaxSelectionIndex();

    @Override
    boolean isSelectedIndex(int index);

    @Override
    int getAnchorSelectionIndex();

    @Override
    void setAnchorSelectionIndex(int index);

    @Override
    int getLeadSelectionIndex();

    @Override
    void setLeadSelectionIndex(int index);

    @Override
    void clearSelection();

    @Override
    boolean isSelectionEmpty();

    @Override
    void insertIndexInterval(int index, int length, boolean before);

    @Override
    void removeIndexInterval(int index0, int index1);

    @Override
    void setValueIsAdjusting(boolean valueIsAdjusting);

    @Override
    boolean getValueIsAdjusting();

    @Override
    void setSelectionMode(int selectionMode);

    @Override
    int getSelectionMode();

    @Override
    void addListSelectionListener(ListSelectionListener x);

    @Override
    void removeListSelectionListener(ListSelectionListener x);

    @Override
    int[] getSelectedIndices();

    @Override
    int getSelectedItemsCount();
}
