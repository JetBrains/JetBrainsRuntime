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

#include "VKTypes.h"

typedef struct VKShaders VKShaders;
typedef struct VKPipelineSet VKPipelineSet;

/**
 * All pipeline types, use these to index into VKPipelineSet.pipelines.
 */
typedef enum {
    PIPELINE_FILL_COLOR = 0,
    PIPELINE_DRAW_COLOR = 1,
    PIPELINE_BLIT = 2,
    PIPELINE_COUNT = 3
} VKPipeline;

/**
 * Global pipeline context.
 */
struct VKPipelineContext {
    VKDevice*             device;
    VkPipelineLayout      pipelineLayout;
    VkPipelineLayout      texturePipelineLayout;
    VkDescriptorSetLayout textureDescriptorSetLayout;

    VkSampler             linearRepeatSampler;

    VKShaders*            shaders;
    VKRenderPassContext** renderPassContexts;
};

/**
 * Per-format context.
 */
struct VKRenderPassContext {
    VKPipelineContext* pipelineContext;
    VkFormat           format;
    VkRenderPass       renderPass;
    VKPipelineSet*     pipelineSet; // TODO we will need a real hash map for this in the future.
};

typedef struct {
    float x, y;
} VKVertex;

typedef struct {
    float px, py;
    float u, v;
} VKTxVertex;

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device);
void VKPipelines_DestroyContext(VKPipelineContext* pipelines);

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format);
VkPipeline VKPipelines_GetPipeline(VKRenderPassContext* renderPassContext, VKPipeline pipeline);

#endif //VKPipelines_h_Included
