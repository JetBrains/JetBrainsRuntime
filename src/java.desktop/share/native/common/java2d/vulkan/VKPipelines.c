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

#include <assert.h>
#include "VKUtil.h"
#include "VKBase.h"
#include "VKPipelines.h"

#define INCLUDE_BYTECODE
#define SHADER_ENTRY(NAME, TYPE) static uint32_t NAME ## _ ## TYPE ## _data[] = {
#define BYTECODE_END };
#include "vulkan/shader_list.h"
#undef INCLUDE_BYTECODE
#undef SHADER_ENTRY
#undef BYTECODE_END

typedef struct VKPipelineSet {
    VkPipeline pipelines[PIPELINE_COUNT];
} VKPipelineSet;

typedef struct VKShaders {
#   define SHADER_ENTRY(NAME, TYPE) VkPipelineShaderStageCreateInfo NAME ## _ ## TYPE;
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
} VKShaders;

static void VKPipelines_DestroyShaders(VKDevice* device, VKShaders* shaders) {
    assert(device != NULL);
    if (shaders == NULL) return;
#   define SHADER_ENTRY(NAME, TYPE) if (shaders->NAME##_##TYPE.module != VK_NULL_HANDLE) \
    device->vkDestroyShaderModule(device->handle, shaders->NAME##_##TYPE.module, NULL);
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    free(shaders);
}

static VKShaders* VKPipelines_CreateShaders(VKDevice* device) {
    assert(device != NULL);
    const VkShaderStageFlagBits vert = VK_SHADER_STAGE_VERTEX_BIT;
    const VkShaderStageFlagBits frag = VK_SHADER_STAGE_FRAGMENT_BIT;

    VKShaders* shaders = (VKShaders*) calloc(1, sizeof(VKShaders));
    VK_RUNTIME_ASSERT(shaders);
    VkShaderModuleCreateInfo createInfo = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
#   define SHADER_ENTRY(NAME, TYPE)                                   \
    shaders->NAME ## _ ## TYPE = (VkPipelineShaderStageCreateInfo) {  \
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, \
        .stage = (TYPE),                                              \
        .pName = "main"                                               \
    };                                                                \
    createInfo.codeSize = sizeof(NAME ## _ ## TYPE ## _data);         \
    createInfo.pCode = NAME ## _ ## TYPE ## _data;                    \
    VK_IF_ERROR(device->vkCreateShaderModule(device->handle,          \
                &createInfo, NULL, &shaders->NAME##_##TYPE.module)) { \
        VKPipelines_DestroyShaders(device, shaders);                  \
        return NULL;                                                  \
    }
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    return shaders;
}

#define MAKE_INPUT_STATE(NAME, TYPE, ...)                                                         \
static const VkVertexInputAttributeDescription INPUT_STATE_ATTRIBUTES_##NAME[] = { __VA_ARGS__ }; \
static const VkVertexInputBindingDescription INPUT_STATE_BINDING_##NAME = {                       \
        .binding = 0,                                                                             \
        .stride = sizeof(TYPE),                                                                   \
        .inputRate = VK_VERTEX_INPUT_RATE_VERTEX                                                  \
};                                                                                                \
static const VkPipelineVertexInputStateCreateInfo INPUT_STATE_##NAME = {                          \
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,                       \
        .vertexBindingDescriptionCount = 1,                                                       \
        .pVertexBindingDescriptions = &INPUT_STATE_BINDING_##NAME,                                \
        .vertexAttributeDescriptionCount = SARRAY_COUNT_OF(INPUT_STATE_ATTRIBUTES_##NAME),        \
        .pVertexAttributeDescriptions = INPUT_STATE_ATTRIBUTES_##NAME                             \
}

typedef struct {
    VkGraphicsPipelineCreateInfo createInfo;
    VkPipelineMultisampleStateCreateInfo multisampleState;
    VkPipelineColorBlendStateCreateInfo colorBlendState;
    VkPipelineDynamicStateCreateInfo dynamicState;
    VkDynamicState dynamicStates[2];
} PipelineCreateState;

typedef struct {
    VkPipelineShaderStageCreateInfo createInfos[2]; // vert + frag
} ShaderStages;

/**
 * Init default pipeline state. Some members are left uninitialized:
 * - pStages (but stageCount is set to 2)
 * - pVertexInputState
 * - pInputAssemblyState
 * - colorBlendState.pAttachments (but attachmentCount is set to 1)
 * - createInfo.layout
 * - createInfo.renderPass
 * - renderingCreateInfo.pColorAttachmentFormats (but colorAttachmentCount is set to 1)
 */
static void VKPipelines_InitPipelineCreateState(PipelineCreateState* state) {
    static const VkViewport viewport = {};
    static const VkRect2D scissor = {};
    static const VkPipelineViewportStateCreateInfo viewportState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            .viewportCount = 1,
            .pViewports = &viewport,
            .scissorCount = 1,
            .pScissors = &scissor
    };
    static const VkPipelineRasterizationStateCreateInfo rasterizationState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            .polygonMode = VK_POLYGON_MODE_FILL,
            .cullMode = VK_CULL_MODE_NONE,
            .lineWidth = 1.0f
    };
    state->multisampleState = (VkPipelineMultisampleStateCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
    };
    state->colorBlendState = (VkPipelineColorBlendStateCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            .logicOpEnable = VK_FALSE,
            .logicOp = VK_LOGIC_OP_XOR,
            .attachmentCount = 1,
            .pAttachments = NULL,
    };
    state->dynamicState = (VkPipelineDynamicStateCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            .dynamicStateCount = 2,
            .pDynamicStates = state->dynamicStates
    };
    state->dynamicStates[0] = VK_DYNAMIC_STATE_VIEWPORT;
    state->dynamicStates[1] = VK_DYNAMIC_STATE_SCISSOR;
    state->createInfo = (VkGraphicsPipelineCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .stageCount = 2,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizationState,
            .pMultisampleState = &state->multisampleState,
            .pColorBlendState = &state->colorBlendState,
            .pDynamicState = &state->dynamicState,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };
}

