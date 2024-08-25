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

// VK_DEBUG_RANDOM may be used to randomly tune some parameters and turn off some features,
// which would allow to cover wider range of scenarios and catch configuration-specific errors early.
// In debug builds it returns 1 with approximately CHANCE_PERCENT chance, on release builds it is always 0.
// When using this macro, make sure to leave sufficient info in the log to track failing configurations.
#ifdef DEBUG
#define VK_DEBUG_RANDOM(CHANCE_PERCENT) ((rand() & 100) < CHANCE_PERCENT)
#else
#define VK_DEBUG_RANDOM(CHANCE_PERCENT) 0
#endif

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

typedef struct {
    VkFormat unorm;
    VkFormat snorm;
    VkFormat uscaled;
    VkFormat sscaled;
    VkFormat uint;
    VkFormat sint;
    VkFormat srgb;
    VkFormat sfloat;
    uint     bytes;
} FormatGroup;

/**
 * Convert 32-bit sRGB encoded straight-alpha Java color to [0, 1] normalized
 * floating-point representation with pre-multiplied alpha in both linear and sRGB color space.
 *
 * Vulkan always works with LINEAR colors, so great care must be taken to use the right encoding.
 * If you ever need to use sRGB encoded color, leave the detailed comment explaining this decision.
 *
 * Read more about presenting sRGB content in VKSD_ConfigureWindowSurface.
 *
 * Note: we receive colors from Java with straight (non-premultiplied) alpha, which is unusual.
 * This is controlled by PixelConverter parameter of SurfaceType, see VKSurfaceData.java.
 * This is done to prevent precision loss and redundant conversions,
 * as we need straight alpha to convert from sRGB to liner color space anyway.
 */
Color VKUtil_DecodeJavaColor(uint32_t color);

/**
 * Get group of formats with the same component layout.
 */
FormatGroup VKUtil_GetFormatGroup(VkFormat format);

/**
 * Integer log2, the same as index of highest set bit.
 * https://graphics.stanford.edu/~seander/bithacks.html#IntegerLogLookup
 */
inline uint32_t VKUtil_Log2(uint64_t i) {
    static const char LogTable256[256] = {
#define LT(n) n, n, n, n, n, n, n, n, n, n, n, n, n, n, n, n
            -1, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3,
            LT(4), LT(5), LT(5), LT(6), LT(6), LT(6), LT(6),
            LT(7), LT(7), LT(7), LT(7), LT(7), LT(7), LT(7), LT(7) };
    register uint32_t t;
    if      (t = i >> 56) return 56 + LogTable256[t];
    else if (t = i >> 48) return 48 + LogTable256[t];
    else if (t = i >> 40) return 40 + LogTable256[t];
    else if (t = i >> 32) return 32 + LogTable256[t];
    else if (t = i >> 24) return 24 + LogTable256[t];
    else if (t = i >> 16) return 16 + LogTable256[t];
    else if (t = i >>  8) return  8 + LogTable256[t];
    else                  return      LogTable256[i];
}

#endif //VKUtil_h_Included
