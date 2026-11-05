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

#ifndef SKImageAtlas_h_Included
#define SKImageAtlas_h_Included

#include "jni.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"

struct SKConfigContext;
struct SKSurfaceState;
struct SKImageAtlas;

// Small Skia-surface sources (the images of a UI, cached images) blitted unscaled and pixel-aligned are copied once into
// an RGBA atlas of the context and drawn from it as sprites of one mesh (SKQuadBatch family 3), instead of
// one Graphite draw per blit (each with its own image paint key, and a flush check of the source's device).
// A copy is keyed by the source surface's identity (SKSurfaceState::uid) and its rect, and dated by the
// source's content version (SKSurfaceState::contentVersion): a source drawn into since is copied again.
// The atlas is refilled from the start when it is full, after the batch was drawn and the recording ended.
SKImageAtlas *SKImageAtlas_Create(SKConfigContext *cc);
void SKImageAtlas_Free(SKImageAtlas *ia);

// The rect fits a cell (SKImageAtlas_Draw may take it).
bool SKImageAtlas_Fits(jint w, jint h);

// Draws the source rect (sx, sy, w, h) of `source` (an image of the surface `st`) at integer device position
// (dx, dy) with `paint` (SrcOver, the extra alpha in its alpha, no shader) as a sprite of the atlas batch on
// `canvas`; false if the atlas cannot take it (the caller draws the blit itself).
bool SKImageAtlas_Draw(SKImageAtlas *ia, SkCanvas *canvas, const SkPaint &paint, SKSurfaceState *st,
                      const sk_sp<SkImage> &source, jint sx, jint sy, jint w, jint h, jint dx, jint dy);

// Draws the queued mesh (before any other draw that may overlap it, a state or destination change, and the
// end of a recording).
void SKImageAtlas_Flush(SKImageAtlas *ia);
// The queued mesh has a sprite within r (SKQuadBatch::touches).
bool SKImageAtlas_Touches(const SKImageAtlas *ia, const SkRect &r);

// Diagnostics and tests (SKEnvironment.imageAtlasStats): since the start, blits drawn from the atlas, copies
// into it, refills, and the atlas batch's mesh draws.
enum SKImageAtlasStat { SK_IMAGE_ATLAS_BLITS, SK_IMAGE_ATLAS_COPIES, SK_IMAGE_ATLAS_REFILLS, SK_IMAGE_ATLAS_BATCH_DRAWS, SK_IMAGE_ATLAS_STAT_COUNT };
jlong SKImageAtlas_Stat(SKImageAtlasStat stat);

#endif /* SKImageAtlas_h_Included */
