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

VkShaderModule createShaderModule(VKDevice* device, uint32_t* shader, uint32_t sz) {
    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = sz;
    createInfo.pCode = (uint32_t*)shader;
    VkShaderModule shaderModule;
    if (device->vkCreateShaderModule(device->handle, &createInfo, NULL, &shaderModule) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "failed to create shader module\n");
        return VK_NULL_HANDLE;
    }
    return shaderModule;
}

VKRenderer* VKRenderer_CreateFillTexturePoly(VKDevice* device) {
    VKRenderer* fillTexturePoly = malloc(sizeof (VKRenderer ));

    // Create graphics pipeline
    VkShaderModule vertShaderModule = createShaderModule(device, blit_vert_data, sizeof (blit_vert_data));
    VkShaderModule fragShaderModule = createShaderModule(device, blit_frag_data, sizeof (blit_frag_data));

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

    if (device->vkCreateDescriptorSetLayout(device->handle, &layoutInfo, NULL, &fillTexturePoly->descriptorSetLayout) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_INFO,  "failed to create descriptor set layout!");
        return JNI_FALSE;
    }

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 1,
            .pSetLayouts = &fillTexturePoly->descriptorSetLayout,
            .pushConstantRangeCount = 0
    };

    if (device->vkCreatePipelineLayout(device->handle, &pipelineLayoutInfo, NULL,
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
            .renderPass = device->renderPass,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };

    if (device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, 1, &pipelineInfo, NULL,
                                              &fillTexturePoly->graphicsPipeline) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create graphics pipeline!\n");
        return JNI_FALSE;
    }
    device->vkDestroyShaderModule(device->handle, fragShaderModule, NULL);
    device->vkDestroyShaderModule(device->handle, vertShaderModule, NULL);

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

    if (device->vkCreateSampler(device->handle, &samplerInfo, NULL, &device->textureSampler) != VK_SUCCESS) {
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

    if (device->vkCreateDescriptorPool(device->handle, &descrPoolInfo, NULL, &fillTexturePoly->descriptorPool) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to create descriptor pool!");
        return JNI_FALSE;
    }

    VkDescriptorSetAllocateInfo descrAllocInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = fillTexturePoly->descriptorPool,
            .descriptorSetCount = 1,
            .pSetLayouts = &fillTexturePoly->descriptorSetLayout
    };

    if (device->vkAllocateDescriptorSets(device->handle, &descrAllocInfo, &fillTexturePoly->descriptorSets) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to allocate descriptor sets!");
        return JNI_FALSE;
    }
    fillTexturePoly->primitiveTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    return fillTexturePoly;
}

VKRenderer *VKRenderer_CreateRenderColorPoly(VKDevice* device,
                                             VkPrimitiveTopology primitiveTopology,
                                             VkPolygonMode polygonMode)
{
    VKRenderer* renderColorPoly = malloc(sizeof (VKRenderer ));

    // Create graphics pipeline
    VkShaderModule vertShaderModule = createShaderModule(device, color_vert_data, sizeof (color_vert_data));
    VkShaderModule fragShaderModule = createShaderModule(device, color_frag_data, sizeof (color_frag_data));

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

    if (device->vkCreatePipelineLayout(device->handle, &pipelineLayoutInfo, NULL,
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
            .renderPass = device->renderPass,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };

    if (device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, 1, &pipelineInfo, NULL,
                                      &renderColorPoly->graphicsPipeline) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create graphics pipeline!\n");
        return JNI_FALSE;
    }
    device->vkDestroyShaderModule(device->handle, fragShaderModule, NULL);
    device->vkDestroyShaderModule(device->handle, vertShaderModule, NULL);
    renderColorPoly->primitiveTopology = primitiveTopology;
    return renderColorPoly;
}

