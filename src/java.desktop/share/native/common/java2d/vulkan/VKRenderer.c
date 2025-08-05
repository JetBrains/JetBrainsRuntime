/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#ifndef HEADLESS

#include <jlong.h>
#include "Trace.h"
#include "CArrayUtil.h"
#include "VKVertex.h"
#include "VKRenderer.h"

#define INCLUDE_BYTECODE
#define SHADER_ENTRY(NAME, TYPE) static uint32_t NAME ## _ ## TYPE ## _data[] = {
#define BYTECODE_END };
#include "vulkan/shader_list.h"
#undef INCLUDE_BYTECODE
#undef SHADER_ENTRY
#undef BYTECODE_END

VkRenderPassCreateInfo* VKRenderer_GetGenericRenderPassInfo() {
    static VkAttachmentDescription colorAttachment = {
            .format = VK_FORMAT_B8G8R8A8_UNORM, //TODO: swapChain colorFormat
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_LOAD,
            .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
    };

    static VkAttachmentReference colorReference = {
            .attachment = 0,
            .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    };

    static VkSubpassDescription subpassDescription = {
            .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .colorAttachmentCount = 1,
            .pColorAttachments = &colorReference
    };

    // Subpass dependencies for layout transitions
    static VkSubpassDependency dependency = {
            .srcSubpass = VK_SUBPASS_EXTERNAL,
            .dstSubpass = 0,
            .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
    };

    static VkRenderPassCreateInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .attachmentCount = 1,
            .pAttachments = &colorAttachment,
            .subpassCount = 1,
            .pSubpasses = &subpassDescription,
            .dependencyCount = 1,
            .pDependencies = &dependency
    };
    return &renderPassInfo;
}

VkShaderModule createShaderModule(VKLogicalDevice* logicalDevice, uint32_t* shader, uint32_t sz) {
    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = sz;
    createInfo.pCode = (uint32_t*)shader;
    VkShaderModule shaderModule;
    if (logicalDevice->vkCreateShaderModule(logicalDevice->device, &createInfo, NULL, &shaderModule) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "failed to create shader module\n");
        return VK_NULL_HANDLE;
    }
    return shaderModule;
}

