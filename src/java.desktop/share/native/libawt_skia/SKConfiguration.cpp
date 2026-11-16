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
// The backend's configuration (SKConfiguration.h), set by SKEnvironment.configureNative.

#include "SKConfiguration.h"

#include "Trace.h"

#include <cstdlib>

namespace {

// The side of SKGlyphCache's textures unless SKConfiguration::configure is given another (SKEnvironment.GLYPH_CACHE_TEXELS).
constexpr jint DEFAULT_GLYPH_CACHE_TEXELS = 1024;

// The bounds SKConfiguration::configure accepts for the glyph cache textures' side (the 32-pixel bucket's,
// SKGraphicsConfig::glyphCache): whole 32-pixel cells, at least one, at most the texture size every GPU allows.
constexpr jint MIN_GLYPH_CACHE_TEXELS = 32;
constexpr jint MAX_GLYPH_CACHE_TEXELS = 16384;
constexpr jint GLYPH_CACHE_TEXEL_STEP = 32;

// The configuration SKEnvironment.configureNative hands over.
struct Configuration {
    jint glyphCacheTexels = DEFAULT_GLYPH_CACHE_TEXELS;
    bool errorTraces = false;         // J2D_TRACE_LEVEL at J2D_TRACE_ERROR or above
};

// The trace level J2D_TRACE_LEVEL names, as Trace.c's J2dTraceInit reads it (whose level is its own):
// J2D_TRACE_OFF without it or for a value out of range.
int traceLevel() {
    const char *levelString = std::getenv("J2D_TRACE_LEVEL");
    if (levelString == nullptr) {
        return J2D_TRACE_OFF;
    }
    // leading blanks and a sign accepted, anything after the digits ignored, as "%d" does in sscanf
    char *end = nullptr;
    const long level = std::strtol(levelString, &end, 10);
    if (end == levelString || level <= J2D_TRACE_INVALID || level >= J2D_TRACE_MAX) {
        return J2D_TRACE_OFF;
    }
    return static_cast<int>(level);
}

Configuration gConfiguration;

} // namespace

void SKConfiguration::configure(const jint glyphCacheTexels) {
    gConfiguration.errorTraces = traceLevel() >= J2D_TRACE_ERROR;
    if (glyphCacheTexels >= MIN_GLYPH_CACHE_TEXELS && glyphCacheTexels <= MAX_GLYPH_CACHE_TEXELS
            && glyphCacheTexels % GLYPH_CACHE_TEXEL_STEP == 0) {
        gConfiguration.glyphCacheTexels = glyphCacheTexels;
    }
}

jint SKConfiguration::glyphCacheTexels() {
    return gConfiguration.glyphCacheTexels;
}

bool SKConfiguration::errorTraces() {
    return gConfiguration.errorTraces;
}
