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

#ifndef VKTexturePool_h_Included
#define VKTexturePool_h_Included

#include "AccelTexturePool.h"
#include "jni.h"


/* VKTexturePoolHandle API */
typedef ATexturePoolHandle VKTexturePoolHandle;

void VKTexturePoolHandle_ReleaseTexture(VKTexturePoolHandle *handle);

ATexturePrivPtr* VKTexturePoolHandle_GetTexture(VKTexturePoolHandle *handle);

jint VKTexturePoolHandle_GetRequestedWidth(VKTexturePoolHandle *handle);
jint VKTexturePoolHandle_GetRequestedHeight(VKTexturePoolHandle *handle);


/* VKTexturePool API */
typedef ATexturePool VKTexturePool;

VKTexturePool* VKTexturePool_InitWithDevice(VKDevice *device) ;

void VKTexturePool_Dispose(VKTexturePool *pool);

ATexturePoolLockWrapper* VKTexturePool_GetLockWrapper(VKTexturePool *pool);

VKTexturePoolHandle* VKTexturePool_GetTexture(VKTexturePool *pool,
                                        jint width,
                                        jint height,
                                        jlong format);

#endif /* VKTexturePool_h_Included */
