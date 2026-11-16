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
#ifndef SKConfiguration_h_Included
#define SKConfiguration_h_Included

// The backend's configuration: the values SKEnvironment.configureNative hands over before the
// first context is created; configure reads the trace level then (errorTraces).

#include "jni.h"

namespace SKConfiguration {

// glyphCacheTexels: the side of SKGlyphCache's textures (Java's glyph images); 0 or a value out of range keeps the default
void configure(jint glyphCacheTexels);
[[nodiscard]] jint glyphCacheTexels();     // the side SKGlyphCache creates its textures with
// Whether the release traces of errors print (J2D_TRACE_LEVEL at J2D_TRACE_ERROR or above, Trace.c), read
// once by configure: a check that only feeds such a trace (SKGraphicsConfig's texture paint images) runs only
// then, and costs nothing otherwise.
[[nodiscard]] bool errorTraces();

} // namespace SKConfiguration

#endif /* SKConfiguration_h_Included */
