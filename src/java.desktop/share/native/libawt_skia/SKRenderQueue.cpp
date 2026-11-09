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
// The native side of sun.java2d.skia.SKRenderQueue: the interpreter of the Java2D render queue for the
// Skia backend (flusher thread), dispatching each operation to the module of the Java class that sends
// it. The entries the pipes use when an operation does not fit the queue buffer (flushAndInvokeNow)
// are the pipes' own (SKDirectEntry.h).

#include "SKAnalyticShapes.h"
#include "SKBlitLoops.h"
#include "SKContext.h"
#include "SKEntryLock.h"
#include "SKGraphicsConfig.h"
#include "SKMaskBlit.h"
#include "SKMaskFill.h"
#include "SKPaints.h"
#include "SKPlatform.h"
#include "SKPresentation.h"
#include "SKRectBatch.h"
#include "SKRenderPipe.h"
#include "SKSurfaceData.h"
#include "SKTextPipe.h"

#include "jlong.h"
#include "jni_util.h"
#include "Trace.h"
#include "sun_java2d_pipe_BufferedOpCodes.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"
#include "sun_java2d_pipe_BufferedRenderPipe.h"
#include "sun_java2d_pipe_BufferedTextPipe.h"
#include "sun_java2d_skia_SKRenderQueue.h"
#include "sun_java2d_skia_SKAnalyticShapes.h"

#define BYTES_PER_POLY_POINT sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_POLY_POINT
#define BYTES_PER_SCANLINE   sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SCANLINE
#define BYTES_PER_SPAN       sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SPAN
#define BYTES_PER_GLYPH_IMAGE    sun_java2d_pipe_BufferedTextPipe_BYTES_PER_GLYPH_IMAGE
#define BYTES_PER_GLYPH_POSITION sun_java2d_pipe_BufferedTextPipe_BYTES_PER_GLYPH_POSITION
#define OFFSET_POSITIONS sun_java2d_pipe_BufferedTextPipe_OFFSET_POSITIONS
#define OFFSET_SUBPIXPOS sun_java2d_pipe_BufferedTextPipe_OFFSET_SUBPIXPOS
#define OFFSET_RGBORDER  sun_java2d_pipe_BufferedTextPipe_OFFSET_RGBORDER
#define OFFSET_CONTRAST  sun_java2d_pipe_BufferedTextPipe_OFFSET_CONTRAST

// The readers of the queue buffer, as the other pipelines' render queues have them
// (MTLRenderQueue.m): each advances the cursor past what it reads.
#define NEXT_VAL(cursor, type) (((type *)((cursor) += sizeof(type)))[-1])
#define NEXT_INT(cursor)       NEXT_VAL(cursor, jint)
#define NEXT_FLOAT(cursor)     NEXT_VAL(cursor, jfloat)
#define NEXT_BOOLEAN(cursor)   (jboolean)NEXT_INT(cursor)
#define NEXT_LONG(cursor)      NEXT_VAL(cursor, jlong)
#define NEXT_DOUBLE(cursor)    NEXT_VAL(cursor, jdouble)
#define SKIP_BYTES(cursor, numbytes) (cursor) += (numbytes)
#define EXTRACT_BOOLEAN(packed, offset) (((packed) >> (offset)) & 1)

#define OP(name) case sun_java2d_pipe_BufferedOpCodes_##name

