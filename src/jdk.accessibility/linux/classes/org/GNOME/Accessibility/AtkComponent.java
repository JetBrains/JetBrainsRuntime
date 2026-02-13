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
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Dimension;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Component interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleComponent interface
 * and the ATK (Accessibility Toolkit) component interface.
 */
public class AtkComponent {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleComponent> accessibleComponentWeakRef;

    private AtkComponent(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        AccessibleComponent accessibleComponent = ac.getAccessibleComponent();
        if (accessibleComponent == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleComponent");
        }

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleComponentWeakRef = new WeakReference<AccessibleComponent>(accessibleComponent);
    }

    private static Point getWindowLocation(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        while (ac != null) {
            AccessibleRole accessibleRole = ac.getAccessibleRole();
            if (accessibleRole == AccessibleRole.DIALOG ||
                    accessibleRole == AccessibleRole.FRAME ||
                    accessibleRole == AccessibleRole.WINDOW) {
                AccessibleComponent accessibleComponent = ac.getAccessibleComponent();
                if (accessibleComponent == null) {
                    return null;
                }
                return accessibleComponent.getLocationOnScreen();
            }
            Accessible accessibleParent = ac.getAccessibleParent();
            if (accessibleParent == null) {
                return null;
            }
            ac = accessibleParent.getAccessibleContext();
        }
        return null;
    }

