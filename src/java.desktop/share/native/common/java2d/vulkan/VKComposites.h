// Copyright 2025 JetBrains s.r.o.
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

#ifndef VKComposites_h_Included
#define VKComposites_h_Included

#include "java_awt_AlphaComposite.h"
#include "VKUtil.h"

/**
 * There are two groups of composite modes:
 * - Logic composite - using logicOp.
 * - Alpha compisite - using blending.
 */
typedef enum {
    LOGIC_COMPOSITE_XOR      = 0,
    LOGIC_COMPOSITE_GROUP    = LOGIC_COMPOSITE_XOR,
    ALPHA_COMPOSITE_CLEAR    = java_awt_AlphaComposite_CLEAR,
    ALPHA_COMPOSITE_SRC      = java_awt_AlphaComposite_SRC,
    ALPHA_COMPOSITE_DST      = java_awt_AlphaComposite_DST,
    ALPHA_COMPOSITE_SRC_OVER = java_awt_AlphaComposite_SRC_OVER,
    ALPHA_COMPOSITE_DST_OVER = java_awt_AlphaComposite_DST_OVER,
    ALPHA_COMPOSITE_SRC_IN   = java_awt_AlphaComposite_SRC_IN,
    ALPHA_COMPOSITE_DST_IN   = java_awt_AlphaComposite_DST_IN,
    ALPHA_COMPOSITE_SRC_OUT  = java_awt_AlphaComposite_SRC_OUT,
    ALPHA_COMPOSITE_DST_OUT  = java_awt_AlphaComposite_DST_OUT,
    ALPHA_COMPOSITE_SRC_ATOP = java_awt_AlphaComposite_SRC_ATOP,
    ALPHA_COMPOSITE_DST_ATOP = java_awt_AlphaComposite_DST_ATOP,
    ALPHA_COMPOSITE_XOR      = java_awt_AlphaComposite_XOR,
    ALPHA_COMPOSITE_GROUP    = ALPHA_COMPOSITE_XOR,
    NO_COMPOSITE             = 0x7FFFFFFF
} VKCompositeMode;
#define COMPOSITE_GROUP(COMPOSITE) (                               \
    (COMPOSITE) <= LOGIC_COMPOSITE_GROUP ? LOGIC_COMPOSITE_GROUP : \
    (COMPOSITE) <= ALPHA_COMPOSITE_GROUP ? ALPHA_COMPOSITE_GROUP : \
    NO_COMPOSITE )

typedef struct {
    VKCompositeMode mode;
    VkBool32        dstOpaque;
} VKCompositeDescriptor;

typedef struct {
    VkPipelineColorBlendAttachmentState attachmentState;
    VkPipelineColorBlendStateCreateInfo blendState;
    AlphaType                           outAlphaType;
} VKCompositeState;

typedef struct {
    MAP(VKCompositeDescriptor, VKCompositeState) map;
} VKComposites;

VKComposites VKComposites_Create();
void VKComposites_Destroy(VKComposites composites);
void VKComposites_AddState(VKComposites* composites, VKCompositeMode mode, VKCompositeState state);
const VKCompositeState* VKComposites_GetState(VKComposites* composites, VKCompositeMode mode, VkBool32 dstOpaque);

#endif //VKComposites_h_Included
