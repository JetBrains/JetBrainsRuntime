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
import java.util.HashMap;
import java.util.Map;

/**
 * The ATK Action interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleAction interface
 * and the ATK (Accessibility Toolkit) action interface.
 */
public class AtkAction {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleAction> accessibleActionWeakRef;

    private final Map<Integer, String> actionDescriptionsCached;
    private final Map<Integer, String> actionLocalizedNamesCached;

    private final Object actionDescriptionsLock = new Object();
    private final Object actionLocalizedNamesLock = new Object();

    private AtkAction(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        AccessibleAction accessibleAction = ac.getAccessibleAction();
        if (accessibleAction == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleAction");
        }

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);
        this.accessibleActionWeakRef = new WeakReference<AccessibleAction>(accessibleAction);
        this.actionDescriptionsCached = new HashMap<>();
        this.actionLocalizedNamesCached = new HashMap<>();
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
        if (index < 0) {
            return false;
        }
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int count = accessibleAction.getAccessibleActionCount();
            if (index >= count) {
                return false;
            }
            return accessibleAction.doAccessibleAction(index);
        }, false);
    }

    /**
     * Gets the number of accessible actions available on the object.
     * Called from native code via JNI.
     *
     * @return the number of actions, or 0 if this object does not implement actions
     */
    private int get_n_actions() {
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return 0;
        }
        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleAction.getAccessibleActionCount();
        }, 0);
    }

    /**
     * Returns a description of the specified action of the object.
     * Called from native code via JNI.
     *
     * @param index the action index corresponding to the action
     * @return a description string, or null if the action does not exist
     */
    private String get_description(int index) {
        if (index < 0) {
            return null;
        }
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return null;
        }

        String desc;

        synchronized (actionDescriptionsLock) {
            desc = actionDescriptionsCached.get(index);
            if (desc != null) {
                return desc;
            }
        }

        String computedActionDesc = AtkUtil.invokeInSwingAndWait(() -> {
            int count = accessibleAction.getAccessibleActionCount();
            if (index >= count) {
                return null;
            }
            return accessibleAction.getAccessibleActionDescription(index);
        }, null);

        synchronized (actionDescriptionsLock) {
            desc = actionDescriptionsCached.get(index);
            if (desc == null) {
                actionDescriptionsCached.put(index, computedActionDesc);
                desc = computedActionDesc;
            }
        }

        return desc;
    }

    /**
     * Sets a description of the specified action of the object.
     * Called from native code via JNI.
     *
     * @param index       the action index corresponding to the action
     * @param description the description to be assigned to this action
     * @return true if the description was successfully set, false otherwise
     */
    private boolean set_description(int index, String description) {
        if (index < 0) {
            return false;
        }
        AccessibleAction accessibleAction = accessibleActionWeakRef.get();
        if (accessibleAction == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int count = accessibleAction.getAccessibleActionCount();
            if (index >= count) {
                return false;
            }
            synchronized (actionDescriptionsLock) {
                actionDescriptionsCached.put(index, description);
            }
            return true;
        }, false);
    }

    /**
     * Returns the localized name of the specified action of the object.
     * Called from native code via JNI.
     *
     * @param index the action index corresponding to the action
     * @return a localized name string, or null if the action does not exist
     */
    private String get_localized_name(int index) {
        if (index < 0) {
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

        String localizedName;

        synchronized (actionLocalizedNamesLock) {
            localizedName = actionLocalizedNamesCached.get(index);
            if (localizedName != null) {
                return localizedName;
            }
        }

        String computedLocalizedName = AtkUtil.invokeInSwingAndWait(() -> {
            int count = accessibleAction.getAccessibleActionCount();
            if (index >= count) {
                return null;
            }
            String description = accessibleAction.getAccessibleActionDescription(index);
            if (description != null) {
                return description;
            }
            String name = accessibleContext.getAccessibleName();
            if (name != null) {
                return name;
            }
            return "";
        }, null);

        synchronized (actionLocalizedNamesLock) {
            localizedName = actionLocalizedNamesCached.get(index);
            if (localizedName == null) {
                actionLocalizedNamesCached.put(index, computedLocalizedName);
                localizedName = computedLocalizedName;
            }
        }

        return localizedName;
    }
}
