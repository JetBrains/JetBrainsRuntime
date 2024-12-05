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

#include <jni_util.h>
#include <Trace.h>
#include "VKBase.h"
#include "VKUtil.h"
#include "VKSurfaceData.h"
#include "VKImage.h"
#include "VKBuffer.h"

static void WLVKSurfaceData_OnResize(VKWinSDOps* surface, VkExtent2D extent) {
    JNIEnv* env = (JNIEnv*)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    JNU_CallMethodByName(env, NULL, surface->vksdOps.sdOps.sdObject, "bufferAttached", "()V");
}

JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKSurfaceData_initOps(JNIEnv *env, jobject vksd, jint backgroundRGB) {
    J2dTraceLn1(J2D_TRACE_VERBOSE, "WLVKSurfaceData_initOps(%p)", vksd);
    VKWinSDOps* sd = (VKWinSDOps*)SurfaceData_InitOps(env, vksd, sizeof(VKWinSDOps));
    if (sd == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    sd->vksdOps.drawableType = VKSD_WINDOW;
    sd->vksdOps.background = VKUtil_DecodeJavaColor(backgroundRGB);
    sd->resizeCallback = WLVKSurfaceData_OnResize;
    VKSD_ResetSurface(&sd->vksdOps);
}

JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKSurfaceData_assignWlSurface(JNIEnv *env, jobject vksd, jlong wlSurfacePtr) {
    J2dRlsTraceLn2(J2D_TRACE_INFO, "WLVKSurfaceData_assignWlSurface(%p): wl_surface=%p", (void*)vksd, wlSurfacePtr);
    VKWinSDOps* sd = (VKWinSDOps*)SurfaceData_GetOps(env, vksd);
    if (sd == NULL) {
        J2dRlsTraceLn1(J2D_TRACE_ERROR, "WLVKSurfaceData_assignWlSurface(%p): VKWinSDOps is NULL", vksd);
        VK_UNHANDLED_ERROR();
    }

    if (sd->surface != VK_NULL_HANDLE) {
        VKSD_ResetSurface(&sd->vksdOps);
        J2dRlsTraceLn1(J2D_TRACE_INFO, "WLVKSurfaceData_assignWlSurface(%p): surface reset", vksd);
    }

    struct wl_surface* wl_surface = (struct wl_surface*)jlong_to_ptr(wlSurfacePtr);

    if (wl_surface != NULL) {
        VKGraphicsEnvironment* ge = VKGE_graphics_environment();
        VkWaylandSurfaceCreateInfoKHR surfaceCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR,
                .display = ge->waylandDisplay,
                .surface = wl_surface
        };
        VK_IF_ERROR(ge->vkCreateWaylandSurfaceKHR(ge->vkInstance, &surfaceCreateInfo, NULL, &sd->surface)) {
            VK_UNHANDLED_ERROR();
        }
        J2dRlsTraceLn1(J2D_TRACE_INFO, "WLVKSurfaceData_assignWlSurface(%p): surface created", vksd);
        // Swapchain will be created later after CONFIGURE_SURFACE.
    }
}

