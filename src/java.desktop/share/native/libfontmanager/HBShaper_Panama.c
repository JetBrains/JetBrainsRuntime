/*
 * Copyright (c) 2023, 2025, Oracle and/or its affiliates. All rights reserved.
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

#include <stdlib.h>
#include <stdbool.h>
#include "hb.h"
#include "hb-jdk-p.h"
#include "hb-ot.h"
#include "scriptMapping.h"
#include "HBShaper.h"

static float euclidianDistance(float a, float b)
{
    float root;
    if (a < 0) {
        a = -a;
    }

    if (b < 0) {
        b = -b;
    }

    if (a == 0) {
        return b;
    }

    if (b == 0) {
        return a;
    }

    /* Do an initial approximation, in root */
    root = a > b ? a + (b / 2) : b + (a / 2);

    /* An unrolled Newton-Raphson iteration sequence */
    root = (root + (a * (a / root)) + (b * (b / root)) + 1) / 2;
    root = (root + (a * (a / root)) + (b * (b / root)) + 1) / 2;
    root = (root + (a * (a / root)) + (b * (b / root)) + 1) / 2;

    return root;
}

JDKEXPORT void jdk_hb_shape(
     float ptSize,
     float *matrix,
     void* pFace,
     unsigned short *chars,
     int len,
     int script,
     int offset,
     int limit,
     int baseIndex,
     float startX,
     float startY,
     int ltrDirection,
     const char *features,
     int slot,
     hb_font_funcs_t* font_funcs,
     store_layoutdata_func_t store_layout_results_fn) {

     hb_buffer_t *buffer;
     hb_face_t* hbface;
     hb_font_t* hbfont;
     int glyphCount;
     hb_glyph_info_t *glyphInfo;
     hb_glyph_position_t *glyphPos;
     int featureCount = 0;
     unsigned int buflen;

     float devScale = 1.0f;
     if (getenv("HB_NODEVTX") != NULL) {
         float xPtSize = euclidianDistance(matrix[0], matrix[1]);
         devScale = xPtSize / ptSize;
     }

     hbface = (hb_face_t*)pFace;
     hbfont = jdk_font_create_hbp(hbface,
                                  ptSize, devScale, NULL,
                                  font_funcs);

     buffer = create_buffer(script, ltrDirection);

     int charCount = limit - offset;
     hb_buffer_add_utf16(buffer, chars, len, offset, charCount);

     shape_full(hbfont, buffer, features);

     glyphCount = hb_buffer_get_length(buffer);
     glyphInfo = hb_buffer_get_glyph_infos(buffer, 0);
     glyphPos = hb_buffer_get_glyph_positions(buffer, &buflen);

     (*store_layout_results_fn)
               (slot, baseIndex, offset, startX, startY, devScale,
                charCount, glyphCount, glyphInfo, glyphPos);

     hb_buffer_destroy (buffer);
     hb_font_destroy(hbfont);
     return;
}
