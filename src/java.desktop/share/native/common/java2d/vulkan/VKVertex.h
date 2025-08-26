/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

#ifndef VKVertex_h_Included
#define VKVertex_h_Included

#include <vulkan/vulkan.h>
#include "VKBuffer.h"

#define RGBA_TO_L4(c)              \
    (((c) >> 16) & (0xFF))/255.0f, \
    (((c) >> 8) & 0xFF)/255.0f,    \
    ((c) & 0xFF)/255.0f,           \
    (((c) >> 24) & 0xFF)/255.0f

#define ARRAY_TO_VERTEX_BUF(vertices)                                           \
    VKBuffer_CreateFromData(vertices, ARRAY_SIZE(vertices)*sizeof (vertices[0]))

typedef struct {
    VkVertexInputAttributeDescription *attributeDescriptions;
    uint32_t attributeDescriptionCount;
    VkVertexInputBindingDescription* bindingDescriptions;
    uint32_t bindingDescriptionCount;
} VKVertexDescr;

typedef struct {
    float px, py;
    float u, v;
} VKTxVertex;

typedef struct {
    float px, py;
    float r, g, b, a;
} VKCVertex;

VKVertexDescr VKVertex_GetTxVertexDescr();
VKVertexDescr VKVertex_GetCVertexDescr();



#endif //VKVertex_h_Included
