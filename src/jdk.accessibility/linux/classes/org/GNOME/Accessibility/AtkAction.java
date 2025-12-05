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
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Action interface implementation for Java accessibility.
 *
 * This class provides a bridge between Java's AccessibleAction interface
 * and the ATK (Accessibility Toolkit) action interface.
 */
public class AtkAction {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleAction> accessibleActionWeakRef;

    private final String[] actionDescriptions;
    private final String[] actionLocalizedNames;
    private final int actionCount; // the number of accessible actions available on the object

    private AtkAction(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        AccessibleAction accessibleAction = ac.getAccessibleAction();
        if (accessibleAction == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleAction");
        }

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleActionWeakRef = new WeakReference<AccessibleAction>(accessibleAction);
        this.actionCount = accessibleAction.getAccessibleActionCount();
        this.actionDescriptions = new String[actionCount];
        this.actionLocalizedNames = new String[actionCount];
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkAction instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkAction instance, or null if creation fails
     */
    private static AtkAction create_atk_action(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkAction(ac);
        }, null);
    }

    /**
     * Performs the specified action on the object.
     * Called from native code via JNI.
     *
     * @param index the action index corresponding to the action to be performed
     * @return true if the action was successfully performed, false otherwise
     */
    private boolean do_action(int index) {
        if (index < 0 || index >= actionCount) {
            return false;
        }
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleAction.doAccessibleAction(index);
        }, false);
    }

    /**
     * Gets the number of accessible actions available on the object.
     * If there are more than one, the first one is considered the "default" action.
     * Called from native code via JNI.
     *
     * @return the number of actions, or 0 if this object does not implement actions
     */
    private int get_n_actions() {
        return this.actionCount;
    }

    /**
     * Returns a description of the specified action of the object.
     * Called from native code via JNI.
     *
     * @param index the action index corresponding to the action
     * @return a description string, or null if the action does not exist
     */
    private String get_description(int index) {
        if (index < 0 || index >= actionCount) {
            return null;
        }
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return null;
        }
        if (actionDescriptions[index] != null) {
            return actionDescriptions[index];
        }
        actionDescriptions[index] = AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleAction.getAccessibleActionDescription(index);
        }, null);
        return actionDescriptions[index];
    }

    /**
     * Sets a description of the specified action of the object.
     * Called from native code via JNI.
     *
     * @param index the action index corresponding to the action
     * @param description the description to be assigned to this action
     * @return true if the description was successfully set, false otherwise
     */
    private boolean set_description(int index, String description) {
        if (index < 0 || index >= actionCount) {
            return false;
        }
        actionDescriptions[index] = description;
        return true;
    }

    /**
     * Returns the localized name of the specified action of the object.
     * Called from native code via JNI.
     *
     * @param index the action index corresponding to the action
     * @return a localized name string, or null if the action does not exist
     */
    private String get_localized_name(int index) {
        if (index < 0 || index >= actionCount) {
            return null;
        }
        AccessibleContext accessibleContext = accessibleContextWeakRef.get();
        if (accessibleContext == null) {
            return null;
        }
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return null;
        }
        if (actionLocalizedNames[index] != null) {
            return actionLocalizedNames[index];
        }
        return AtkUtil.invokeInSwingAndWait(() -> {
            actionLocalizedNames[index] = accessibleAction.getAccessibleActionDescription(index);
            if (actionLocalizedNames[index] != null) {
                return actionLocalizedNames[index];
            }
            String name = accessibleContext.getAccessibleName();
            if (name != null) {
                actionLocalizedNames[index] = name;
                return actionLocalizedNames[index];
            }
            actionLocalizedNames[index] = "";
            return actionLocalizedNames[index];
        }, null);
    }
}
