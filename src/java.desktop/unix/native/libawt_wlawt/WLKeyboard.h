// Copyright 2023-2025 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#ifndef WLKEYBOARD_H_INCLUDED
#define WLKEYBOARD_H_INCLUDED

#include <stdbool.h>
#include <stdint.h>
#include <jvm_md.h>
#include <jni_util.h>

bool wlInitKeyboard(JNIEnv* env);

struct WLKeyEvent {
    long serial;
    long timestamp;
    int id;
    int keyCode;
    int keyLocation;
    int rawCode;
    int extendedKeyCode;
    uint16_t keyChar;
    int modifiers;
};

void wlHandleKeyboardLeave(void);
void wlSetKeymap(const char* serializedKeymap);
void wlSetKeyState(long serial, long timestamp, uint32_t keycode, bool isPressed);
void wlSetRepeatInfo(int charsPerSecond, int delayMillis);
void wlSetModifiers(uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group);
void wlPostKeyEvent(const struct WLKeyEvent* event);

#endif
