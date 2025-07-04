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

public class AtkHypertext extends AtkText {

    private WeakReference<AccessibleHypertext> _acc_hyper_text;

    private AtkHypertext(AccessibleContext ac) {
        super(ac);

        assert EventQueue.isDispatchThread();

        AccessibleText ac_text = ac.getAccessibleText();
        if (ac_text instanceof AccessibleHypertext accessibleHypertext) {
            _acc_hyper_text = new WeakReference<AccessibleHypertext>(accessibleHypertext);
        } else {
            _acc_hyper_text = null;
        }
    }

    // JNI upcalls section

    private static AtkHypertext create_atk_hypertext(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkHypertext(ac);
        }, null);
    }

    private AtkHyperlink get_link(int link_index) {
        if (_acc_hyper_text == null)
            return null;
        AccessibleHypertext acc_hyper_text = _acc_hyper_text.get();
        if (acc_hyper_text == null)
            return null;

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleHyperlink link = acc_hyper_text.getLink(link_index);
            if (link != null)
                return AtkHyperlink.createAtkHyperlink(link);
            return null;
        }, null);
    }

    private int get_n_links() {
        if (_acc_hyper_text == null)
            return 0;
        AccessibleHypertext acc_hyper_text = _acc_hyper_text.get();
        if (acc_hyper_text == null)
            return 0;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_hyper_text.getLinkCount();
        }, 0);
    }

    private int get_link_index(int char_index) {
        if (_acc_hyper_text == null)
            return 0;
        AccessibleHypertext acc_hyper_text = _acc_hyper_text.get();
        if (acc_hyper_text == null)
            return 0;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_hyper_text.getLinkIndex(char_index);
        }, 0);
    }
}
