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

#include "CArrayUtil.h"
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

VKShaders* VKPipelines_CreateShaders(VKDevice* device) {
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
    VK_IF_ERROR(device->vkCreateShaderModule(device->handle, &createInfo, NULL, &shaders->NAME##_##TYPE.module)) goto fail;
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    return shaders;

    fail:
    VKPipelines_DestroyShaders(device, shaders);
    return NULL;
}

void VKPipelines_DestroyShaders(VKDevice* device, VKShaders* shaders) {
    assert(device != NULL);
    if (shaders == NULL) return;
#   define SHADER_ENTRY(NAME, TYPE) if (shaders->NAME##_##TYPE.module != VK_NULL_HANDLE) \
    device->vkDestroyShaderModule(device->handle, shaders->NAME##_##TYPE.module, NULL);
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    free(shaders);
}

static VkResult VKPipelines_CreateRenderPasses(VKDevice* device, VKPipelines* pipelines) {
    if (device->dynamicRendering) return VK_SUCCESS;
    VkAttachmentDescription colorAttachment = {
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
    INIT_FORMAT_ALIASED_HANDLE(pipelines->renderPass, pipelines->format, i) {
        colorAttachment.format = pipelines->format[i];
        VkResult result = device->vkCreateRenderPass(device->handle, &createInfo, NULL, &pipelines->renderPass[i]);
        VK_IF_ERROR(result) return result;
    }
    return VK_SUCCESS;
}

static VkResult VKPipelines_CreatePipelineLayout(VKDevice* device, VKPipelines* pipelines) {
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
    return device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->pipelineLayout);
}

typedef struct PipelineSet {
    VkPipeline pipelines[PIPELINE_COUNT];
    // Here we could keep a cache of custom pipelines.
    // This way we could implement custom loadable painters.
} PipelineSet;

/**
 * Depending on availability of dynamic blend equation feature, alpha composites may not need dedicated pipelines.
 * This function takes a composite mode and compacts it into index of corresponding PipelineSet.
 * We could use vkCmdSetLogicOpEXT to combine all logic ops into one pipeline set as well,
 * but currently there is only one XOR logic composite anyway, so don't bother.
 * We could also use vkCmdSetLogicOpEnableEXT to combine logic and alpha composites into one pipeline set,
 * but this added complexity would be impractical, as most of the time we would need to restart
 * the whole render pass anyway because of sRGB color attachment (see FormatAlias).
 */
static uint32_t VKPipelines_GetPipelineSetIndex(VKDevice* device, CompositeMode composite) {
    assert(device != NULL);
    assert(composite < COMPOSITE_COUNT);
    if (device->dynamicBlending) { // Dynamic blending: all alpha composites are combined into one pipeline.
        return (uint32_t) (IS_ALPHA_COMPOSITE(composite) ? LOGIC_COMPOSITE_COUNT : composite);
    } else return (uint32_t) composite; // No dynamic blending: dedicated pipelines for each composite.
}

VKPipelines* VKPipelines_Create(VKDevice* device, VKShaders* shaders, VkFormat format) {
    assert(device != NULL);
    assert(shaders != NULL);
    uint32_t compositeSets = VKPipelines_GetPipelineSetIndex(device, COMPOSITE_COUNT - 1) + 1;
    VKPipelines* pipelines = calloc(1, sizeof(VKPipelines) + sizeof(PipelineSet) * compositeSets);
    VK_RUNTIME_ASSERT(pipelines);
    pipelines->device = device;
    pipelines->shaders = shaders;
    SET_ALIASED_FORMAT_FROM_REAL(pipelines->format, format);
    pipelines->pipelineSets = (PipelineSet*) (pipelines+1);

    VK_IF_ERROR(VKPipelines_CreateRenderPasses(device, pipelines)) VK_UNHANDLED_ERROR();
    VK_IF_ERROR(VKPipelines_CreatePipelineLayout(device, pipelines)) VK_UNHANDLED_ERROR();
    J2dRlsTraceLn3(J2D_TRACE_INFO, "VKPipelines_Create: pipelines=%p, format=%d, compositeSets=%d", pipelines, format, compositeSets);

    return pipelines;
}

void VKPipelines_Destroy(VKDevice* device, VKPipelines* pipelines) {
    if (pipelines == NULL) return;
    uint32_t compositeSets = VKPipelines_GetPipelineSetIndex(device, COMPOSITE_COUNT - 1) + 1;
    for (uint32_t i = 0; i < compositeSets; i++) {
        for (uint32_t j = 0; j < PIPELINE_COUNT; j++) {
            device->vkDestroyPipeline(device->handle, pipelines->pipelineSets[i].pipelines[j], NULL);
        }
    }
    device->vkDestroyPipelineLayout(device->handle, pipelines->pipelineLayout, NULL);
    DESTROY_FORMAT_ALIASED_HANDLE(pipelines->renderPass, i) {
        device->vkDestroyRenderPass(device->handle, pipelines->renderPass[i], NULL);
    }
    J2dRlsTraceLn1(J2D_TRACE_INFO, "VKPipelines_Destroy(%p)", pipelines);
    free(pipelines);
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
    VkPipelineRenderingCreateInfoKHR renderingCreateInfo;
    VkPipelineMultisampleStateCreateInfo multisampleState;
    VkPipelineColorBlendStateCreateInfo colorBlendState;
    VkPipelineDynamicStateCreateInfo dynamicState;
    VkDynamicState dynamicStates[3];
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
 * - createInfo.renderPass
 * - renderingCreateInfo.pColorAttachmentFormats (but colorAttachmentCount is set to 1)
 */
static void VKPipelines_InitPipelineCreateState(VKPipelines* pipelines, PipelineCreateState* state) {
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
    state->renderingCreateInfo = (VkPipelineRenderingCreateInfoKHR) {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR,
            .viewMask = 0,
            .colorAttachmentCount = 1
    };
    state->createInfo = (VkGraphicsPipelineCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .pNext = pipelines->device->dynamicRendering ? &state->renderingCreateInfo : NULL,
            .stageCount = 2,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizationState,
            .pMultisampleState = &state->multisampleState,
            .pColorBlendState = &state->colorBlendState,
            .pDynamicState = &state->dynamicState,
            .layout = pipelines->pipelineLayout,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };
}

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

MAKE_INPUT_STATE(COLOR_VERTEX, VKColorVertex, INPUT_ATTRIBUTE_POSITION, INPUT_ATTRIBUTE_COLOR);

static PipelineSet* VKPipelines_GetPipelineSet(VKPipelines* pipelines, CompositeMode composite) {
    assert(pipelines != NULL);
    assert(composite < COMPOSITE_COUNT);
    PipelineSet* set = &pipelines->pipelineSets[VKPipelines_GetPipelineSetIndex(pipelines->device, composite)];
    if (set->pipelines[0] != VK_NULL_HANDLE) return set;

    VKDevice* device = pipelines->device;
    VKShaders* shaders = pipelines->shaders;
    // Setup default pipeline parameters.
    PipelineCreateState base;
    VKPipelines_InitPipelineCreateState(pipelines, &base);
    FormatAlias formatAlias = COMPOSITE_TO_FORMAT_ALIAS(composite);
    base.createInfo.renderPass = pipelines->renderPass[formatAlias];
    base.renderingCreateInfo.pColorAttachmentFormats = &pipelines->format[formatAlias];
    base.colorBlendState.pAttachments = &COMPOSITE_BLEND_STATES[composite];
    if (IS_LOGIC_COMPOSITE(composite)) base.colorBlendState.logicOpEnable = VK_TRUE;
    else if (pipelines->device->dynamicBlending) {
        base.dynamicStates[base.dynamicState.dynamicStateCount++] = VK_DYNAMIC_STATE_COLOR_BLEND_EQUATION_EXT;
    }
    assert(base.dynamicState.dynamicStateCount <= SARRAY_COUNT_OF(base.dynamicStates));

    ShaderStages stages[PIPELINE_COUNT];
    VkGraphicsPipelineCreateInfo createInfos[PIPELINE_COUNT];
    for (uint32_t i = 0; i < PIPELINE_COUNT; i++) {
        createInfos[i] = base.createInfo;
        createInfos[i].pStages = stages[i].createInfos;
    }

    { // Setup plain color pipelines.
        createInfos[PIPELINE_DRAW_COLOR].pVertexInputState = createInfos[PIPELINE_FILL_COLOR].pVertexInputState = &INPUT_STATE_COLOR_VERTEX;
        stages[PIPELINE_DRAW_COLOR] = stages[PIPELINE_FILL_COLOR] = (ShaderStages) {{ shaders->color_vert, shaders->color_frag }};
        createInfos[PIPELINE_FILL_COLOR].pInputAssemblyState = &INPUT_ASSEMBLY_STATE_TRIANGLE_LIST;
        createInfos[PIPELINE_DRAW_COLOR].pInputAssemblyState = &INPUT_ASSEMBLY_STATE_LINE_LIST;
    }

    // Create pipelines.
    // TODO pipeline cache
    VK_IF_ERROR(device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, PIPELINE_COUNT,
                                                  createInfos, NULL, set->pipelines)) VK_UNHANDLED_ERROR();
    J2dRlsTraceLn2(J2D_TRACE_INFO, "VKPipelines_GetPipelineSet: created pipelines, composite=%d, dynamicBlending=%d", composite, pipelines->device->dynamicBlending);
    return set;
}

VkPipeline VKPipelines_GetPipeline(VKPipelines* pipelines, CompositeMode composite, PipelineType type) {
    assert(pipelines != NULL);
    assert(composite < COMPOSITE_COUNT); // We could append custom composites after that index.
    assert(type < PIPELINE_COUNT); // We could append custom pipelines after that index.
    return VKPipelines_GetPipelineSet(pipelines, composite)->pipelines[type];
}
