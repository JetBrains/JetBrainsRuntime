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

#include "awt.h"

#include "awt_Toolkit.h"

#include <windows.h>

extern "C" {

struct LowLevelMouse {
    static HHOOK llMouseHook;
    static bool listening;
};

bool LowLevelMouse::listening = false;
HHOOK LowLevelMouse::llMouseHook = NULL;

LRESULT CALLBACK MouseLowLevelHook(int code, WPARAM wParam, LPARAM lParam) {
    if (LowLevelMouse::listening) {
        MSLLHOOKSTRUCT *mouseInfo = (MSLLHOOKSTRUCT*)lParam;
        POINT pt = mouseInfo->pt;
        JNIEnv *env = AwtToolkit::GetEnv();
        jclass llMouseCls = env->FindClass("com/jetbrains/desktop/JBRLowLevelMouse");
        switch (wParam) {
            case WM_MOUSEMOVE: {
                jmethodID notifyMouseMovedID = env->GetStaticMethodID(llMouseCls, "notifyMouseMoved", "(II)V");
                env->CallStaticVoidMethod(llMouseCls, notifyMouseMovedID, pt.x, pt.y);
                break;
            }
            case WM_LBUTTONDOWN: {
                jmethodID notifyMouseMovedID = env->GetStaticMethodID(llMouseCls, "notifyMouseClicked", "(II)V");
                env->CallStaticVoidMethod(llMouseCls, notifyMouseMovedID, pt.x, pt.y);
                break;
            }
        }
    }
    return ::CallNextHookEx(LowLevelMouse::llMouseHook, code, wParam, lParam);
}

JNIEXPORT void JNICALL
Java_com_jetbrains_desktop_JBRLowLevelMouse_startListening(JNIEnv *env, jclass unused) {
    LowLevelMouse::llMouseHook = AwtToolkit::GetInstance().InstallCustomMouseLowLevelHook((HOOKPROC)MouseLowLevelHook);
    LowLevelMouse::listening = true;
}

JNIEXPORT void JNICALL
Java_com_jetbrains_desktop_JBRLowLevelMouse_stopListening(JNIEnv *env, jclass unused) {
    AwtToolkit::GetInstance().UninstallCustomMouseLowLevelHook(LowLevelMouse::llMouseHook);
    LowLevelMouse::listening = false;
}

} // extern "C"