    /**
     * Return the position of the object relative to the coordinate type
     * 0 - Coordinates are relative to the screen.
     * 1 - Coordinates are relative to the component's toplevel window.
     * 2 - Coordinates are relative to the component's immediate parent.
     *
     * @param ac
     * @param coordType
     * @return
     */
    public static Point getLocationByCoordinateType(AccessibleContext ac, int coordType) {
        assert EventQueue.isDispatchThread();

        AccessibleComponent accessibleComponent = ac.getAccessibleComponent();
        if (accessibleComponent == null) {
            return null;
        }

        if (coordType == AtkCoordType.SCREEN) {
            // Returns the location of the object on the screen.
            return accessibleComponent.getLocationOnScreen();
        }

        if (coordType == AtkCoordType.WINDOW) {
            Point windowLocation = getWindowLocation(ac);
            if (windowLocation == null) {
                return null;
            }
            Point componentLocationOnScreen = accessibleComponent.getLocationOnScreen();
            if (componentLocationOnScreen == null) {
                return null;
            }
            componentLocationOnScreen.translate(-windowLocation.x, -windowLocation.y);
            return componentLocationOnScreen;
        }

        if (coordType == AtkCoordType.PARENT) {
            // Gets the location of the object relative to the parent
            // in the form of a point specifying the object's top-left
            // corner in the screen's coordinate space.
            return accessibleComponent.getLocation();
        }

        return null;
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkComponent instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkComponent instance, or null if creation fails
     */
    private static AtkComponent create_atk_component(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkComponent(ac);
        }, null);
    }

    /**
     * Checks whether the specified point is within the extent of the component.
     * Called from native code via JNI.
     *
     * @param x         x coordinate
     * @param y         y coordinate
     * @param coordType specifies whether the coordinates are relative to the screen,
     *                  the component's toplevel window, or the component's parent
     * @return true if the specified point is within the extent of the component
     */
    private boolean contains(int x, int y, int coordType) {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return false;
        }
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleComponent.isVisible()) {
                Point componentLocation = getLocationByCoordinateType(accessibleContext, coordType);
                if (componentLocation == null) {
                    return false;
                }

                return accessibleComponent.contains(new Point(x - componentLocation.x, y - componentLocation.y));
            }
            return false;
        }, false);
    }

    /**
     * Gets a reference to the accessible child, if one exists, at the coordinate point
     * specified by x and y.
     * Called from native code via JNI.
     *
     * @param x         x coordinate
     * @param y         y coordinate
     * @param coordType specifies whether the coordinates are relative to the screen,
     *                  the component's toplevel window, or the component's parent
     * @return the AccessibleContext of the child at the specified point, or null if none exists
     */
    private AccessibleContext get_accessible_at_point(int x, int y, int coordType) {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return null;
        }
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleComponent.isVisible()) {
                Point componentLocation = getLocationByCoordinateType(accessibleContext, coordType);
                if (componentLocation == null) {
                    return null;
                }

                Accessible accessibleAt = accessibleComponent.getAccessibleAt(new Point(x - componentLocation.x, y - componentLocation.y));
                if (accessibleAt == null) {
                    return null;
                }
                AccessibleContext accessibleContextAt = accessibleAt.getAccessibleContext();
                if (accessibleContextAt != null) {
                    AtkWrapperDisposer.getInstance().addRecord(accessibleContextAt);
                }
                return accessibleContextAt;
            }
            return null;
        }, null);
    }

    /**
     * Grabs focus for this component.
     * Called from native code via JNI.
     *
     * @return true if successful, false otherwise
     */
    private boolean grab_focus() {
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (!accessibleComponent.isFocusTraversable()) {
                return false;
            }
            accessibleComponent.requestFocus();
            return true;
        }, false);
    }

    /**
     * Sets the extents of the component.
     * Called from native code via JNI.
     *
     * @param newXByCoordType         x coordinate
     * @param newYByCoordType         y coordinate
     * @param width     width to set for the component
     * @param height    height to set for the component
     * @param coordType specifies whether the coordinates are relative to the screen,
     *                  the component's toplevel window, or the component's parent
     * @return true if the extents were set successfully, false otherwise
     */
    private boolean set_extents(int newXByCoordType, int newYByCoordType, int width, int height, int coordType) {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return false;
        }
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleComponent.isVisible()) {
                Point locationByCoordType = getLocationByCoordinateType(accessibleContext, coordType);
                if (locationByCoordType == null) {
                    return false;
                }

                Point locationByParent = accessibleComponent.getLocation();
                if (locationByParent == null) {
                    return false;
                }

                accessibleComponent.setBounds(new Rectangle(locationByParent.x + (newXByCoordType - locationByCoordType.x), locationByParent.y + (newYByCoordType - locationByCoordType.y), width, height));
                return true;
            }
            return false;
        }, false);
    }

    /**
     * Gets the rectangle which gives the extent of the component.
     * Called from native code via JNI.
     *
     * @param coordType specifies whether the coordinates are relative to the screen,
     *                  the component's toplevel window, or the component's parent
     * @return the Rectangle representing the component's extent, or null if it cannot be obtained
     */
    private Rectangle get_extents(int coordType) {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return null;
        }
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleComponent.isVisible()) {
                Dimension dimension = accessibleComponent.getSize();
                if (dimension == null) {
                    return null;
                }
                Point componentLocation = getLocationByCoordinateType(accessibleContext, coordType);
                if (componentLocation == null) {
                    return null;
                }

                return new Rectangle(componentLocation.x, componentLocation.y, dimension.width, dimension.height);
            }
            return null;
        }, null);
    }

    /**
     * Sets the position of the component.
     * Called from native code via JNI.
     *
     * @param newXByCoordType         x coordinate
     * @param newYByCoordType         y coordinate
     * @param coordType specifies whether the coordinates are relative to the screen,
     *                  the component's toplevel window, or the component's parent
     * @return true if the extents were set successfully, false otherwise
     */
    private boolean set_position(int newXByCoordType, int newYByCoordType, int coordType) {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return false;
        }
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleComponent.isVisible()) {
                Point locationByCoordType = getLocationByCoordinateType(accessibleContext, coordType);
                if (locationByCoordType == null) {
                    return false;
                }

                Point locationByParent = accessibleComponent.getLocation();
                if (locationByParent == null) {
                    return false;
                }

                accessibleComponent.setLocation(new Point(locationByParent.x + (newXByCoordType - locationByCoordType.x), locationByParent.y + (newYByCoordType - locationByCoordType.y)));
                return true;
            }
            return false;
        }, false);
    }

    /**
     * Sets the size of the component.
     * Called from native code via JNI.
     *
     * @param width  width to set for the component
     * @param height height to set for the component
     * @return true if the size was set successfully, false otherwise
     */
    private boolean set_size(int width, int height) {
        AccessibleComponent accessibleComponent = accessibleComponentWeakRef.get();
        if (accessibleComponent == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleComponent.isVisible()) {
                accessibleComponent.setSize(new Dimension(width, height));
                return true;
            }
            return false;
        }, false);
    }

    /**
     * Gets the AtkLayer of the component based on AccessibleRole.
     * Called from native code via JNI.
     *
     * @return an int representing the AtkLayer of the component, or AtkLayer.INVALID if an error occurs
     */
    private int get_layer() {
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return AtkLayer.INVALID;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleRole accessibleRole = accessibleContext.getAccessibleRole();
            if (accessibleRole == AccessibleRole.MENU ||
                    accessibleRole == AccessibleRole.MENU_ITEM ||
                    accessibleRole == AccessibleRole.POPUP_MENU) {
                return AtkLayer.POPUP;
            }
            if (accessibleRole == AccessibleRole.INTERNAL_FRAME) {
                return AtkLayer.MDI;
            }
            if (accessibleRole == AccessibleRole.GLASS_PANE) {
                return AtkLayer.OVERLAY;
            }
            if (accessibleRole == AccessibleRole.CANVAS ||
                    accessibleRole == AccessibleRole.ROOT_PANE ||
                    accessibleRole == AccessibleRole.LAYERED_PANE) {
                return AtkLayer.CANVAS;
            }
            if (accessibleRole == AccessibleRole.WINDOW) {
                return AtkLayer.WINDOW;
            }
            return AtkLayer.WIDGET;
        }, AtkLayer.INVALID);
    }
}
