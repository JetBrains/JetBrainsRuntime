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

public interface AtkInterface {
    @Native int INTERFACE_ACTION = 0x00000001;
    @Native int INTERFACE_COMPONENT = 0x00000002;
    @Native int INTERFACE_DOCUMENT = 0x00000004;
    @Native int INTERFACE_EDITABLE_TEXT = 0x00000008;
    @Native int INTERFACE_HYPERLINK = 0x00000010;
    @Native int INTERFACE_HYPERTEXT = 0x00000020;
    @Native int INTERFACE_IMAGE = 0x00000040;
    @Native int INTERFACE_SELECTION = 0x00000080;
    @Native int INTERFACE_STREAMABLE_CONTENT = 0x00000100;
    @Native int INTERFACE_TABLE = 0x00000200;
    @Native int INTERFACE_TABLE_CELL = 0x00000400;
    @Native int INTERFACE_TEXT = 0x00000800;
    @Native int INTERFACE_VALUE = 0x00001000;
}

