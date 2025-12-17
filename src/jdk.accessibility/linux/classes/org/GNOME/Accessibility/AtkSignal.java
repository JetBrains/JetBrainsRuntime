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

import java.lang.annotation.Native;

public interface AtkSignal {
    @Native
    int TEXT_CARET_MOVED = 0;
    @Native
    int TEXT_PROPERTY_CHANGED_INSERT = 1;
    @Native
    int TEXT_PROPERTY_CHANGED_DELETE = 2;
    @Native
    int TEXT_PROPERTY_CHANGED_REPLACE = 3;
    @Native
    int OBJECT_CHILDREN_CHANGED_ADD = 4;
    @Native
    int OBJECT_CHILDREN_CHANGED_REMOVE = 5;
    @Native
    int OBJECT_ACTIVE_DESCENDANT_CHANGED = 6;
    @Native
    int OBJECT_SELECTION_CHANGED = 7;
    @Native
    int OBJECT_VISIBLE_DATA_CHANGED = 8;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_ACTIONS = 9;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_VALUE = 10;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_DESCRIPTION = 11;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_NAME = 12;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_HYPERTEXT_OFFSET = 13;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_CAPTION = 14;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_SUMMARY = 15;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_HEADER = 16;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_DESCRIPTION = 17;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_HEADER = 18;
    @Native
    int OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_DESCRIPTION = 19;
    @Native
    int TABLE_MODEL_CHANGED = 20;
    @Native
    int TEXT_PROPERTY_CHANGED = 21;
}