VKRenderer* VKRenderer_CreateFillTexturePoly(VKLogicalDevice* logicalDevice) {
    VKRenderer* fillTexturePoly = malloc(sizeof (VKRenderer ));

    VkDevice device = logicalDevice->device;

    if (logicalDevice->vkCreateRenderPass(logicalDevice->device, VKRenderer_GetGenericRenderPassInfo(),
                               NULL, &fillTexturePoly->renderPass) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "Cannot create render pass for device");
        return JNI_FALSE;
    }

    // Create graphics pipeline
    VkShaderModule vertShaderModule = createShaderModule(logicalDevice, blit_vert_data, sizeof (blit_vert_data));
    VkShaderModule fragShaderModule = createShaderModule(logicalDevice, blit_frag_data, sizeof (blit_frag_data));

    VkPipelineShaderStageCreateInfo vertShaderStageInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertShaderModule,
            .pName = "main"
    };

    VkPipelineShaderStageCreateInfo fragShaderStageInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragShaderModule,
            .pName = "main"
    };

    VkPipelineShaderStageCreateInfo shaderStages[] = {vertShaderStageInfo, fragShaderStageInfo};
    VKVertexDescr vertexDescr = VKVertex_GetTxVertexDescr();
    VkPipelineVertexInputStateCreateInfo vertexInputInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
            .vertexBindingDescriptionCount = vertexDescr.bindingDescriptionCount,
            .vertexAttributeDescriptionCount = vertexDescr.attributeDescriptionCount,
            .pVertexBindingDescriptions = vertexDescr.bindingDescriptions,
            .pVertexAttributeDescriptions = vertexDescr.attributeDescriptions
    };

    VkPipelineInputAssemblyStateCreateInfo inputAssembly = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
            .primitiveRestartEnable = VK_FALSE
    };

    VkPipelineViewportStateCreateInfo viewportState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            .viewportCount = 1,
            .scissorCount = 1
    };

    VkPipelineRasterizationStateCreateInfo rasterizer = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            .depthClampEnable = VK_FALSE,
            .rasterizerDiscardEnable = VK_FALSE,
            .polygonMode = VK_POLYGON_MODE_FILL,
            .lineWidth = 1.0f,
            .cullMode = VK_CULL_MODE_NONE,
            .depthBiasEnable = VK_FALSE,
            .depthBiasConstantFactor = 0.0f,
            .depthBiasClamp = 0.0f,
            .depthBiasSlopeFactor = 0.0f
    };

    VkPipelineMultisampleStateCreateInfo multisampling = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .sampleShadingEnable = VK_FALSE,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
    };

    VkPipelineColorBlendAttachmentState colorBlendAttachment = {
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                              VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT |
                              VK_COLOR_COMPONENT_A_BIT,
            .blendEnable = VK_FALSE
    };

    VkPipelineColorBlendStateCreateInfo colorBlending = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            .logicOpEnable = VK_FALSE,
            .logicOp = VK_LOGIC_OP_COPY,
            .attachmentCount = 1,
            .pAttachments = &colorBlendAttachment,
            .blendConstants[0] = 0.0f,
            .blendConstants[1] = 0.0f,
            .blendConstants[2] = 0.0f,
            .blendConstants[3] = 0.0f
    };

    VkDynamicState dynamicStates[] = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
    };

    VkPipelineDynamicStateCreateInfo dynamicState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            .dynamicStateCount = 2,
            .pDynamicStates = dynamicStates
    };

    VkDescriptorSetLayoutBinding samplerLayoutBinding = {
            .binding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImmutableSamplers = NULL,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT
    };

    VkDescriptorSetLayoutCreateInfo layoutInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = 1,
            .pBindings = &samplerLayoutBinding
    };

    if (logicalDevice->vkCreateDescriptorSetLayout(device, &layoutInfo, NULL, &fillTexturePoly->descriptorSetLayout) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_INFO,  "failed to create descriptor set layout!");
        return JNI_FALSE;
    }

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 1,
            .pSetLayouts = &fillTexturePoly->descriptorSetLayout,
            .pushConstantRangeCount = 0
    };

    if (logicalDevice->vkCreatePipelineLayout(device, &pipelineLayoutInfo, NULL,
                                   &fillTexturePoly->pipelineLayout) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create pipeline layout!\n");
        return JNI_FALSE;
    }

    VkGraphicsPipelineCreateInfo pipelineInfo = {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .stageCount = 2,
            .pStages = shaderStages,
            .pVertexInputState = &vertexInputInfo,
            .pInputAssemblyState = &inputAssembly,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizer,
            .pMultisampleState = &multisampling,
            .pColorBlendState = &colorBlending,
            .pDynamicState = &dynamicState,
            .layout = fillTexturePoly->pipelineLayout,
            .renderPass = fillTexturePoly->renderPass,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };

    if (logicalDevice->vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, NULL,
                                              &fillTexturePoly->graphicsPipeline) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create graphics pipeline!\n");
        return JNI_FALSE;
    }
    logicalDevice->vkDestroyShaderModule(device, fragShaderModule, NULL);
    logicalDevice->vkDestroyShaderModule(device, vertShaderModule, NULL);

    VkSamplerCreateInfo samplerInfo = {
            .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .magFilter = VK_FILTER_LINEAR,
            .minFilter = VK_FILTER_LINEAR,

            .addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT,
            .addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT,

            .anisotropyEnable = VK_FALSE,
            .maxAnisotropy = 1.0f,

            .compareEnable = VK_FALSE,
            .compareOp = VK_COMPARE_OP_ALWAYS,
            .mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR,
            .mipLodBias = 0.0f,
            .minLod = 0.0f,
            .maxLod = 0.0f
    };

    if (logicalDevice->vkCreateSampler(device, &samplerInfo, NULL, &logicalDevice->textureSampler) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to create texture sampler!");
        return JNI_FALSE;
    }

    VkDescriptorPoolSize poolSize = {
            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1
    };

    VkDescriptorPoolCreateInfo descrPoolInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .poolSizeCount = 1,
            .pPoolSizes = &poolSize,
            .maxSets = 1
    };

    if (logicalDevice->vkCreateDescriptorPool(device, &descrPoolInfo, NULL, &fillTexturePoly->descriptorPool) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to create descriptor pool!");
        return JNI_FALSE;
    }

    VkDescriptorSetAllocateInfo descrAllocInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = fillTexturePoly->descriptorPool,
            .descriptorSetCount = 1,
            .pSetLayouts = &fillTexturePoly->descriptorSetLayout
    };

    if (logicalDevice->vkAllocateDescriptorSets(device, &descrAllocInfo, &fillTexturePoly->descriptorSets) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to allocate descriptor sets!");
        return JNI_FALSE;
    }
    fillTexturePoly->primitiveTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    return fillTexturePoly;
}

