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

public final class AtkCoordType {
    /**
     * Coordinates are relative to the screen.
     */
    public static final int SCREEN = 0;

    /**
     * Coordinates are relative to the component's toplevel window.
     */
    public static final int WINDOW = 1;

    /**
     * Coordinates are relative to the component's immediate parent.
     */
    public static final int PARENT = 2;

    private AtkCoordType() {}
}
