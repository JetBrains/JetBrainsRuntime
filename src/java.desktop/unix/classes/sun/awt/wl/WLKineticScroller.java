/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

package sun.awt.wl;

import sun.security.action.GetPropertyAction;
import sun.util.logging.PlatformLogger;

import java.security.AccessController;


// TODO: to stop the scrolling when the touchpad is just touched but not pressed,
//       the "pointer-gestures" protocol is required
final class WLKineticScroller {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLKineticScroller");


    public static boolean isAvailable() {
        return enabled;
    }

    public static WLKineticScroller getInstance() {
        if (!isAvailable()) {
            return null;
        }

        if (INSTANCE == null) {
            synchronized (WLKineticScroller.class) {
                if (INSTANCE == null) {
                    INSTANCE = new WLKineticScroller();
                }
            }
        }
        return INSTANCE;
    }


    public void stopScrollingSession() {
        // TODO: implement
    }


    private static final boolean enabled;
    private static volatile WLKineticScroller INSTANCE = null;

    static {
        final String enabledPropertyName = "sun.awt.wl.KineticScrolling.enabled";
        final boolean enabledDefault = true;
        boolean enabledInitializer = enabledDefault;
        try {
            @SuppressWarnings("removal") // still needed for JBR21
            final boolean propertyVal = Boolean.parseBoolean(
                AccessController.doPrivileged(new GetPropertyAction(enabledPropertyName, Boolean.toString(enabledDefault)))
            );
            enabledInitializer = propertyVal;
        } catch (Exception err) {
            log.severe(
                String.format(
                    "Failed to read the value of the system property \"%s\". Assuming the default value(=%b).",
                    enabledPropertyName,
                    enabledDefault
                ),
                err
            );
        } finally {
            enabled = enabledInitializer;
        }
    }
}