VKRenderer *VKRenderer_CreateRenderColorPoly(VKLogicalDevice* logicalDevice,
                                             VkPrimitiveTopology primitiveTopology,
                                             VkPolygonMode polygonMode)
{
    VKRenderer* renderColorPoly = malloc(sizeof (VKRenderer ));

    VkDevice device = logicalDevice->device;

    if (logicalDevice->vkCreateRenderPass(logicalDevice->device, VKRenderer_GetGenericRenderPassInfo(),
                               NULL, &renderColorPoly->renderPass) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "Cannot create render pass for device");
        return JNI_FALSE;
    }

    // Create graphics pipeline
    VkShaderModule vertShaderModule = createShaderModule(logicalDevice, color_vert_data, sizeof (color_vert_data));
    VkShaderModule fragShaderModule = createShaderModule(logicalDevice, color_frag_data, sizeof (color_frag_data));

    VkPipelineShaderStageCreateInfo vertShaderStageInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertShaderModule,
            .pName = "main"
    };

    VkPipelineShaderStageCreateInfo fragShaderStageInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragShaderModule,
            .pName = "main"
    };

    VkPipelineShaderStageCreateInfo shaderStages[] = {vertShaderStageInfo, fragShaderStageInfo};
    VKVertexDescr vertexDescr = VKVertex_GetVertexDescr();
    VkPipelineVertexInputStateCreateInfo vertexInputInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
            .vertexBindingDescriptionCount = vertexDescr.bindingDescriptionCount,
            .vertexAttributeDescriptionCount = vertexDescr.attributeDescriptionCount,
            .pVertexBindingDescriptions = vertexDescr.bindingDescriptions,
            .pVertexAttributeDescriptions = vertexDescr.attributeDescriptions
    };

    VkPipelineInputAssemblyStateCreateInfo inputAssembly = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .topology = primitiveTopology,
            .primitiveRestartEnable = VK_FALSE
    };

    VkPipelineViewportStateCreateInfo viewportState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            .viewportCount = 1,
            .scissorCount = 1
    };

    VkPipelineRasterizationStateCreateInfo rasterizer = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            .depthClampEnable = VK_FALSE,
            .rasterizerDiscardEnable = VK_FALSE,
            .polygonMode = polygonMode,
            .lineWidth = 1.0f,
            .cullMode = VK_CULL_MODE_NONE,
            .depthBiasEnable = VK_FALSE,
            .depthBiasConstantFactor = 0.0f,
            .depthBiasClamp = 0.0f,
            .depthBiasSlopeFactor = 0.0f
    };

    VkPipelineMultisampleStateCreateInfo multisampling = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .sampleShadingEnable = VK_FALSE,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
    };

    VkPipelineColorBlendAttachmentState colorBlendAttachment = {
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                              VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT |
                              VK_COLOR_COMPONENT_A_BIT,
            .blendEnable = VK_FALSE
    };

    VkPipelineColorBlendStateCreateInfo colorBlending = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            .logicOpEnable = VK_FALSE,
            .logicOp = VK_LOGIC_OP_COPY,
            .attachmentCount = 1,
            .pAttachments = &colorBlendAttachment,
            .blendConstants[0] = 0.0f,
            .blendConstants[1] = 0.0f,
            .blendConstants[2] = 0.0f,
            .blendConstants[3] = 0.0f
    };

    VkDynamicState dynamicStates[] = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
    };

    VkPipelineDynamicStateCreateInfo dynamicState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            .dynamicStateCount = 2,
            .pDynamicStates = dynamicStates
    };

    VkPushConstantRange pushConstantRange = {
            .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
            .offset = 0,
            .size = sizeof(float) * 4
    };

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 0,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushConstantRange
    };

    if (logicalDevice->vkCreatePipelineLayout(device, &pipelineLayoutInfo, NULL,
                                   &renderColorPoly->pipelineLayout) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create pipeline layout!\n");
        return JNI_FALSE;
    }

    VkGraphicsPipelineCreateInfo pipelineInfo = {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .stageCount = 2,
            .pStages = shaderStages,
            .pVertexInputState = &vertexInputInfo,
            .pInputAssemblyState = &inputAssembly,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizer,
            .pMultisampleState = &multisampling,
            .pColorBlendState = &colorBlending,
            .pDynamicState = &dynamicState,
            .layout = renderColorPoly->pipelineLayout,
            .renderPass = renderColorPoly->renderPass,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };

    if (logicalDevice->vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, NULL,
                                      &renderColorPoly->graphicsPipeline) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create graphics pipeline!\n");
        return JNI_FALSE;
    }
    logicalDevice->vkDestroyShaderModule(device, fragShaderModule, NULL);
    logicalDevice->vkDestroyShaderModule(device, vertShaderModule, NULL);
    renderColorPoly->primitiveTopology = primitiveTopology;
    return renderColorPoly;
}

