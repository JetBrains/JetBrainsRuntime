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

public class AtkHypertext extends AtkText {

	WeakReference<AccessibleHypertext> _acc_hyper_text;

	public AtkHypertext (AccessibleContext ac) {
		super(ac);

		AccessibleText ac_text = ac.getAccessibleText();
		if (ac_text instanceof AccessibleHypertext) {
			_acc_hyper_text = new WeakReference<AccessibleHypertext>((AccessibleHypertext)ac_text);
		} else {
			_acc_hyper_text = null;
		}
	}

	public static AtkHypertext createAtkHypertext(AccessibleContext ac){
		return AtkUtil.invokeInSwing ( () -> { return new AtkHypertext(ac); }, null);
	}

	public AtkHyperlink get_link (int link_index) {
		if (_acc_hyper_text == null)
			return null;
		AccessibleHypertext acc_hyper_text = _acc_hyper_text.get();
		if (acc_hyper_text == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			AccessibleHyperlink link = acc_hyper_text.getLink(link_index);
			if (link != null)
				return new AtkHyperlink(link);
			return null;
		}, null);
	}

	public int get_n_links () {
		if (_acc_hyper_text == null)
			return 0;
		AccessibleHypertext acc_hyper_text = _acc_hyper_text.get();
		if (acc_hyper_text == null)
			return 0;

		return AtkUtil.invokeInSwing ( () -> {
			return acc_hyper_text.getLinkCount();
		}, 0);
	}

	public int get_link_index (int char_index) {
		if (_acc_hyper_text == null)
			return 0;
		AccessibleHypertext acc_hyper_text = _acc_hyper_text.get();
		if (acc_hyper_text == null)
			return 0;

		return AtkUtil.invokeInSwing ( () -> {
			return acc_hyper_text.getLinkIndex(char_index);
		}, 0);
	}
}
