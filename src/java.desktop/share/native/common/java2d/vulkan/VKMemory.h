/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

#ifndef VKMemory_h_Included
#define VKMemory_h_Included

#define VK_NO_PROTOTYPES
#define VULKAN_HPP_NO_DEFAULT_DISPATCHER
#include <vulkan/vulkan_raii.hpp>
#include <vk_mem_alloc.hpp>

class VKBuffer {
    friend class VKMemory;
    vma::UniqueBuffer     _buffer;
    vma::UniqueAllocation _allocation;
    vma::AllocationInfo   _allocationInfo;
    uint32_t              _size = 0;
    uint32_t              _position = 0;
public:
    VKBuffer(nullptr_t) {}
    vk::Buffer operator*() const {
        return *_buffer;
    }
    uint32_t size() const {
        return _size;
    }
    uint32_t& position() {
        return _position;
    }
    void* data() {
        return _allocationInfo.pMappedData;
    }
};

class VKMemory : vma::Allocator {
    vma::UniqueAllocator _allocator;

public:
    void init(vk::Instance instance, const vk::raii::PhysicalDevice& physicalDevice,
              const vk::raii::Device& device, uint32_t apiVersion, bool extMemoryBudget);

    VKBuffer allocateBuffer(uint32_t size, vk::BufferUsageFlags usage,
                            vma::AllocationCreateFlags flags, vma::MemoryUsage memoryUsage);
};

#endif //VKMemory_h_Included