VKRenderer* VKRenderer_CreateFillMaxColorPoly(VKDevice* device) {
    VKRenderer* fillColorPoly = malloc(sizeof (VKRenderer ));

    // Create graphics pipeline
    VkShaderModule vertShaderModule = createShaderModule(device, color_max_rect_vert_data, sizeof (color_max_rect_vert_data));
    VkShaderModule fragShaderModule = createShaderModule(device, color_max_rect_frag_data, sizeof (color_max_rect_frag_data));

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

    if (device->vkCreatePipelineLayout(device->handle, &pipelineLayoutInfo, NULL,
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
            .renderPass = device->renderPass,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1
    };

    if (device->vkCreateGraphicsPipelines(device->handle, VK_NULL_HANDLE, 1, &pipelineInfo, NULL,
                                      &fillColorPoly->graphicsPipeline) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "failed to create graphics pipeline!\n");
        return JNI_FALSE;
    }
    device->vkDestroyShaderModule(device->handle, fragShaderModule, NULL);
    device->vkDestroyShaderModule(device->handle, vertShaderModule, NULL);
    fillColorPoly->primitiveTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    return fillColorPoly;
}

void VKRenderer_BeginRendering(VKDevice* device) {
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;

    if (device->vkBeginCommandBuffer(device->commandBuffer, &beginInfo) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to begin recording command buffer!");
        return;
    }
}

void VKRenderer_EndRendering(VKDevice* device, VkBool32 notifyRenderFinish, VkBool32 waitForDisplayImage) {
    if (device->vkEndCommandBuffer(device->commandBuffer) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to record command buffer!");
        return;
    }

    VkSemaphore waitSemaphores[] = {device->imageAvailableSemaphore};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSemaphore signalSemaphores[] = {device->renderFinishedSemaphore};

    VkSubmitInfo submitInfo = {
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = (waitForDisplayImage ? 1 : 0),
            .pWaitSemaphores = waitSemaphores,
            .pWaitDstStageMask = waitStages,
            .commandBufferCount = 1,
            .pCommandBuffers = &device->commandBuffer,
            .signalSemaphoreCount = (notifyRenderFinish ? 1 : 0),
            .pSignalSemaphores = signalSemaphores
    };

    if (device->vkQueueSubmit(device->queue, 1, &submitInfo, device->inFlightFence) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,"failed to submit draw command buffer!");
        return;
    }
}

void VKRenderer_TextureRender(VKDevice* device, VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum)
{
    VkDescriptorImageInfo imageInfo = {
            .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .imageView = srcImage->view,
            .sampler = device->textureSampler
    };

    VkWriteDescriptorSet descriptorWrites = {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = device->fillTexturePoly->descriptorSets,
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .pImageInfo = &imageInfo
    };

    device->vkUpdateDescriptorSets(device->handle, 1, &descriptorWrites, 0, NULL);


    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = device->renderPass,
            .framebuffer = destImage->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = destImage->extent,
            .clearValueCount = 1,
            .pClearValues = &clearColor
    };

    device->vkCmdBeginRenderPass(device->commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    device->vkCmdBindPipeline(device->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          device->fillTexturePoly->graphicsPipeline);

    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    device->vkCmdBindVertexBuffers(device->commandBuffer, 0, 1, vertexBuffers, offsets);
    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = destImage->extent.width,
            .height = destImage->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };

    device->vkCmdSetViewport(device->commandBuffer, 0, 1, &viewport);

    VkRect2D scissor = {
            .offset = (VkOffset2D){0, 0},
            .extent = destImage->extent,
    };

    device->vkCmdSetScissor(device->commandBuffer, 0, 1, &scissor);
    device->vkCmdBindDescriptorSets(device->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                device->fillTexturePoly->pipelineLayout, 0, 1, &device->fillTexturePoly->descriptorSets, 0, NULL);
    device->vkCmdDraw(device->commandBuffer, vertexNum, 1, 0, 0);

    device->vkCmdEndRenderPass(device->commandBuffer);


}

