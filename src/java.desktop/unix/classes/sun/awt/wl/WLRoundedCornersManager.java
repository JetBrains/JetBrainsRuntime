/*
 * Copyright 2025 JetBrains s.r.o.
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

import com.jetbrains.exported.JBRApi;
import sun.awt.AWTAccessor;
import sun.awt.RoundedCornersManager;

import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.Window;

public class WLRoundedCornersManager implements RoundedCornersManager {

    public WLRoundedCornersManager() {
        var toolkit = Toolkit.getDefaultToolkit();
        if (toolkit == null || !toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            throw new JBRApi.ServiceNotAvailableException("Supported only with WLToolkit");
        }
    }

    public abstract static class RoundedCornerKind {
        public static final RoundedCornerKind DEFAULT = new DefaultRoundedCorners();
        public static final RoundedCornerKind NONE = new NoRoundedCorners();
        public abstract int radius();
    }

    private final static class DefaultRoundedCorners extends RoundedCornerKind {
        @Override
        public int radius() {
            return 12;
        }
    }

    private final static class NoRoundedCorners extends RoundedCornerKind {
        @Override
        public int radius() {
            return 0;
        }
    }

    public final static class CustomRoundedCorners extends RoundedCornerKind {
        private final int radius;
        private final int borderWidth;
        private final Color borderColor;

        CustomRoundedCorners(int radius) {
            this.radius = radius;
            this.borderWidth = 0;
            this.borderColor = null;
        }

        CustomRoundedCorners(int radius, int borderWidth, Color borderColor) {
            this.radius = radius;
            this.borderWidth = borderWidth;
            this.borderColor = borderColor;
        }

        @Override
        public int radius() {
            return radius;
        }

        public int borderWidth() {
            return borderWidth;
        }

        public Color borderColor() {
            return borderColor;
        }
    }

    public static RoundedCornerKind roundedCornerKindFrom(Object o) {
        if (o instanceof String kind) {
            return switch (kind) {
                case "none" -> RoundedCornerKind.NONE;
                case "small" -> new CustomRoundedCorners(8);
                case "full" -> new CustomRoundedCorners(24);
                default -> RoundedCornerKind.DEFAULT;
            };
        }
        else if (o instanceof Float radius) {
            return new CustomRoundedCorners(Math.round(radius));
        }
        else if (o instanceof Object[] values) {
            if (
                values.length == 3 &&
                values[0] instanceof Float radius &&
                values[1] instanceof Integer borderWidth &&
                values[2] instanceof Color borderColor
            ) {
                return new CustomRoundedCorners(Math.round(radius), borderWidth, borderColor);
            }
        }
        return RoundedCornerKind.DEFAULT;
    }

    @Override
    public void setRoundedCorners(Window window, Object params) {
        Object peer = AWTAccessor.getComponentAccessor().getPeer(window);
        if (peer instanceof WLWindowPeer) {
            RoundedCornerKind kind = roundedCornerKindFrom(params);
            ((WLWindowPeer) peer).setRoundedCornerKind(kind);
        } else if (window instanceof RootPaneContainer) {
            JRootPane rootpane = ((RootPaneContainer)window).getRootPane();
            if (rootpane != null) {
                rootpane.putClientProperty(WLWindowPeer.WINDOW_CORNER_RADIUS, params);
            }
        }
    }
}
