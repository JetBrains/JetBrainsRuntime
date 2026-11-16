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
// Submits, the presents of the windows drawn into and the end of a flush (SKPresentation.h).
// All functions run on the Skia queue flusher thread.

#include "SKPresentation.h"
#include "SKGraphicsConfig.h"
#include "SKPlatform.h"
#include "SKSurfaceData.h"

#include "jni_util.h"
#include "Trace.h"

#include "include/gpu/graphite/GraphiteTypes.h"

#include <algorithm>
#include <unordered_set>

using namespace skgpu::graphite;

namespace {

// How many flushes in a row may hold their recordings: upload buffers and scratch textures
// recycle only after a submit.
constexpr int MAX_HELD_FLUSHES = 16;

std::unordered_set<SKSurfaceDataOps *> gTouchedWindows;     // drawn into and not presented yet
std::unordered_set<SKSurfaceDataOps *> gRequestedWindows;   // whose paint cycle ended in this flush (requestPresent)
std::unordered_set<SKGraphicsConfig *> gTouchedGraphicsConfigs;

void submitOne(SKGraphicsConfig *graphicsConfig, const bool syncToCPU) {
    graphicsConfig->endRecording();
    if (!graphicsConfig->context()->submit(syncToCPU ? SyncToCpu::kYes : SyncToCpu::kNo)) {
        // a lost device fails every submit until the recovery replaces the context (Win32SKScreenUpdateManager);
        // the first failure is traced, the device's own trace names the reason
        static bool traced = false;
        if (!traced) {
            traced = true;
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPresentation: a submit of context %p failed",
                          static_cast<void *>(graphicsConfig->context()));
        }
    }
}

// Tells the Java surface of a window that it was presented or that presenting
// failed, for the awt.window.counters of JBR (MTLLayer.countNewFrame on macOS).
void countFrame(JNIEnv *env, const SKSurfaceDataOps *destination, const bool presented) {
    if (env == nullptr || destination->sdOps.sdObject == nullptr) {
        return;
    }
    // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
    if (const jobject surfaceData = env->NewLocalRef(destination->sdOps.sdObject); surfaceData != nullptr) {
        JNU_CallMethodByName(env, nullptr, surfaceData, presented ? "countNewFrame" : "countDroppedFrame", "()V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(surfaceData);
    }
}

} // namespace

void SKPresentation::submit(SKGraphicsConfig *graphicsConfig, const bool syncToCPU) {
    if (graphicsConfig == nullptr || graphicsConfig->context() == nullptr) {
        return;
    }
    submitOne(graphicsConfig, syncToCPU);
}

bool SKPresentation::endOfFlush(JNIEnv *env, const bool syncToCPU, const bool presentAll) {
    // The windows this flush presents: those drawn into whose paint cycle ended in it, or every
    // window drawn into for a SYNC and the flusher's timeout flush. A window whose cycle has not
    // ended stays as it is on the screen: a flush in the middle of a paint (an image upload, a
    // read-back) must not show it half-painted. The Vulkan pipeline also presents on commit only.
    const bool everyWindow = syncToCPU || presentAll;
    const auto presentsNow = [everyWindow](SKSurfaceDataOps *window) {
        return everyWindow || gRequestedWindows.contains(window);
    };
    // A flush that presents nothing and waits for nothing does not end its recordings: the next
    // flush continues them, so each recording's fixed cost (snap, insert, submit) is paid per
    // present, not per render-queue buffer; about a tenth of the IDE's typing flushes present
    // nothing. At most MAX_HELD_FLUSHES in a row; the flusher's timeout flush (100 ms, presentAll)
    // follows a flush that returns true, and read-backs, syncs and released surfaces submit on
    // their own.
    static int heldFlushes = 0;
    if (!everyWindow && std::ranges::none_of(gTouchedWindows, presentsNow) && heldFlushes < MAX_HELD_FLUSHES
            && !gTouchedGraphicsConfigs.empty()) {
        heldFlushes++;
        gRequestedWindows.clear();
        return true;
    }
    heldFlushes = 0;
    // The windows are presented after what was drawn is submitted.
    for (SKGraphicsConfig *graphicsConfig : gTouchedGraphicsConfigs) {
        submitOne(graphicsConfig, syncToCPU);
    }
    gTouchedGraphicsConfigs.clear();
    for (auto touched = gTouchedWindows.begin(); touched != gTouchedWindows.end(); ) {
        SKSurfaceDataOps *destination = *touched;
        if (!presentsNow(destination)) {
            ++touched;
            continue;
        }
        if (destination->platform != nullptr) {
            const bool presented = destination->platform->requestRedraw();
            if (destination->countFrames) {
                countFrame(env, destination, presented);
            }
        }
        touched = gTouchedWindows.erase(touched);
    }
    gRequestedWindows.clear();
    return !gTouchedWindows.empty();   // windows drawn into and not presented: the timeout flush presents them
}

void SKPresentation::requestPresent(SKSurfaceDataOps *window) {
    gRequestedWindows.insert(window);
}

void SKPresentation::markTouched(SKGraphicsConfig *graphicsConfig) {
    gTouchedGraphicsConfigs.insert(graphicsConfig);
}

void SKPresentation::markTouchedWindow(SKSurfaceDataOps *window) {
    gTouchedWindows.insert(window);
}

void SKPresentation::forgetWindow(SKSurfaceDataOps *window) {
    gTouchedWindows.erase(window);
    gRequestedWindows.erase(window);
}

void SKPresentation::forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig) {
    gTouchedGraphicsConfigs.erase(graphicsConfig);
}