VKRenderer* VKRenderer_CreateFillMaxColorPoly(VKLogicalDevice* logicalDevice) {
    VKRenderer* fillColorPoly = malloc(sizeof (VKRenderer ));

    VkDevice device = logicalDevice->device;

    if (logicalDevice->vkCreateRenderPass(logicalDevice->device, VKRenderer_GetGenericRenderPassInfo(),
                               NULL, &fillColorPoly->renderPass) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "Cannot create render pass for device");
        return JNI_FALSE;
    }

    // Create graphics pipeline
    VkShaderModule vertShaderModule = createShaderModule(logicalDevice, color_max_rect_vert_data, sizeof (color_max_rect_vert_data));
    VkShaderModule fragShaderModule = createShaderModule(logicalDevice, color_max_rect_frag_data, sizeof (color_max_rect_frag_data));

    VkPipelineShaderStageCreateInfo vertShaderStageInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertShaderModule,
            .pName = "main"
    };

    VkPipelineShaderStageCreateInfo fragShaderStageInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragShaderModule,
            .pName = "main"
    };

    VkPipelineShaderStageCreateInfo shaderStages[] = {vertShaderStageInfo, fragShaderStageInfo};
    VkPipelineVertexInputStateCreateInfo vertexInputInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
            .vertexBindingDescriptionCount = 0,
            .vertexAttributeDescriptionCount = 0,
    };

    VkPipelineInputAssemblyStateCreateInfo inputAssembly = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
            .primitiveRestartEnable = VK_FALSE
    };

    VkPipelineViewportStateCreateInfo viewportState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            .viewportCount = 1,
            .scissorCount = 1
    };

    VkPipelineRasterizationStateCreateInfo rasterizer = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            .depthClampEnable = VK_FALSE,
            .rasterizerDiscardEnable = VK_FALSE,
            .polygonMode = VK_POLYGON_MODE_FILL,
            .lineWidth = 1.0f,
            .cullMode = VK_CULL_MODE_NONE,
            .depthBiasEnable = VK_FALSE,
            .depthBiasConstantFactor = 0.0f,
            .depthBiasClamp = 0.0f,
            .depthBiasSlopeFactor = 0.0f
    };

    VkPipelineMultisampleStateCreateInfo multisampling = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .sampleShadingEnable = VK_FALSE,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
    };

    VkPipelineColorBlendAttachmentState colorBlendAttachment = {
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                              VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT |
                              VK_COLOR_COMPONENT_A_BIT,
            .blendEnable = VK_FALSE
    };

    VkPipelineColorBlendStateCreateInfo colorBlending = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            .logicOpEnable = VK_FALSE,
            .logicOp = VK_LOGIC_OP_COPY,
            .attachmentCount = 1,
            .pAttachments = &colorBlendAttachment,
            .blendConstants[0] = 0.0f,
            .blendConstants[1] = 0.0f,
            .blendConstants[2] = 0.0f,
            .blendConstants[3] = 0.0f
    };

    VkDynamicState dynamicStates[] = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
    };

    VkPipelineDynamicStateCreateInfo dynamicState = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            .dynamicStateCount = 2,
            .pDynamicStates = dynamicStates
    };

    VkPushConstantRange pushConstantRange = {
            .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
            .offset = 0,
            .size = sizeof(float) * 4
    };

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 0,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushConstantRange
    };

    if (logicalDevice->vkCreatePipelineLayout(device, &pipelineLayoutInfo, NULL,
                                   &fillColorPoly->pipelineLayout) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create pipeline layout!\n");
        return JNI_FALSE;
    }

    VkGraphicsPipelineCreateInfo pipelineInfo = {
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .stageCount = 2,
            .pStages = shaderStages,
            .pVertexInputState = &vertexInputInfo,
            .pInputAssemblyState = &inputAssembly,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizer,
            .pMultisampleState = &multisampling,
            .pColorBlendState = &colorBlending,
            .pDynamicState = &dynamicState,
            .layout = fillColorPoly->pipelineLayout,
            .renderPass = fillColorPoly->renderPass,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };

    if (logicalDevice->vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, NULL,
                                      &fillColorPoly->graphicsPipeline) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create graphics pipeline!\n");
        return JNI_FALSE;
    }
    logicalDevice->vkDestroyShaderModule(device, fragShaderModule, NULL);
    logicalDevice->vkDestroyShaderModule(device, vertShaderModule, NULL);
    fillColorPoly->primitiveTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    return fillColorPoly;
}

