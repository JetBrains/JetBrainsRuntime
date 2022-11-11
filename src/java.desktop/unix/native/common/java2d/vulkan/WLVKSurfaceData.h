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

#ifndef WLVKSurfaceData_h_Included
#define WLVKSurfaceData_h_Included

#include <cstdlib>
#include <vulkan/vulkan.h>
#include <SurfaceData.h>
#include <VKBase.h>

#ifdef HEADLESS
#define WLVKSDOps void
#else /* HEADLESS */

class WLVKSurfaceData : public VKSurfaceData {
    wl_surface*            _wl_surface;
    vk::raii::SurfaceKHR   _surface_khr;
    vk::raii::SwapchainKHR _swapchain_khr;
public:
    WLVKSurfaceData(uint32_t w, uint32_t h, uint32_t s, uint32_t bgc);
    void validate(wl_surface* wls);
    void revalidate(uint32_t w, uint32_t h, uint32_t s);
    void set_bg_color(uint32_t bgc);
    void update();
};

/**
 * The WLVKSDOps structure contains the WLVK-specific information for a given
 * WLVKSurfaceData.  It is referenced by the native OGLSDOps structure.
 *
 *     wl_surface* wlSurface;
 * For onscreen windows, we maintain a reference to that window's associated
 * wl_surface handle here.  Offscreen surfaces have no associated Window, so for
 * those surfaces, this value will simply be zero.
 *
 *     VkSurfaceKHR* surface;
 * Vulkan surface associated with this surface.
 */
typedef struct _WLVKSDOps {
    SurfaceDataOps      sdOps;
    WLVKSurfaceData*    wlvkSD;
    pthread_mutex_t     lock;
} WLVKSDOps;

#endif /* HEADLESS */

#endif /* WLVKSurfaceData_h_Included */
