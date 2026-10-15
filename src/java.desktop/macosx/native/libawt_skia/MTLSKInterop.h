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
#ifndef MTLSKInterop_h_Included
#define MTLSKInterop_h_Included

// How libawt_skia talks to the Metal pipeline in libawt_lwawt without linking
// against it: the destination texture, layer and configuration are reached
// through the native ops of the wrapped MTLSurfaceData (BMTLSDOps, MTLSDOps and
// MTLGraphicsConfigInfo from the shared header), and Objective-C objects are
// messaged through informal protocols (resolved at runtime, no symbols needed).

#import <Metal/Metal.h>
#include "jni.h"
#include "MTLSurfaceDataBase.h"   // BMTLSDOps, MTLSDOps, MTLGraphicsConfigInfo, SurfaceData.h, Trace.h

// Subset of MTLContext we use.
@protocol MTLSKContextLike
- (id<MTLDevice>) device;
- (id<MTLCommandQueue>) commandQueue;
- (id<MTLCommandQueue>) blitCommandQueue;
@end

// Subset of MTLLayer we use.
@protocol MTLSKLayerLike
- (void) startRedraw;
@end

static inline MTLSDOps *MTLSK_PrivOps(BMTLSDOps *bmtlsdo) {
    return (MTLSDOps *) bmtlsdo->privOps;
}

static inline MTLGraphicsConfigInfo *MTLSK_ConfigInfo(BMTLSDOps *bmtlsdo) {
    MTLSDOps *priv = MTLSK_PrivOps(bmtlsdo);
    return priv != NULL ? priv->configInfo : NULL;
}

#endif /* MTLSKInterop_h_Included */