static const VkPipelineInputAssemblyStateCreateInfo INPUT_ASSEMBLY_STATE_TRIANGLE_STRIP = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
};
static const VkPipelineInputAssemblyStateCreateInfo INPUT_ASSEMBLY_STATE_TRIANGLE_LIST = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
};
static const VkPipelineInputAssemblyStateCreateInfo INPUT_ASSEMBLY_STATE_LINE_LIST = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST
};

// Blend states are hard-coded, but can also be loaded dynamically to implement custom composites.
#define DEF_BLEND(NAME, SRC_COLOR, DST_COLOR, SRC_ALPHA, DST_ALPHA)       \
{ .blendEnable = VK_TRUE,                                                 \
  .srcColorBlendFactor = VK_BLEND_FACTOR_ ## SRC_COLOR,                   \
  .dstColorBlendFactor = VK_BLEND_FACTOR_ ## DST_COLOR,                   \
  .colorBlendOp = VK_BLEND_OP_ADD,                                        \
  .srcAlphaBlendFactor = VK_BLEND_FACTOR_ ## SRC_ALPHA,                   \
  .dstAlphaBlendFactor = VK_BLEND_FACTOR_ ## DST_ALPHA,                   \
  .alphaBlendOp = VK_BLEND_OP_ADD,                                        \
  .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | \
                    VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT }


const VkPipelineColorBlendAttachmentState COMPOSITE_BLEND_STATES[COMPOSITE_COUNT] = {
        { .blendEnable = VK_FALSE, // LOGIC_XOR
          .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT },
//               NAME ||      SRC_COLOR       |       DST_COLOR      |      SRC_ALPHA       |       DST_ALPHA      ||
DEF_BLEND(|     CLEAR |, ZERO                 , ZERO                 , ZERO                 , ZERO                 ),
DEF_BLEND(|       SRC |, ONE                  , ZERO                 , ONE                  , ZERO                 ),
DEF_BLEND(|  SRC_OVER |, ONE                  , ONE_MINUS_SRC_ALPHA  , ONE                  , ONE_MINUS_SRC_ALPHA  ),
DEF_BLEND(|  DST_OVER |, ONE_MINUS_DST_ALPHA  , ONE                  , ONE_MINUS_DST_ALPHA  , ONE                  ),
DEF_BLEND(|    SRC_IN |, DST_ALPHA            , ZERO                 , DST_ALPHA            , ZERO                 ),
DEF_BLEND(|    DST_IN |, ZERO                 , SRC_ALPHA            , ZERO                 , SRC_ALPHA            ),
DEF_BLEND(|   SRC_OUT |, ONE_MINUS_DST_ALPHA  , ZERO                 , ONE_MINUS_DST_ALPHA  , ZERO                 ),
DEF_BLEND(|   DST_OUT |, ZERO                 , ONE_MINUS_SRC_ALPHA  , ZERO                 , ONE_MINUS_SRC_ALPHA  ),
DEF_BLEND(|       DST |, ZERO                 , ONE                  , ZERO                 , ONE                  ),
DEF_BLEND(|  SRC_ATOP |, DST_ALPHA            , ONE_MINUS_SRC_ALPHA  , ZERO                 , ONE                  ),
DEF_BLEND(|  DST_ATOP |, ONE_MINUS_DST_ALPHA  , SRC_ALPHA            , ONE                  , ZERO                 ),
DEF_BLEND(|       XOR |, ONE_MINUS_DST_ALPHA  , ONE_MINUS_SRC_ALPHA  , ONE_MINUS_DST_ALPHA  , ONE_MINUS_SRC_ALPHA  ),
};

