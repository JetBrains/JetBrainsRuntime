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
import javax.swing.*;
import java.lang.ref.WeakReference;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

public class AtkAction {

    private WeakReference<AccessibleContext> _ac;
    private WeakReference<AccessibleAction> _acc_action;
    private WeakReference<AccessibleExtendedComponent> _acc_ext_component;
    private String[] descriptions;
    private int nactions;

    private AtkAction(AccessibleContext ac) {
        super();
        this._ac = new WeakReference<AccessibleContext>(ac);
        AccessibleAction acc_action = ac.getAccessibleAction();
        this._acc_action = new WeakReference<AccessibleAction>(acc_action);
        this.nactions = acc_action.getAccessibleActionCount();
        this.descriptions = new String[nactions];
        AccessibleComponent acc_component = ac.getAccessibleComponent();
        if (acc_component instanceof AccessibleExtendedComponent accessibleExtendedComponent) {
            this._acc_ext_component = new WeakReference<AccessibleExtendedComponent>(accessibleExtendedComponent);
        }
    }

    private static String keyStrokeToShortcut(KeyStroke keyStroke) {
        String keybinding = "";

        if (keyStroke.getModifiers() != 0) {
            // a String describing the extended modifier keys and mouse buttons, such as "Shift", "Button1", or "Ctrl+Shift"
            String modString = KeyEvent.getModifiersExText(keyStroke.getModifiers());
            keybinding += modString;
        }
        if (keyStroke.getModifiers() != 0 && keyStroke.getKeyCode() != 0) {
            keybinding += "+";
        }
        if (keyStroke.getKeyCode() != 0) {
            // a String describing the keyCode, such as "HOME", "F1" or "A".
            String keyString = KeyEvent.getKeyText(keyStroke.getKeyCode());
            keybinding += keyString;
        }

        return keybinding;
    }

    // JNI upcalls section

    private static AtkAction create_atk_action(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkAction(ac);
        }, null);
    }

    private boolean do_action(int i) {
        if (i < 0) {
            return false;
        }
        AccessibleAction acc_action = _acc_action.get();
        if (acc_action == null)
            return false;

        AtkUtil.invokeInSwing(() -> {
            acc_action.doAccessibleAction(i);
        });
        return true;
    }

    private int get_n_actions() {
        return this.nactions;
    }

    private String get_description(int i) {
        if (i < 0) {
            return null;
        }
        AccessibleAction acc_action = _acc_action.get();
        if (acc_action == null)
            return null;

        if (i >= nactions) {
            return null;
        }
        if (descriptions[i] != null) {
            return descriptions[i];
        }
        descriptions[i] = AtkUtil.invokeInSwingAndWait(() -> {
            return acc_action.getAccessibleActionDescription(i);
        }, "");
        return descriptions[i];
    }

    private boolean set_description(int i, String description) {
        if (i >= nactions || i < 0) {
            return false;
        }
        descriptions[i] = description;
        return true;
    }

    /**
     * @param i an integer holding the index of the name of
     *          the accessible.
     * @return the localized name of the object or otherwise,
     * null if the "action" object does not have a
     * name (really, java's AccessibleAction class
     * does not provide
     * a getter for an AccessibleAction
     * name so a getter from the AcccessibleContext
     * class is one way to work around that)
     */
    private String get_localized_name(int i) {
        if (i < 0) {
            return null;
        }
        AccessibleContext ac = _ac.get();
        if (ac == null)
            return null;
        AccessibleAction acc_action = _acc_action.get();
        if (acc_action == null)
            return null;

        if (i >= nactions) {
            return null;
        }
        if (descriptions[i] != null) {
            return descriptions[i];
        }
        return AtkUtil.invokeInSwingAndWait(() -> {
            descriptions[i] = acc_action.getAccessibleActionDescription(i);
            if (descriptions[i] != null)
                return descriptions[i];
            String name = ac.getAccessibleName();
            if (name != null)
                return name;
            descriptions[i] = "";
            return descriptions[i];
        }, null);
    }

    /*
     * Gets the keybinding which can be used to activate the action, if one exists.
     * The string returned should contain localized, human-readable, key sequences
     * as they would appear when displayed on screen.
     * It must be in the format "mnemonic;sequence;shortcut".
     *
     * @return null if there is no keybinding
     */
    private String get_keybinding(int index) {
        // TODO: implement
        //  There is no dependency between javax.accessibility.AccessibleAction and keybindings,
        //  so there is no way to return the correct keybinding based on AccessibleContext.
        return null;
    }
}
