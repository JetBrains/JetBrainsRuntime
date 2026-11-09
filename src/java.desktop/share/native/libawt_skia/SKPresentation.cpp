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
#include "SKNeverDestroyed.h"
#include "SKPendingBatches.h"
#include "SKPlatform.h"
#include "SKSurfaceData.h"

#include "jni_util.h"
#include "Trace.h"

#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Surface.h"

#include <unordered_map>
#include <unordered_set>

using namespace skgpu::graphite;

namespace {

// How many flushes in a row may hold their recordings: upload buffers and scratch textures
// recycle only after a submit.
constexpr int MAX_HELD_FLUSHES = 16;

std::unordered_set<SKSDOps *> gTouchedWindows;     // drawn into and not presented yet
std::unordered_set<SKGraphicsConfig *> gTouchedGraphicsConfigs;

// Where Graphite presents (Windows over Vulkan): a window's draw into its next swap chain image,
// recorded into the window's context at the end of a flush, before the flush's submit, so that
// the frame is one recording and one submit instead of two of each. The submit ends the context's
// recording with the image as its present target (SKGraphicsConfig::endRecording: wait for the
// acquire, signal the present, the present layout); SKPresentation::endOfFlush presents it after
// the submit. One window per context and flush; any other window of the context presents on its
// own (presentThroughGraphite). Registered (foldPresent) only after the context's pending batches
// are drawn, and taken by the submit that follows: nothing ends a recording in between.
struct SKFoldedPresent {
    SKSDOps *destination = nullptr;
    SKPlatformPresentTarget target;
    sk_sp<SkSurface> image;   // null: the image could not be wrapped, nothing drawn into it
    bool inserted = false;
};
// Never destroyed (SKNeverDestroyed.h): a present still registered at process exit (a flush the exit
// interrupted) holds a swap chain image's wrapper, of a Graphite context that is not released then either.
using SKFoldedPresents = std::unordered_map<SKGraphicsConfig *, SKFoldedPresent>;
SKNeverDestroyed<SKFoldedPresents> gFoldedPresents;

// Nothing to insert for an acquired image (no recording, or the context was not submitted): the
// acquire is released as a failed one.
void releaseAcquiredImage(const SKPlatformPresentTarget &target) {
    if (target.finishedProc != nullptr) {
        target.finishedProc(target.finishedContext, skgpu::CallbackResult::kFailed);
    }
}

// The context's recording ends; a folded present of the context not inserted yet goes with it.
void endRecordingOf(SKGraphicsConfig *graphicsConfig) {
    auto folded = gFoldedPresents->find(graphicsConfig);
    if (folded == gFoldedPresents->end() || folded->second.inserted) {
        graphicsConfig->endRecording();
        return;
    }
    SKFoldedPresent &present = folded->second;
    switch (graphicsConfig->endRecording(&present.target, present.image.get())) {
        case SKRecordingEnd::Inserted:
            present.inserted = true;
            break;
        case SKRecordingEnd::InsertFailed:
            present.inserted = true;
            present.image.reset();   // nothing to present
            break;
        case SKRecordingEnd::NothingRecorded:
            if (present.target.finishedProc != nullptr) {
                releaseAcquiredImage(present.target);
                present.inserted = true;
                present.image.reset();
            }
            break;
    }
}

void submitOne(SKGraphicsConfig *graphicsConfig, bool syncToCPU) {
    endRecordingOf(graphicsConfig);
    graphicsConfig->context()->submit(syncToCPU ? SyncToCpu::kYes : SyncToCpu::kNo);
}

// Tells the Java surface of a window that it was presented or that presenting
// failed, for JBR's awt.window.counters (MTLLayer.countNewFrame on macOS).
void countFrame(JNIEnv *env, SKSDOps *destination, bool presented) {
    if (env == nullptr || destination->sdOps.sdObject == nullptr) {
        return;
    }
    jobject surfaceData = env->NewLocalRef(destination->sdOps.sdObject);
    if (surfaceData != nullptr) {
        JNU_CallMethodByName(env, nullptr, surfaceData, presented ? "countNewFrame" : "countDroppedFrame", "()V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(surfaceData);
    }
}

// Draws the window surface's client area into the acquired swap chain image (wrapped once per
// image of a swap chain generation, SKSurfaceState::presentTarget); null if the image cannot be wrapped.
sk_sp<SkSurface> drawIntoPresentTarget(SKSurfaceState *surfaceState, const SKPlatformPresentTarget &target) {
    sk_sp<SkSurface> image = surfaceState->presentTarget(target);
    if (image) {
        const SkIRect &source = target.source;
        SkPaint paint;
        paint.setBlendMode(SkBlendMode::kSrc);
        image->getCanvas()->drawImageRect(SkSurfaces::AsImage(surfaceState->surface()), SkRect::Make(source),
                                          SkRect::MakeIWH(source.width(), source.height()),
                                          SkSamplingOptions(SkFilterMode::kNearest), &paint,
                                          SkCanvas::kFast_SrcRectConstraint);
    } else {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "drawIntoPresentTarget: cannot wrap the swap chain image (%dx%d)",
                      target.texture.dimensions().width(), target.texture.dimensions().height());
    }
    return image;
}

// Before the flush's submit (SKFoldedPresent): acquires the window's next swap chain image and
// records the window's draw into it. False where the platform presents itself, when the window
// was never drawn into, or when the context has a folded present already.
bool foldPresent(SKSDOps *destination) {
    SKSurfaceState *surfaceState = destination->state;
    SKGraphicsConfig *graphicsConfig = surfaceState != nullptr ? surfaceState->graphicsConfig() : nullptr;
    if (graphicsConfig == nullptr || !surfaceState->surface() || graphicsConfig->context() == nullptr
            || gFoldedPresents->count(graphicsConfig) != 0) {
        return false;
    }
    SKPlatformPresentTarget target;
    if (!SKPlatform::acquirePresentTarget(destination->platform, &target)) {
        return false;
    }
    if (!target.texture.isValid()) {
        SKFoldedPresent &folded = (*gFoldedPresents)[graphicsConfig];
        folded.destination = destination;
        folded.inserted = true;   // no image now: nothing to insert, the present fails
        return true;
    }
    // The window's batched quads, before the draw that presents them. The present is registered only
    // afterwards, complete: a recording ended on the way must not be inserted with its target.
    SKPendingBatches::flushOf(graphicsConfig);
    sk_sp<SkSurface> image = drawIntoPresentTarget(surfaceState, target);
    SKFoldedPresent &folded = (*gFoldedPresents)[graphicsConfig];
    folded.destination = destination;
    folded.target = target;
    folded.image = std::move(image);
    return true;
}

// Where Graphite presents (SKPlatform::acquirePresentTarget, Windows over Vulkan) and the window's
// present was not folded into the flush (a second window of its context): draws the window
// surface's client area into the next swap chain image in a recording of its own, which waits for
// the image and leaves it ready to present, submits it and presents. False where the platform
// presents itself (SKPlatform::requestRedraw).
bool presentThroughGraphite(SKSDOps *destination, bool *presented) {
    SKSurfaceState *surfaceState = destination->state;
    SKGraphicsConfig *graphicsConfig = surfaceState != nullptr ? surfaceState->graphicsConfig() : nullptr;
    if (graphicsConfig == nullptr || !surfaceState->surface() || graphicsConfig->context() == nullptr) {
        return false;   // never drawn into: nothing Graphite could present
    }
    SKPlatformPresentTarget target;
    if (!SKPlatform::acquirePresentTarget(destination->platform, &target)) {
        return false;
    }
    *presented = false;
    if (!target.texture.isValid()) {
        return true;
    }
    sk_sp<SkSurface> image = drawIntoPresentTarget(surfaceState, target);
    // inserted even without a draw: the image's acquire semaphore must be waited for. The flush's submit
    // (endOfFlush) drew the context's pending batches and no glyph cell was used since, so the end of this
    // recording draws nothing else and its generation step changes no cell's age.
    switch (graphicsConfig->endRecording(&target, image.get())) {
        case SKRecordingEnd::NothingRecorded:
            J2dRlsTraceLn(J2D_TRACE_WARNING, "presentThroughGraphite: nothing recorded");
            releaseAcquiredImage(target);
            return true;
        case SKRecordingEnd::InsertFailed:
            return true;
        case SKRecordingEnd::Inserted:
            break;
    }
    graphicsConfig->context()->submit(SyncToCpu::kNo);
    *presented = image != nullptr && SKPlatform::presentTarget(destination->platform);
    return true;
}

// The folded present of a window, if its context folded this window's.
SKFoldedPresent *foldedPresentOf(SKSDOps *destination) {
    for (auto &entry : *gFoldedPresents) {
        if (entry.second.destination == destination) {
            return &entry.second;
        }
    }
    return nullptr;
}

} // namespace

