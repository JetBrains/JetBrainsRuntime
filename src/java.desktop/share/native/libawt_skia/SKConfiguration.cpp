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

#include <cstdio>
#include <cstdlib>

namespace {

// The side of SKGlyphCache's textures until SKConfiguration::configure sets it (SKEnvironment.GLYPH_CACHE_TEXELS).
constexpr jint DEFAULT_GLYPH_CACHE_TEXELS = 1024;

// The bounds SKConfiguration::configure accepts for the glyph cache textures' side (the 32-pixel bucket's,
// SKGraphicsConfig::glyphCache): whole 32-pixel cells, at least one, at most the texture size every GPU allows.
constexpr jint MIN_GLYPH_CACHE_TEXELS = 32;
constexpr jint MAX_GLYPH_CACHE_TEXELS = 16384;
constexpr jint GLYPH_CACHE_TEXEL_STEP = 32;

// Architecture-dependent configuration, see SKEnvironment.Configuration.
struct Configuration {
    SKLCDCompositing lcdCompositing = SKLCDCompositing::Blend;
    bool framebufferFetch = false;
    jint glyphCacheTexels = DEFAULT_GLYPH_CACHE_TEXELS;
    bool imageAtlas = true;           // sun.java2d.skia.imageAtlas: small image sources drawn from an atlas (SKImageAtlas.h)
    bool errorTraces = false;         // J2D_TRACE_LEVEL at J2D_TRACE_ERROR or above
};

// The trace level J2D_TRACE_LEVEL names, as Trace.c's J2dTraceInit reads it (whose level is its own):
// J2D_TRACE_OFF without it or for a value out of range.
int traceLevel() {
    const char *levelString = std::getenv("J2D_TRACE_LEVEL");
    int level = J2D_TRACE_INVALID;
    if (levelString == nullptr || std::sscanf(levelString, "%d", &level) != 1
            || level <= J2D_TRACE_INVALID || level >= J2D_TRACE_MAX) {
        return J2D_TRACE_OFF;
    }
    return level;
}

Configuration gConfiguration;

} // namespace

void SKConfiguration::configure(SKLCDCompositing lcdCompositing, bool framebufferFetch, jint glyphCacheTexels, bool imageAtlas) {
    gConfiguration.imageAtlas = imageAtlas;
    gConfiguration.errorTraces = traceLevel() >= J2D_TRACE_ERROR;
    gConfiguration.lcdCompositing = lcdCompositing;
    gConfiguration.framebufferFetch = framebufferFetch;
    if (glyphCacheTexels >= MIN_GLYPH_CACHE_TEXELS && glyphCacheTexels <= MAX_GLYPH_CACHE_TEXELS
            && glyphCacheTexels % GLYPH_CACHE_TEXEL_STEP == 0) {
        gConfiguration.glyphCacheTexels = glyphCacheTexels;
    }
}

SKLCDCompositing SKConfiguration::lcdCompositing() {
    return gConfiguration.lcdCompositing;
}

bool SKConfiguration::framebufferFetch() {
    return gConfiguration.framebufferFetch;
}

jint SKConfiguration::glyphCacheTexels() {
    return gConfiguration.glyphCacheTexels;
}

bool SKConfiguration::imageAtlas() {
    return gConfiguration.imageAtlas;
}

bool SKConfiguration::errorTraces() {
    return gConfiguration.errorTraces;
}
