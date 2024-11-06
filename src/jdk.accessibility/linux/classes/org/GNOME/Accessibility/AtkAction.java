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

	WeakReference<AccessibleContext> _ac;
	WeakReference<AccessibleAction> _acc_action;
	WeakReference<AccessibleExtendedComponent> _acc_ext_component;
	String[] descriptions;
	int nactions;

	public AtkAction (AccessibleContext ac) {
		super();
		this._ac = new WeakReference<AccessibleContext>(ac);
		AccessibleAction acc_action = ac.getAccessibleAction();
		this._acc_action = new WeakReference<AccessibleAction>(acc_action);
		this.nactions = acc_action.getAccessibleActionCount();
		this.descriptions = new String[nactions];
		AccessibleComponent acc_component = ac.getAccessibleComponent();
		if (acc_component instanceof AccessibleExtendedComponent) {
			this._acc_ext_component = new WeakReference<AccessibleExtendedComponent>((AccessibleExtendedComponent)acc_component);
		}
	}

	public static AtkAction createAtkAction(AccessibleContext ac){
		return AtkUtil.invokeInSwing ( () -> { return new AtkAction(ac); }, null);
	}

	public boolean do_action (int i) {
		AccessibleAction acc_action = _acc_action.get();
		if (acc_action == null)
			return false;

		AtkUtil.invokeInSwing( () -> { acc_action.doAccessibleAction(i); });
		return true;
	}

	public int get_n_actions () { return this.nactions; }

	public String get_description (int i) {
		AccessibleAction acc_action = _acc_action.get();
		if (acc_action == null)
			return null;

		if (i >= nactions){
			return null;
		}
		if(descriptions[i] != null){
			return descriptions[i];
		}
		descriptions[i] = AtkUtil.invokeInSwing( () -> { return acc_action.getAccessibleActionDescription(i); }, "");
		return descriptions[i];
	}
	
	public boolean setDescription(int i, String description) {
		if (i >= nactions){
			return false;
		}
		descriptions[i] = description;
		return true;
	}

 /**
  * @param i an integer holding the index of the name of
  *          the accessible.
  * @return  the localized name of the object or otherwise,
  *          null if the "action" object does not have a
  *          name (really, java's AccessibleAction class
  *          does not provide
  *          a getter for an AccessibleAction
  *          name so a getter from the AcccessibleContext
  *          class is one way to work around that)
  */
  	public String getLocalizedName (int i) {
		AccessibleContext ac = _ac.get();
		if (ac == null)
			return null;
		AccessibleAction acc_action = _acc_action.get();
		if (acc_action == null)
			return null;

		if (i >= nactions){
			return null;
		}
		if (descriptions[i] != null){
			return descriptions[i];
		}
		return AtkUtil.invokeInSwing ( () -> {
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

	private String convertModString (String mods) {
		if (mods == null || mods.length() == 0) {
			return "";
		}

		String modStrs[] = mods.split("\\+");
		String newModString = "";
		for (int i = 0; i < modStrs.length; i++) {
			newModString += "<" + modStrs[i] + ">";
		}

		return newModString;
	}

	public String get_keybinding (int index) {
		AccessibleExtendedComponent acc_ext_component;
		if (_acc_ext_component == null)
			return "";

		acc_ext_component = _acc_ext_component.get();

		// TODO: improve/fix conversion to strings, concatenate,
		//       and follow our formatting convention for the role of
		//       various keybindings (i.e. global, transient, etc.)

		//
		// Presently, JAA doesn't define a relationship between the index used
		// and the action associated. As such, all keybindings are only
		// associated with the default (index 0 in GNOME) action.
		//
		if (index > 0) {
			return "";
		}

		if(acc_ext_component != null) {
			AccessibleKeyBinding akb = acc_ext_component.getAccessibleKeyBinding();

			if (akb != null && akb.getAccessibleKeyBindingCount() > 0) {
				String  rVal = "";
				int     i;

				// Privately Agreed interface with StarOffice to workaround
				// deficiency in JAA.
				//
				// The aim is to use an array of keystrokes, if there is more
				// than one keypress involved meaning that we would have:
				//
				//	KeyBinding(0)    -> nmeumonic       KeyStroke
				//	KeyBinding(1)    -> full key path   KeyStroke[]
				//	KeyBinding(2)    -> accelerator     KeyStroke
				//
				// GNOME Expects a string in the format:
				//
				//	<nmemonic>;<full-path>;<accelerator>
				//
				// The keybindings in <full-path> should be separated by ":"
				//
				// Since only the first three are relevant, ignore others
				for (i = 0;( i < akb.getAccessibleKeyBindingCount() && i < 3); i++) {
					Object o = akb.getAccessibleKeyBinding(i);

					if ( i > 0 ) {
						rVal += ";";
					}

					if (o instanceof KeyStroke) {
						KeyStroke keyStroke = (KeyStroke)o;
						String modString = InputEvent.getModifiersExText(keyStroke.getModifiers());
						String keyString = KeyEvent.getKeyText(keyStroke.getKeyCode());

						if ( keyString != null ) {
							if ( modString != null && modString.length() > 0 ) {
								rVal += convertModString(modString) + keyString;
							} else {
								rVal += keyString;
							}
						}
					} else if (o instanceof KeyStroke[]) {
						KeyStroke[] keyStroke = (KeyStroke[])o;
						for ( int j = 0; j < keyStroke.length; j++ ) {
							String modString = InputEvent.getModifiersExText(keyStroke[j].getModifiers());
							String keyString = KeyEvent.getKeyText(keyStroke[j].getKeyCode());

							if (j > 0) {
								rVal += ":";
							}

							if ( keyString != null ) {
								if (modString != null && modString.length() > 0) {
									rVal += convertModString(modString) + keyString;
								} else {
									rVal += keyString;
								}
							}
						}
					}
				}

				if ( i < 2 ) rVal += ";";
				if ( i < 3 ) rVal += ";";

				return rVal;
			}
		}

		return "";
	}
}
