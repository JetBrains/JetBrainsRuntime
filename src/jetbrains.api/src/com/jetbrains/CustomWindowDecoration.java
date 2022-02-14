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

/**
 * Custom window decoration allows merging of window content with native title bar,
 * which is usually done by treating title bar as part of client area, but with some
 * special behavior like dragging or maximizing on double click.
 * @implNote Behavior is platform-dependent, only macOS and Windows are supported.
 */
public interface CustomWindowDecoration {

    /*CONST java.awt.Window.*_HIT_SPOT*/
    /*CONST java.awt.Window.*_BUTTON*/
    /*CONST java.awt.Window.MENU_BAR*/
    /*CONST java.awt.Window.DRAGGABLE_AREA*/

    /**
     * Turn custom decoration on or off, {@link #setCustomDecorationTitleBarHeight(Window, int)}
     * must be called to configure title bar height.
     */
    void setCustomDecorationEnabled(Window window, boolean enabled);
    /**
     * @see #setCustomDecorationEnabled(Window, boolean)
     */
    boolean isCustomDecorationEnabled(Window window);

    /**
     * Set list of hit test spots for a custom decoration.
     * Hit test spot is a special area inside custom-decorated title bar.
     * Each hit spot type has its own (probably platform-specific) behavior:
     * <ul>
     *     <li>{@link #NO_HIT_SPOT} - default title bar area, can be dragged to move a window, maximizes on double-click</li>
     *     <li>{@link #OTHER_HIT_SPOT} - generic hit spot, window cannot be moved with drag or maximized on double-click</li>
     *     <li>{@link #MINIMIZE_BUTTON} - like {@link #OTHER_HIT_SPOT} but displays tooltip on Windows when hovered</li>
     *     <li>{@link #MAXIMIZE_BUTTON} - like {@link #OTHER_HIT_SPOT} but displays tooltip / snap layout menu on Windows when hovered</li>
     *     <li>{@link #CLOSE_BUTTON} - like {@link #OTHER_HIT_SPOT} but displays tooltip on Windows when hovered</li>
     *     <li>{@link #MENU_BAR} - currently no different from {@link #OTHER_HIT_SPOT}</li>
     *     <li>{@link #DRAGGABLE_AREA} - special type, moves window when dragged, but doesn't maximize on double-click</li>
     * </ul>
     * @param spots pairs of hit spot shapes with corresponding types.
     * @implNote Hit spots are tested in sequence, so in case of overlapping areas, first found wins.
     */
    void setCustomDecorationHitTestSpots(Window window, List<Map.Entry<Shape, Integer>> spots);
    /**
     * @see #setCustomDecorationHitTestSpots(Window, List)
     */
    List<Map.Entry<Shape, Integer>> getCustomDecorationHitTestSpots(Window window);

    /**
     * Set height of custom window decoration, won't take effect until custom decoration
     * is enabled via {@link #setCustomDecorationEnabled(Window, boolean)}.
     */
    void setCustomDecorationTitleBarHeight(Window window, int height);
    /**
     * @see #setCustomDecorationTitleBarHeight(Window, int)
     */
    int getCustomDecorationTitleBarHeight(Window window);
}