namespace {

// DRAW_GLYPH_LIST packs the LCD contrast into 8 bits at OFFSET_CONTRAST (BufferedTextPipe).
constexpr jint LCD_CONTRAST_MASK = 0xff;

// SKAnalyticShapes' operands (SKAnalyticShapes.java): the AffineTransform's six floats
// (m00 m10 m01 m11 m02 m12), the stroke's four (width, cap, join, miter limit), and the six
// of a rect, round rect or oval (x, y, width, height, arc width, arc height).
constexpr jint XFORM_FLOATS = 6;
constexpr jint STROKE_FLOATS = 4;
constexpr jint SHAPE_PARAM_FLOATS = 6;

// The BufferedImageOp operands (BufferedBufImgOps), skipped: a ConvolveOp's header is the source
// ops, the edge condition and the kernel's width and height, then the kernel; a RescaleOp's is
// the source ops and the premultiplication flag, then RESCALE_FACTORS scale factors and as many
// offsets (one per color band, RGBA).
constexpr size_t CONVOLVE_OP_HEADER = sizeof(jlong) + 3 * sizeof(jint);
constexpr size_t RESCALE_OP_HEADER = sizeof(jlong) + sizeof(jint);
constexpr jint RESCALE_FACTORS = 4;

// DISPOSE_CONFIG (SKRenderQueue.disposeGraphicsConfig): the configuration's context is submitted, offers its
// pipeline data, leaves the table and every reference to it, and goes with its caches. Nothing made with its
// recorder outlives it: the surfaces' wrappers go with it (SKGraphicsConfig::unwrapSurface), the paints that
// may hold its images (the render state's, the rect batch's, the last glyph list's) before it; a texture
// paint's image of it still referenced then is reported by its destructor (a release trace, from the images'
// references: Graphite frees an image's texture through its context).
void disposeGraphicsConfig(SKPlatformConfig *platformConfig) {
    SKGraphicsConfig *graphicsConfig = SKGraphicsConfig::existing(platformConfig);
    if (graphicsConfig == nullptr) {
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKRenderQueue disposeGraphicsConfig: config=%p context=%p surfaces=%zu", platformConfig,
               graphicsConfig, graphicsConfig->surfaceCount());
    SKPresentation::submit(graphicsConfig, true);   // draws the rect batch too
    graphicsConfig->syncPipelineData();
    std::unique_ptr<SKGraphicsConfig> disposed = SKGraphicsConfig::remove(platformConfig);
    SKContext::current().forgetGraphicsConfig(graphicsConfig);
    SKRectBatch::forgetPaints();
    SKTextPipe::forgetGraphicsConfig(graphicsConfig);
    disposed.reset();   // last: the destructor reports a texture paint image some holder kept
}

// Runs the entries of one flush; presentAll (the flusher's timeout flush) ends the recordings
// held by earlier flushes that presented nothing. Returns whether this flush holds its
// recordings (SKPresentation::endOfFlush), so that the timeout flush follows.
jboolean flushEntries(JNIEnv *env, unsigned char *cursor, jint limit, bool presentAll)
{
    jboolean pending = JNI_FALSE;
    unsigned char *end = cursor + limit;

    { SKPlatformScope scope;
    while (cursor < end) {
        jint opcode = NEXT_INT(cursor);
        switch (opcode) {

        // draw ops
        OP(DRAW_LINE): {
            jint x1 = NEXT_INT(cursor), y1 = NEXT_INT(cursor), x2 = NEXT_INT(cursor), y2 = NEXT_INT(cursor);
            SKRenderPipe::drawLine(x1, y1, x2, y2);
            break;
        }
        OP(DRAW_RECT): {
            jint x = NEXT_INT(cursor), y = NEXT_INT(cursor), width = NEXT_INT(cursor), height = NEXT_INT(cursor);
            SKRenderPipe::drawRect(x, y, width, height);
            break;
        }
        OP(DRAW_POLY): {
            jint pointCount = NEXT_INT(cursor);
            jboolean isClosed = NEXT_BOOLEAN(cursor);
            jint transX = NEXT_INT(cursor), transY = NEXT_INT(cursor);
            jint *xPoints = (jint *) cursor;
            jint *yPoints = xPoints + pointCount;
            SKRenderPipe::drawPoly(pointCount, isClosed, transX, transY, xPoints, yPoints);
            SKIP_BYTES(cursor, pointCount * BYTES_PER_POLY_POINT);
            break;
        }
        OP(DRAW_PIXEL): {
            jint x = NEXT_INT(cursor), y = NEXT_INT(cursor);
            SKRenderPipe::drawPixel(x, y);
            break;
        }
        OP(DRAW_SCANLINES): {
            jint count = NEXT_INT(cursor);
            SKRenderPipe::drawScanlines(count, (jint *) cursor);
            SKIP_BYTES(cursor, count * BYTES_PER_SCANLINE);
            break;
        }
        OP(DRAW_PARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(cursor), y11 = NEXT_FLOAT(cursor);
            jfloat dx21 = NEXT_FLOAT(cursor), dy21 = NEXT_FLOAT(cursor);
            jfloat dx12 = NEXT_FLOAT(cursor), dy12 = NEXT_FLOAT(cursor);
            jfloat lwr21 = NEXT_FLOAT(cursor), lwr12 = NEXT_FLOAT(cursor);
            SKRenderPipe::drawParallelogram(false, x11, y11, dx21, dy21, dx12, dy12, lwr21, lwr12);
            break;
        }
        OP(DRAW_AAPARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(cursor), y11 = NEXT_FLOAT(cursor);
            jfloat dx21 = NEXT_FLOAT(cursor), dy21 = NEXT_FLOAT(cursor);
            jfloat dx12 = NEXT_FLOAT(cursor), dy12 = NEXT_FLOAT(cursor);
            jfloat lwr21 = NEXT_FLOAT(cursor), lwr12 = NEXT_FLOAT(cursor);
            SKRenderPipe::drawParallelogram(true, x11, y11, dx21, dy21, dx12, dy12, lwr21, lwr12);
            break;
        }

        // fill ops
        OP(FILL_RECT): {
            jint x = NEXT_INT(cursor), y = NEXT_INT(cursor), width = NEXT_INT(cursor), height = NEXT_INT(cursor);
            SKRenderPipe::fillRect(x, y, width, height);
            break;
        }
        OP(FILL_SPANS): {
            jint count = NEXT_INT(cursor);
            SKRenderPipe::fillSpans(count, (jint *) cursor);
            SKIP_BYTES(cursor, count * BYTES_PER_SPAN);
            break;
        }
        OP(FILL_PARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(cursor), y11 = NEXT_FLOAT(cursor);
            jfloat dx21 = NEXT_FLOAT(cursor), dy21 = NEXT_FLOAT(cursor);
            jfloat dx12 = NEXT_FLOAT(cursor), dy12 = NEXT_FLOAT(cursor);
            SKRenderPipe::fillParallelogram(false, x11, y11, dx21, dy21, dx12, dy12);
            break;
        }
        OP(FILL_AAPARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(cursor), y11 = NEXT_FLOAT(cursor);
            jfloat dx21 = NEXT_FLOAT(cursor), dy21 = NEXT_FLOAT(cursor);
            jfloat dx12 = NEXT_FLOAT(cursor), dy12 = NEXT_FLOAT(cursor);
            SKRenderPipe::fillParallelogram(true, x11, y11, dx21, dy21, dx12, dy12);
            break;
        }

        // text
        OP(DRAW_GLYPH_LIST): {
            jint numGlyphs = NEXT_INT(cursor);
            jint packedParams = NEXT_INT(cursor);
            jfloat origX = NEXT_FLOAT(cursor), origY = NEXT_FLOAT(cursor);
            jboolean usePositions = EXTRACT_BOOLEAN(packedParams, OFFSET_POSITIONS);
            jboolean subPixPos = EXTRACT_BOOLEAN(packedParams, OFFSET_SUBPIXPOS);
            jboolean rgbOrder = EXTRACT_BOOLEAN(packedParams, OFFSET_RGBORDER);
            jint lcdContrast = (packedParams >> OFFSET_CONTRAST) & LCD_CONTRAST_MASK;
            const jlong *images = (const jlong *) cursor;
            const jfloat *positions = usePositions ? (const jfloat *) (cursor + numGlyphs * BYTES_PER_GLYPH_IMAGE) : nullptr;
            SKTextPipe::drawGlyphList(numGlyphs, usePositions, subPixPos, rgbOrder, lcdContrast, origX, origY, images, positions);
            SKIP_BYTES(cursor, numGlyphs * (BYTES_PER_GLYPH_IMAGE + (usePositions ? BYTES_PER_GLYPH_POSITION : 0)));
            break;
        }

        // copy / blit
        OP(COPY_AREA): {
            jint x = NEXT_INT(cursor), y = NEXT_INT(cursor), width = NEXT_INT(cursor), height = NEXT_INT(cursor);
            jint dx = NEXT_INT(cursor), dy = NEXT_INT(cursor);
            SKBlitLoops::copyArea(x, y, width, height, dx, dy);
            break;
        }
        OP(BLIT): {
            jint packedParams = NEXT_INT(cursor);
            jint sx1 = NEXT_INT(cursor), sy1 = NEXT_INT(cursor), sx2 = NEXT_INT(cursor), sy2 = NEXT_INT(cursor);
            jdouble dx1 = NEXT_DOUBLE(cursor), dy1 = NEXT_DOUBLE(cursor), dx2 = NEXT_DOUBLE(cursor), dy2 = NEXT_DOUBLE(cursor);
            jlong sourceOps = NEXT_LONG(cursor), destinationOps = NEXT_LONG(cursor);
            SKBlitLoops::blit(env, packedParams, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2, sourceOps, destinationOps);
            break;
        }
        OP(SURFACE_TO_SW_BLIT): {
            jint sx = NEXT_INT(cursor), sy = NEXT_INT(cursor), dx = NEXT_INT(cursor), dy = NEXT_INT(cursor);
            jint width = NEXT_INT(cursor), height = NEXT_INT(cursor), destType = NEXT_INT(cursor);
            jlong sourceOps = NEXT_LONG(cursor), destinationOps = NEXT_LONG(cursor);
            SKBlitLoops::surfaceToSoftwareBlit(env, sx, sy, dx, dy, width, height, destType, sourceOps, destinationOps);
            break;
        }
        OP(MASK_FILL): {
            jint x = NEXT_INT(cursor), y = NEXT_INT(cursor), width = NEXT_INT(cursor), height = NEXT_INT(cursor);
            jint maskOffset = NEXT_INT(cursor), maskScan = NEXT_INT(cursor), maskLength = NEXT_INT(cursor);
            SKMaskFill::maskFill(x, y, width, height, maskOffset, maskScan, maskLength, maskLength > 0 ? cursor : nullptr);
            SKIP_BYTES(cursor, maskLength);
            break;
        }
        OP(MASK_BLIT): {
            jint destX = NEXT_INT(cursor), destY = NEXT_INT(cursor), width = NEXT_INT(cursor), height = NEXT_INT(cursor);
            SKMaskBlit::maskBlit(destX, destY, width, height, cursor);
            SKIP_BYTES(cursor, width * height * sizeof(jint));
            break;
        }

        // state: clip
        OP(SET_RECT_CLIP): {
            jint x1 = NEXT_INT(cursor), y1 = NEXT_INT(cursor), x2 = NEXT_INT(cursor), y2 = NEXT_INT(cursor);
            SKContext::current().setRectClip(x1, y1, x2, y2);
            break;
        }
        OP(BEGIN_SHAPE_CLIP): {
            SKContext::current().beginShapeClip();
            break;
        }
        OP(SET_SHAPE_CLIP_SPANS): {
            jint count = NEXT_INT(cursor);
            SKContext::current().addShapeClipSpans(count, (jint *) cursor);
            SKIP_BYTES(cursor, count * BYTES_PER_SPAN);
            break;
        }
        OP(END_SHAPE_CLIP): {
            SKContext::current().endShapeClip();
            break;
        }
        OP(RESET_CLIP): {
            SKContext::current().resetClip();
            break;
        }

        // state: composite
        OP(SET_ALPHA_COMPOSITE): {
            jint rule = NEXT_INT(cursor);
            jfloat extraAlpha = NEXT_FLOAT(cursor);
            jint flags = NEXT_INT(cursor);
            SKContext::current().setAlphaComposite(rule, extraAlpha, flags);
            break;
        }
        OP(SET_XOR_COMPOSITE): {
            jint xorPixel = NEXT_INT(cursor);
            SKContext::current().setXORComposite(xorPixel);
            break;
        }
        OP(RESET_COMPOSITE): {
            SKContext::current().resetComposite();
            break;
        }

        // state: transform
        OP(SET_TRANSFORM): {
            jdouble m00 = NEXT_DOUBLE(cursor), m10 = NEXT_DOUBLE(cursor), m01 = NEXT_DOUBLE(cursor);
            jdouble m11 = NEXT_DOUBLE(cursor), m02 = NEXT_DOUBLE(cursor), m12 = NEXT_DOUBLE(cursor);
            SKContext::current().setTransform(m00, m10, m01, m11, m02, m12);
            break;
        }
        OP(RESET_TRANSFORM): {
            SKContext::current().resetTransform();
            break;
        }

        // state: surfaces / context
        OP(SET_SURFACES): {
            jlong sourceOps = NEXT_LONG(cursor);
            jlong destinationOps = NEXT_LONG(cursor);
            (void) sourceOps;   // sources are taken per blit
            SKContext::current().setDestination((SKSDOps *) jlong_to_ptr(destinationOps));
            break;
        }
        OP(SET_SCRATCH_SURFACE): {
            jlong configInfo = NEXT_LONG(cursor);
            SKContext::current().setScratch((SKPlatformConfig *) jlong_to_ptr(configInfo));
            break;
        }
        OP(FLUSH_SURFACE):
        OP(DISPOSE_SURFACE): {
            jlong surfaceOps = NEXT_LONG(cursor);
            SKSDOps *ops = (SKSDOps *) jlong_to_ptr(surfaceOps);
            if (ops == SKContext::current().destination()) {
                SKContext::current().invalidate();
            }
            const bool dispose = opcode == sun_java2d_pipe_BufferedOpCodes_DISPOSE_SURFACE;
            SKContext::current().releaseSurface(ops, dispose);
            // A flushed surface is never drawn to or from again (the Java side counts it
            // as lost): its platform side goes too, as Metal releases a flushed texture at
            // once: a window's swap chain is free for the successor, and the texture of a
            // flushed VolatileImage or a replaced cached image goes back to the GPU without
            // waiting for the Disposer to collect the Java surface.
            if (ops != nullptr && ops->platform != nullptr) {
                SKPlatform::disposeSurface(ops->platform);
                ops->platform = nullptr;
            }
            break;
        }
        OP(DISPOSE_CONFIG): {
            jlong configInfo = NEXT_LONG(cursor);
            SKContext::current().invalidate();
            disposeGraphicsConfig((SKPlatformConfig *) jlong_to_ptr(configInfo));
            break;
        }
        case sun_java2d_skia_SKAnalyticShapes_FILL_SHAPE:
        case sun_java2d_skia_SKAnalyticShapes_DRAW_SHAPE: {
            const bool fill = opcode == sun_java2d_skia_SKAnalyticShapes_FILL_SHAPE;
            jint kind = NEXT_INT(cursor);
            const jfloat *xform = (const jfloat *) cursor;
            SKIP_BYTES(cursor, XFORM_FLOATS * sizeof(jfloat));
            const jfloat *stroke = nullptr;
            if (!fill) {
                stroke = (const jfloat *) cursor;
                SKIP_BYTES(cursor, STROKE_FLOATS * sizeof(jfloat));
            }
            if (kind == sun_java2d_skia_SKAnalyticShapes_KIND_PATH) {
                jint windingRule = NEXT_INT(cursor), numTypes = NEXT_INT(cursor), numCoords = NEXT_INT(cursor);
                const jint *types = (const jint *) cursor;
                SKIP_BYTES(cursor, numTypes * sizeof(jint));
                const jfloat *coords = (const jfloat *) cursor;
                SKIP_BYTES(cursor, numCoords * sizeof(jfloat));
                SKAnalyticShapes::drawShape(fill, kind, xform, stroke, nullptr, windingRule, numTypes, types, numCoords, coords);
            } else {
                const jfloat *params = (const jfloat *) cursor;
                SKIP_BYTES(cursor, SHAPE_PARAM_FLOATS * sizeof(jfloat));
                SKAnalyticShapes::drawShape(fill, kind, xform, stroke, params, 0, 0, nullptr, 0, nullptr);
            }
            break;
        }
        OP(SYNC): {
            SKPresentation::endOfFlush(env, true);
            break;
        }
        OP(SWAP_BUFFERS): {
            jlong window = NEXT_LONG(cursor);
            (void) window;   // the windows drawn into are presented at the end of the flush
            break;
        }
        OP(NOOP):
            break;

        // state: paint
        OP(RESET_PAINT): {
            SKPaints::resetPaint();
            break;
        }
        OP(SET_COLOR): {
            jint pixel = NEXT_INT(cursor);
            SKPaints::setColor(pixel);
            break;
        }
        OP(SET_GRADIENT_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(cursor);
            jboolean cyclic = NEXT_BOOLEAN(cursor);
            jdouble p0 = NEXT_DOUBLE(cursor), p1 = NEXT_DOUBLE(cursor), p3 = NEXT_DOUBLE(cursor);
            jint pixel1 = NEXT_INT(cursor), pixel2 = NEXT_INT(cursor);
            (void) useMask;   // the paints shade Marlin's tiles and Skia's shapes alike
            SKPaints::setGradientPaint(cyclic, p0, p1, p3, pixel1, pixel2);
            break;
        }
        OP(SET_LINEAR_GRADIENT_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(cursor);
            jboolean linear = NEXT_BOOLEAN(cursor);
            jint cycleMethod = NEXT_INT(cursor);
            jint numStops = NEXT_INT(cursor);
            jfloat p0 = NEXT_FLOAT(cursor), p1 = NEXT_FLOAT(cursor), p3 = NEXT_FLOAT(cursor);
            const jfloat *fractions = (const jfloat *) cursor;
            SKIP_BYTES(cursor, numStops * sizeof(jfloat));
            const jint *pixels = (const jint *) cursor;
            SKIP_BYTES(cursor, numStops * sizeof(jint));
            (void) useMask;
            SKPaints::setLinearGradientPaint(linear, cycleMethod, numStops, p0, p1, p3, fractions, pixels);
            break;
        }
        OP(SET_RADIAL_GRADIENT_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(cursor);
            jboolean linear = NEXT_BOOLEAN(cursor);
            jint numStops = NEXT_INT(cursor);
            jint cycleMethod = NEXT_INT(cursor);
            jfloat m00 = NEXT_FLOAT(cursor), m01 = NEXT_FLOAT(cursor), m02 = NEXT_FLOAT(cursor);
            jfloat m10 = NEXT_FLOAT(cursor), m11 = NEXT_FLOAT(cursor), m12 = NEXT_FLOAT(cursor);
            jfloat focusX = NEXT_FLOAT(cursor);
            const jfloat *fractions = (const jfloat *) cursor;
            SKIP_BYTES(cursor, numStops * sizeof(jfloat));
            const jint *pixels = (const jint *) cursor;
            SKIP_BYTES(cursor, numStops * sizeof(jint));
            (void) useMask;
            SKPaints::setRadialGradientPaint(linear, cycleMethod, numStops, m00, m01, m02, m10, m11, m12,
                                               focusX, fractions, pixels);
            break;
        }
        OP(SET_TEXTURE_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(cursor);
            jboolean filter = NEXT_BOOLEAN(cursor);
            jlong sourceOps = NEXT_LONG(cursor);
            jdouble xp0 = NEXT_DOUBLE(cursor), xp1 = NEXT_DOUBLE(cursor), xp3 = NEXT_DOUBLE(cursor);
            jdouble yp0 = NEXT_DOUBLE(cursor), yp1 = NEXT_DOUBLE(cursor), yp3 = NEXT_DOUBLE(cursor);
            (void) useMask;
            SKPaints::setTexturePaint(env, filter, sourceOps, xp0, xp1, xp3, yp0, yp1, yp3);
            break;
        }

        // BufferedImageOps are never enqueued by SKDrawImage; skip defensively.
        OP(ENABLE_CONVOLVE_OP): {
            SKIP_BYTES(cursor, CONVOLVE_OP_HEADER);
            jint kernelWidth = ((jint *) cursor)[-2], kernelHeight = ((jint *) cursor)[-1];   // the header's last two ints
            SKIP_BYTES(cursor, kernelWidth * kernelHeight * sizeof(jfloat));
            break;
        }
        OP(ENABLE_RESCALE_OP): {
            SKIP_BYTES(cursor, RESCALE_OP_HEADER);
            SKIP_BYTES(cursor, RESCALE_FACTORS * 2 * sizeof(jfloat));   // the scale factors, then the offsets
            break;
        }
        OP(ENABLE_LOOKUP_OP): {
            SKIP_BYTES(cursor, sizeof(jlong));   // the source ops
            jboolean nonPremult = NEXT_BOOLEAN(cursor);
            jboolean shortData  = NEXT_BOOLEAN(cursor);
            jint numBands       = NEXT_INT(cursor);
            jint bandLength     = NEXT_INT(cursor);
            jint offset         = NEXT_INT(cursor);
            (void) nonPremult; (void) offset;
            SKIP_BYTES(cursor, numBands * bandLength * (shortData ? sizeof(jshort) : sizeof(jbyte)));
            break;
        }
        OP(DISABLE_CONVOLVE_OP):
        OP(DISABLE_RESCALE_OP):
        OP(DISABLE_LOOKUP_OP):
            break;

        default:
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKRenderQueue_flushBuffer: invalid opcode=%d", opcode);
            cursor = end;   // the rest of the entry cannot be read
            break;
        }
    }
    pending = SKPresentation::endOfFlush(env, false, presentAll) ? JNI_TRUE : JNI_FALSE;
    } // SKPlatformScope
    return pending;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKRenderQueue_flushBuffer(JNIEnv *env, jobject renderQueue, jlong buffer, jint limit, jboolean presentAll)
{
    SK_ENTRY_LOCK;
    unsigned char *cursor = (unsigned char *) jlong_to_ptr(buffer);
    if (cursor == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKRenderQueue_flushBuffer: cannot get direct buffer address");
        return JNI_FALSE;
    }
    return flushEntries(env, cursor, limit, presentAll == JNI_TRUE);
}

} // extern "C"
