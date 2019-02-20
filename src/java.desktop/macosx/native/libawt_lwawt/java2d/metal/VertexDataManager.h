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

#ifndef VertexDataManager_h_Included
#define VertexDataManager_h_Included

#import <Metal/Metal.h>
#import "shaders/MetalShaderTypes.h"

// ---------------------------------------------------------------------
//TODO : This implementation should be redesigned as a singleton class
//TODO : Drawing optimization can be done - if current primitive-type to be 
//       drawn is equal to previous primitive-type in the list/array 
//       (Similar to previous-op in OGL)
// ---------------------------------------------------------------------

typedef struct 
{
    MTLPrimitiveType type;
    unsigned int offset_in_index_buffer;
    unsigned int no_of_indices;
    unsigned int primitiveInstances;
} MetalPrimitiveData;

void VertexDataManager_init(id<MTLDevice> device);

void VertexDataManager_addLineVertexData(MetalVertex v1, MetalVertex v2);
void VertexDataManager_addQuadVertexData(MetalVertex v1, MetalVertex v2, MetalVertex v3, MetalVertex v4);

id<MTLBuffer> VertexDataManager_getVertexBuffer();
id<MTLBuffer> VertexDataManager_getIndexBuffer();
MetalPrimitiveData** VertexDataManager_getAllPrimitives();
unsigned int VertexDataManager_getNoOfPrimitives();

void VertexDataManager_freeAllPrimitives();

// should be private
void addVertex(MetalVertex vert);
void addIndex(unsigned short vertexNum);

#endif //VertexDataManager_h_Included