void VKRenderer_BeginRendering(VKLogicalDevice* logicalDevice) {
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;

    if (logicalDevice->vkBeginCommandBuffer(logicalDevice->commandBuffer, &beginInfo) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to begin recording command buffer!");
        return;
    }
}

void VKRenderer_EndRendering(VKLogicalDevice* logicalDevice, VkBool32 notifyRenderFinish, VkBool32 waitForDisplayImage) {
    if (logicalDevice->vkEndCommandBuffer(logicalDevice->commandBuffer) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to record command buffer!");
        return;
    }

    VkSemaphore waitSemaphores[] = {logicalDevice->imageAvailableSemaphore};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSemaphore signalSemaphores[] = {logicalDevice->renderFinishedSemaphore};

    VkSubmitInfo submitInfo = {
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = (waitForDisplayImage ? 1 : 0),
            .pWaitSemaphores = waitSemaphores,
            .pWaitDstStageMask = waitStages,
            .commandBufferCount = 1,
            .pCommandBuffers = &logicalDevice->commandBuffer,
            .signalSemaphoreCount = (notifyRenderFinish ? 1 : 0),
            .pSignalSemaphores = signalSemaphores
    };

    if (logicalDevice->vkQueueSubmit(logicalDevice->queue, 1, &submitInfo, logicalDevice->inFlightFence) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,"failed to submit draw command buffer!");
        return;
    }
}

