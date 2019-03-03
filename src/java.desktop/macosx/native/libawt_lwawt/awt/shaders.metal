/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#include <simd/simd.h>
#include <metal_stdlib>
#include "common.h"

using namespace metal;

struct VertexInput {
    float3 position [[attribute(VertexAttributePosition)]];
};

struct TxtVertexInput {
    float3 position [[attribute(VertexAttributePosition)]];
    float2 texCoords [[attribute(VertexAttributeTexPos)]];
};

struct ColShaderInOut {
    float4 position [[position]];
    half4  color;
};

struct TxtShaderInOut {
    float4 position [[position]];
    float2 texCoords;
};

vertex ColShaderInOut vert_col(VertexInput in [[stage_in]],
	   constant FrameUniforms& uniforms [[buffer(FrameUniformBuffer)]]) {
    ColShaderInOut out;
    out.position = float4(in.position, 1.0);
    out.color = half4(uniforms.color.r, uniforms.color.g, uniforms.color.b, uniforms.color.a);
    return out;
}

vertex TxtShaderInOut vert_txt(TxtVertexInput in [[stage_in]],
	   constant FrameUniforms& uniforms [[buffer(FrameUniformBuffer)]]) {
    TxtShaderInOut out;
    out.position = float4(in.position, 1.0);
    out.texCoords = in.texCoords;
    return out;
}

fragment half4 frag_col(ColShaderInOut in [[stage_in]]) {
    return in.color;
}

fragment half4 frag_txt(
        TxtShaderInOut vert [[stage_in]],
        texture2d<float, access::sample> renderTexture [[texture(0)]]
        )
{
    constexpr sampler textureSampler (mag_filter::linear,
                                  min_filter::linear);
    float4 pixelColor = renderTexture.sample(textureSampler, vert.texCoords);
    return half4(pixelColor.r, pixelColor.g, pixelColor.b , pixelColor.a);
}