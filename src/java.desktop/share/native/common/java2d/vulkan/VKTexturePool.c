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

#include <pthread.h>

#include "VKImage.h"
#include "VKUtil.h"
#include "VKTexturePool.h"
#include "AccelTexturePool.h"
#include "jni.h"
#include "Trace.h"


#define TRACE_LOCK              0
#define TRACE_TEX               0


/* lock API */
ATexturePoolLockPrivPtr* VKTexturePoolLock_initImpl(void) {
    pthread_mutex_t *l = (pthread_mutex_t*)malloc(sizeof(pthread_mutex_t));
    CHECK_NULL_LOG_RETURN(l, NULL, "VKTexturePoolLock_initImpl: could not allocate pthread_mutex_t");

    int status = pthread_mutex_init(l, NULL);
    if (status != 0) {
      return NULL;
    }
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePoolLock_initImpl: lock=%p", l);
    return (ATexturePoolLockPrivPtr*)l;
}

void VKTexturePoolLock_DisposeImpl(ATexturePoolLockPrivPtr *lock) {
    pthread_mutex_t* l = (pthread_mutex_t*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePoolLock_DisposeImpl: lock=%p", l);
    pthread_mutex_destroy(l);
    free(l);
}

void VKTexturePoolLock_lockImpl(ATexturePoolLockPrivPtr *lock) {
    pthread_mutex_t* l = (pthread_mutex_t*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePoolLock_lockImpl: lock=%p", l);
    pthread_mutex_lock(l);
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePoolLock_lockImpl: lock=%p - locked", l);
}

void VKTexturePoolLock_unlockImpl(ATexturePoolLockPrivPtr *lock) {
    pthread_mutex_t* l = (pthread_mutex_t*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePoolLock_unlockImpl: lock=%p", l);
    pthread_mutex_unlock(l);
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePoolLock_unlockImpl: lock=%p - unlocked", l);
}

static void VKTexturePool_FindImageMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_ALL_MEMORY_PROPERTIES);
}

/* Texture allocate/free API */
static ATexturePrivPtr* VKTexturePool_createTexture(ADevicePrivPtr *device,
                                                    int width,
                                                    int height,
                                                    long format)
{
    CHECK_NULL_RETURN(device, NULL);
    VKImage* texture = VKImage_Create((VKDevice*)device, width, height,
                                      0, (VkFormat)format,
                                      VK_IMAGE_TILING_OPTIMAL,
                                      VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                                      VK_SAMPLE_COUNT_1_BIT, VKTexturePool_FindImageMemoryType);
    if IS_NULL(texture) {
        J2dRlsTrace(J2D_TRACE_ERROR, "VKTexturePool_createTexture: Cannot create VKImage");
        return NULL;
    }
    // usage   = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    // storage = MTLStorageModeManaged

    if (TRACE_TEX) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePool_createTexture: created texture: tex=%p, w=%d h=%d, pf=%d",
                                 texture, width, height, format);
    return texture;
}

static int VKTexturePool_bytesPerPixel(long format) {
    FormatGroup group = VKUtil_GetFormatGroup((VkFormat)format);
    if (group.bytes == 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKTexturePool_bytesPerPixel: format=%d not supported (4 bytes by default)", format);
        return 4;
    } else return (int) group.bytes;
}

static void VKTexturePool_freeTexture(ADevicePrivPtr *device, ATexturePrivPtr *texture) {
    CHECK_NULL(device);
    CHECK_NULL(texture);
    VKImage* tex = (VKImage*)texture;
    if (TRACE_TEX) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKTexturePool_freeTexture: free texture: tex=%p, w=%d h=%d, pf=%d",
                                 tex, tex->extent.width, tex->extent.height, tex->format);

    VKImage_Destroy((VKDevice*)device, tex);
}

/* VKTexturePoolHandle API */
void VKTexturePoolHandle_ReleaseTexture(VKTexturePoolHandle *handle) {
    ATexturePoolHandle_ReleaseTexture(handle);
}

VKImage* VKTexturePoolHandle_GetTexture(VKTexturePoolHandle *handle) {
    return ATexturePoolHandle_GetTexture(handle);
}

jint VKTexturePoolHandle_GetRequestedWidth(VKTexturePoolHandle *handle) {
    return ATexturePoolHandle_GetRequestedWidth(handle);
}

jint VKTexturePoolHandle_GetRequestedHeight(VKTexturePoolHandle *handle) {
    return ATexturePoolHandle_GetRequestedHeight(handle);
}

jint VKTexturePoolHandle_GetActualWidth(VKTexturePoolHandle *handle) {
    return ATexturePoolHandle_GetActualWidth(handle);
}

jint VKTexturePoolHandle_GetActualHeight(VKTexturePoolHandle *handle) {
    return ATexturePoolHandle_GetActualHeight(handle);
}


/* VKTexturePool API */
VKTexturePool* VKTexturePool_InitWithDevice(VKDevice *device) {
    CHECK_NULL_RETURN(device, NULL);
    // TODO: get vulkan device memory information (1gb fixed here):
    uint64_t maxDeviceMemory = 1024 * UNIT_MB;

    ATexturePoolLockWrapper *lockWrapper = ATexturePoolLockWrapper_init(&VKTexturePoolLock_initImpl,
                                                                        &VKTexturePoolLock_DisposeImpl,
                                                                        &VKTexturePoolLock_lockImpl,
                                                                        &VKTexturePoolLock_unlockImpl);

    return ATexturePool_initWithDevice(device,
                                       (jlong)maxDeviceMemory,
                                       &VKTexturePool_createTexture,
                                       &VKTexturePool_freeTexture,
                                       &VKTexturePool_bytesPerPixel,
                                       lockWrapper,
                                       VK_FORMAT_R8G8B8A8_UNORM);
}

void VKTexturePool_Dispose(VKTexturePool *pool) {
    ATexturePoolLockWrapper_Dispose(ATexturePool_getLockWrapper(pool));
    ATexturePool_Dispose(pool);
}

ATexturePoolLockWrapper* VKTexturePool_GetLockWrapper(VKTexturePool *pool) {
    return ATexturePool_getLockWrapper(pool);
}

VKTexturePoolHandle* VKTexturePool_GetTexture(VKTexturePool *pool,
                                              jint width,
                                              jint height,
                                              jlong format)
{
    return ATexturePool_getTexture(pool, width, height, format);
}