void VKRenderer_TextureRender(VKLogicalDevice* logicalDevice, VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum)
{
    VkDescriptorImageInfo imageInfo = {
            .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .imageView = srcImage->view,
            .sampler = logicalDevice->textureSampler
    };

    VkWriteDescriptorSet descriptorWrites = {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = logicalDevice->fillTexturePoly->descriptorSets,
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .pImageInfo = &imageInfo
    };

    logicalDevice->vkUpdateDescriptorSets(logicalDevice->device, 1, &descriptorWrites, 0, NULL);


    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = logicalDevice->fillTexturePoly->renderPass,
            .framebuffer = destImage->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = destImage->extent,
            .clearValueCount = 1,
            .pClearValues = &clearColor
    };

    logicalDevice->vkCmdBeginRenderPass(logicalDevice->commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    logicalDevice->vkCmdBindPipeline(logicalDevice->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          logicalDevice->fillTexturePoly->graphicsPipeline);

    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    logicalDevice->vkCmdBindVertexBuffers(logicalDevice->commandBuffer, 0, 1, vertexBuffers, offsets);
    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = destImage->extent.width,
            .height = destImage->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };

    logicalDevice->vkCmdSetViewport(logicalDevice->commandBuffer, 0, 1, &viewport);

    VkRect2D scissor = {
            .offset = (VkOffset2D){0, 0},
            .extent = destImage->extent,
    };

    logicalDevice->vkCmdSetScissor(logicalDevice->commandBuffer, 0, 1, &scissor);
    logicalDevice->vkCmdBindDescriptorSets(logicalDevice->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                logicalDevice->fillTexturePoly->pipelineLayout, 0, 1, &logicalDevice->fillTexturePoly->descriptorSets, 0, NULL);
    logicalDevice->vkCmdDraw(logicalDevice->commandBuffer, vertexNum, 1, 0, 0);

    logicalDevice->vkCmdEndRenderPass(logicalDevice->commandBuffer);


}

void VKRenderer_ColorRender(VKLogicalDevice* logicalDevice, VKImage *destImage, VKRenderer *renderer, uint32_t rgba,
                            VkBuffer vertexBuffer,
                            uint32_t vertexNum)
{
    if (!vertexBuffer) {
        J2dRlsTrace(J2D_TRACE_ERROR, "VKRenderer_ColorRender: vertex buffer is NULL\n");
        return;
    }
    logicalDevice->vkWaitForFences(logicalDevice->device, 1, &logicalDevice->inFlightFence, VK_TRUE, UINT64_MAX);
    logicalDevice->vkResetFences(logicalDevice->device, 1, &logicalDevice->inFlightFence);

    logicalDevice->vkResetCommandBuffer(logicalDevice->commandBuffer, 0);

    VKRenderer_BeginRendering(logicalDevice);

    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = renderer->renderPass,
            .framebuffer = destImage->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = destImage->extent,
            .clearValueCount = 1,
            .pClearValues = &clearColor
    };

    logicalDevice->vkCmdBeginRenderPass(logicalDevice->commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    logicalDevice->vkCmdBindPipeline(logicalDevice->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          renderer->graphicsPipeline);
    struct PushConstants {
        float r, g, b, a;
    } pushConstants;

    pushConstants = (struct PushConstants){RGBA_TO_L4(rgba)};

    logicalDevice->vkCmdPushConstants(
            logicalDevice->commandBuffer,
            renderer->pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(struct PushConstants),
            &pushConstants
    );

    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    logicalDevice->vkCmdBindVertexBuffers(logicalDevice->commandBuffer, 0, 1, vertexBuffers, offsets);
    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = destImage->extent.width,
            .height = destImage->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };

    logicalDevice->vkCmdSetViewport(logicalDevice->commandBuffer, 0, 1, &viewport);

    VkRect2D scissor = {
            .offset = (VkOffset2D){0, 0},
            .extent = destImage->extent,
    };

    logicalDevice->vkCmdSetScissor(logicalDevice->commandBuffer, 0, 1, &scissor);
    logicalDevice->vkCmdDraw(logicalDevice->commandBuffer, vertexNum, 1, 0, 0);

    logicalDevice->vkCmdEndRenderPass(logicalDevice->commandBuffer);
    VKRenderer_EndRendering(logicalDevice, VK_FALSE, VK_FALSE);
}

