/*
 * Copyright 2018 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#include <stdlib.h>
#include <jlong.h>

#include "MTLMaskBlit.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceDataBase.h"

/**
 * REMIND: This method assumes that the dimensions of the incoming pixel
 *         array are less than or equal to the cached blit texture tile;
 *         these are rather fragile assumptions, and should be cleaned up...
 */
void
MTLMaskBlit_MaskBlit(JNIEnv *env, MTLContext *mtlc,
                     jint dstx, jint dsty,
                     jint width, jint height,
                     void *pPixels)
{
   // GLfloat tx1, ty1, tx2, ty2;

    J2dTraceLn(J2D_TRACE_INFO, "MTLMaskBlit_MaskBlit");

    if (width <= 0 || height <= 0) {
        J2dTraceLn(J2D_TRACE_WARNING,
                   "MTLMaskBlit_MaskBlit: invalid dimensions");
        return;
    }
/*
    RETURN_IF_NULL(pPixels);
    RETURN_IF_NULL(mtlc);
    CHECK_PREVIOUS_OP(GL_TEXTURE_2D);

    if (mtlc->blitTextureID == 0) {
        if (!MTLContext_InitBlitTileTexture(mtlc)) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                "MTLMaskBlit_MaskBlit: could not init blit tile");
            return;
        }
    }

    // set up texture parameters
    j2d_glBindTexture(GL_TEXTURE_2D, mtlc->blitTextureID);
    MTLC_UPDATE_TEXTURE_FUNCTION(mtlc, GL_MODULATE);
    j2d_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    j2d_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);

    // copy system memory IntArgbPre surface into cached texture
    j2d_glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, width, height,
                        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pPixels);

    tx1 = 0.0f;
    ty1 = 0.0f;
    tx2 = ((GLfloat)width) / MTLC_BLIT_TILE_SIZE;
    ty2 = ((GLfloat)height) / MTLC_BLIT_TILE_SIZE;

    // render cached texture to the OpenGL surface
    j2d_glBegin(GL_QUADS);
    j2d_glTexCoord2f(tx1, ty1); j2d_glVertex2i(dstx, dsty);
    j2d_glTexCoord2f(tx2, ty1); j2d_glVertex2i(dstx + width, dsty);
    j2d_glTexCoord2f(tx2, ty2); j2d_glVertex2i(dstx + width, dsty + height);
    j2d_glTexCoord2f(tx1, ty2); j2d_glVertex2i(dstx, dsty + height);
    j2d_glEnd();
    */
}

#endif /* !HEADLESS */
