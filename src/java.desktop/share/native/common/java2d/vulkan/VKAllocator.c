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
#include "VKAllocator.h"

struct VKAllocator {
    VKDevice* device;
    VkPhysicalDeviceMemoryProperties memoryProperties;
};

VKAllocator* VKAllocator_Create(VKDevice* device) {
    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    VKAllocator* allocator = (VKAllocator*) calloc(1, sizeof(VKAllocator));
    allocator->device = device;

    ge->vkGetPhysicalDeviceMemoryProperties(device->physicalDevice, &allocator->memoryProperties);

    J2dRlsTraceLn1(J2D_TRACE_INFO, "VKAllocator_Create: allocator=%p", allocator);
    return allocator;
}

void VKAllocator_Destroy(VKAllocator* allocator) {
    if (allocator == NULL) return;

    J2dRlsTraceLn1(J2D_TRACE_INFO, "VKAllocator_Destroy(%p)", allocator);
    free(allocator);
}

uint32_t VKAllocator_FindMemoryType(VKAllocator* allocator, uint32_t typeFilter,
                                    VkMemoryPropertyFlags requiredProperties, VkMemoryPropertyFlags allowedProperties) {
    // TODO also skip heaps with insufficient memory?
    allowedProperties |= requiredProperties;
    for (uint32_t i = 0; i < allocator->memoryProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) == 0) continue;
        VkMemoryPropertyFlags flags = allocator->memoryProperties.memoryTypes[i].propertyFlags;
        if ((flags & requiredProperties) == requiredProperties && (flags & allowedProperties) == flags) return i;
    }
    return VK_NO_MEMORY_TYPE;
}
