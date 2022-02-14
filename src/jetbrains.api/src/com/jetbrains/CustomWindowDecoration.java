/*
 * Copyright 2000-2022 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package com.jetbrains;

import java.awt.*;
import java.util.List;
import java.util.Map;

public interface CustomWindowDecoration {

    /*CONST java.awt.Window.*_HIT_SPOT*/
    /*CONST java.awt.Window.*_BUTTON*/
    /*CONST java.awt.Window.MENU_BAR*/

    void setCustomDecorationEnabled(Window window, boolean enabled);
    boolean isCustomDecorationEnabled(Window window);

    void setCustomDecorationHitTestSpots(Window window, List<Map.Entry<Shape, Integer>> spots);
    List<Map.Entry<Shape, Integer>> getCustomDecorationHitTestSpots(Window window);

    void setCustomDecorationTitleBarHeight(Window window, int height);
    int getCustomDecorationTitleBarHeight(Window window);
}