void VKRenderer_ColorRender(VKDevice* device, VKImage *destImage, VKRenderer *renderer, uint32_t rgba,
                            VkBuffer vertexBuffer,
                            uint32_t vertexNum)
{
    if (!vertexBuffer) {
        J2dRlsTrace(J2D_TRACE_ERROR, "VKRenderer_ColorRender: vertex buffer is NULL\n");
        return;
    }
    device->vkWaitForFences(device->handle, 1, &device->inFlightFence, VK_TRUE, UINT64_MAX);
    device->vkResetFences(device->handle, 1, &device->inFlightFence);

    device->vkResetCommandBuffer(device->commandBuffer, 0);

    VKRenderer_BeginRendering(device);

    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = device->renderPass,
            .framebuffer = destImage->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = destImage->extent,
            .clearValueCount = 1,
            .pClearValues = &clearColor
    };

    device->vkCmdBeginRenderPass(device->commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    device->vkCmdBindPipeline(device->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          renderer->graphicsPipeline);
    struct PushConstants {
        float r, g, b, a;
    } pushConstants;

    pushConstants = (struct PushConstants){RGBA_TO_L4(rgba)};

    device->vkCmdPushConstants(
            device->commandBuffer,
            renderer->pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(struct PushConstants),
            &pushConstants
    );

    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    device->vkCmdBindVertexBuffers(device->commandBuffer, 0, 1, vertexBuffers, offsets);
    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = destImage->extent.width,
            .height = destImage->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };

    device->vkCmdSetViewport(device->commandBuffer, 0, 1, &viewport);

    VkRect2D scissor = {
            .offset = (VkOffset2D){0, 0},
            .extent = destImage->extent,
    };

    device->vkCmdSetScissor(device->commandBuffer, 0, 1, &scissor);
    device->vkCmdDraw(device->commandBuffer, vertexNum, 1, 0, 0);

    device->vkCmdEndRenderPass(device->commandBuffer);
    VKRenderer_EndRendering(device, VK_FALSE, VK_FALSE);
}

void VKRenderer_ColorRenderMaxRect(VKDevice* device, VKImage *destImage, uint32_t rgba) {
    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = device->renderPass,
            .framebuffer = destImage->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = destImage->extent,
            .clearValueCount = 1,
            .pClearValues = &clearColor
    };

    device->vkCmdBeginRenderPass(device->commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    device->vkCmdBindPipeline(device->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          device->fillMaxColorPoly->graphicsPipeline);

    struct PushConstants {
        float r, g, b, a;
    } pushConstants;

    pushConstants = (struct PushConstants){RGBA_TO_L4(rgba)};

    device->vkCmdPushConstants(
            device->commandBuffer,
            device->fillMaxColorPoly->pipelineLayout,
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

    device->vkCmdSetViewport(device->commandBuffer, 0, 1, &viewport);

    VkRect2D scissor = {
            .offset = (VkOffset2D){0, 0},
            .extent = destImage->extent,
    };

    device->vkCmdSetScissor(device->commandBuffer, 0, 1, &scissor);
    device->vkCmdDraw(device->commandBuffer, 4, 1, 0, 0);

    device->vkCmdEndRenderPass(device->commandBuffer);
}

void
VKRenderer_FillRect(VKDevice* device, jint x, jint y, jint w, jint h)
{
    J2dTraceLn(J2D_TRACE_INFO, "VKRenderer_FillRect %d %d %d %d", x, y, w, h);

    if (w <= 0 || h <= 0) {
        return;
    }
}

void VKRenderer_RenderParallelogram(VKDevice* device,
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

    VKBuffer* renderVertexBuffer = ARRAY_TO_VERTEX_BUF(device, vertices);
    ARRAY_FREE(vertices);

    VKRenderer_ColorRender(
            device,
            vksdOps->image, renderer,
            color,
            renderVertexBuffer->buffer, vertexNum);
}

void VKRenderer_FillSpans(VKDevice* device, jint color, VKSDOps *dstOps, jint spanCount, jint *spans)
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

    VKBuffer *fillVertexBuffer = ARRAY_TO_VERTEX_BUF(device, vertices);
    if (!fillVertexBuffer) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create vertex buffer\n");
        return;
    }
    ARRAY_FREE(vertices);

    VKRenderer_ColorRender(
            device,
            vksdOps->image, device->fillColorPoly,
            color,
            fillVertexBuffer->buffer, VERT_COUNT);
}

jboolean VK_CreateLogicalDeviceRenderers(VKDevice* device) {
    device->fillTexturePoly = VKRenderer_CreateFillTexturePoly(device);
    device->fillColorPoly = VKRenderer_CreateRenderColorPoly(device, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                                                                    VK_POLYGON_MODE_FILL);
    device->drawColorPoly = VKRenderer_CreateRenderColorPoly(device, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP,
                                                                    VK_POLYGON_MODE_LINE);
    device->fillMaxColorPoly = VKRenderer_CreateFillMaxColorPoly(device);
    if (!device->fillTexturePoly || !device->fillColorPoly || !device->drawColorPoly ||
        !device->fillMaxColorPoly)
    {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
#endif /* !HEADLESS */