void VKRenderer_ColorRenderMaxRect(VKLogicalDevice* logicalDevice, VKImage *destImage, uint32_t rgba) {
    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = logicalDevice->fillMaxColorPoly->renderPass,
            .framebuffer = destImage->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = destImage->extent,
            .clearValueCount = 1,
            .pClearValues = &clearColor
    };

    logicalDevice->vkCmdBeginRenderPass(logicalDevice->commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    logicalDevice->vkCmdBindPipeline(logicalDevice->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          logicalDevice->fillMaxColorPoly->graphicsPipeline);

    struct PushConstants {
        float r, g, b, a;
    } pushConstants;

    pushConstants = (struct PushConstants){RGBA_TO_L4(rgba)};

    logicalDevice->vkCmdPushConstants(
            logicalDevice->commandBuffer,
            logicalDevice->fillMaxColorPoly->pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(struct PushConstants),
            &pushConstants
    );

    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = destImage->extent.width,
            .height = destImage->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };

    logicalDevice->vkCmdSetViewport(logicalDevice->commandBuffer, 0, 1, &viewport);

    VkRect2D scissor = {
            .offset = (VkOffset2D){0, 0},
            .extent = destImage->extent,
    };

    logicalDevice->vkCmdSetScissor(logicalDevice->commandBuffer, 0, 1, &scissor);
    logicalDevice->vkCmdDraw(logicalDevice->commandBuffer, 4, 1, 0, 0);

    logicalDevice->vkCmdEndRenderPass(logicalDevice->commandBuffer);
}

void
VKRenderer_FillRect(VKLogicalDevice* logicalDevice, jint x, jint y, jint w, jint h)
{
    J2dTraceLn(J2D_TRACE_INFO, "VKRenderer_FillRect %d %d %d %d", x, y, w, h);

    if (w <= 0 || h <= 0) {
        return;
    }
}

void VKRenderer_RenderParallelogram(VKLogicalDevice* logicalDevice,
                                  VKRenderer* renderer,
                                  jint color, VKSDOps *dstOps,
                                  jfloat x11, jfloat y11,
                                  jfloat dx21, jfloat dy21,
                                  jfloat dx12, jfloat dy12)
{
    if (dstOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_RenderParallelogram: current dest is null");
        return;
    }

    if (renderer->primitiveTopology != VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST &&
        renderer->primitiveTopology != VK_PRIMITIVE_TOPOLOGY_LINE_STRIP)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_RenderParallelogram: primitive topology should be either "
                                       "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST or VK_PRIMITIVE_TOPOLOGY_LINE_STRIP");
        return;
    }

    VKSDOps *vksdOps = (VKSDOps *)dstOps;
    float width = vksdOps->width;
    float height = vksdOps->height;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                  "VKRenderQueue_flushBuffer: FILL_PARALLELOGRAM(W=%f, H=%f)",
                  width, height);
    VKVertex* vertices = ARRAY_ALLOC(VKVertex, 6);
    /*                   dx21
     *    (p1)---------(p2) |          (p1)------
     *     |\            \  |            |  \    dy21
     *     | \            \ |       dy12 |   \
     *     |  \            \|            |   (p2)-
     *     |  (p4)---------(p3)        (p4)   |
     *      dx12                           \  |  dy12
     *                              dy21    \ |
     *                                  -----(p3)
     */
    float p1x = -1.0f + x11 / width;
    float p1y = -1.0f + y11 / height;
    float p2x = -1.0f + (x11 + dx21) / width;
    float p2y = -1.0f + (y11 + dy21) / height;
    float p3x = -1.0f + (x11 + dx21 + dx12) / width;
    float p3y = -1.0f + (y11 + dy21 + dy12) / height;
    float p4x = -1.0f + (x11 + dx12) / width;
    float p4y = -1.0f + (y11 + dy12) / height;

    ARRAY_PUSH_BACK(&vertices, ((VKVertex) {p1x, p1y}));
    ARRAY_PUSH_BACK(&vertices, ((VKVertex) {p2x, p2y}));
    ARRAY_PUSH_BACK(&vertices, ((VKVertex) {p3x, p3y}));

    if (renderer->primitiveTopology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST) {
        ARRAY_PUSH_BACK(&vertices, ((VKVertex) {p3x, p3y}));
    }

    ARRAY_PUSH_BACK(&vertices, ((VKVertex) {p4x, p4y}));
    ARRAY_PUSH_BACK(&vertices, ((VKVertex) {p1x, p1y}));

    int vertexNum = ARRAY_SIZE(vertices);

    VKBuffer* renderVertexBuffer = ARRAY_TO_VERTEX_BUF(logicalDevice, vertices);
    ARRAY_FREE(vertices);

    VKRenderer_ColorRender(
            logicalDevice,
            vksdOps->image, renderer,
            color,
            renderVertexBuffer->buffer, vertexNum);
}

