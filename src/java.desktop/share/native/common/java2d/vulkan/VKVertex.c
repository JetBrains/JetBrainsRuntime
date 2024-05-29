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

#ifndef HEADLESS

#include <string.h>
#include "CArrayUtil.h"
#include "VKVertex.h"

VKVertexDescr VKVertex_GetTxVertexDescr() {
    static VkVertexInputBindingDescription bindingDescriptions[] = {
            {
                    .binding = 0,
                    .stride = sizeof(VKTxVertex),
                    .inputRate = VK_VERTEX_INPUT_RATE_VERTEX
            }
    };

    static VkVertexInputAttributeDescription attributeDescriptions [] = {
            {
                    .binding = 0,
                    .location = 0,
                    .format = VK_FORMAT_R32G32_SFLOAT,
                    .offset = offsetof(VKTxVertex, px)
            },
            {
                    .binding = 0,
                    .location = 1,
                    .format = VK_FORMAT_R32G32_SFLOAT,
                    .offset = offsetof(VKTxVertex, u)
            }
    };

    return (VKVertexDescr) {
        .attributeDescriptions = attributeDescriptions,
        .attributeDescriptionCount = SARRAY_COUNT_OF(attributeDescriptions),
        .bindingDescriptions = bindingDescriptions,
        .bindingDescriptionCount = SARRAY_COUNT_OF(bindingDescriptions)
    };
}

VKVertexDescr VKVertex_GetCVertexDescr() {
    static VkVertexInputBindingDescription bindingDescriptions[] = {
            {
                    .binding = 0,
                    .stride = sizeof(VKCVertex),
                    .inputRate = VK_VERTEX_INPUT_RATE_VERTEX
            }
    };

    static VkVertexInputAttributeDescription attributeDescriptions [] = {
            {
                    .binding = 0,
                    .location = 0,
                    .format = VK_FORMAT_R32G32_SFLOAT,
                    .offset = offsetof(VKCVertex, px)
            },
            {
                    .binding = 0,
                    .location = 1,
                    .format = VK_FORMAT_R32G32B32A32_SFLOAT,
                    .offset = offsetof(VKCVertex, r)
            }
    };

    return (VKVertexDescr) {
            .attributeDescriptions = attributeDescriptions,
            .attributeDescriptionCount = SARRAY_COUNT_OF(attributeDescriptions),
            .bindingDescriptions = bindingDescriptions,
            .bindingDescriptionCount = SARRAY_COUNT_OF(bindingDescriptions)
    };
}

#endif /* !HEADLESS */