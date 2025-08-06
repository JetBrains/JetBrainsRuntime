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

#ifndef VKPipelines_h_Included
#define VKPipelines_h_Included

#include "java_awt_AlphaComposite.h"
#include "VKTypes.h"
#include "VKUtil.h"

/**
 * All pipeline types.
 */
typedef enum {
    PIPELINE_FILL_COLOR      = 0,
    PIPELINE_DRAW_COLOR      = 1,
    PIPELINE_BLIT            = 2,
    PIPELINE_MASK_FILL_COLOR = 3,
    PIPELINE_COUNT           = 4,
    NO_PIPELINE              = 0x7FFFFFFF
} VKPipeline;

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
    COMPOSITE_COUNT          = ALPHA_COMPOSITE_GROUP + 1,
    NO_COMPOSITE             = 0x7FFFFFFF
} VKCompositeMode;
#define COMPOSITE_GROUP(COMPOSITE) (                               \
    (COMPOSITE) <= LOGIC_COMPOSITE_GROUP ? LOGIC_COMPOSITE_GROUP : \
    (COMPOSITE) <= ALPHA_COMPOSITE_GROUP ? ALPHA_COMPOSITE_GROUP : \
    NO_COMPOSITE )

extern const VkPipelineColorBlendAttachmentState COMPOSITE_BLEND_STATES[COMPOSITE_COUNT];

/**
 * Global pipeline context.
 */
struct VKPipelineContext {
    VKDevice*                   device;
    VkPipelineLayout            pipelineLayout;
    VkDescriptorSetLayout       textureDescriptorSetLayout;
    VkPipelineLayout            texturePipelineLayout;
    VkDescriptorSetLayout       maskFillDescriptorSetLayout;
    VkPipelineLayout            maskFillPipelineLayout;

    VkSampler                   linearRepeatSampler;

    struct VKShaders*           shaders;
    ARRAY(VKRenderPassContext*) renderPassContexts;
};

/**
 * All features describing a pipeline set.
 * When adding new fields, update hash and comparison in VKPipelines_GetPipeline.
 */
typedef struct {
    VKCompositeMode composite;
} VKPipelineSetDescriptor;

/**
 * Per-format context.
 */
struct VKRenderPassContext {
    VKPipelineContext*           pipelineContext;
    VkFormat                     format;
    VkRenderPass                 renderPass;
    MAP(VKPipelineSetDescriptor, struct VKPipelineSet*) pipelineSets;
};

typedef struct {
    float x, y;
    Color color;
} VKColorVertex;

typedef struct {
    float px, py;
    float u, v;
} VKTxVertex;

typedef struct {
    int x, y, maskOffset, maskScanline;
    Color color;
} VKMaskFillColorVertex;

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device);
void VKPipelines_DestroyContext(VKPipelineContext* pipelines);

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format);
VkPipeline VKPipelines_GetPipeline(VKRenderPassContext* renderPassContext, VKPipelineSetDescriptor descriptor, VKPipeline pipeline);

#endif //VKPipelines_h_Included
