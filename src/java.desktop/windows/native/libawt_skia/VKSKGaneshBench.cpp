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

// EXPERIMENT (x64hw, 2026-11-04), diagnostics only: Ganesh's per-draw floor on the Vulkan device the
// backend's Graphite context uses (SKEnvironment.benchGanesh), the same kinds as SKBackend_BenchGraphite,
// so the two Skia backends compare on one device, one queue and one thread. Nothing else uses Ganesh.

#include "VKSKPlatform.h"
#include "SKBackend.h"
#include "SKStats.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/core/SkVertices.h"
#include "include/gpu/GpuTypes.h"
#include "include/gpu/ganesh/GrContextOptions.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"

#include <algorithm>
#include <vector>
#include "jni.h"

// `draws` draws of one kind into an off-screen 1024x1024 surface, then the context's flush (the ops
// prepared and executed into the command buffer: Graphite's snap and insert together) and submit;
// per frame the nanoseconds of each, the median over `frames` frames (after as many warm-up frames)
// divided by the draws in out[0..2] (record, flush, submit). Kinds as SKBackend_BenchGraphite. A
// Ganesh context of its own per call, on the device's queue; Graphite's work is drained first (the
// caller synced the queue) and the device is idle when it returns.
static bool benchGanesh(int kind, int draws, int frames, bool reduceOpsTaskSplitting, double out[3]) {
    std::shared_ptr<const skgpu::VulkanBackendContext> backend = VKSK_SharedBackend();
    if (backend == nullptr || draws <= 0 || frames <= 0) {
        return false;
    }
    GrContextOptions options;
    options.fReduceOpsTaskSplitting = reduceOpsTaskSplitting ? GrContextOptions::Enable::kYes
                                                             : GrContextOptions::Enable::kNo;
    sk_sp<GrDirectContext> context = GrDirectContexts::MakeVulkan(*backend, options);
    if (!context) {
        return false;
    }
    const SkImageInfo big = SkImageInfo::Make(1024, 1024, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    const SkImageInfo small = SkImageInfo::Make(32, 32, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> surface = SkSurfaces::RenderTarget(context.get(), skgpu::Budgeted::kYes, big, 0,
                                                        kTopLeft_GrSurfaceOrigin, nullptr);
    sk_sp<SkSurface> second = SkSurfaces::RenderTarget(context.get(), skgpu::Budgeted::kYes, big, 0,
                                                       kTopLeft_GrSurfaceOrigin, nullptr);
    sk_sp<SkSurface> source = SkSurfaces::RenderTarget(context.get(), skgpu::Budgeted::kYes, small, 0,
                                                       kTopLeft_GrSurfaceOrigin, nullptr);
    if (!surface || !second || !source) {
        return false;
    }
    source->getCanvas()->clear(SK_ColorBLUE);
    sk_sp<SkImage> image = source->makeImageSnapshot();
    std::vector<sk_sp<SkImage>> images;
    for (int i = 0; i < 16; i++) {
        source->getCanvas()->clear(SkColorSetARGB(255, (U8CPU) (i * 16), 80, 160));
        images.push_back(source->makeImageSnapshot());   // copy on write: 16 textures
    }
    context->flushAndSubmit(GrSyncCpu::kYes);
    const SkPoint pos[4] = {{0, 0}, {16, 0}, {16, 16}, {0, 16}};
    const uint16_t idx[6] = {0, 1, 2, 0, 2, 3};
    std::vector<double> t[3];
    for (int f = 0; f < 2 * frames; f++) {
        SkCanvas *canvas = surface->getCanvas();
        SkPaint paint;
        const uint64_t t0 = SKStats_Now();
        for (int i = 0; i < draws; i++) {
            const float x = (float) ((i * 37) % 1000), y = (float) ((i * 53) % 1000);
            switch (kind) {
                case 0: paint.setColor(SK_ColorRED); canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint); break;
                case 1: paint.setColor(i % 2 ? SK_ColorRED : SK_ColorGREEN); canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint); break;
                case 2: {
                    canvas->save();
                    canvas->translate(x, y);
                    paint.setColor(SK_ColorRED);
                    canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, pos, nullptr, nullptr, 6, idx),
                                         SkBlendMode::kModulate, paint);
                    canvas->restore();
                    break;
                }
                case 3: canvas->drawImageRect(image, SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint); break;
                case 4: canvas->drawImageRect(images[(size_t) i % images.size()], SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint); break;
                case 5:
                    if (i % 3 == 0) {
                        paint.setColor(SK_ColorRED);
                        canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint);
                    } else if (i % 3 == 1) {
                        canvas->save();
                        canvas->translate(x, y);
                        canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, pos, nullptr, nullptr, 6, idx),
                                             SkBlendMode::kModulate, paint);
                        canvas->restore();
                    } else {
                        canvas->drawImageRect(images[(size_t) i % images.size()], SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint);
                    }
                    break;
                default: {
                    SkCanvas *c = (i / 50) % 2 == 0 ? canvas : second->getCanvas();
                    paint.setColor(SK_ColorRED);
                    c->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint);
                    break;
                }
            }
        }
        const uint64_t t1 = SKStats_Now();
        context->flush(GrFlushInfo());
        const uint64_t t2 = SKStats_Now();
        context->submit(GrSyncCpu::kNo);
        const uint64_t t3 = SKStats_Now();
        context->submit(GrSyncCpu::kYes);   // drained between frames, not timed
        if (f >= frames) {
            t[0].push_back((double) (t1 - t0));
            t[1].push_back((double) (t2 - t1));
            t[2].push_back((double) (t3 - t2));
        }
    }
    images.clear();
    image.reset();
    source.reset();
    second.reset();
    surface.reset();
    context->flushAndSubmit(GrSyncCpu::kYes);
    context.reset();
    for (int i = 0; i < 3; i++) {
        std::sort(t[i].begin(), t[i].end());
        out[i] = t[i][t[i].size() / 2] / draws;
    }
    return true;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_sun_java2d_skia_SKEnvironment_benchGaneshNative(JNIEnv *env, jclass clazz, jint kind, jint draws, jint frames,
                                                     jboolean reduceOpsTaskSplitting)
{
    SK_ENTRY_LOCK;   // the flusher may be running a handed-off flush (SKBackend_EntryMutex)
    double out[3];
    if (!benchGanesh(kind, draws, frames, reduceOpsTaskSplitting == JNI_TRUE, out)) {
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(3);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, 3, out);
    }
    return result;
}
