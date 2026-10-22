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
#ifndef MTLOptions_h_Included
#define MTLOptions_h_Included

#include <jni.h>
#import <Foundation/Foundation.h>

// Experimental switches of the Metal pipeline, read once from system
// properties; every default is the shipped behaviour, so the pipeline can be
// measured with and without each change under otherwise identical conditions.

// sun.java2d.metal.coalesceCommits: one command buffer per flush while the
// composite is SrcOver with alpha (COMP_ALPHA) and the paint a colour or
// gradient, instead of a commit after every paint reset and every run of
// mask operations.
BOOL MTLOptions_CoalesceCommits(void);

// sun.java2d.metal.glyphCacheSize: side of the glyph cache textures in texels,
// a multiple of 32 between 32 and 16384 (512 gives 256 cells of 32x32 per
// cache, 1024 gives 1024 cells); 0 when unset or invalid, meaning the
// pipeline's default.
jint MTLOptions_GlyphCacheSize(void);

// sun.java2d.metal.glyphCacheSync: wait for the GPU when a full glyph cache is
// replaced. Metal retains the old texture until the command buffer that
// samples it completes, so the wait is not needed for correctness.
BOOL MTLOptions_GlyphCacheSync(void);

// sun.java2d.metal.gpuOptimizedTextures: let the driver store the glyph and
// mask cache textures in its compressed layout; off makes the CPU uploads of
// those caches plain copies.
BOOL MTLOptions_GpuOptimizedTextures(void);

/**
 * sun.java2d.metal.textGammaCorrection (default true): grayscale text blended
 * with the brightness-dependent coverage exponent of JBR-3820 (frag_gmc_text).
 * False blends it like the software loops (frag_text), for glyph images that
 * already carry the text luminance (the Skia scaler's CoreGraphics route).
 */
BOOL MTLOptions_TextGammaCorrection(void);

#endif /* MTLOptions_h_Included */
