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

void VKPipelines_DestroyShaders(VKDevice* device, VKShaders* shaders) {
    if (shaders == NULL) return;
#   define SHADER_ENTRY(NAME, TYPE) if (shaders->NAME##_##TYPE.module != VK_NULL_HANDLE) \
    device->vkDestroyShaderModule(device->handle, shaders->NAME##_##TYPE.module, NULL);
#   include "vulkan/shader_list.h"
#   undef SHADER_ENTRY
    free(shaders);
}

static VkResult VKPipelines_CreateRenderPass(VKDevice* device, VKPipelines* pipelines) {
    VkAttachmentDescription colorAttachment = {
            .format = pipelines->format,
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
    return device->vkCreateRenderPass(device->handle, &createInfo, NULL, &pipelines->renderPass);
}

static VkResult VKPipelines_CreatePipelineLayout(VKDevice* device, VKPipelines* pipelines) {
    VkPushConstantRange pushConstantRange = {
            .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
            .offset = 0,
            .size = sizeof(float) * 4
    };
    VkPipelineLayoutCreateInfo createInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 0,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushConstantRange
    };
    return device->vkCreatePipelineLayout(device->handle, &createInfo, NULL, &pipelines->pipelineLayout);
}

static VkGraphicsPipelineCreateInfo VKPipelines_DefaultPipeline() {
    static const VkPipelineInputAssemblyStateCreateInfo inputAssemblyState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    };
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
    static const VkPipelineMultisampleStateCreateInfo multisampleState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
    };
    static const VkPipelineColorBlendAttachmentState colorBlendAttachment = {
            .blendEnable = VK_FALSE, // TODO enable blending???
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                              VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT |
                              VK_COLOR_COMPONENT_A_BIT
    };
    static const VkPipelineColorBlendStateCreateInfo colorBlendState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            .attachmentCount = 1,
            .pAttachments = &colorBlendAttachment,
    };
    static const VkDynamicState dynamicStates[] = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
    };
    static const VkPipelineDynamicStateCreateInfo dynamicState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            .dynamicStateCount = 2,
            .pDynamicStates = dynamicStates
    };
    return (VkGraphicsPipelineCreateInfo) {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .pInputAssemblyState = &inputAssemblyState,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizationState,
            .pMultisampleState = &multisampleState,
            .pColorBlendState = &colorBlendState,
            .pDynamicState = &dynamicState,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };
}

VKPipelines* VKPipelines_Create(VKDevice* device, VKShaders* shaders, VkFormat format) {
    VKPipelines* pipelines = calloc(1, sizeof(VKPipelines));
    VK_RUNTIME_ASSERT(pipelines);
    pipelines->format = format;

    VK_IF_ERROR(VKPipelines_CreateRenderPass(device, pipelines)) {
        VKPipelines_Destroy(device, pipelines);
        return NULL;
    }
    VK_IF_ERROR(VKPipelines_CreatePipelineLayout(device, pipelines)) {
        VKPipelines_Destroy(device, pipelines);
        return NULL;
    }

    // Setup default pipeline parameters.
    typedef struct {
        VkPipelineShaderStageCreateInfo createInfos[2]; // vert + frag
    } ShaderStages;
    ShaderStages stages[NUM_PIPELINES];
    VkGraphicsPipelineCreateInfo createInfos[NUM_PIPELINES];
    for (uint32_t i = 0; i < NUM_PIPELINES; i++) {
        createInfos[i] = VKPipelines_DefaultPipeline();
        createInfos[i].stageCount = 2;
        createInfos[i].pStages = stages[i].createInfos;
        createInfos[i].layout = pipelines->pipelineLayout;
        createInfos[i].renderPass = pipelines->renderPass;
    }

    // Setup common state variants.
    static const VkVertexInputAttributeDescription positionAttribute = {
            .location = 0,
            .binding = 0,
            .format = VK_FORMAT_R32G32_SFLOAT,
            .offset = 0
    };
#   define MAKE_INPUT_STATE(TYPE, ...)                                             \
    static const VkVertexInputAttributeDescription attributes[] = { __VA_ARGS__ }; \
    static const VkVertexInputBindingDescription binding = {                       \
            .binding = 0,                                                          \
            .stride = sizeof(TYPE),                                                \
            .inputRate = VK_VERTEX_INPUT_RATE_VERTEX                               \
    };                                                                             \
    static const VkPipelineVertexInputStateCreateInfo inputState = {               \
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,    \
            .vertexBindingDescriptionCount = 1,                                    \
            .pVertexBindingDescriptions = &binding,                                \
            .vertexAttributeDescriptionCount = SARRAY_COUNT_OF(attributes),        \
            .pVertexAttributeDescriptions = attributes                             \
    }
    static const VkPipelineInputAssemblyStateCreateInfo lineInputAssemblyState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .topology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST
    };


    { // Setup plain color pipelines.
        MAKE_INPUT_STATE(VKVertex, positionAttribute);
        createInfos[PIPELINE_DRAW_COLOR].pVertexInputState = createInfos[PIPELINE_FILL_COLOR].pVertexInputState = &inputState;
        stages[PIPELINE_DRAW_COLOR] = stages[PIPELINE_FILL_COLOR] = (ShaderStages) {{ shaders->color_vert, shaders->color_frag }};
        createInfos[PIPELINE_DRAW_COLOR].pInputAssemblyState = &lineInputAssemblyState;
    }

    // Create pipelines.
    // TODO pipeline cache
    VK_IF_ERROR(device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, NUM_PIPELINES,
                                               createInfos, NULL, pipelines->pipelines)) {
        VKPipelines_Destroy(device, pipelines);
        return NULL;
    }
    return pipelines;
}

void VKPipelines_Destroy(VKDevice* device, VKPipelines* pipelines) {
    if (pipelines == NULL) return;
    if (pipelines->pipelineLayout != VK_NULL_HANDLE) {
        device->vkDestroyPipelineLayout(device->handle, pipelines->pipelineLayout, NULL);
    }
    if (pipelines->renderPass != VK_NULL_HANDLE) {
        device->vkDestroyRenderPass(device->handle, pipelines->renderPass, NULL);
    }
    free(pipelines);
}
