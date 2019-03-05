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

#ifndef HEADLESS

#import "VertexDataManager.h"
#import <Metal/Metal.h>



static int MAX_PRIMITIVES = 100; //TODO : this needs to be changed to dynamic array of structures to support any number of primitives

// TODO : all static members should be class members when singleton is implemented
static id<MTLBuffer> VertexBuffer;
static id<MTLBuffer> IndexBuffer;
static unsigned int no_of_vertices = 0;
static unsigned int no_of_indices = 0;
static unsigned int no_of_primitives = 0;
MetalPrimitiveData** AllPrimitives = NULL;


void VertexDataManager_init(id<MTLDevice> device) {
    // This limited buffer size allocation is for PoC purpose. 
    // TODO : Need to implement a logic where we allocate more chunks if needed

    VertexBuffer = [device newBufferWithLength:1024 * 32 options:MTLResourceOptionCPUCacheModeDefault];
    IndexBuffer  = [device newBufferWithLength:1024 * 8 options:MTLResourceOptionCPUCacheModeDefault];

    AllPrimitives = (MetalPrimitiveData**) malloc(sizeof(MetalPrimitiveData*) * MAX_PRIMITIVES); //[[NSMutableArray<MetalPrimitiveData*> alloc] init];
}

void VertexDataManager_addLineVertexData(MetalVertex v1, MetalVertex v2) {

	  // Create a structure of MetalPrimitiveData (populate it) and it to the array
	  MetalPrimitiveData* data = (MetalPrimitiveData*) malloc(sizeof(MetalPrimitiveData));//[[MetalPrimitiveData alloc] init];
	  data->type = MTLPrimitiveTypeLine;
	  data->offset_in_index_buffer = (no_of_indices) * sizeof(unsigned short);
	  data->no_of_indices = 2;
	  data->primitiveInstances = 1;
    
    AllPrimitives[no_of_primitives] = data;  
	  no_of_primitives++;

	  // Add v1, v2 to VertexBuffer
	  addIndex(no_of_vertices);

    addVertex(v1);
    
    addIndex(no_of_vertices);

    addVertex(v2);
}

void VertexDataManager_addQuadVertexData(MetalVertex v1, MetalVertex v2, MetalVertex v3, MetalVertex v4) {
	
    MetalPrimitiveData* data = (MetalPrimitiveData*) malloc(sizeof(MetalPrimitiveData));//[[MetalPrimitiveData alloc] init];
	  data->type = MTLPrimitiveTypeTriangle;
	  data->offset_in_index_buffer = (no_of_indices) * sizeof(unsigned short);
	  data->no_of_indices = 6;
	  data->primitiveInstances = 2;

	  AllPrimitives[no_of_primitives] = data;
    no_of_primitives++;

	  // Add all 4 vertices to the Vertexbuffer
	  unsigned int firstVertexNumber = no_of_vertices;

    addVertex(v1);
    addVertex(v2);
    addVertex(v3);
    addVertex(v4);

    /*
       v1-------v4
       | \      |
       |  \     |
       |   \    |
       |    \   |
       |     \  |
       |      \ |
       |       \|
       v2-------v3
    */
    
    // A quad is made up of two triangles
    // Order of vertices is important - it is counter-clockwise
	  // Specify 2 set of triangles using 3 indices each
    addIndex(firstVertexNumber);     // v1
    addIndex(firstVertexNumber + 1); // v2
    addIndex(firstVertexNumber + 2); // v3

    addIndex(firstVertexNumber + 2); // v3
    addIndex(firstVertexNumber + 3); // v4
    addIndex(firstVertexNumber);     // v1
}

void addVertex(MetalVertex vert) {
    memcpy(VertexBuffer.contents + (no_of_vertices * sizeof(MetalVertex)), &vert, sizeof(MetalVertex));
    no_of_vertices++;
}

void addIndex(unsigned short vertexNum) {
	memcpy(IndexBuffer.contents + no_of_indices * sizeof(unsigned short), &vertexNum,  sizeof(unsigned short));
	no_of_indices++;
}


id<MTLBuffer> VertexDataManager_getVertexBuffer() {
	return VertexBuffer;
}

id<MTLBuffer> VertexDataManager_getIndexBuffer() {
	return IndexBuffer;
}

MetalPrimitiveData** VertexDataManager_getAllPrimitives() {
    return AllPrimitives;
}

unsigned int VertexDataManager_getNoOfPrimitives() {
	return no_of_primitives;
}

void VertexDataManager_freeAllPrimitives() {
 
    for (int i = 0; i < no_of_primitives; i++) {
    	free(AllPrimitives[i]);
    }

    free(AllPrimitives);
}

void VertexDataManager_reset(id<MTLDevice> device) {
    VertexDataManager_freeAllPrimitives();
    VertexBuffer = NULL;
    IndexBuffer = NULL;
    no_of_vertices = 0;
    no_of_indices = 0;
    no_of_primitives = 0;
    AllPrimitives = NULL;
    VertexDataManager_init(device);
}

#endif /* !HEADLESS */
