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

#include <assert.h>
#include "VKDevice.h"
#include "VKSamplers.h"

VKSamplers VKSamplers_Create(VKDevice* device) {
    VKSamplers result = { 0 };
    // Create descriptor pool.
    uint32_t size = SAMPLER_FILTER_COUNT * SAMPLER_ADDRESSING_COUNT;
    VkDescriptorPoolSize poolSize = {
        .type = VK_DESCRIPTOR_TYPE_SAMPLER,
        .descriptorCount = size
    };
    VkDescriptorPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
        .maxSets = size
    };
    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &poolInfo, NULL, &result.descriptorPool)) {
        return (VKSamplers) { 0 };
    }
    // Create descriptor set layout.
    VkDescriptorSetLayoutBinding descriptorSetLayoutBinding = {
        .binding = 0,
        .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER,
        .descriptorCount = 1,
        .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        .pImmutableSamplers = NULL
    };
    VkDescriptorSetLayoutCreateInfo descriptorSetLayoutCreateInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 1,
        .pBindings = &descriptorSetLayoutBinding
    };
    VK_IF_ERROR(device->vkCreateDescriptorSetLayout(device->handle, &descriptorSetLayoutCreateInfo, NULL, &result.descriptorSetLayout)) {
        VKSamplers_Destroy(device, result);
        return (VKSamplers) { 0 };
    }
    return result;
}

void VKSamplers_Destroy(VKDevice* device, VKSamplers samplers) {
    device->vkDestroyDescriptorPool(device->handle, samplers.descriptorPool, NULL);
    device->vkDestroyDescriptorSetLayout(device->handle, samplers.descriptorSetLayout, NULL);
    for (jint filter = 0; filter < SAMPLER_FILTER_COUNT; filter++) {
        for (VKSamplerAddressing addressing = 0; addressing < SAMPLER_ADDRESSING_COUNT; addressing++) {
            device->vkDestroySampler(device->handle, samplers.table[filter][addressing].sampler, NULL);
        }
    }
}

VkDescriptorSet VKSamplers_GetDescriptorSet(VKDevice* device, VKSamplers* samplers, jint filter, VKSamplerAddressing addressing) {
    assert(device != NULL && samplers != NULL);
    assert(filter > 0 && filter <= SAMPLER_FILTER_COUNT);
    assert(addressing >= 0 && addressing < SAMPLER_ADDRESSING_COUNT);
    if (samplers->table[filter-1][addressing].descriptorSet == VK_NULL_HANDLE) {
        // Resolve filtering mode.
        VkFilter filterMode;
        switch (filter) {
        case java_awt_image_AffineTransformOp_TYPE_NEAREST_NEIGHBOR:
            filterMode = VK_FILTER_NEAREST;
            break;
        case java_awt_image_AffineTransformOp_TYPE_BILINEAR:
            filterMode = VK_FILTER_LINEAR;
            break;
        case java_awt_image_AffineTransformOp_TYPE_BICUBIC:
            filterMode = VK_FILTER_CUBIC_EXT;
            assert(false); // Cubic filter is currently not supported, see VK_EXT_filter_cubic.
            break;
        default: return VK_NULL_HANDLE;
        }
        // Resolve address mode.
        VkSamplerAddressMode addressMode;
        VkBool32 unnormalizedCoordinates;
        switch (addressing) {
        case SAMPLER_ADDRESSING_EDGE_UNNORMALIZED:
            addressMode = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            unnormalizedCoordinates = VK_TRUE;
            break;
        case SAMPLER_ADDRESSING_REPEAT_NORMALIZED:
            addressMode = VK_SAMPLER_ADDRESS_MODE_REPEAT;
            unnormalizedCoordinates = VK_FALSE;
            break;
        default: return VK_NULL_HANDLE;
        }
        // Create sampler.
        VkSamplerCreateInfo samplerCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .magFilter = filterMode,
            .minFilter = filterMode,
            .addressModeU = addressMode,
            .addressModeV = addressMode,
            .addressModeW = addressMode,
            .unnormalizedCoordinates = unnormalizedCoordinates
        };
        VK_IF_ERROR(device->vkCreateSampler(device->handle, &samplerCreateInfo, NULL, &samplers->table[filter-1][addressing].sampler)) {
            VK_UNHANDLED_ERROR();
        }
        // Create descriptor set.
        VkDescriptorSetAllocateInfo descriptorSetAllocateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = samplers->descriptorPool,
            .descriptorSetCount = 1,
            .pSetLayouts = &samplers->descriptorSetLayout
        };
        VK_IF_ERROR(device->vkAllocateDescriptorSets(device->handle, &descriptorSetAllocateInfo,
                                                     &samplers->table[filter-1][addressing].descriptorSet)) {
            VK_UNHANDLED_ERROR();
        }
        VkDescriptorImageInfo samplerImageInfo = {
            .sampler = samplers->table[filter-1][addressing].sampler
        };
        VkWriteDescriptorSet descriptorWrites = {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = samplers->table[filter-1][addressing].descriptorSet,
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER,
            .descriptorCount = 1,
            .pImageInfo = &samplerImageInfo
        };
        device->vkUpdateDescriptorSets(device->handle, 1, &descriptorWrites, 0, NULL);
    }
    return samplers->table[filter-1][addressing].descriptorSet;
}
