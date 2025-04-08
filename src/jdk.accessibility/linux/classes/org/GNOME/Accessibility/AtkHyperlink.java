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

public class AtkHyperlink {

    private WeakReference<AccessibleHyperlink> _acc_hyperlink;

    private AtkHyperlink(AccessibleHyperlink hl) {
        super();
        _acc_hyperlink = new WeakReference<AccessibleHyperlink>(hl);
    }

    public static AtkHyperlink createAtkHyperlink(AccessibleHyperlink hl) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkHyperlink(hl);
        }, null);
    }

    // JNI upcalls section

    private static AtkHyperlink create_atk_hyperlink(AccessibleHyperlink hl) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkHyperlink(hl);
        }, null);
    }

    private String get_uri(int i) {
        AccessibleHyperlink acc_hyperlink = _acc_hyperlink.get();
        if (acc_hyperlink == null)
            return "";

        return AtkUtil.invokeInSwingAndWait(() -> {
            Object o = acc_hyperlink.getAccessibleActionObject(i);
            if (o != null)
                return o.toString();
            return "";
        }, "");
    }

    private AccessibleContext get_object(int i) {
        AccessibleHyperlink acc_hyperlink = _acc_hyperlink.get();
        if (acc_hyperlink == null)
            return null;

        return AtkUtil.invokeInSwingAndWait(() -> {
            Object anchor = acc_hyperlink.getAccessibleActionAnchor(i);
            if (anchor instanceof Accessible accessible) {
                AccessibleContext accessibleContext = accessible.getAccessibleContext();
                AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
                return accessibleContext;
            }
            return null;
        }, null);
    }

    private int get_end_index() {
        AccessibleHyperlink acc_hyperlink = _acc_hyperlink.get();
        if (acc_hyperlink == null)
            return 0;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_hyperlink.getEndIndex();
        }, 0);
    }

    private int get_start_index() {
        AccessibleHyperlink acc_hyperlink = _acc_hyperlink.get();
        if (acc_hyperlink == null)
            return 0;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_hyperlink.getStartIndex();
        }, 0);
    }

    private boolean is_valid() {
        AccessibleHyperlink acc_hyperlink = _acc_hyperlink.get();
        if (acc_hyperlink == null)
            return false;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_hyperlink.isValid();
        }, false);
    }

    private int get_n_anchors() {
        AccessibleHyperlink acc_hyperlink = _acc_hyperlink.get();
        if (acc_hyperlink == null)
            return 0;

        return AtkUtil.invokeInSwingAndWait(() -> {
            return acc_hyperlink.getAccessibleActionCount();
        }, 0);
    }
}
