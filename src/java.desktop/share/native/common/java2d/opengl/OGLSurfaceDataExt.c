/*
 * Copyright 2025 JetBrains s.r.o.
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

#include "sun_java2d_opengl_OGLSurfaceDataExt.h"

#include "jni_util.h"

#include "OGLSurfaceData.h"

void
OGLSD_DisposeTextureWrapper
    (JNIEnv *env, SurfaceDataOps *ops)
{
    OGLSDOps *oglsdo = (OGLSDOps *)ops;
    if (!oglsdo) {
        J2dTraceLn(J2D_TRACE_ERROR, "OGLSD_DisposeTextureWrapper: oglsdo is null");
    }

    if(oglsdo->textureID) {
        oglsdo->textureID = 0;
        J2dTraceLn(J2D_TRACE_VERBOSE, "OGLSD_DisposeTextureWrapper: texture %d is reset", oglsdo->textureID);
    } else {
        J2dTraceLn(J2D_TRACE_WARNING, "OGLSD_DisposeTextureWrapper: texture ID is 0");
    }
    OGLSD_Dispose(env, ops);
}

// from OGLSurfaceData.c
extern void
OGLSD_SetNativeDimensions
(JNIEnv *env, OGLSDOps *oglsdo, jint width, jint height);

JNIEXPORT
jboolean JNICALL
Java_sun_java2d_opengl_OGLSurfaceDataExt_initWithTexture
    (JNIEnv *env, jclass cls, jlong pData, jlong textureId)
{
    OGLSDOps *oglsdo = jlong_to_ptr(pData);

    if (oglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSurfaceData_initWithTexture: ops are null");
        return JNI_FALSE;
    }

    j2d_glBindTexture(GL_TEXTURE_2D, textureId);
    GLenum error = j2d_glGetError();
    if (error != GL_NO_ERROR) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "OGLSurfaceData_initWithTexture: could not bind texture: id=%d error=%x",
            textureId, error);
        return JNI_FALSE;
    }

    if (!j2d_glIsTexture(textureId)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSurfaceData_initWithTexture: textureId is not a valid texture id");
        j2d_glBindTexture(GL_TEXTURE_2D, 0);
        return JNI_FALSE;
    }

    GLsizei width, height;
    j2d_glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &width);
    j2d_glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &height);
    j2d_glBindTexture(GL_TEXTURE_2D, 0);

    GLint texMax;
    j2d_glGetIntegerv(GL_MAX_TEXTURE_SIZE, &texMax);
    if (width >= texMax || height >= texMax || width <= 0 || height <= 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "OGLSurfaceData_initWithTexture: wrong texture size %d x %d",
            width, height);
        return JNI_FALSE;
    }

    oglsdo->xOffset = 0;
    oglsdo->yOffset = 0;
    oglsdo->width = width;
    oglsdo->height = height;
    oglsdo->textureID = textureId;
    oglsdo->textureWidth = width;
    oglsdo->textureHeight = height;
    oglsdo->isOpaque = JNI_FALSE;
    oglsdo->textureTarget = GL_TEXTURE_2D;

    // Add a custom disposer to reset the texture id before deleting it
    oglsdo->sdOps.Dispose = OGLSD_DisposeTextureWrapper;

    GLuint fbobjectID, depthID;
    if (!OGLSD_InitFBObject(&fbobjectID, &depthID,
                            oglsdo->textureID, oglsdo->textureTarget,
                            oglsdo->textureWidth, oglsdo->textureHeight))
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "OGLSurfaceData_initWithTexture: could not init fbobject");
        return JNI_FALSE;
    }

    oglsdo->drawableType = OGLSD_FBOBJECT;
    oglsdo->fbobjectID = fbobjectID;
    oglsdo->depthID = depthID;

    OGLSD_SetNativeDimensions(env, oglsdo, width, height);

    oglsdo->activeBuffer = GL_COLOR_ATTACHMENT0_EXT;

    J2dTraceLn(J2D_TRACE_VERBOSE, "OGLSurfaceData_initWithTexture: wrapped texture: w=%d h=%d id=%d",
        width, height, textureId);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_sun_java2d_opengl_OGLSurfaceDataExt_resetTextureId
    (JNIEnv *env, jclass cls, jlong pData)
{
    OGLSDOps *oglsdo = jlong_to_ptr(pData);
    oglsdo->textureID = 0;
}

#endif /* !HEADLESS */
