/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.awt;

import java.awt.Component;
import java.awt.Window;

public class AWTUtilities {
    /**
     * Get the closest window ancestor of a component (including, potentially, the component itself).
     *
     * @param c The component.
     * @return The closest Window ancestor of the given Component (including itself), or null if none.
     */
    public static Window getWindowThisOrAncestor(Component c) {
        for(; c != null; c = c.getParent()) {
            if (c instanceof Window w) {
                return w;
            }
        }
        return null;
    }

    /**
     * Call updateWindow() on the closest window ancestor of a component (including, potentially, the component itself).
     *
     * @param c The component.
     */
    public static void updateWindowThisOrAncestor(Component c) {
        Window w = getWindowThisOrAncestor(c);
        if (w != null) {
            AWTAccessor.getWindowAccessor().updateWindow(w);
        }
    }
}
