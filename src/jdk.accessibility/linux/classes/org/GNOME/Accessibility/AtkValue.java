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
import java.lang.ref.WeakReference;
import java.awt.EventQueue;
import java.lang.Double;

/**
 * The ATK Value interface implementation for Java accessibility.
 *
 * This class provides a bridge between Java's AccessibleValue interface
 * and the ATK (Accessibility Toolkit) value interface, enabling access to
 * numeric values and their ranges for accessible objects.
 */
public class AtkValue {
    private WeakReference<AccessibleValue> accessibleValueWeakRef;

    private AtkValue(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        AccessibleValue accessibleValue = ac.getAccessibleValue();
        if (accessibleValue == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleValue");
        }

        this.accessibleValueWeakRef = new WeakReference<AccessibleValue>(accessibleValue);
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkValue instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkValue instance, or null if creation fails
     */
    private static AtkValue create_atk_value(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkValue(ac);
        }, null);
    }

    /**
     * Gets the current value of this object.
     * Called from native code via JNI.
     *
     * @return a Number representing the current accessible value, or null if the value
     *         is unavailable or the object doesn't implement this interface
     */
    private Number get_current_value() {
        AccessibleValue accessibleValue = accessibleValueWeakRef.get();
        if (accessibleValue == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleValue.getCurrentAccessibleValue();
        }, null);
    }

    /**
     * Gets the maximum value of this object.
     * Called from native code via JNI.
     *
     * @return a Double representing the maximum accessible value, or null if the value
     *         is unavailable or the object doesn't implement this interface
     */
    private Double get_maximum_value() {
        AccessibleValue accessibleValue = accessibleValueWeakRef.get();
        if (accessibleValue == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Number max = accessibleValue.getMaximumAccessibleValue();
            if (max == null) {
                return null;
            }
            return Double.valueOf(max.doubleValue());
        }, null);
    }

    /**
     * Gets the minimum value of this object.
     * Called from native code via JNI.
     *
     * @return a Double representing the minimum accessible value, or null if the value
     *         is unavailable or the object doesn't implement this interface
     */
    private Double get_minimum_value() {
        AccessibleValue accessibleValue = accessibleValueWeakRef.get();
        if (accessibleValue == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Number min = accessibleValue.getMinimumAccessibleValue();
            if (min == null) {
                return null;
            }
            return Double.valueOf(min.doubleValue());
        }, null);
    }

    /**
     * Sets the current value of this object.
     * Called from native code via JNI.
     *
     * @param n the Number value to set as the current accessible value
     */
    private void set_value(Number n) {
        AccessibleValue accessibleValue = accessibleValueWeakRef.get();
        if (accessibleValue == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleValue.setCurrentAccessibleValue(n);
        });
    }

    /**
     * Gets the minimum increment by which the value may be changed.
     * Called from native code via JNI.
     *
     * @return the minimum increment value, returns Double.MIN_VALUE
     */
    private double get_increment() {
        return Double.MIN_VALUE;
    }
}
