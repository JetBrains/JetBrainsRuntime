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

#define SHADER_VARIANT_GRADIENT_CLAMP 0
#define SHADER_VARIANT_GRADIENT_CYCLE 1

struct VKGradientPaintConstants {
    vec4 c0, c1;
    vec3 p;
};
PUSH_CONSTANTS(VKGradientPaintConstants);
GENERIC_INOUT();

void main() {
    float t = dot(vec3(in_Position, 1.0), push.p);
    t = const_ShaderVariant == SHADER_VARIANT_GRADIENT_CYCLE ?
        abs(mod(t + 1.0, 2.0) - 1.0) : // Cycle
        clamp(t, 0.0, 1.0);            // Clamp
    OUTPUT(convertAlpha(mix(push.c0, push.c1, t)));
}
