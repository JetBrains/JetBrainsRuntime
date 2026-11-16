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
#ifndef SKPresentation_h_Included
#define SKPresentation_h_Included

// The end of the backend's work on the GPU side: submits, the presents of the windows drawn into, and
// the end of a flush (the render queue's flushBuffer, its SYNC and the direct entries).
// All functions run on the Skia queue flusher thread.

#include "jni.h"

class SKGraphicsConfig;
struct SKSurfaceDataOps;

namespace SKPresentation {

// The context's recording ends (SKGraphicsConfig::endRecording) and is submitted; syncToCPU waits for the GPU.
void submit(SKGraphicsConfig *graphicsConfig, bool syncToCPU);

// The end of a flush: submits, and presents the windows drawn into whose paint cycle ended in it
// (requestPresent), or every window drawn into for a SYNC (syncToCPU, which waits for the GPU) and the
// flusher's timeout flush (presentAll). A flush that presents no window and neither waits nor is the
// timeout flush holds its recordings for the next one. Returns true while recordings are held or
// windows drawn into are not presented, so that the timeout flush follows.
bool endOfFlush(JNIEnv *env, bool syncToCPU = false, bool presentAll = false);

// The window's paint cycle ended (FLUSH_BUFFER, SKSurfaceData.requestPresent): the end of this flush
// presents it if it was drawn into.
void requestPresent(SKSurfaceDataOps *window);

// A context and a window drawn into (SKContext::markTouched): the end of the flush submits the one
// and presents the other.
void markTouched(SKGraphicsConfig *graphicsConfig);
void markTouchedWindow(SKSurfaceDataOps *window);
// A released window surface is not presented, a disposed context not submitted, at the end of the flush.
void forgetWindow(SKSurfaceDataOps *window);
void forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig);

} // namespace SKPresentation

#endif /* SKPresentation_h_Included */
