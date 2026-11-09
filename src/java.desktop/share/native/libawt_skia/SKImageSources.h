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
#ifndef SKImageSources_h_Included
#define SKImageSources_h_Included

// The sources of image draws (the blits, SKBlitLoops, and the texture paint, SKPaints) as images of
// the current destination's context: a Skia surface of this context or another one's, or a software
// surface (a BufferedImage's) uploaded.
// All functions run on the Skia queue flusher thread.

#include "SKContext.h"

#include "jni.h"
#include "SurfaceData.h"

#include "include/core/SkImage.h"

struct SKSDOps;

namespace SKImageSources {

// The largest tile edge of a software source without alpha (SKBlitLoops' software blit): 64 MB of staging at most.
constexpr jint OPAQUE_TILE = 4096;

// A Skia source can be drawn on the current destination (its device is the destination's; another
// context's pending work is submitted first); `caller` names the draw in the warning otherwise.
[[nodiscard]] bool sourceUsable(SKSDOps *source, const char *caller);
// A Skia surface of the destination's context, sampled through its linked image (blitSourceImage, with the
// context whose recorder wrapped the surface).
[[nodiscard]] bool isLinkedSource(const SKSDOps *source);
[[nodiscard]] SKOwnedImage blitSourceImage(SKSDOps *source);

// The software source types without alpha: converted into a staging buffer with the alpha set.
[[nodiscard]] bool isOpaqueSoftwareType(jint sourceType);
// A software surface's pixels in (sx1, sy1)-(sx2, sy2) uploaded to a texture of the current context, with that
// context; sourceType is its SKSurfaceData PF_* type.
[[nodiscard]] SKOwnedImage imageFromSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops, jint sourceType,
                                                      jint sx1, jint sy1, jint sx2, jint sy2);
// The whole software surface `ops` (a BufferedImage's) uploaded to a texture of the current
// context, with that context; null for a pixel format other than the int formats Skia samples directly.
[[nodiscard]] SKOwnedImage imageOfSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops);

} // namespace SKImageSources

#endif /* SKImageSources_h_Included */
