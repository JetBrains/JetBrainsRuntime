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
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

typedef enum {
    ALPHA_TYPE_PRE_MULTIPLIED = 0,
    ALPHA_TYPE_STRAIGHT = 1
} AlphaType;

/**
 * Floating-point RGBA color in unspecified color space and alpha type.
 */
typedef union {
    struct {
        float r, g, b, a;
    };
    VkClearValue vkClearValue;
} RGBA;

/**
 * Floating-point encoding-agnostic color.
 */
typedef struct {
    RGBA values[ALPHA_TYPE_STRAIGHT+1];
} Color;

/**
 * Transform matrix
 * [ x']   [  m00  m01  m02  ] [ x ]   [ m00x + m01y + m02 ]
 * [ y'] = [  m10  m11  m12  ] [ y ] = [ m10x + m11y + m12 ]
 * [ 1 ]   [   0    0    1   ] [ 1 ]   [         1         ]
 */
typedef struct {
    float m00, m01, m02;
    float m10 __attribute__((aligned(16))), m11, m12;
} VKTransform;

VK_DEFINE_NON_DISPATCHABLE_HANDLE(VKMemory);

typedef struct VKEnv VKEnv;
typedef struct VKDevice VKDevice;
typedef struct VKAllocator VKAllocator;
typedef struct VKRenderer VKRenderer;
typedef struct VKRenderPass VKRenderPass;
typedef struct VKRenderingContext VKRenderingContext;
typedef struct VKPipelineContext VKPipelineContext;
typedef struct VKRenderPassContext VKRenderPassContext;
typedef struct VKTexelBuffer VKTexelBuffer;
typedef struct VKBuffer VKBuffer;
typedef struct VKImage VKImage;
typedef struct VKSDOps VKSDOps;
typedef struct VKWinSDOps VKWinSDOps;

typedef char* pchar;

#endif //VKTypes_h_Included
