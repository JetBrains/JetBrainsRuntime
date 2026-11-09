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
#ifndef SKBlitLoops_h_Included
#define SKBlitLoops_h_Included

// The native side of sun.java2d.skia.SKBlitLoops: blits from Skia and software surfaces (transformed,
// tiled, through the image atlas), copyArea and readbacks into software surfaces.
// All functions run on the Skia queue flusher thread.

#include "jni.h"

namespace SKBlitLoops {

// The blits take the source rect (sx1, sy1)-(sx2, sy2) to the destination rect (dx1, dy1)-(dx2, dy2),
// as the Java2D blit loops name them; sourceOps and destinationOps are the surfaces' native ops.
void blit(JNIEnv *env, jint packedParams, jint sx1, jint sy1, jint sx2, jint sy2,
          jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2, jlong sourceOps, jlong destinationOps);
void surfaceToSoftwareBlit(JNIEnv *env, jint sx, jint sy, jint dx, jint dy, jint width, jint height,
                           jint destType, jlong sourceOps, jlong destinationOps);
void copyArea(jint x, jint y, jint width, jint height, jint dx, jint dy);

} // namespace SKBlitLoops

#endif /* SKBlitLoops_h_Included */
