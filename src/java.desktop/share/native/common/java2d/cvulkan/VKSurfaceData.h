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

#ifndef VKSurfaceData_h_Included
#define VKSurfaceData_h_Included

#include <pthread.h>
#include "jni.h"
#include "SurfaceData.h"
#include "VKBase.h"

/**
 * The VKSDOps structure describes a native Vulkan surface and contains all
 * information pertaining to the native surface.  Some information about
 * the more important/different fields:
 *
 *     void *privOps;
 * Pointer to native-specific SurfaceData info, such as the
 * native Drawable handle and GraphicsConfig data.
 */
typedef struct {
    SurfaceDataOps         sdOps;
    void                   *privOps;

    pthread_mutex_t         mutex;
    uint32_t                width;
    uint32_t                height;
    uint32_t                scale; // TODO Is it needed there at all?
    uint32_t                bg_color;
    VKLogicalDevice*        device;
    VkFormat                format;
    VkImageLayout           layout;
    // We track any access and write access separately, as read-read access does not need synchronization.
    VkPipelineStageFlagBits lastStage;
    VkPipelineStageFlagBits lastWriteStage;
    VkAccessFlagBits        lastAccess;
    VkAccessFlagBits        lastWriteAccess;
} VKSDOps;


/**
 * Exported methods.
 */
jint VKSD_Lock(JNIEnv *env,
                SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo,
                jint lockflags);
void VKSD_GetRasInfo(JNIEnv *env,
                      SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo);
void VKSD_Unlock(JNIEnv *env,
                  SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo);
void VKSD_Dispose(JNIEnv *env, SurfaceDataOps *ops);
void VKSD_Delete(JNIEnv *env, VKSDOps *oglsdo);

#endif /* VKSurfaceData_h_Included */
