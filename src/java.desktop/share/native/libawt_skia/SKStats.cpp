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

#include "SKStats.h"

#include "sun_java2d_pipe_BufferedOpCodes.h"
#include "sun_java2d_skia_SKShapePipe.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>

namespace {

constexpr int OPCODE_SLOTS = 256;
constexpr uint64_t DUMP_INTERVAL = 1000000000ull;   // 1 s
constexpr int TOP_OPS = 8;

struct Accumulator {
    uint64_t count = 0;
    uint64_t nanos = 0;
};

struct Stats {
    Accumulator spans[SK_SPAN_COUNT];
    Accumulator ops[OPCODE_SLOTS];
    uint64_t counters[SK_COUNT_COUNT] = {};
    uint64_t intervalStart = 0;
};

Stats stats;
const uint64_t origin = SKStats_Now();

const char *const SPAN_NAMES[SK_SPAN_COUNT] = {
    "queue", "batches", "snap", "insert", "insert:compiling", "submit", "submit+wait", "readback", "image upload", "present",
    "present:acquire", "present:copy", "present:call"
};

const char *opName(int opcode) {
#define NAME(op) case sun_java2d_pipe_BufferedOpCodes_##op: return #op
    switch (opcode) {
        NAME(DRAW_LINE); NAME(DRAW_RECT); NAME(DRAW_POLY); NAME(DRAW_PIXEL); NAME(DRAW_SCANLINES);
        NAME(DRAW_PARALLELOGRAM); NAME(DRAW_AAPARALLELOGRAM); NAME(FILL_RECT); NAME(FILL_SPANS);
        NAME(FILL_PARALLELOGRAM); NAME(FILL_AAPARALLELOGRAM); NAME(COPY_AREA); NAME(BLIT); NAME(MASK_FILL);
        NAME(MASK_BLIT); NAME(SURFACE_TO_SW_BLIT); NAME(DRAW_GLYPH_LIST); NAME(SET_RECT_CLIP);
        NAME(BEGIN_SHAPE_CLIP); NAME(SET_SHAPE_CLIP_SPANS); NAME(END_SHAPE_CLIP); NAME(RESET_CLIP);
        NAME(SET_ALPHA_COMPOSITE); NAME(SET_XOR_COMPOSITE); NAME(RESET_COMPOSITE); NAME(SET_TRANSFORM);
        NAME(RESET_TRANSFORM); NAME(SET_SURFACES); NAME(SET_SCRATCH_SURFACE); NAME(FLUSH_SURFACE);
        NAME(DISPOSE_SURFACE); NAME(DISPOSE_CONFIG); NAME(INVALIDATE_CONTEXT); NAME(SYNC);
        NAME(SWAP_BUFFERS); NAME(FLUSH_BUFFER); NAME(NOOP); NAME(RESET_PAINT); NAME(SET_COLOR);
        NAME(SET_GRADIENT_PAINT); NAME(SET_LINEAR_GRADIENT_PAINT); NAME(SET_RADIAL_GRADIENT_PAINT);
        NAME(SET_TEXTURE_PAINT);
        case sun_java2d_skia_SKShapePipe_FILL_SHAPE: return "FILL_SHAPE";
        case sun_java2d_skia_SKShapePipe_DRAW_SHAPE: return "DRAW_SHAPE";
        default: return nullptr;
    }
#undef NAME
}

double millis(uint64_t nanos) {
    return nanos / 1e6;
}

void dump(uint64_t now) {
    const double seconds = (now - stats.intervalStart) / 1e9;
    const Accumulator &queue = stats.spans[SK_SPAN_QUEUE];
    uint64_t ops = 0;
    for (const Accumulator &a : stats.ops) {
        ops += a.count;
    }
    fprintf(stderr, "[%7llums] SKStats %.2f s: %llu entries, %llu ops, queue %.1f ms |",
            (unsigned long long) ((now - origin) / 1000000), seconds, (unsigned long long) queue.count,
            (unsigned long long) ops, millis(queue.nanos));
    for (int s = SK_SPAN_BATCHES; s < SK_SPAN_COUNT; s++) {
        const Accumulator &a = stats.spans[s];
        if (a.count != 0) {
            fprintf(stderr, " %s %.1f ms (%llu)", SPAN_NAMES[s], millis(a.nanos), (unsigned long long) a.count);
        }
    }
    fprintf(stderr, " | mesh draws %llu, atlas uploads %llu, presents failed %llu, pipelines built %llu (precompiled %llu), found %llu, blobs loaded %llu (hits %llu), stored %llu\n",
            (unsigned long long) stats.counters[SK_COUNT_MESH_DRAWS],
            (unsigned long long) stats.counters[SK_COUNT_ATLAS_UPLOADS],
            (unsigned long long) stats.counters[SK_COUNT_PRESENTS_FAILED],
            (unsigned long long) stats.counters[SK_COUNT_PIPELINES_ADDED],
            (unsigned long long) stats.counters[SK_COUNT_PIPELINES_PRECOMPILED],
            (unsigned long long) stats.counters[SK_COUNT_PIPELINES_FOUND],
            (unsigned long long) stats.counters[SK_COUNT_BLOB_LOADS],
            (unsigned long long) stats.counters[SK_COUNT_BLOB_HITS],
            (unsigned long long) stats.counters[SK_COUNT_BLOB_STORES]);

    int order[OPCODE_SLOTS];
    int n = 0;
    for (int op = 0; op < OPCODE_SLOTS; op++) {
        if (stats.ops[op].count != 0) {
            order[n++] = op;
        }
    }
    std::sort(order, order + n, [](int a, int b) { return stats.ops[a].nanos > stats.ops[b].nanos; });
    fprintf(stderr, "[%7llums] SKStats ops by time:", (unsigned long long) ((now - origin) / 1000000));
    for (int i = 0; i < std::min(n, TOP_OPS); i++) {
        const Accumulator &a = stats.ops[order[i]];
        const char *name = opName(order[i]);
        if (name != nullptr) {
            fprintf(stderr, " %s", name);
        } else {
            fprintf(stderr, " op%d", order[i]);
        }
        fprintf(stderr, " %.1f ms (%llu, %.2f us)", millis(a.nanos), (unsigned long long) a.count,
                a.nanos / 1e3 / a.count);
    }
    fprintf(stderr, "\n");
    fflush(stderr);
}

} // namespace

