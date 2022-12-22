/*
 * Copyright 2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation. Oracle designates this
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

// Created by nikita.gubarkov on 26.01.2023.

#ifndef JBR_CUSTOMTITLEBARCONTROLS_H
#define JBR_CUSTOMTITLEBARCONTROLS_H

#include "awt_Toolkit.h"

class CustomTitleBarControls {
public:

    enum class State {
        NORMAL,
        HOVERED, // "Hot" in Windows theme terminology
        PRESSED, // "Pushed" in Windows theme terminology
        DISABLED,
        INACTIVE, // Didn't find this state in Windows, it represents button in inactive window
        UNKNOWN
    };

    enum class Type {
        MINIMIZE,
        MAXIMIZE,
        RESTORE,
        CLOSE,
        UNKNOWN
    };

    enum class HitType {
        RESET,
        TEST,
        MOVE,
        PRESS,
        RELEASE
    };

private:
    class Resources;
    class Style;

    jweak target;
    HWND parent, hwnd;
    Resources* resources;
    Style* style;
    LRESULT hit;
    bool pressed;
    State windowState;

    CustomTitleBarControls(HWND parent, jweak target, const Style& style);
    void PaintButton(Type type, State state, int x, int width, float scale, bool dark);

public:
    static void Refresh(CustomTitleBarControls*& controls, HWND parent, jobject target, JNIEnv* env);
    void Update(State windowState = State::UNKNOWN);
    LRESULT Hit(HitType type, int ncx, int ncy); // HTNOWHERE / HTMINBUTTON / HTMAXBUTTON / HTCLOSE
    ~CustomTitleBarControls();

};

#endif //JBR_CUSTOMTITLEBARCONTROLS_H
