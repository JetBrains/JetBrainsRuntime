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

#ifndef VKTypes_h_Included
#define VKTypes_h_Included
#include <vulkan/vulkan.h>

/**
 * Floating-point RGBA color components with pre-multiplied alpha.
 * May be encoded as either linear, or sRGB depending on the context (see Color.linear, Color.nonlinearSrgb).
 */
typedef union {
    struct {
        float r, g, b, a;
    };
    VkClearValue vkClearValue;
} RGBAColorComponents;

/**
 * Floating-point RGBA color with pre-multiplied alpha in both linear and sRGB encoding.
 *
 * Vulkan always works with LINEAR colors, so great care must be taken to use the right encoding.
 * If you ever need to use sRGB encoded color, leave the detailed comment explaining this decision.
 *
 * Read more about presenting sRGB content in VKSD_ConfigureWindowSurface.
 * Read more about conversion from Java colors in VKUtil_DecodeJavaColor.
 */
typedef struct {
    RGBAColorComponents linear;
    RGBAColorComponents nonlinearSrgb;
} Color;

VK_DEFINE_NON_DISPATCHABLE_HANDLE(VKMemory);

typedef struct {
    VkImage  handle;
    VKMemory memory;
} VKImage;

typedef struct {
    VkBuffer handle;
    VKMemory memory;
} VKBuffer;

#define STRUCT(NAME) typedef struct NAME NAME

typedef char* pchar;

STRUCT(VKGraphicsEnvironment);
STRUCT(VKDevice);
STRUCT(VKAllocator);
STRUCT(VKRenderer);
STRUCT(VKRenderPass);
STRUCT(VKRenderingContext);
STRUCT(VKPipelines);
STRUCT(VKShaders);
STRUCT(VKSDOps);
STRUCT(VKWinSDOps);

#undef STRUCT

#endif //VKTypes_h_Included
