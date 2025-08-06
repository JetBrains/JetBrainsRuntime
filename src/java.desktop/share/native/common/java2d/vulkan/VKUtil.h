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
#include <Trace.h>
#include "awt.h"
#include "jni_util.h"
#include "VKTypes.h"

// VK_DEBUG_RANDOM may be used to randomly tune some parameters and turn off some features,
// which would allow to cover wider range of scenarios and catch configuration-specific errors early.
// In debug builds it returns 1 with approximately CHANCE_PERCENT chance, on release builds it is always 0.
// When using this macro, make sure to leave sufficient info in the log to track failing configurations.
#ifdef DEBUG
//#define VK_DEBUG_RANDOM(CHANCE_PERCENT) ((rand() % 100) < CHANCE_PERCENT)
// TODO: Disable this functionality for now. We may turn it on at the final stage of the vulkan implementation.
#define VK_DEBUG_RANDOM(CHANCE_PERCENT) (0)
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

#define C_ARRAY_UTIL_ALLOCATION_FAILED() VK_FATAL_ERROR("CArrayUtil allocation failed")
#include "CArrayUtil.h"

#define VK_ID_TRANSFORM ((VKTransform)\
    {1.0f, 0.0f, 0.0f,                \
     0.0f, 1.0f, 0.0f})
//   0.0f, 0.0f, 1.0f  -- omitted values

#define VK_IS_NEQ_TRANSFORM(PA, PB) \
    ((PA)->m00 != (PB)->m00 ||     \
     (PA)->m01 != (PB)->m01 ||     \
     (PA)->m02 != (PB)->m02 ||     \
     (PA)->m10 != (PB)->m10 ||     \
     (PA)->m11 != (PB)->m11 ||     \
     (PA)->m12 != (PB)->m12 )

typedef enum {
    FORMAT_ALIAS_ORIGINAL = 0,
    FORMAT_ALIAS_UNORM    = 1,
    FORMAT_ALIAS_SNORM    = 2,
    FORMAT_ALIAS_USCALED  = 3,
    FORMAT_ALIAS_SSCALED  = 4,
    FORMAT_ALIAS_UINT     = 5,
    FORMAT_ALIAS_SINT     = 6,
    FORMAT_ALIAS_SFLOAT   = 7,
    FORMAT_ALIAS_SRGB     = 8,
    FORMAT_ALIAS_COUNT    = 9
} FormatAlias;

/**
 * Group of format aliases. Use FormatAlias enum values to index into FormatGroup.aliases.
 */
typedef struct {
    VkFormat aliases[FORMAT_ALIAS_COUNT];
    uint     bytes;
} FormatGroup;

/**
 * Vulkan expects linear colors.
 * However Java2D expects legacy behavior, as if colors were blended in sRGB color space.
 * Therefore this function converts straight-alpha Java color in range [0, 255]
 * to pre-multiplied alpha normalized [0, 1] color, still representing sRGB color.
 * This is also accounted for in VKSD_ConfigureWindowSurface, so that Vulkan doesn't do any
 * color space conversions on its own, as the colors we are drawing are already in sRGB.
 *
 * Note: we receive colors from Java with straight (non-premultiplied) alpha, which is done to prevent precision loss.
 * This is controlled by PixelConverter parameter of SurfaceType, see VKSurfaceData.java.
 */
Color VKUtil_DecodeJavaColor(uint32_t color, AlphaType alphaType);

/**
 * Get floating-point RGBA color components with specified AlphaType.
 */
RGBA VKUtil_GetRGBA(Color color, AlphaType alphaType);

/**
 * Integer log2, the same as index of highest set bit.
 */
uint32_t VKUtil_Log2(uint64_t i);

/**
 * Get group of formats with the same component layout.
 */
FormatGroup VKUtil_GetFormatGroup(VkFormat format);

/**
 * Apply src transform to dst
 */
void VKUtil_ConcatenateTransform(VKTransform* dst, const VKTransform* src);

/*
 * The following macros allow the caller to return (or continue) if the
 * provided value is NULL.  (The strange else clause is included below to
 * allow for a trailing ';' after RETURN/CONTINUE_IF_NULL() invocations.)
 */
#define ACT_IF_NULL(ACTION, value)        \
    if ((value) == NULL) {                \
        J2dTraceLn(J2D_TRACE_ERROR,       \
                   "%s is null", #value); \
        ACTION;                           \
    } else do { } while (0)
#define RETURN_IF_NULL(value)   ACT_IF_NULL(return, value)
#define CONTINUE_IF_NULL(value) ACT_IF_NULL(continue, value)

#endif //VKUtil_h_Included
