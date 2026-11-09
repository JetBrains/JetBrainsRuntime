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
// Marlin's coverage tiles (SKMaskFill.h).
// All functions run on the Skia queue flusher thread.

#include "SKMaskFill.h"
#include "SKContext.h"
#include "SKDirectEntry.h"
#include "SKGraphicsConfig.h"
#include "SKMaskCache.h"
#include "SKPaints.h"
#include "SKPendingBatches.h"

#include "sun_java2d_skia_SKMaskFill.h"

#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"

void SKMaskFill::maskFill(jint x, jint y, jint width, jint height, jint maskOffset, jint maskScan, jint maskLength,
                          const unsigned char *mask) {
    if (!SKContext::current().beginDraw(SKBatchKind::MaskTiles)) {
        return;
    }
    if (width <= 0 || height <= 0) return;
    SKContext &context = SKContext::current();
    SKGraphicsConfig *graphicsConfig = context.graphicsConfig();
    SkPaint paint = SKPaints::makePaint(false);
    const bool solidTile = mask == nullptr || maskLength == 0;
    if (solidTile && (context.shader() || !SKMaskCache::fits(width, height))) {
        // a solid tile under a shader paint, or an oversized one, is a plain rect
        context.flushAllBatches();   // drawn through the canvas: every pending batch first, of any kind
        graphicsConfig->flushMaskCache();
        context.canvas()->drawIRect(SkIRect::MakeXYWH(x, y, width, height), paint);
        return;
    }
    if (SKMaskCache::fits(width, height)) {
        SKMaskCache *maskCache = graphicsConfig->maskCache();
        if (maskCache != nullptr) {
            if (solidTile) {
                // a shape's interior tiles stay in the mesh with its edge tiles
                maskCache->drawSolid(context.canvas(), paint, x, y, width, height);
            } else {
                maskCache->draw(context.canvas(), paint, x, y, width, height, mask + maskOffset, maskScan);
            }
            return;
        }
    }
    context.flushAllBatches();    // the rest draws through the canvas: every pending batch first, of any kind
    if (solidTile) {
        context.canvas()->drawIRect(SkIRect::MakeXYWH(x, y, width, height), paint);
        return;
    }
    // Oversized tile: upload it on its own.
    graphicsConfig->flushMaskCache();
    SkPixmap pixmap(SkImageInfo::MakeA8(width, height), mask + maskOffset, (size_t) maskScan);
    sk_sp<SkImage> coverage = context.uploadPixmap(pixmap).image;
    if (coverage) {
        context.canvas()->drawImage(coverage, (SkScalar) x, (SkScalar) y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
    }
}

// The direct entry (SKRenderQueue.flushAndInvokeNow): an operation larger than the render buffer.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKMaskFill_maskFillNative(JNIEnv *env, jobject self,
                                               jint x, jint y, jint width, jint height,
                                               jint maskOffset, jint maskScan, jint maskLength,
                                               jbyteArray maskArray)
{
    SKDirectEntry entry(env);
    unsigned char *mask = nullptr;
    if (maskArray != nullptr) {
        mask = (unsigned char *) env->GetPrimitiveArrayCritical(maskArray, nullptr);
    }
    if (maskArray == nullptr || mask != nullptr) {
        SKMaskFill::maskFill(x, y, width, height, maskOffset, maskScan, maskLength, mask);
    }
    if (mask != nullptr) {
        env->ReleasePrimitiveArrayCritical(maskArray, mask, JNI_ABORT);
    }
    return entry.end();
}

} // extern "C"
