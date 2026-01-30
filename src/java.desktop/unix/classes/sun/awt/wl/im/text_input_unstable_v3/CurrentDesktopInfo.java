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

package sun.awt.wl.im.text_input_unstable_v3;

import sun.awt.UNIXToolkit;

import java.awt.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class CurrentDesktopInfo {

    private CurrentDesktopInfo() {}


    public static boolean isGnome() {
        Boolean result = isGnome.get();

        if (result == null) {
            synchronized (CurrentDesktopInfo.class) {
                if (Toolkit.getDefaultToolkit() instanceof UNIXToolkit unixToolkit) {
                    result = "gnome".equals(unixToolkit.getDesktop());
                } else {
                    result = false;
                }

                isGnome.set(result);
            }

            return result;
        }

        return result;
    }
    // {@code null} if not initialized yet
    private static final AtomicReference<Boolean> isGnome = new AtomicReference<>(null);

    /** negative if couldn't obtain or the desktop is not GNOME */
    public static int getGnomeShellMajorVersion() {
        Integer result = gnomeVersion.get();

        if (result == null) {
            synchronized (CurrentDesktopInfo.class) {
                if (!isGnome()) {
                    result = -1;
                } else if (Toolkit.getDefaultToolkit() instanceof UNIXToolkit unixToolkit) {
                    try {
                        @SuppressWarnings("removal")
                        final Integer version = AccessController.doPrivileged(
                            (PrivilegedAction<Integer>)unixToolkit::getGnomeShellMajorVersion
                        );

                        result = Objects.requireNonNullElse(version, -1);
                    } catch (Exception ignored) {
                        result = -1;
                    }
                } else {
                    result = -1;
                }

                gnomeVersion.set(result);
            }

            return result;
        }

        return result;
    }
    // {@code null} if not initialized yet, negative if couldn't obtain or the desktop is not GNOME
    private static final AtomicReference<Integer> gnomeVersion = new AtomicReference<>(null);
}
