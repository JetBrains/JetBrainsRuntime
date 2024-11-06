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

public class AtkImage {

	WeakReference<AccessibleContext> _ac;
	WeakReference<AccessibleIcon[]> _acc_icons;

	public AtkImage (AccessibleContext ac) {
		super();
		this._ac = new WeakReference<AccessibleContext>(ac);
		this._acc_icons = new WeakReference<AccessibleIcon[]>(ac.getAccessibleIcon());
	}

	public static AtkImage createAtkImage(AccessibleContext ac){
		return AtkUtil.invokeInSwing ( () -> { return new AtkImage(ac); }, null);
	}

	public Point get_image_position (int coord_type) {
		AccessibleContext ac = _ac.get();
		if (ac == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			AccessibleComponent acc_component = ac.getAccessibleComponent();
			if (acc_component == null)
				return null;
			return AtkComponent.getComponentOrigin(ac, acc_component, coord_type);
		}, null);
	}

	public String get_image_description () {
		AccessibleIcon[] acc_icons = _acc_icons.get();
		if (acc_icons == null)
			return "";

		return AtkUtil.invokeInSwing ( () -> {
			String desc = "";
			if (acc_icons != null && acc_icons.length > 0) {
				desc = acc_icons[0].getAccessibleIconDescription();
				if (desc == null)
					desc = "";
			}
			return desc;
		}, "");
	}

	public Dimension get_image_size () {
		Dimension d = new Dimension(0, 0);

		AccessibleContext ac = _ac.get();
		if (ac == null)
			return d;

		AccessibleIcon[] acc_icons = _acc_icons.get();

		return AtkUtil.invokeInSwing ( () -> {
			if (acc_icons != null && acc_icons.length > 0) {
				d.height = acc_icons[0].getAccessibleIconHeight();
				d.width = acc_icons[0].getAccessibleIconWidth();
			} else {
				AccessibleComponent acc_component = ac.getAccessibleComponent();
				if (acc_component != null) {
					Rectangle rect = acc_component.getBounds();
					if (rect != null) {
						d.height = rect.height;
						d.width = rect.width;
					}
				}
			}
			return d;
		},d);
	}
}
