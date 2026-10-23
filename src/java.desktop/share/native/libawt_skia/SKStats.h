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

#ifndef SKStats_h_Included
#define SKStats_h_Included

#include <cstdint>

// Opt-in statistics of the flusher's work, enabled by the J2D_SKIA_STATS
// environment variable (after Wayland's J2D_STATS): time and count per phase
// and per render-queue opcode, plus counts of mesh draws, uploads and failed
// presents, printed on stderr about once a second. Off, every hook is one
// predictable branch. The flusher holds the AWT lock while it works, so the
// accumulators are not synchronised.
//
// The phases nest: a queue entry contains its ops and its end-of-flush
// submits; batch flushes happen inside ops (a state change ends a batch) and
// inside snaps' preparation; a readback contains its own snap, insert and
// waiting submit, which are therefore also counted under snap and insert but
// not under submit.

enum SKStatsSpan {
    SK_SPAN_QUEUE,          // one JNI entry: flushBuffer or a direct entry point
    SK_SPAN_BATCHES,        // glyph, tile and rect batches drawn into the canvas
    SK_SPAN_SNAP,           // Recorder::snap
    SK_SPAN_INSERT,         // Context::insertRecording
    SK_SPAN_SUBMIT,         // Context::submit at the end of a flush, no CPU wait
    SK_SPAN_SUBMIT_WAIT,    // Context::submit(SyncToCpu::kYes) for SYNC
    SK_SPAN_READBACK,       // surface-to-software blits, with their submit and wait
    SK_SPAN_IMAGE_UPLOAD,   // raster images made textures (one per software blit)
    SK_SPAN_PRESENT,        // copy into the swap chain image and present, all of it
    SK_SPAN_PRESENT_ACQUIRE,   // of which: GetCurrentTexture (may wait for a free image)
    SK_SPAN_PRESENT_COPY,      // of which: the copy, its encoder and queue submit
    SK_SPAN_PRESENT_CALL,      // of which: Present()
    SK_SPAN_COUNT
};

enum SKStatsCounter {
    SK_COUNT_MESH_DRAWS,        // drawVertices calls (batched quads, tiles, glyphs)
    SK_COUNT_ATLAS_UPLOADS,     // writePixels into the glyph and mask atlases
    SK_COUNT_PRESENTS_FAILED,   // a window to present but no swap chain image
    SK_COUNT_COUNT
};

bool SKStats_IsEnabled();
uint64_t SKStats_Now();     // nanoseconds, monotonic
void SKStats_AddSpan(SKStatsSpan span, uint64_t nanos);
void SKStats_AddOp(int opcode, uint64_t nanos);
void SKStats_Count(SKStatsCounter counter, uint64_t n = 1);
// Called at the end of every queue entry: prints and resets once a second has passed.
void SKStats_EndOfEntry();

// Times its scope into a span when the statistics are on.
class SKStatsScope {
public:
    explicit SKStatsScope(SKStatsSpan span) : span(span), start(SKStats_IsEnabled() ? SKStats_Now() : 0) {}
    ~SKStatsScope() {
        if (start != 0) {
            SKStats_AddSpan(span, SKStats_Now() - start);
        }
    }
    SKStatsScope(const SKStatsScope &) = delete;
    SKStatsScope &operator=(const SKStatsScope &) = delete;
private:
    SKStatsSpan span;
    uint64_t start;
};

// Times one render-queue op (the body of the interpreter's loop) under its opcode.
class SKStatsOpScope {
public:
    explicit SKStatsOpScope(int opcode) : opcode(opcode), start(SKStats_IsEnabled() ? SKStats_Now() : 0) {}
    ~SKStatsOpScope() {
        if (start != 0) {
            SKStats_AddOp(opcode, SKStats_Now() - start);
        }
    }
    SKStatsOpScope(const SKStatsOpScope &) = delete;
    SKStatsOpScope &operator=(const SKStatsOpScope &) = delete;
private:
    int opcode;
    uint64_t start;
};

#endif // SKStats_h_Included