static const VkVertexInputAttributeDescription INPUT_ATTRIBUTE_POSITION = {
        .location = 0,
        .binding = 0,
        .format = VK_FORMAT_R32G32_SFLOAT,
        .offset = 0
};
static const VkVertexInputAttributeDescription INPUT_ATTRIBUTE_COLOR = {
        .location = 1,
        .binding = 0,
        .format = VK_FORMAT_R32G32B32A32_SFLOAT,
        .offset = sizeof(float) * 2
};
static const VkVertexInputAttributeDescription INPUT_ATTRIBUTE_TEXCOORD = {
        .location = 1,
        .binding = 0,
        .format = VK_FORMAT_R32G32_SFLOAT,
        .offset = sizeof(float) * 2
};

MAKE_INPUT_STATE(COLOR_VERTEX, VKColorVertex, INPUT_ATTRIBUTE_POSITION, INPUT_ATTRIBUTE_COLOR);
MAKE_INPUT_STATE(TEXCOORD_VERTEX, VKTxVertex, INPUT_ATTRIBUTE_POSITION, INPUT_ATTRIBUTE_TEXCOORD);

static void VKPipelines_DestroyPipelineSet(VKDevice* device, VKPipelineSet* set) {
    assert(device != NULL);
    if (set == NULL) return;
    for (uint32_t i = 0; i < PIPELINE_COUNT; i++) {
        device->vkDestroyPipeline(device->handle, set->pipelines[i], NULL);
    }
    free(set);
}

static VKPipelineSet* VKPipelines_CreatePipelineSet(VKRenderPassContext* renderPassContext, VKCompositeMode composite) {
    assert(renderPassContext != NULL && renderPassContext->pipelineContext != NULL);
    assert(composite < COMPOSITE_COUNT);
    VKPipelineContext* pipelineContext = renderPassContext->pipelineContext;

    VKPipelineSet* set = calloc(1, sizeof(VKPipelineSet));
    VK_RUNTIME_ASSERT(set);
    VKDevice* device = pipelineContext->device;
    VKShaders* shaders = pipelineContext->shaders;

    // Setup default pipeline parameters.
    PipelineCreateState base;
    VKPipelines_InitPipelineCreateState(&base);
    base.createInfo.layout = pipelineContext->pipelineLayout;
    base.createInfo.renderPass = renderPassContext->renderPass;
    base.colorBlendState.pAttachments = &COMPOSITE_BLEND_STATES[composite];
    if (COMPOSITE_GROUP(composite) == LOGIC_COMPOSITE_GROUP) base.colorBlendState.logicOpEnable = VK_TRUE;
    assert(base.dynamicState.dynamicStateCount <= SARRAY_COUNT_OF(base.dynamicStates));

    ShaderStages stages[PIPELINE_COUNT];
    VkGraphicsPipelineCreateInfo createInfos[PIPELINE_COUNT];
    for (uint32_t i = 0; i < PIPELINE_COUNT; i++) {
        createInfos[i] = base.createInfo;
        createInfos[i].pStages = stages[i].createInfos;
    }

    { // Setup plain color pipelines.
        createInfos[PIPELINE_DRAW_COLOR].pVertexInputState = createInfos[PIPELINE_FILL_COLOR].pVertexInputState = &INPUT_STATE_COLOR_VERTEX;
        createInfos[PIPELINE_FILL_COLOR].pInputAssemblyState = &INPUT_ASSEMBLY_STATE_TRIANGLE_LIST;
        createInfos[PIPELINE_DRAW_COLOR].pInputAssemblyState = &INPUT_ASSEMBLY_STATE_LINE_LIST;
        stages[PIPELINE_DRAW_COLOR] = stages[PIPELINE_FILL_COLOR] = (ShaderStages) {{ shaders->color_vert, shaders->color_frag }};
    }

    { // Setup texture pipeline.
        createInfos[PIPELINE_BLIT].pVertexInputState = &INPUT_STATE_TEXCOORD_VERTEX;
        createInfos[PIPELINE_BLIT].pInputAssemblyState = &INPUT_ASSEMBLY_STATE_TRIANGLE_STRIP;
        createInfos[PIPELINE_BLIT].layout = pipelineContext->texturePipelineLayout;
        stages[PIPELINE_BLIT] = (ShaderStages) {{ shaders->blit_vert, shaders->blit_frag }};
    }

    // Create pipelines.
    // TODO pipeline cache
    VK_IF_ERROR(device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, PIPELINE_COUNT,
                                                  createInfos, NULL, set->pipelines)) VK_UNHANDLED_ERROR();
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreatePipelineSet: composite=%d", composite);
    return set;
}

