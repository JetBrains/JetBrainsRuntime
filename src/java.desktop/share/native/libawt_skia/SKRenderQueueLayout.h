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
#ifndef SKRenderQueueLayout_h_Included
#define SKRenderQueueLayout_h_Included

// How the render queue lays out the scanlines and spans it hands to the modules (SKRenderPipe's draws
// and fills, SKContext's shape clip): (x1, x2, y) triples and (x1, y1, x2, y2) quadruples of ints
// (BufferedRenderPipe.BYTES_PER_SCANLINE, BYTES_PER_SPAN).

#include "jni.h"
#include "sun_java2d_pipe_BufferedRenderPipe.h"

constexpr jint SK_INTS_PER_SCANLINE = sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SCANLINE / sizeof(jint);
constexpr jint SK_INTS_PER_SPAN = sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SPAN / sizeof(jint);

#endif /* SKRenderQueueLayout_h_Included */
