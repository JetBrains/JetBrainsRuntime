/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

import sun.awt.AWTAccessor;

import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import java.awt.Toolkit;
import java.awt.Window;

public class WLRoundedCornersManager {

    public WLRoundedCornersManager() {
    }

    public enum RoundedCornerKind {
        DEFAULT,
        NONE,
        SMALL,
        FULL
    }

    public static int roundCornerRadiusFor(RoundedCornerKind kind) {
        return switch (kind) {
            case DEFAULT -> 12;
            case FULL -> 24;
            case NONE -> 0;
            case SMALL -> 8;
        };
    }

    public static RoundedCornerKind roundedCornerKindFrom(Object o) {
        if (o instanceof String kind) {
            return switch (kind) {
                case "none" -> RoundedCornerKind.NONE;
                case "small" -> RoundedCornerKind.SMALL;
                case "full" -> RoundedCornerKind.FULL;
                default -> RoundedCornerKind.DEFAULT;
            };
        }

        return RoundedCornerKind.DEFAULT;
    }
}

