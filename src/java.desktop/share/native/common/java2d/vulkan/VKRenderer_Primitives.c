// Copyright 2025 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#include "VKRenderer_Internal.h"

void VKRenderer_RenderRect(VkBool32 fill, jint x, jint y, jint w, jint h) {
    VKRenderer_RenderParallelogram(fill, (float) x, (float) y,
                                   (float) w, 0, 0,(float) h);
}

void VKRenderer_RenderParallelogram(VkBool32 fill,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12)
{
    if (!VKRenderer_Validate(SHADER_COLOR,
                             fill ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                                  : VK_PRIMITIVE_TOPOLOGY_LINE_LIST, ALPHA_TYPE_UNKNOWN)) return; // Not ready.
    RGBA c = VKRenderer_GetColor();
    /*                   dx21
     *    (p1)---------(p2) |          (p1)------
     *     |\            \  |            |  \    dy21
     *     | \            \ |       dy12 |   \
     *     |  \            \|            |   (p2)-
     *     |  (p4)---------(p3)        (p4)   |
     *      dx12                           \  |  dy12
     *                              dy21    \ |
     *                                  -----(p3)
     */
    VKColorVertex p1 = {x11, y11, c};
    VKColorVertex p2 = {x11 + dx21, y11 + dy21, c};
    VKColorVertex p3 = {x11 + dx21 + dx12, y11 + dy21 + dy12, c};
    VKColorVertex p4 = {x11 + dx12, y11 + dy12, c};

    VKColorVertex* vs;
    VK_DRAW(vs, 1, fill ? 6 : 8);
    uint32_t i = 0;
    vs[i++] = p1;
    vs[i++] = p2;
    vs[i++] = p3;
    vs[i++] = p4;
    vs[i++] = p1;
    if (!fill) {
        vs[i++] = p4;
        vs[i++] = p2;
    }
    vs[i++] = p3;
}

void VKRenderer_FillSpans(jint spanCount, jint *spans) {
    if (spanCount == 0) return;
    if (!VKRenderer_Validate(SHADER_COLOR, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, ALPHA_TYPE_UNKNOWN)) return; // Not ready.
    RGBA c = VKRenderer_GetColor();

    jfloat x1 = (float)*(spans++);
    jfloat y1 = (float)*(spans++);
    jfloat x2 = (float)*(spans++);
    jfloat y2 = (float)*(spans++);
    VKColorVertex p1 = {x1, y1, c};
    VKColorVertex p2 = {x2, y1, c};
    VKColorVertex p3 = {x2, y2, c};
    VKColorVertex p4 = {x1, y2, c};

    VKColorVertex* vs;
    VK_DRAW(vs, 1, 6);
    vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;

    for (int i = 1; i < spanCount; i++) {
        p1.x = p4.x = (float)*(spans++);
        p1.y = p2.y = (float)*(spans++);
        p2.x = p3.x = (float)*(spans++);
        p3.y = p4.y = (float)*(spans++);

        VK_DRAW(vs, 1, 6);
        vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;
    }
}
