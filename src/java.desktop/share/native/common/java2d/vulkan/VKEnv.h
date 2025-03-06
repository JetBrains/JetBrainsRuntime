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

#ifndef VKEnv_h_Included
#define VKEnv_h_Included
#include "VKComposites.h"
#include "VKDevice.h"
#include "VKUtil.h"

// For old Vulkan headers - define version-related macros.
#ifndef VK_MAKE_API_VERSION
#   define VK_MAKE_API_VERSION(variant, major, minor, patch) VK_MAKE_VERSION(major, minor, patch)
#   define VK_API_VERSION_MAJOR(version) VK_VERSION_MAJOR(version)
#   define VK_API_VERSION_MINOR(version) VK_VERSION_MINOR(version)
#   define VK_API_VERSION_PATCH(version) VK_VERSION_PATCH(version)
#endif
static const uint32_t REQUIRED_VULKAN_VERSION = VK_MAKE_API_VERSION(0, 1, 2, 0);

typedef struct {
#define GLOBAL_FUNCTION_TABLE_ENTRY(NAME) PFN_ ## NAME NAME
#include "VKFunctionTable.inl"
} VKFunctionTableGlobal;

struct VKEnv {
    VkInstance      instance;
    ARRAY(VKDevice) devices;
    VKDevice*       currentDevice;

    VKComposites composites;

#if defined(DEBUG)
    VkDebugUtilsMessengerEXT debugMessenger;
#endif

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    struct wl_display* waylandDisplay;
#endif

#define INSTANCE_FUNCTION_TABLE_ENTRY(NAME) PFN_ ## NAME NAME
#define OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(NAME) PFN_ ## NAME NAME
#include "VKFunctionTable.inl"
};

VKEnv* VKEnv_GetInstance();

#endif //VKEnv_h_Included
