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

import javax.accessibility.*;
import java.awt.Point;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Image interface implementation for Java accessibility.
 *
 * This class provides a bridge between Java's AccessibleIcon interface
 * and the ATK (Accessibility Toolkit) image interface.
 * AtkImage should be implemented by components which display
 * image/pixmap content on-screen, such as icons, buttons with icons,
 * toolbar elements, and image viewing panes.
 *
 * This interface provides two types of information: coordinate information
 * (useful for screen review mode and onscreen magnifiers) and descriptive
 * information (for alternative text-only presentation).
 */
public class AtkImage {

    private WeakReference<AccessibleContext> accessibleContextWeakRef;
    private WeakReference<AccessibleIcon[]> accessibleIcons;

    private AtkImage(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleIcons = new WeakReference<AccessibleIcon[]>(ac.getAccessibleIcon());
    }

    // JNI upcalls section

    /**
     * Factory method to create an AtkImage instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkImage instance, or null if creation fails
     */
    private static AtkImage create_atk_image(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkImage(ac);
        }, null);
    }

    /**
     * Gets the position of the image in the form of a point specifying the
     * image's top-left corner.
     * Called from native code via JNI.
     *
     * @param coordType specifies whether the coordinates are relative to the screen
     *                  or to the component's top level window
     * @return a Point representing the image position (x, y coordinates), or null
     *         if the position cannot be obtained (e.g., missing support).
     */
    private Point get_image_position(int coordType) {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return AtkComponent.getLocationByCoordinateType(ac, coordType);
        }, null);
    }

    /**
     * Gets a textual description of this image.
     * Called from native code via JNI.
     *
     * @return a string representing the image description, or null if there is
     *         no description available or an error occurs
     */
    private String get_image_description() {
        AccessibleIcon[] accessibleIcons = this.accessibleIcons.get();
        if (accessibleIcons == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String description = null;
            if (accessibleIcons.length > 0) {
                description = accessibleIcons[0].getAccessibleIconDescription();
            }
            return description;
        }, null);
    }

    /**
     * Gets the width and height in pixels for the specified image.
     * Called from native code via JNI.
     *
     * @return a Dimension containing the width and height of the image.
     *         Returns a Dimension with width and height set to -1 if the values
     *         cannot be obtained (for instance, if the object is not onscreen).
     *         If AccessibleIcon information is available, uses that; otherwise,
     *         falls back to the component's bounds.
     */
    private Dimension get_image_size() {
        Dimension dimension = new Dimension(-1, -1);

        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return dimension;
        }

        AccessibleIcon[] accessibleIcons = this.accessibleIcons.get();

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleIcons != null && accessibleIcons.length > 0) {
                dimension.height = accessibleIcons[0].getAccessibleIconHeight();
                dimension.width = accessibleIcons[0].getAccessibleIconWidth();
            } else {
                AccessibleComponent accessibleComponent = ac.getAccessibleComponent();
                if (accessibleComponent != null) {
                    Rectangle rectangle = accessibleComponent.getBounds();
                    if (rectangle != null) {
                        dimension.height = rectangle.height;
                        dimension.width = rectangle.width;
                    }
                }
            }
            return dimension;
        }, dimension);
    }
}
