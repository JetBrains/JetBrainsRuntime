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

#include "VKComposites.h"
#include "VKSamplers.h"
#include "VKTypes.h"
#include "VKUtil.h"

#define CLIP_STENCIL_INCLUDE_VALUE 0x80U
#define CLIP_STENCIL_EXCLUDE_VALUE 0U

/**
 * Shader programs.
 */
typedef enum {
    SHADER_COLOR,
    SHADER_MASK_FILL_COLOR,
    SHADER_BLIT,
    SHADER_CLIP,
    NO_SHADER = 0x7FFFFFFF
} VKShader;

typedef enum {
    STENCIL_MODE_NONE = 0, // No stencil attachment.
    STENCIL_MODE_OFF  = 1, // Has stencil attachment, stencil test disabled.
    STENCIL_MODE_ON   = 2  // Has stencil attachment, stencil test enabled.
} VKStencilMode;

/**
 * All features describing a pipeline.
 * When adding new fields, update hash and comparison in VKPipelines.c.
 */
typedef struct {
    VKStencilMode       stencilMode : 2;
    VkBool32            dstOpaque   : 1;
    AlphaType           inAlphaType : 1;
    VKCompositeMode     composite;
    VKShader            shader;
    VkPrimitiveTopology topology;
} VKPipelineDescriptor;

typedef struct {
    VkPipeline pipeline;
    AlphaType  outAlphaType;
} VKPipelineInfo;

/**
 * Global pipeline context.
 */
struct VKPipelineContext {
    VKDevice*                   device;
    VkPipelineLayout            colorPipelineLayout;
    VkDescriptorSetLayout       textureDescriptorSetLayout;
    VkPipelineLayout            texturePipelineLayout;
    VkDescriptorSetLayout       maskFillDescriptorSetLayout;
    VkPipelineLayout            maskFillPipelineLayout;

    VKSamplers                  samplers;
    struct VKShaders*           shaders;
    ARRAY(VKRenderPassContext*) renderPassContexts;
};

/**
 * Per-format context.
 */
struct VKRenderPassContext {
    VKPipelineContext* pipelineContext;
    VkFormat           format;
    VkRenderPass       renderPass[2]; // Color-only and color+stencil.
    MAP(VKPipelineDescriptor, VKPipelineInfo) pipelines;
};

typedef struct {
    int x, y;
} VKIntVertex;

typedef struct {
    float x, y;
    RGBA color;
} VKColorVertex;

typedef struct {
    float px, py;
    float u, v;
} VKTxVertex;

typedef struct {
    int x, y, maskOffset, maskScanline;
    RGBA color;
} VKMaskFillColorVertex;

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device);
void VKPipelines_DestroyContext(VKPipelineContext* pipelines);

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format);
VKPipelineInfo VKPipelines_GetPipelineInfo(VKRenderPassContext* renderPassContext, VKPipelineDescriptor descriptor);

#endif //VKPipelines_h_Included
