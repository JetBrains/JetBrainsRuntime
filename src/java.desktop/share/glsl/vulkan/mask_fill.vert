/*
 * Copyright (c) 2025, 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, 2026, JetBrains s.r.o.. All rights reserved.
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

#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in ivec4 in_PositionOffsetAndScanline;
layout(location = 1) in  uint in_Data;

layout(location = 0) out vec2 out_Position;
layout(location = 1) out uint out_Data;

// This starts with "Origin" and not "Position" intentionally.
// When drawing, vertices are ordered in a such way, that provoking vertex is always the top-left one.
// This gives us an easy way to calculate offset within the rectangle without additional inputs.
layout(location = 2) out flat ivec4 out_OriginOffsetAndScanline;

void main() {
    out_Position = in_PositionOffsetAndScanline.xy;
    out_Data = in_Data;
    out_OriginOffsetAndScanline = in_PositionOffsetAndScanline;
    gl_Position = transformToDeviceSpace(in_PositionOffsetAndScanline.xy);
}
