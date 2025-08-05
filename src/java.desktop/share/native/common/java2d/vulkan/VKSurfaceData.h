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

#ifndef VKSurfaceData_h_Included
#define VKSurfaceData_h_Included

#include "java_awt_image_AffineTransformOp.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"

#include "SurfaceData.h"
#include "Trace.h"

/**
 * The VKSDOps structure describes a native Vulkan surface and contains all
 * information pertaining to the native surface.  Some information about
 * the more important/different fields:
 *
 *     void *privOps;
 * Pointer to native-specific SurfaceData info, such as the
 * native Drawable handle and GraphicsConfig data.
 *
 *     jobject graphicsConfig;
 * Strong reference to the VKGraphicsConfig used by this VKSurfaceData.
 *
 *     jint drawableType;
 * The surface type; can be any one of the surface type constants defined
 * below (VK_WINDOW, VK_TEXTURE, etc).
 *
 *     jboolean isOpaque;
 * If true, the surface should be treated as being fully opaque.
 *
 *     jboolean needsInit;
 * If true, the surface requires some one-time initialization, which should
 * be performed after a context has been made current to the surface for
 * the first time.
 *
 *     jint x/yOffset
 * The offset in pixels of the Vulkan viewport origin from the lower-left
 * corner of the heavyweight drawable.
 *
 *     jint width/height;
 * The cached surface bounds.  For offscreen surface types (VK_RT_TEXTURE,
 * VK_TEXTURE, etc.) these values must remain constant.  Onscreen window
 * surfaces (VK_WINDOW, etc.) may have their
 * bounds changed in response to a programmatic or user-initiated event, so
 * these values represent the last known dimensions.  To determine the true
 * current bounds of this surface, query the native Drawable through the
 * privOps field.
 *
 */
typedef struct _VKSDOps {
    SurfaceDataOps               sdOps;
    void                         *privOps;
    jobject                      graphicsConfig;
    jint                         drawableType;
    jboolean                     isOpaque;
    jboolean                     needsInit;
    jint                         xOffset;
    jint                         yOffset;
    jint                         width;
    jint                         height;
  } VKSDOps;

/**
 * These are shorthand names for the surface type constants defined in
 * VKSurfaceData.java.
 */
#define VKSD_UNDEFINED       sun_java2d_pipe_hw_AccelSurface_UNDEFINED
#define VKSD_WINDOW          sun_java2d_pipe_hw_AccelSurface_WINDOW
#define VKSD_TEXTURE         sun_java2d_pipe_hw_AccelSurface_TEXTURE
#define VKSD_RT_TEXTURE      sun_java2d_pipe_hw_AccelSurface_RT_TEXTURE
#endif /* VKSurfaceData_h_Included */
