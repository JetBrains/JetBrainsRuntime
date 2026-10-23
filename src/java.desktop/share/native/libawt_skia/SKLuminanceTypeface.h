/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */

#ifndef SKLuminanceTypeface_h_Included
#define SKLuminanceTypeface_h_Included

#include "include/core/SkRefCnt.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkTypeface.h"

// The cg grayscale route inside Skia's own text path: a typeface standing in for a
// platform typeface whose A8 glyph masks come from CoreGraphics with font smoothing
// for one text-luminance class (SKPlatform_SmoothedGlyphMask), everything else being
// the wrapped typeface's. The proxy's own identity is the strike key, so Skia keeps one
// strike per luminance class, as the Java side does with FontStrikeDesc.luminance.
// luminance: the class representative (0..255). One proxy per (typeface, class).
sk_sp<SkTypeface> SKLuminanceTypeface_For(sk_sp<SkTypeface> typeface, int luminance);

// A font manager whose matches (fallback for a character, a family and style, the
// legacy lookup) are the luminance proxies of the wrapped manager's typefaces, so the
// fallback runs of Skia's shaper and paragraph engine take the route too. One per
// (manager, class).
sk_sp<SkFontMgr> SKLuminanceFontMgr_For(sk_sp<SkFontMgr> manager, int luminance);

#endif /* SKLuminanceTypeface_h_Included */
