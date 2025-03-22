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

public interface AtkSignal {
    int TEXT_CARET_MOVED = 0;
    int TEXT_PROPERTY_CHANGED_INSERT = 1;
    int TEXT_PROPERTY_CHANGED_DELETE = 2;
    int TEXT_PROPERTY_CHANGED_REPLACE = 3;
    int OBJECT_CHILDREN_CHANGED_ADD = 4;
    int OBJECT_CHILDREN_CHANGED_REMOVE = 5;
    int OBJECT_ACTIVE_DESCENDANT_CHANGED = 6;
    int OBJECT_SELECTION_CHANGED = 7;
    int OBJECT_VISIBLE_DATA_CHANGED = 8;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_ACTIONS = 9;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_VALUE = 10;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_DESCRIPTION = 11;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_NAME = 12;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_HYPERTEXT_OFFSET = 13;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_CAPTION = 14;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_SUMMARY = 15;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_HEADER = 16;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_DESCRIPTION = 17;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_HEADER = 18;
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_DESCRIPTION = 19;
    int TABLE_MODEL_CHANGED = 20;
    int TEXT_PROPERTY_CHANGED = 21;
}