bool SKStats_IsEnabled() {
    static const bool enabled = getenv("J2D_SKIA_STATS") != nullptr;
    return enabled;
}

uint64_t SKStats_Now() {
    return (uint64_t) std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

void SKStats_AddSpan(SKStatsSpan span, uint64_t nanos) {
    stats.spans[span].count++;
    stats.spans[span].nanos += nanos;
}

void SKStats_AddOp(int opcode, uint64_t nanos) {
    Accumulator &a = stats.ops[opcode & (OPCODE_SLOTS - 1)];
    a.count++;
    a.nanos += nanos;
}

void SKStats_Count(SKStatsCounter counter, uint64_t n) {
    if (SKStats_IsEnabled()) {
        stats.counters[counter] += n;
    }
}

void SKStats_PipelineEvent(bool added, bool fromPrecompile, const char *label, uint32_t keyHash) {
    if (!SKStats_IsEnabled()) {
        return;
    }
    stats.counters[added ? SK_COUNT_PIPELINES_ADDED : SK_COUNT_PIPELINES_FOUND]++;
    if (added && fromPrecompile) {
        stats.counters[SK_COUNT_PIPELINES_PRECOMPILED]++;
    }
    if (added) {
        fprintf(stderr, "[%7llums] SKStats pipeline %s: %08x %s\n", (unsigned long long) ((SKStats_Now() - origin) / 1000000),
                fromPrecompile ? "precompiled" : "built", (unsigned) keyHash, label);
    }
}

void SKStats_EndOfEntry() {
    if (!SKStats_IsEnabled()) {
        return;
    }
    const uint64_t now = SKStats_Now();
    if (stats.intervalStart == 0) {
        stats.intervalStart = now;
    } else if (now - stats.intervalStart >= DUMP_INTERVAL) {
        dump(now);
        stats = Stats();
        stats.intervalStart = now;
    }
}
