/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
 
#include <metal_stdlib>
using namespace metal;

#import "MetalShaderTypes.h"


struct VertexOut {
    float4 color;
    float4 pos [[position]];
};


/*
    Java2D coordinate system : Top Left (0, 0) : Bottom Right (width and height)
    Metal coordinate system is : 
    Center is (0.0, 0.0)
    Bottom Left (-1.0, -1.0) : Top Right (1.0, 1.0)
    Top Left (-1.0, 1.0) : Bottom Right (1.0, -1.0)
*/

vertex VertexOut vertexShader(device MetalVertex *vertices [[buffer(0)]], 
                              constant unsigned int *viewportSize [[buffer(1)]],
                              uint vid [[vertex_id]]) {
    VertexOut out;
    out.pos = vertices[vid].position;

    float halfViewWidth = (float)(viewportSize[0] >> 1);
    float halfViewHeight = (float)(viewportSize[1] >> 1);

    out.pos.x = (out.pos.x - halfViewWidth) / halfViewWidth;
    out.pos.y = (halfViewHeight - out.pos.y) / halfViewHeight;
   
    out.color = vertices[vid].color;

    return out;
}


fragment float4 fragmentShader(MetalVertex in [[stage_in]]) {
    return in.color;
}