void VKRenderer_FillSpans(VKLogicalDevice* logicalDevice, jint color, VKSDOps *dstOps, jint spanCount, jint *spans)
{
    if (dstOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_FillSpans: current dest is null");
        return;
    }

    if (spanCount == 0) {
        return;
    }

    VKSDOps *vksdOps = (VKSDOps *)dstOps;
    float width = vksdOps->width;
    float height = vksdOps->height;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FillSpans(W=%f, H=%f, COUNT=%d)",
                  width, height, spanCount);

    const int VERT_COUNT = spanCount * 6;
    VKVertex* vertices = ARRAY_ALLOC(VKVertex, VERT_COUNT);
    for (int i = 0; i < spanCount; i++) {
        jfloat x1 = *(spans++);
        jfloat y1 = *(spans++);
        jfloat x2 = *(spans++);
        jfloat y2 = *(spans++);

        float p1x = -1.0f + x1 / width;
        float p1y = -1.0f + y1 / height;
        float p2x = -1.0f + x2 / width;
        float p2y = p1y;
        float p3x = p2x;
        float p3y = -1.0f + y2 / height;
        float p4x = p1x;
        float p4y = p3y;

        ARRAY_PUSH_BACK(&vertices, ((VKVertex){p1x,p1y}));

        ARRAY_PUSH_BACK(&vertices, ((VKVertex){p2x,p2y}));

        ARRAY_PUSH_BACK(&vertices, ((VKVertex){p3x,p3y}));

        ARRAY_PUSH_BACK(&vertices, ((VKVertex){p3x,p3y}));

        ARRAY_PUSH_BACK(&vertices, ((VKVertex){p4x,p4y}));

        ARRAY_PUSH_BACK(&vertices, ((VKVertex){p1x,p1y}));
    }

    VKBuffer *fillVertexBuffer = ARRAY_TO_VERTEX_BUF(logicalDevice, vertices);
    if (!fillVertexBuffer) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create vertex buffer\n");
        return;
    }
    ARRAY_FREE(vertices);

    VKRenderer_ColorRender(
            logicalDevice,
            vksdOps->image, logicalDevice->fillColorPoly,
            color,
            fillVertexBuffer->buffer, VERT_COUNT);
}

jboolean VK_CreateLogicalDeviceRenderers(VKLogicalDevice* logicalDevice) {
    logicalDevice->fillTexturePoly = VKRenderer_CreateFillTexturePoly(logicalDevice);
    logicalDevice->fillColorPoly = VKRenderer_CreateRenderColorPoly(logicalDevice, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                                                                    VK_POLYGON_MODE_FILL);
    logicalDevice->drawColorPoly = VKRenderer_CreateRenderColorPoly(logicalDevice, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP,
                                                                    VK_POLYGON_MODE_LINE);
    logicalDevice->fillMaxColorPoly = VKRenderer_CreateFillMaxColorPoly(logicalDevice);
    if (!logicalDevice->fillTexturePoly || !logicalDevice->fillColorPoly || !logicalDevice->drawColorPoly ||
        !logicalDevice->fillMaxColorPoly)
    {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
#endif /* !HEADLESS */