static VkResult VKPipelines_InitRenderPass(VKDevice* device, VKRenderPassContext* renderPassContext) {
    assert(device != NULL && renderPassContext != NULL);
    VkAttachmentDescription colorAttachment = {
            .format = renderPassContext->format,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_LOAD,
            .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    };
    VkAttachmentReference colorReference = {
            .attachment = 0,
            .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    };
    VkSubpassDescription subpassDescription = {
            .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .colorAttachmentCount = 1,
            .pColorAttachments = &colorReference
    };
    // TODO this is probably not needed?
//    // Subpass dependencies for layout transitions
//    VkSubpassDependency dependency = {
//            .srcSubpass = VK_SUBPASS_EXTERNAL,
//            .dstSubpass = 0,
//            .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
//            .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
//            .srcAccessMask = 0,
//            .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
//    };
    VkRenderPassCreateInfo createInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .attachmentCount = 1,
            .pAttachments = &colorAttachment,
            .subpassCount = 1,
            .pSubpasses = &subpassDescription,
            .dependencyCount = 0,
            .pDependencies = NULL
    };
    return device->vkCreateRenderPass(device->handle, &createInfo, NULL, &renderPassContext->renderPass);
}

static void VKPipelines_DestroyRenderPassContext(VKRenderPassContext* renderPassContext) {
    if (renderPassContext == NULL) return;
    VKDevice* device = renderPassContext->pipelineContext->device;
    assert(device != NULL);
    for (uint32_t i = 0; i < ARRAY_SIZE(renderPassContext->pipelineSets); i++) {
        VKPipelines_DestroyPipelineSet(device, renderPassContext->pipelineSets[i]);
    }
    ARRAY_FREE(renderPassContext->pipelineSets);
    device->vkDestroyRenderPass(device->handle, renderPassContext->renderPass, NULL);
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_DestroyRenderPassContext(%p): format=%d",
                  renderPassContext, renderPassContext->format);
    free(renderPassContext);
}

static VKRenderPassContext* VKPipelines_CreateRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format) {
    assert(pipelineContext != NULL && pipelineContext->device != NULL);
    VKRenderPassContext* renderPassContext = calloc(1, sizeof(VKRenderPassContext));
    VK_RUNTIME_ASSERT(renderPassContext);
    renderPassContext->pipelineContext = pipelineContext;
    renderPassContext->format = format;

    VK_IF_ERROR(VKPipelines_InitRenderPass(pipelineContext->device, renderPassContext)) {
        VKPipelines_DestroyRenderPassContext(renderPassContext);
        return NULL;
    }

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreateRenderPassContext(%p): format=%d", renderPassContext, format);
    return renderPassContext;
}

static VkResult VKPipelines_InitPipelineLayouts(VKDevice* device, VKPipelineContext* pipelines) {
    assert(device != NULL && pipelines != NULL);
    VkResult result;

    // We want all our pipelines to have same push constant range to ensure common state is compatible between pipelines.
    VkPushConstantRange pushConstantRange = {
            .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
            .offset = 0,
            .size = sizeof(float) * 2
    };
    VkPipelineLayoutCreateInfo createInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 0,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushConstantRange
    };
    result = device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->pipelineLayout);
    VK_IF_ERROR(result) return result;

    VkDescriptorSetLayoutBinding textureLayoutBinding = {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .pImmutableSamplers = NULL
    };
    VkDescriptorSetLayoutCreateInfo textureDescriptorSetLayoutCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = 1,
            .pBindings = &textureLayoutBinding
    };
    result = device->vkCreateDescriptorSetLayout(device->handle, &textureDescriptorSetLayoutCreateInfo, NULL, &pipelines->textureDescriptorSetLayout);
    VK_IF_ERROR(result) return result;

    createInfo.setLayoutCount = 1;
    createInfo.pSetLayouts = &pipelines->textureDescriptorSetLayout;
    result = device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->texturePipelineLayout);
    VK_IF_ERROR(result) return result;

    return VK_SUCCESS;
}

