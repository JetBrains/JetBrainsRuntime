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

#include <jni.h>

#include <windows.h>

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_jetbrains_desktop_JBRColorPicker_getPixelColor(JNIEnv *env, jclass unused, jint x, jint y) {
    HDC dc = ::GetDC(NULL);
    COLORREF color = ::GetPixel(dc, (int)x, (int)y);
    ReleaseDC(NULL, dc);
    jint r = (jint)GetRValue(color);
    jint g = (jint)GetGValue(color);
    jint b = (jint)GetBValue(color);
    jclass colorCls = env->FindClass("java/awt/Color");
    jmethodID colorCtrID = env->GetMethodID(colorCls, "<init>", "(III)V");
    jobject colorObj = env->NewObject(colorCls, colorCtrID, r, g, b);
    return colorObj;
}

JNIEXPORT jobject JNICALL
Java_com_jetbrains_desktop_JBRColorPicker_getPixelColorUnderCursor(JNIEnv *env, jclass colorPickerCls) {
    POINT p = { 0, 0 };
    ::GetCursorPos(&p);
    return Java_com_jetbrains_desktop_JBRColorPicker_getPixelColor(env, colorPickerCls, p.x, p.y);
}

} // extern "C"
