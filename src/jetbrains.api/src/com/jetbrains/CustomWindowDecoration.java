/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

package com.jetbrains;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Custom window decoration allows merging of window content with native title bar,
 * which is usually done by treating title bar as part of client area, but with some
 * special behavior like dragging or maximizing on double click.
 * @implNote Behavior is platform-dependent, only macOS and Windows are supported.
 */
@Deprecated
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


    @SuppressWarnings("all")
    class __Fallback implements CustomWindowDecoration {

        private final Method
                hasCustomDecoration,
                setHasCustomDecoration,
                setCustomDecorationHitTestSpots,
                setCustomDecorationTitleBarHeight;
        private final Field peer;
        private final Class<?> wpeer;

        __Fallback() throws Exception {
            hasCustomDecoration = Window.class.getDeclaredMethod("hasCustomDecoration");
            hasCustomDecoration.setAccessible(true);
            setHasCustomDecoration = Window.class.getDeclaredMethod("setHasCustomDecoration");
            setHasCustomDecoration.setAccessible(true);
            wpeer = Class.forName("sun.awt.windows.WWindowPeer");
            setCustomDecorationHitTestSpots = wpeer.getDeclaredMethod("setCustomDecorationHitTestSpots", List.class);
            setCustomDecorationHitTestSpots.setAccessible(true);
            setCustomDecorationTitleBarHeight = wpeer.getDeclaredMethod("setCustomDecorationTitleBarHeight", int.class);
            setCustomDecorationTitleBarHeight.setAccessible(true);
            peer = Component.class.getDeclaredField("peer");
            peer.setAccessible(true);
        }

        @Override
        public void setCustomDecorationEnabled(Window window, boolean enabled) {
            if (enabled) {
                try {
                    setHasCustomDecoration.invoke(window);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public boolean isCustomDecorationEnabled(Window window) {
            try {
                return (boolean) hasCustomDecoration.invoke(window);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
                return false;
            }
        }

        @Override
        public void setCustomDecorationHitTestSpots(Window window, List<Map.Entry<Shape, Integer>> spots) {
            List<Rectangle> hitTestSpots = spots.stream().map(e -> e.getKey().getBounds()).collect(Collectors.toList());
            try {
                setCustomDecorationHitTestSpots.invoke(wpeer.cast(peer.get(window)), hitTestSpots);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            } catch(ClassCastException | NullPointerException ignore) {}
        }

        @Override
        public List<Map.Entry<Shape, Integer>> getCustomDecorationHitTestSpots(Window window) {
            return List.of();
        }

        @Override
        public void setCustomDecorationTitleBarHeight(Window window, int height) {
            try {
                setCustomDecorationTitleBarHeight.invoke(wpeer.cast(peer.get(window)), height);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            } catch(ClassCastException | NullPointerException ignore) {}
        }

        @Override
        public int getCustomDecorationTitleBarHeight(Window window) {
            return 0;
        }
    }
}
