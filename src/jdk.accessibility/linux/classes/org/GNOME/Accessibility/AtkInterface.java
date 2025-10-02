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
    /**
     * The ATK interface provided by UI components
     * which the user can activate/interact with.
     */
    @Native
    int INTERFACE_ACTION = 0x00000001;

    /**
     * The ATK interface provided by UI components
     * which occupy a physical area on the screen.
     * which the user can activate/interact with.
     */
    @Native
    int INTERFACE_COMPONENT = 0x00000002;

    /**
     * The ATK interface which represents the toplevel container for document content.
     */
    @Native
    int INTERFACE_DOCUMENT = 0x00000004;

    /**
     * The ATK interface implemented by components containing user-editable text content.
     */
    @Native
    int INTERFACE_EDITABLE_TEXT = 0x00000008;

    /**
     * The ATK interface which provides standard mechanism for manipulating hyperlinks.
     */
    @Native
    int INTERFACE_HYPERTEXT = 0x00000020;

    /**
     * The ATK Interface implemented by components
     * which expose image or pixmap content on-screen.
     */
    @Native
    int INTERFACE_IMAGE = 0x00000040;

    /**
     * The ATK interface implemented by container objects
     * whose AtkObject children can be selected.
     */
    @Native
    int INTERFACE_SELECTION = 0x00000080;

    /**
     * The ATK interface implemented for UI components
     * which contain tabular or row/column information.
     */
    @Native
    int INTERFACE_TABLE = 0x00000200;

    /**
     * The ATK interface implemented for a cell inside a two-dimentional AtkTable.
     */
    @Native
    int INTERFACE_TABLE_CELL = 0x00000400;

    /**
     * The ATK interface implemented by components with text content.
     */
    @Native
    int INTERFACE_TEXT = 0x00000800;

    /**
     * The ATK interface implemented by valuators and components
     * which display or select a value from a bounded range of values.
     */
    @Native
    int INTERFACE_VALUE = 0x00001000;
}

