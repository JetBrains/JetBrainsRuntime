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
    // Base shaders.
    SHADER_COLOR,
    SHADER_GRADIENT,
    SHADER_BLIT,
    SHADER_CLIP,
    NO_SHADER = 0x7FFFFFFF,
    // Mask modifier bit (MASK_FILL & MASK_BLIT).
    SHADER_MASK = ~NO_SHADER
} VKShader;

/**
 * Shader variant.
 * It is used to specialize shader behavior, and its meaning varies with each particular shader.
 */
typedef enum {
    SHADER_VARIANT_GRADIENT_CLAMP = 0,
    SHADER_VARIANT_GRADIENT_CYCLE = 1,
    NO_SHADER_VARIANT = 0x7FFFFFFF
} VKShaderVariant;

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
    VKShaderVariant     shaderVariant;
    VkPrimitiveTopology topology;
} VKPipelineDescriptor;

typedef struct {
    VkPipeline       pipeline;
    VkPipelineLayout layout;
    AlphaType        outAlphaType;
} VKPipelineInfo;

/**
 * Global pipeline context.
 */
struct VKPipelineContext {
    VKDevice*                   device;
    VkPipelineLayout            commonPipelineLayout;
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
    unsigned int xorColor;
    float extraAlpha;
} VKCompositeConstants;

typedef struct {
    RGBA c0, c1;
    float p0, p1, p3;
} VKGradientPaintConstants;

typedef union {
    // The minimum guaranteed size of push constants is 128 bytes.
    alignas(32) // The maximum alignment for built-in glsl types is 32 bytes (dvec4).
    char data[(128 - sizeof(VKTransform) - sizeof(VKCompositeConstants)) / 32 * 32];
    VKGradientPaintConstants gradientPaint;
} VKShaderConstants;
#define MAX_SHADER_CONSTANTS_SIZE 96 // We expect 96 bytes
typedef char VKShaderConstantsCheckOffset[sizeof(VKShaderConstants) == MAX_SHADER_CONSTANTS_SIZE ? 1 : -1]; // Verify.

typedef struct {
    VKTransform transform;
    VKCompositeConstants composite;
    VKShaderConstants shader;
} VKPushConstants;
typedef char VKPushConstantsCheckSize[sizeof(VKPushConstants) <= 128 ? 1 : -1]; // We should not exceed 128 bytes.
static const uint32_t PUSH_CONSTANTS_OFFSET = (uintptr_t) &((VKPushConstants*) NULL)->composite;
static const uint32_t PUSH_CONSTANTS_SIZE = sizeof(VKPushConstants) - PUSH_CONSTANTS_OFFSET;

typedef struct {
    int x, y;
} VKIntVertex;

typedef struct {
    float x, y;
    unsigned int data;
} VKVertex;

typedef struct {
    float px, py;
    float u, v;
} VKTxVertex;

typedef struct {
    int x, y, maskOffset, maskScanline;
    unsigned int data;
} VKMaskFillVertex;

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device);
void VKPipelines_DestroyContext(VKPipelineContext* pipelines);

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format);
VKPipelineInfo VKPipelines_GetPipelineInfo(VKRenderPassContext* renderPassContext, VKPipelineDescriptor descriptor);

#endif //VKPipelines_h_Included