void SKPresentation::submit(SKGraphicsConfig *graphicsConfig, bool syncToCPU) {
    if (graphicsConfig == nullptr || graphicsConfig->context() == nullptr) {
        return;
    }
    submitOne(graphicsConfig, syncToCPU);
}

bool SKPresentation::endOfFlush(JNIEnv *env, bool syncToCPU, bool presentAll) {
    // A flush that presents nothing and waits for nothing does not end its recordings: the next
    // flush continues them, so each recording's fixed cost (snap, insert, submit: about 280 µs on
    // Skia/Vulkan in the IDE) is paid per present, not per render-queue buffer; about a tenth of
    // the IDE's typing flushes present nothing. At most MAX_HELD_FLUSHES in a row; the flusher's
    // timeout flush (100 ms, presentAll) follows a flush that returns true, and read-backs, syncs
    // and released surfaces submit on their own.
    static int heldFlushes = 0;
    if (!syncToCPU && !presentAll && gTouchedWindows.empty() && heldFlushes < MAX_HELD_FLUSHES
            && !gTouchedGraphicsConfigs.empty()) {
        heldFlushes++;
        return true;
    }
    heldFlushes = 0;
    // Every window drawn into is presented at the end of the flush. Where Graphite presents, each
    // context's first window is drawn into its swap chain image now, inside the recording the
    // submit below sends (SKFoldedPresent).
    for (SKSDOps *destination : gTouchedWindows) {
        if (destination->platform != nullptr && foldPresent(destination)) {
            gTouchedGraphicsConfigs.insert(destination->state->graphicsConfig());   // the folded present's recording is submitted below
        }
    }
    for (SKGraphicsConfig *graphicsConfig : gTouchedGraphicsConfigs) {
        submitOne(graphicsConfig, syncToCPU);
    }
    for (SKGraphicsConfig *graphicsConfig : gTouchedGraphicsConfigs) {
        graphicsConfig->syncPipelineDataWhenDue();
    }
    gTouchedGraphicsConfigs.clear();
    for (auto touched = gTouchedWindows.begin(); touched != gTouchedWindows.end(); ) {
        SKSDOps *destination = *touched;
        if (destination->platform != nullptr) {
            bool presented;
            if (SKFoldedPresent *folded = foldedPresentOf(destination); folded != nullptr) {
                if (!folded->inserted) {
                    // never inserted (its context was not submitted): release what the acquire took
                    releaseAcquiredImage(folded->target);
                }
                presented = folded->inserted && folded->image && SKPlatform::presentTarget(destination->platform);
            } else if (!presentThroughGraphite(destination, &presented)) {
                presented = SKPlatform::requestRedraw(destination->platform);
            }
            if (destination->countFrames) {
                countFrame(env, destination, presented);
            }
        }
        touched = gTouchedWindows.erase(touched);
    }
    gFoldedPresents->clear();
    return false;   // every window drawn into is presented
}

void SKPresentation::markTouched(SKGraphicsConfig *graphicsConfig) {
    gTouchedGraphicsConfigs.insert(graphicsConfig);
}

void SKPresentation::markTouchedWindow(SKSDOps *window) {
    gTouchedWindows.insert(window);
}

void SKPresentation::forgetWindow(SKSDOps *window) {
    gTouchedWindows.erase(window);
}

void SKPresentation::forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig) {
    gTouchedGraphicsConfigs.erase(graphicsConfig);
}
