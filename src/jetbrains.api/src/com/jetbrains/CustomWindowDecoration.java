/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
