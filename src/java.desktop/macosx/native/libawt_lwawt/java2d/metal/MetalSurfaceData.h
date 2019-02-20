/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef MetalSurfaceData_h_Included
#define MetalSurfaceData_h_Included

#import "SurfaceData.h"
#import "MetalGraphicsConfig.h"
#import "AWTWindow.h"
#import "MetalLayer.h"

/**
 * The MetalSDOps structure contains the Metal-specific information for
 * given MetalSurfaceData.
 */
typedef struct _MetalSDOps {
    SurfaceDataOps           sdOps;
    AWTView                  *peerData;
    MetalLayer               *layer;
    //GLclampf              argb[4]; // background clear color
    MetalGraphicsConfigInfo  *configInfo;
    jint                     xOffset;
    jint                     yOffset;
    jboolean                 isOpaque;
    id<MTLTexture>           mtlTexture;
    //id<MTLRenderPipelineState> renderPipelineState;
    jint                  width;
    jint                  height;
} MetalSDOps;

#endif /* MetalSurfaceData_h_Included */
