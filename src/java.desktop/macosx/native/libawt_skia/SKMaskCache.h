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

#ifndef SKMaskCache_h_Included
#define SKMaskCache_h_Included

#include "jni.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"

struct SKConfigContext;
struct SKMaskCache;

// Marlin's coverage tiles (MASK_FILL, at most 32x32 pixels each) go into an
// alpha-only atlas with round-robin cells, like MTLVertexCache's mask cache:
// tiles are not addressable, so the atlas is simply refilled from the start
// when it is full, after the recording that sampled it has been ended.
SKMaskCache *SKMaskCache_Create(SKConfigContext *cc);
void SKMaskCache_Free(SKMaskCache *mc);

bool SKMaskCache_Fits(jint w, jint h);

// Draws one tile at device position x, y with the given paint. A colour paint
// is batched into one mesh; a shader paint (gradient, texture) draws the tile
// from the atlas individually, so the shader is sampled in device space.
void SKMaskCache_Draw(SKMaskCache *mc, SkCanvas *canvas, const SkPaint &paint,
                      jint x, jint y, jint w, jint h, const unsigned char *mask, jint maskscan);

// Draws the queued mesh. Called before any other draw, state or destination
// change, and before ending a recording.
void SKMaskCache_Flush(SKMaskCache *mc);

#endif /* SKMaskCache_h_Included */
