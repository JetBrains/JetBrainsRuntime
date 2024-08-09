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

#ifndef AccelTexturePool_h_Included
#define AccelTexturePool_h_Included

#include "jni.h"


#define UNIT_KB                 1024
#define UNIT_MB                 (UNIT_KB * UNIT_KB)


/* useful macros (from jni_utils.h) */
#define IS_NULL(obj)        ((obj) == NULL)
#define IS_NOT_NULL(obj)    ((obj) != NULL)

#define CHECK_NULL(x)                           \
    do {                                        \
        if ((x) == NULL) {                      \
            return;                             \
        }                                       \
    } while (0)                                 \

#define CHECK_NULL_RETURN(x, y)                 \
    do {                                        \
        if ((x) == NULL) {                      \
            return (y);                         \
        }                                       \
    } while (0)                                 \

#define CHECK_NULL_LOG_RETURN(x, y, msg)             \
    do {                                             \
        if IS_NULL(x) {                              \
            J2dRlsTraceLn(J2D_TRACE_ERROR, (msg));   \
            return (y);                              \
        }                                            \
    } while (0)                                      \


/* Generic native-specific device (platform specific) */
typedef void ADevicePrivPtr;
/* Generic native-specific texture (platform specific) */
typedef void ATexturePrivPtr;


/* Texture allocate/free API */
typedef ATexturePrivPtr* (ATexturePool_createTexture)(ADevicePrivPtr *device,
                                                      int            width,
                                                      int            height,
                                                      long           format);

typedef void (ATexturePool_freeTexture)(ADevicePrivPtr  *device,
                                       ATexturePrivPtr  *texture);

typedef int (ATexturePool_bytesPerPixel)(long format);


/* lock API */
/* Generic native-specific lock (platform specific) */
typedef void ATexturePoolLockPrivPtr;

typedef ATexturePoolLockPrivPtr* (ATexturePoolLock_init)();

typedef void (ATexturePoolLock_dispose) (ATexturePoolLockPrivPtr *lock);

typedef void (ATexturePoolLock_lock)    (ATexturePoolLockPrivPtr *lock);

typedef void (ATexturePoolLock_unlock)  (ATexturePoolLockPrivPtr *lock);


/* forward-definitions (private) */
typedef struct ATexturePoolLockWrapper_ ATexturePoolLockWrapper;
typedef struct ATexturePoolItem_        ATexturePoolItem;
typedef struct ATexturePoolHandle_      ATexturePoolHandle;
typedef struct ATexturePoolCell_        ATexturePoolCell;
typedef struct ATexturePool_            ATexturePool;


/* ATexturePoolLockWrapper API */
ATexturePoolLockWrapper* ATexturePoolLockWrapper_init(ATexturePoolLock_init     *initFunc,
                                                      ATexturePoolLock_dispose  *disposeFunc,
                                                      ATexturePoolLock_lock     *lockFunc,
                                                      ATexturePoolLock_unlock   *unlockFunc);

void ATexturePoolLockWrapper_Dispose(ATexturePoolLockWrapper *lockWrapper);


/* ATexturePoolHandle API */
void ATexturePoolHandle_ReleaseTexture(ATexturePoolHandle *handle);

ATexturePrivPtr* ATexturePoolHandle_GetTexture(ATexturePoolHandle *handle);

jint ATexturePoolHandle_GetRequestedWidth(ATexturePoolHandle *handle);
jint ATexturePoolHandle_GetRequestedHeight(ATexturePoolHandle *handle);


/* ATexturePool API */
ATexturePool* ATexturePool_initWithDevice(ADevicePrivPtr             *device,
                                          jlong                      maxDeviceMemory,
                                          ATexturePool_createTexture *createTextureFunc,
                                          ATexturePool_freeTexture   *freeTextureFunc,
                                          ATexturePool_bytesPerPixel *bytesPerPixelFunc,
                                          ATexturePoolLockWrapper    *lockWrapper,
                                          jlong                      autoTestFormat);

void ATexturePool_Dispose(ATexturePool *pool);

ATexturePoolLockWrapper* ATexturePool_getLockWrapper(ATexturePool *pool);

ATexturePoolHandle* ATexturePool_getTexture(ATexturePool  *pool,
                                            jint          width,
                                            jint          height,
                                            jlong         format);

#endif /* AccelTexturePool_h_Included */
