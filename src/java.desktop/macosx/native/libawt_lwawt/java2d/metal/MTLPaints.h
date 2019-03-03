/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

/*
 */

#ifndef MTLPaints_h_Included
#define MTLPaints_h_Included

#include "MTLContext.h"

void MTLPaints_ResetPaint(MTLContext *mtlc);

void MTLPaints_SetColor(MTLContext *mtlc, jint pixel);

void MTLPaints_SetGradientPaint(MTLContext *mtlc,
                                jboolean useMask, jboolean cyclic,
                                jdouble p0, jdouble p1, jdouble p3,
                                jint pixel1, jint pixel2);

void MTLPaints_SetLinearGradientPaint(MTLContext *mtlc, BMTLSDOps *dstOps,
                                      jboolean useMask, jboolean linear,
                                      jint cycleMethod, jint numStops,
                                      jfloat p0, jfloat p1, jfloat p3,
                                      void *fractions, void *pixels);

void MTLPaints_SetRadialGradientPaint(MTLContext *mtlc, BMTLSDOps *dstOps,
                                      jboolean useMask, jboolean linear,
                                      jint cycleMethod, jint numStops,
                                      jfloat m00, jfloat m01, jfloat m02,
                                      jfloat m10, jfloat m11, jfloat m12,
                                      jfloat focusX,
                                      void *fractions, void *pixels);

void MTLPaints_SetTexturePaint(MTLContext *mtlc,
                               jboolean useMask,
                               jlong pSrcOps, jboolean filter,
                               jdouble xp0, jdouble xp1, jdouble xp3,
                               jdouble yp0, jdouble yp1, jdouble yp3);

#endif /* MTLPaints_h_Included */
