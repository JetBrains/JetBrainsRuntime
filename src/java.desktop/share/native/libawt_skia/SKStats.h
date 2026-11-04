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
    SK_SPAN_INSERT_COMPILING,   // of which: inserts that built at least one new pipeline (the compile waits)
    SK_SPAN_SUBMIT,         // Context::submit at the end of a flush, no CPU wait
    SK_SPAN_SUBMIT_WAIT,    // Context::submit(SyncToCpu::kYes) for SYNC
    SK_SPAN_READBACK,       // surface-to-software blits, with their submit and wait
    SK_SPAN_IMAGE_UPLOAD,   // raster images made textures (one per software blit)
    SK_SPAN_PRESENT,        // copy into the swap chain image and present, all of it
    SK_SPAN_PRESENT_ACQUIRE,   // of which: GetCurrentTexture (may wait for a free image)
    SK_SPAN_PRESENT_COPY,      // of which: the copy, its encoder and queue submit
    SK_SPAN_PRESENT_CALL,      // of which: Present()
    SK_SPAN_BLIT_SOURCE,    // BLIT of a Skia surface: the source checked and its image made
    SK_SPAN_BLIT_SOFTWARE,  // BLIT of a software surface: all of it (uploads, draws)
    SK_SPAN_BLIT_DRAW,      // BLIT of a Skia surface: the draw (with the batches it touches)
    SK_SPAN_COUNT
};

enum SKStatsCounter {
    SK_COUNT_MESH_DRAWS,        // drawVertices calls (batched quads, tiles, glyphs)
    SK_COUNT_ATLAS_UPLOADS,     // writePixels into the glyph and mask atlases
    SK_COUNT_PRESENTS_FAILED,   // a window to present but no swap chain image
    SK_COUNT_PIPELINES_ADDED,   // graphics pipelines Graphite built (compiled) for the first time
    SK_COUNT_PIPELINES_FOUND,   // pipeline lookups that hit the cache
    SK_COUNT_PIPELINES_PRECOMPILED,   // of the built ones, those built ahead from a stored key
    SK_COUNT_BLOB_LOADS,        // EXPERIMENT: Dawn blob cache lookups (J2D_SKIA_DAWN_CACHE)
    SK_COUNT_BLOB_HITS,         // of those, found
    SK_COUNT_BLOB_STORES,       // blobs Dawn handed over for storing
    SK_COUNT_RENDER_PASSES,     // render passes begun on the device (Dawn platform: through its proc table)
    SK_COUNT_TEXTURE_COPIES,    // texture-to-texture copies encoded (destination reads, the present copy)
    SK_COUNT_BUFFER_UPLOADS,    // buffer-to-texture copies encoded (texture uploads)
    SK_COUNT_QUEUE_SUBMITS,     // command buffer submits to the device queue
    SK_COUNT_TIMEOUT_FLUSHES,   // flushes the flusher ran on its own (the 100 ms timeout, presentAll)
    SK_COUNT_MESH_DRAWS_AT_STATE,     // of the mesh draws, those of batches a clip or transform change drew
    SK_COUNT_MESH_DRAWS_AT_SURFACE,   // of the mesh draws, those of batches a destination switch drew
    SK_COUNT_MESH_DRAWS_AT_KIND,      // of the mesh draws, those of batches an op of another kind drew (it touched them)
    SK_COUNT_MESH_DRAWS_AT_CANVAS_OP, // of the mesh draws, those of batches an op drawing through the canvas drew (REQUIRE_CANVAS)
    SK_COUNT_MESH_DRAWS_AT_DIRECT,    // ... a draw through the canvas inside a batched op drew (uncached glyphs, Skia's text)
    SK_COUNT_MESH_DRAWS_AT_BLIT,      // ... a blit that touched them drew
    SK_COUNT_MESH_DRAWS_AT_FLUSH,     // ... the end of a recording or a present drew
    SK_COUNT_MESH_DRAWS_IMMEDIATE,    // of the mesh draws, those of no batch (a blit's own quad)
    SK_COUNT_GLYPH_OVERLAP_DRAWS,     // LCD twopass batches of another colour drawn early: a glyph overlapped them
    // Diagnostics: the mesh draws of destination switches (_AT_SURFACE) by what followed for the surface switched
    // away from: the same destination set again (a new source only), the surface set again before it was read
    // (the batches could have stayed open), read first (a source, a read-back, released), or neither before the
    // flush ended.
    SK_COUNT_SWITCH_DRAWS_SAME,
    SK_COUNT_SWITCH_DRAWS_RETURNED,
    SK_COUNT_SWITCH_DRAWS_READ,
    SK_COUNT_SWITCH_DRAWS_ENDED,
    SK_COUNT_BLITS_PAST_BATCHES,      // blits drawn ahead of pending batches they do not touch (SKBackend_Blit)
    SK_COUNT_SURFACE_BLITS,           // blits from a Skia surface
    SK_COUNT_BLITS_OF_PENDING_SOURCES,   // of those, from a surface drawn into in the same recording since its last blit
    SK_COUNT_PENDING_SOURCES_VOLATILE,   // of those, from a render target (a VolatileImage, a back buffer)
    SK_COUNT_COUNT
};

bool SKStats_IsEnabled();
uint64_t SKStats_Now();     // nanoseconds, monotonic
void SKStats_AddSpan(SKStatsSpan span, uint64_t nanos);
void SKStats_AddOp(int opcode, uint64_t nanos);
void SKStats_Count(SKStatsCounter counter, uint64_t n = 1);
uint64_t SKStats_Counter(SKStatsCounter counter);   // the count since the last print
// What draws the pending batches now (SK_COUNT_MESH_DRAWS_AT_STATE or _AT_SURFACE), SK_COUNT_COUNT for
// nothing in particular: mesh draws counted meanwhile are also counted under it.
// Returns the previous cause.
SKStatsCounter SKStats_SetDrawCause(SKStatsCounter cause);

// The draw cause for a scope (SKStats_SetDrawCause), the previous one restored after it.
class SKDrawCauseScope {
public:
    explicit SKDrawCauseScope(SKStatsCounter cause) : previous(SKStats_SetDrawCause(cause)) {}
    ~SKDrawCauseScope() {
        SKStats_SetDrawCause(previous);
    }
    SKDrawCauseScope(const SKDrawCauseScope &) = delete;
    SKDrawCauseScope &operator=(const SKDrawCauseScope &) = delete;
private:
    SKStatsCounter previous;
};
// Graphite added a pipeline to its cache (built it) or found one; with the statistics on, an added
// pipeline's label and key hash are printed, the input of a precompile list.
void SKStats_PipelineEvent(bool added, bool fromPrecompile, const char *label, uint32_t keyHash);
// Called at the end of every queue entry: prints and resets once a second has passed.
void SKStats_EndOfEntry();

// Times its scope into a span when the statistics are on.
class SKStatsScope {
public:
    explicit SKStatsScope(SKStatsSpan span) : span(span), start(SKStats_IsEnabled() ? SKStats_Now() : 0) {}
    ~SKStatsScope() {
        end();
    }
    // Ends the span before the scope does (once).
    void end() {
        if (start != 0) {
            SKStats_AddSpan(span, SKStats_Now() - start);
            start = 0;
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
