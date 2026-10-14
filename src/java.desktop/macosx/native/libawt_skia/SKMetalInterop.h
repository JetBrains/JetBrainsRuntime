/*
 * Copyright 2026 JetBrains s.r.o.
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
#ifndef SKMetalInterop_h_Included
#define SKMetalInterop_h_Included

// How libawt_skia talks to the Metal pipeline in libawt_lwawt without linking
// against it: the destination texture and layer are reached through the native
// ops of the wrapped MTLSurfaceData (BMTLSDOps, shared header), the two private
// Metal structs below are mirrored field-for-field, and Objective-C objects are
// messaged through informal protocols (resolved at runtime, no symbols needed).

#import <Metal/Metal.h>
#include "jni.h"
#include "MTLSurfaceDataBase.h"   // BMTLSDOps, SurfaceData.h, Trace.h

// The Metal headers that declare these structs (MTLGraphicsConfig.h,
// MTLSurfaceData.h) pull in Cocoa, AWTWindow and MTLLayer; mirroring the two
// plain structs keeps libawt_skia independent of libawt_lwawt's include tree.
// Keep them in sync with MTLGraphicsConfig.h and MTLSurfaceData.h.
// Mirror of MTLGraphicsConfigInfo (MTLGraphicsConfig.h).
typedef struct {
    id    context;      // MTLContext*
    jint  displayID;
} SKMTLGraphicsConfigInfo;

// Mirror of MTLSDOps (MTLSurfaceData.h); referenced by BMTLSDOps.privOps.
typedef struct {
    id    peerData;     // AWTView*
    id    layer;        // MTLLayer*
    jint  argb[4];      // background clear color
    SKMTLGraphicsConfigInfo *configInfo;
} SKMTLSDOps;

// Subset of MTLContext we use.
@protocol SKMTLContextLike
- (id<MTLDevice>) device;
- (id<MTLCommandQueue>) commandQueue;
- (id<MTLCommandQueue>) blitCommandQueue;
@end

// Subset of MTLLayer we use.
@protocol SKMTLLayerLike
- (void) startRedraw;
@end

static inline SKMTLSDOps *SKMTL_PrivOps(BMTLSDOps *bmtlsdo) {
    return (SKMTLSDOps *) bmtlsdo->privOps;
}

static inline SKMTLGraphicsConfigInfo *SKMTL_ConfigInfo(BMTLSDOps *bmtlsdo) {
    SKMTLSDOps *priv = SKMTL_PrivOps(bmtlsdo);
    return priv != NULL ? priv->configInfo : NULL;
}

#endif /* SKMetalInterop_h_Included */
