/*
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

#include "sun_awt_wl_im_text_input_unstable_v3_WLInputMethodDescriptorZwpTextInputV3.h"
#include "WLToolkit.h" // zwp_text_input_manager, zwp_text_input_manager_*

#include <stdbool.h> // bool, true, false


static bool checkIfTheImplementationIsAvailable()
{
    return (zwp_text_input_manager == NULL) ? false : true;
}


// =============================================== JNI downcalls section ==============================================

JNIEXPORT jboolean JNICALL
Java_sun_awt_wl_im_text_1input_1unstable_1v3_WLInputMethodDescriptorZwpTextInputV3_checkIfAvailableOnPlatform(JNIEnv *env, jclass clazz)
{
    return checkIfTheImplementationIsAvailable() ? JNI_TRUE : JNI_FALSE;
}

// =========================================== END of JNI downcalls section ===========================================
