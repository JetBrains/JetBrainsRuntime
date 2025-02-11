// Copyright 2024 JetBrains s.r.o.
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

#ifndef VKUtil_h_Included
#define VKUtil_h_Included
#include <stdlib.h>
#include <vulkan/vulkan.h>
#include <Trace.h>
#include "awt.h"
#include "jni_util.h"
#include "VKTypes.h"

#define C_ARRAY_UTIL_ALLOCATION_FAILED() VK_FATAL_ERROR("CArrayUtil allocation failed")
#include "CArrayUtil.h"

// Useful logging & result checking macros
void VKUtil_LogResultError(const char* string, VkResult result);
inline VkBool32 VKUtil_CheckError(VkResult result, const char* errorMessage) {
    if (result != VK_SUCCESS) {
        VKUtil_LogResultError(errorMessage, result);
        return VK_TRUE;
    } else return VK_FALSE;
}
// Hack for converting __LINE__ to string taken from here: https://stackoverflow.com/a/19343239
#define TO_STRING_HACK(T) #T
#define TO_STRING(T) TO_STRING_HACK(T)
#define LOCATION __FILE__ ": " TO_STRING(__LINE__)

#define VK_IF_ERROR(EXPR) if (VKUtil_CheckError(EXPR, #EXPR " == %s\n    at " LOCATION))

#define VK_FATAL_ERROR(MESSAGE) do {                              \
    J2dRlsTraceLn(J2D_TRACE_ERROR, MESSAGE "\n    at " LOCATION); \
    JNIEnv* env = (JNIEnv*)JNU_GetEnv(jvm, JNI_VERSION_1_2);      \
    if (env != NULL) JNU_RUNTIME_ASSERT(env, 0, (MESSAGE));       \
    else abort();                                                 \
} while(0)
#define VK_UNHANDLED_ERROR() VK_FATAL_ERROR("Unhandled Vulkan error")
#define VK_RUNTIME_ASSERT(...) if (!(__VA_ARGS__)) VK_FATAL_ERROR("Vulkan assertion failed: " #__VA_ARGS__)

/**
 * Vulkan expects linear colors.
 * However Java2D expects legacy behavior, as if colors were blended in sRGB color space.
 * Therefore this function just remaps color components from [0, 255] to [0, 1] range,
 * they still represent sRGB color.
 * This is also accounted for in VKSD_ConfigureWindowSurface, so that Vulkan doesn't do any
 * color space conversions on its own, as the colors we are drawing are already in sRGB.
 */
Color VKUtil_DecodeJavaColor(uint32_t color);

#endif //VKUtil_h_Included
