/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
 *
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

#ifdef HEADLESS
#error This file should not be included in headless library
#endif

#include "screencast_keys.h"
#include "awt.h"
#include "canvas.h"
#include "java_awt_event_KeyEvent.h"
#include <X11/keysymdef.h>

static int getNumpadKey(jint jkey) {
    switch (jkey) {
        case java_awt_event_KeyEvent_VK_NUMPAD0: return XK_KP_Insert;
        case java_awt_event_KeyEvent_VK_NUMPAD1: return XK_KP_End;
        case java_awt_event_KeyEvent_VK_NUMPAD2: return XK_KP_Down;
        case java_awt_event_KeyEvent_VK_NUMPAD3: return XK_KP_Page_Down;
        case java_awt_event_KeyEvent_VK_NUMPAD4: return XK_KP_Left;
        case java_awt_event_KeyEvent_VK_NUMPAD5: return XK_KP_Begin;
        case java_awt_event_KeyEvent_VK_NUMPAD6: return XK_KP_Right;
        case java_awt_event_KeyEvent_VK_NUMPAD7: return XK_KP_Home;
        case java_awt_event_KeyEvent_VK_NUMPAD8: return XK_KP_Up;
        case java_awt_event_KeyEvent_VK_NUMPAD9: return XK_KP_Prior;
        case java_awt_event_KeyEvent_VK_DECIMAL:
        case java_awt_event_KeyEvent_VK_SEPARATOR: return XK_KP_Delete;
        default: return 0;
    }
}

int screencast_getKeySym(JNIEnv *env, jint awtKey) {
    int key = getNumpadKey(awtKey);
    if (!key) {
        AWT_LOCK();
        key = awt_getX11KeySym(awtKey);
        AWT_UNLOCK();
    }
    return key;
}
