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
// Mask blits (SKMaskBlit.h).
// All functions run on the Skia queue flusher thread.

#include "SKMaskBlit.h"
#include "SKContext.h"
#include "SKPaints.h"

#include "include/core/SkPixmap.h"
#include "include/core/SkSamplingOptions.h"

#include <cstddef>

namespace {

// The IntArgbPre pixels of a mask blit are 4 bytes each.
constexpr size_t BYTES_PER_PIXEL = 4;

} // namespace

void SKMaskBlit::maskBlit(jint destX, jint destY, jint width, jint height, const void *pixels) {
    if (!SKContext::current().beginDraw()) {
        return;
    }
    if (width <= 0 || height <= 0) return;
    SkPixmap pixmap(SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
                    pixels, (size_t) width * BYTES_PER_PIXEL);
    sk_sp<SkImage> image = SKContext::current().uploadPixmap(pixmap).image;
    if (!image) return;
    const SkPaint paint = SKPaints::imageSourcePaint();
    SKContext::current().canvas()->drawImage(image, (SkScalar) destX, (SkScalar) destY, SkSamplingOptions(SkFilterMode::kNearest), &paint);
}
