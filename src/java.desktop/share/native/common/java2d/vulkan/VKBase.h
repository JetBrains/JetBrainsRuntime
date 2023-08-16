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

#ifndef VKBase_h_Included
#define VKBase_h_Included
#ifdef __cplusplus


#define VK_NO_PROTOTYPES
#define VULKAN_HPP_NO_DEFAULT_DISPATCHER
#include <queue>
#include <vulkan/vulkan_raii.hpp>
#include "jni.h"

class VKDevice : public vk::raii::Device, public vk::raii::PhysicalDevice {
    friend class VKGraphicsEnvironment;

    std::string              _name;
    std::vector<const char*> _enabled_layers, _enabled_extensions;
    int                      _queue_family = -1;

    // Logical device state
    vk::raii::Queue          _queue = nullptr;
    vk::raii::CommandPool    _commandPool = nullptr;
    vk::raii::Semaphore      _timelineSemaphore = nullptr;
    uint64_t                 _timelineCounter = 0;
    uint64_t                 _lastReadTimelineCounter = 0;

    struct PendingBuffer {
        vk::raii::CommandBuffer buffer;
        uint64_t                counter;
    };
    std::queue<PendingBuffer> _pendingPrimaryBuffers, _pendingSecondaryBuffers;

    explicit VKDevice(vk::raii::PhysicalDevice&& handle);
public:

    uint32_t queue_family() const {
        return (uint32_t) _queue_family;
    }

    const vk::raii::Queue& queue() const {
        return _queue;
    }

    void init(); // Creates actual logical device

    vk::raii::CommandBuffer getCommandBuffer(vk::CommandBufferLevel level);
    void submitCommandBuffer(vk::raii::CommandBuffer&& primary,
                             std::vector<vk::raii::CommandBuffer>& secondary,
                             std::vector<vk::Semaphore>& waitSemaphores,
                             std::vector<vk::PipelineStageFlags>& waitStages,
                             std::vector<vk::Semaphore>& signalSemaphores);

    bool supported() const { // Supported or not
        return *((const vk::raii::PhysicalDevice&) *this);
    }

    explicit operator bool() const { // Initialized or not
        return *((const vk::raii::Device&) *this);
    }
};

class VKGraphicsEnvironment {
    vk::raii::Context                      _vk_context;
    vk::raii::Instance                     _vk_instance;
#if defined(DEBUG)
    vk::raii::DebugUtilsMessengerEXT       _debugMessenger = nullptr;
#endif
    std::vector<std::unique_ptr<VKDevice>> _devices;
    VKDevice*                              _default_device;
    static std::unique_ptr<VKGraphicsEnvironment> _ge_instance;
    VKGraphicsEnvironment();
public:
    static VKGraphicsEnvironment* graphics_environment();
    static void dispose();
    VKDevice& default_device();
    vk::raii::Instance& vk_instance();
};

extern "C" {
#endif //__cplusplus

jboolean VK_Init();

#ifdef __cplusplus
}
#endif //__cplusplus
#endif //VKBase_h_Included