VKPipelineContext* VKPipelines_CreateContext(VKDevice* device) {
    assert(device != NULL);
    VKPipelineContext* pipelineContext = (VKPipelineContext*) calloc(1, sizeof(VKPipelineContext));
    VK_RUNTIME_ASSERT(pipelineContext);
    pipelineContext->device = device;

    pipelineContext->shaders = VKPipelines_CreateShaders(device);
    if (pipelineContext->shaders == NULL) {
        VKPipelines_DestroyContext(pipelineContext);
        return NULL;
    }

    VK_IF_ERROR(VKPipelines_InitPipelineLayouts(device, pipelineContext)) {
        VKPipelines_DestroyContext(pipelineContext);
        return NULL;
    }

    // Create sampler.
    VkSamplerCreateInfo samplerCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .magFilter = VK_FILTER_LINEAR,
            .minFilter = VK_FILTER_LINEAR,
            .addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT,
            .addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT
    };
    VK_IF_ERROR(device->vkCreateSampler(device->handle, &samplerCreateInfo, NULL, &pipelineContext->linearRepeatSampler)) {
        VKPipelines_DestroyContext(pipelineContext);
        return NULL;
    }

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_CreateContext(%p)", pipelineContext);
    return pipelineContext;
}

void VKPipelines_DestroyContext(VKPipelineContext* pipelineContext) {
    if (pipelineContext == NULL) return;
    VKDevice* device = pipelineContext->device;
    assert(device != NULL);

    for (uint32_t i = 0; i < ARRAY_SIZE(pipelineContext->renderPassContexts); i++) {
        VKPipelines_DestroyRenderPassContext(pipelineContext->renderPassContexts[i]);
    }
    ARRAY_FREE(pipelineContext->renderPassContexts);

    VKPipelines_DestroyShaders(device, pipelineContext->shaders);
    device->vkDestroySampler(device->handle, pipelineContext->linearRepeatSampler, NULL);

    device->vkDestroyPipelineLayout(device->handle, pipelineContext->pipelineLayout, NULL);
    device->vkDestroyPipelineLayout(device->handle, pipelineContext->texturePipelineLayout, NULL);
    device->vkDestroyDescriptorSetLayout(device->handle, pipelineContext->textureDescriptorSetLayout, NULL);

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKPipelines_DestroyContext(%p)", pipelineContext);
    free(pipelineContext);
}

VKRenderPassContext* VKPipelines_GetRenderPassContext(VKPipelineContext* pipelineContext, VkFormat format) {
    assert(pipelineContext != NULL && pipelineContext->device != NULL);
    for (uint32_t i = 0; i < ARRAY_SIZE(pipelineContext->renderPassContexts); i++) {
        if (pipelineContext->renderPassContexts[i]->format == format) {
            return pipelineContext->renderPassContexts[i];
        }
    }
    // Not found, create.
    VKRenderPassContext* renderPassContext = VKPipelines_CreateRenderPassContext(pipelineContext, format);
    ARRAY_PUSH_BACK(pipelineContext->renderPassContexts, renderPassContext);
    return renderPassContext;
}

VkPipeline VKPipelines_GetPipeline(VKRenderPassContext* renderPassContext, VKCompositeMode composite, VKPipeline pipeline) {
    assert(renderPassContext != NULL);
    assert(composite < COMPOSITE_COUNT); // We could append custom composites after that index.
    assert(pipeline < PIPELINE_COUNT); // We could append custom pipelines after that index.
    // Currently, our pipelines map to composite modes 1-to-1, but this may change in future when we'll add more states.
    uint32_t setIndex = (uint32_t) composite;

    while (ARRAY_SIZE(renderPassContext->pipelineSets) <= setIndex) ARRAY_PUSH_BACK(renderPassContext->pipelineSets, NULL);
    if (renderPassContext->pipelineSets[setIndex] == NULL) {
        renderPassContext->pipelineSets[setIndex] = VKPipelines_CreatePipelineSet(renderPassContext, composite);
    }
    return renderPassContext->pipelineSets[setIndex]->pipelines[pipeline];
}
