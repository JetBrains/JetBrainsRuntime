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
#ifndef SKRectBatch_h_Included
#define SKRectBatch_h_Included

// The rect batch: the non-AA geometry of the current paint (rects, lines, spans, polygons, non-AA
// parallelograms, SKRenderPipe), drawn as one mesh (SKQuadBatch.h). One for all contexts: it is begun on
// the canvas of the current destination, and drawn before anything else touches a canvas
// (SKPendingBatches.h) and before a context ends its recording (SKGraphicsConfig::endRecording).
// All functions run on the Skia queue flusher thread.

#include "SKQuadBatch.h"

namespace SKRectBatch {

[[nodiscard]] SKQuadBatch &batch();

// A context is disposed (DISPOSE_CONFIG): the batch, drawn by the context's submit, drops the paints it keeps
// (SKQuadBatch::forgetPaints), which may hold the context's images.
void forgetPaints();

} // namespace SKRectBatch

#endif /* SKRectBatch_h_Included */