JNIEXPORT jint JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_pixelAt(JNIEnv *env, jobject vksd, jint x, jint y)
{
    J2dRlsTraceLn(J2D_TRACE_INFO, "Java_sun_java2d_vulkan_WLVKSurfaceData_pixelAt");
    jint pixel = 0xFFB6C1; // the color pink to make errors visible

    VKWinSDOps* sd = (VKWinSDOps*)SurfaceData_GetOps(env, vksd);
    JNU_CHECK_EXCEPTION_RETURN(env, pixel);
    if (sd == NULL) {
        return pixel;
    }

    VKDevice* device = sd->vksdOps.device;
    VKImage* image = sd->vksdOps.image;

    VkCommandBuffer cb = VKRenderer_Record(device->renderer);

    VKBuffer* buffer = VKBuffer_Create(device, sizeof(jint),
                                       VK_BUFFER_USAGE_TRANSFER_DST_BIT  |
                                       VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
                                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    VkBufferImageCopy region = {
            .bufferOffset = 0,
            .bufferRowLength = image->extent.width,
            .bufferImageHeight = image->extent.height,
            .imageSubresource= {
                    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                    .mipLevel = 0,
                    .baseArrayLayer = 0,
                    .layerCount = 1
            },
            .imageOffset = {x, y, 0},
            .imageExtent = {1, 1, 1}
    };

    device->vkCmdCopyImageToBuffer(cb, image->handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   buffer->handle,
                                   1, &region);
    VKRenderer_Flush(device->renderer);
    VKRenderer_Sync(device->renderer);
    void* pixelData;
    device->vkMapMemory(device->handle,  buffer->range.memory, 0, VK_WHOLE_SIZE, 0, &pixelData);
    pixel = *(int*)pixelData;
    device->vkUnmapMemory(device->handle,  buffer->range.memory);
    VKBuffer_Destroy(device, buffer);
    return pixel;
}

JNIEXPORT jarray JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_pixelsAt(JNIEnv *env, jobject vksd, jint x, jint y, jint width, jint height)
{
    J2dRlsTraceLn(J2D_TRACE_INFO, "Java_sun_java2d_vulkan_WLVKSurfaceData_pixelsAt");
    jint pixel = 0xFFB6C1; // the color pink to make errors visible

    VKWinSDOps* sd = (VKWinSDOps*)SurfaceData_GetOps(env, vksd);

    /*
    JNU_CHECK_EXCEPTION_RETURN(env, pixel);
    if (sd == NULL) {
        return pixel;
    }

    VKDevice* device = sd->vksdOps.device;
    VKImage* image = sd->vksdOps.image;

    VkCommandBuffer cb = VKRenderer_Record(device->renderer);

    VKBuffer* buffer = VKBuffer_Create(device, sizeof(jint),
                                       VK_BUFFER_USAGE_TRANSFER_DST_BIT  |
                                       VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
                                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    VkBufferImageCopy region = {
            .bufferOffset = 0,
            .bufferRowLength = image->extent.width,
            .bufferImageHeight = image->extent.height,
            .imageSubresource= {
                    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                    .mipLevel = 0,
                    .baseArrayLayer = 0,
                    .layerCount = 1
            },
            .imageOffset = {x, y, 0},
            .imageExtent = {1, 1, 1}
    };

    device->vkCmdCopyImageToBuffer(cb, image->handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   buffer->handle,
                                   1, &region);
    VKRenderer_Flush(device->renderer);
    VKRenderer_Sync(device->renderer);
    void* pixelData;
    device->vkMapMemory(device->handle,  buffer->range.memory, 0, VK_WHOLE_SIZE, 0, &pixelData);
    pixel = *(int*)pixelData;
    device->vkUnmapMemory(device->handle,  buffer->range.memory);
    VKBuffer_Destroy(device, buffer);
    return pixel;
     */
/*
    SurfaceDataOps *ops = SurfaceData_GetOps(env, wsd);
    JNU_CHECK_EXCEPTION_RETURN(env, NULL);
    if (ops == NULL) {
        return NULL;
    }

    SurfaceDataRasInfo rasInfo = {.bounds = {x, y, x + width, y + height}};
    if (ops->Lock(env, ops, &rasInfo, SD_LOCK_READ)) {
        JNU_ThrowByName(env, "java/lang/ArrayIndexOutOfBoundsException", "Coordinate out of bounds");
        return NULL;
    }

    if (rasInfo.bounds.x2 - rasInfo.bounds.x1 < width || rasInfo.bounds.y2 - rasInfo.bounds.y1 < height) {
        SurfaceData_InvokeUnlock(env, ops, &rasInfo);
        JNU_ThrowByName(env, "java/lang/ArrayIndexOutOfBoundsException", "Surface too small");
        return NULL;
    }

    jintArray arrayObj = NULL;
    ops->GetRasInfo(env, ops, &rasInfo);
    if (rasInfo.rasBase && rasInfo.pixelStride == sizeof(jint)) {
        size_t bufferSizeInPixels = width * height;
        arrayObj = (*env)->NewIntArray(env, bufferSizeInPixels);
        if (arrayObj != NULL) {
            jint *array = (*env)->GetPrimitiveArrayCritical(env, arrayObj, NULL);
            if (array == NULL) {
                JNU_ThrowOutOfMemoryError(env, "Wayland window pixels capture");
            } else {
                for (int i = y; i < y + height; i += 1) {
                    jint *destRow = &array[(i - y) * width];
                    jint *srcRow = (int*)((unsigned char *) rasInfo.rasBase + i * rasInfo.scanStride);
                    for (int j = x; j < x + width; j += 1) {
                        destRow[j - x] = srcRow[j];
                    }
                }
                (*env)->ReleasePrimitiveArrayCritical(env, arrayObj, array, 0);
            }
        }
    }
    SurfaceData_InvokeRelease(env, ops, &rasInfo);
    SurfaceData_InvokeUnlock(env, ops, &rasInfo);
    */
    jintArray arrayObj = NULL;
    size_t bufferSizeInPixels = width * height;
    arrayObj = (*env)->NewIntArray(env, bufferSizeInPixels);
    return arrayObj;
}

