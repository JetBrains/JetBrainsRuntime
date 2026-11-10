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

#ifndef VKSamplers_h_Included
#define VKSamplers_h_Included

#include "java_awt_image_AffineTransformOp.h"
#include "VKUtil.h"

// Filter types in Java are numbered from 1
// Cubic filter is currently not supported, see VK_EXT_filter_cubic.
#define SAMPLER_FILTER_COUNT java_awt_image_AffineTransformOp_TYPE_BILINEAR

// This is one enum because some combinations (like repeat + unnormalized) are unsupported.
typedef enum {
    SAMPLER_ADDRESSING_EDGE_UNNORMALIZED,
    SAMPLER_ADDRESSING_REPEAT_NORMALIZED,
    SAMPLER_ADDRESSING_COUNT
} VKSamplerAddressing;

typedef struct {
    VkDescriptorPool descriptorPool;
    VkDescriptorSetLayout descriptorSetLayout;
    struct {
        VkSampler sampler;
        VkDescriptorSet descriptorSet;
    } table[SAMPLER_FILTER_COUNT][SAMPLER_ADDRESSING_COUNT];
} VKSamplers;

VKSamplers VKSamplers_Create(VKDevice* device);
void VKSamplers_Destroy(VKDevice* device, VKSamplers samplers);
VkDescriptorSet VKSamplers_GetDescriptorSet(VKDevice* device, VKSamplers* samplers, jint filter, VKSamplerAddressing addressing);

#endif //VKSamplers_h_Included
