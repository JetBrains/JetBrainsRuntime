/*
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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
#include <string.h>
#include <jni.h>

#include "jni_util.h"
#include "WLToolkit.h"

jfieldID nativePtrID;

struct WLFrame {
    jobject nativeFramePeer; // weak reference
    struct wl_surface *wl_surface;
    struct xdg_surface *xdg_surface;
    struct xdg_toplevel *xdg_toplevel;
};

static void xdg_surface_configure(void *data,
                                  struct xdg_surface *xdg_surface, uint32_t serial) {
    xdg_surface_ack_configure(xdg_surface, serial);
}

static const struct xdg_surface_listener xdg_surface_listener = {
        .configure = xdg_surface_configure,
};

static void xdg_toplevel_configure(void *data,
       			  struct xdg_toplevel *xdg_toplevel,
       			  int32_t width,
       			  int32_t height,
       			  struct wl_array *states) {
}

static void xdg_toplevel_close(void *data,
		      struct xdg_toplevel *xdg_toplevel) {
    struct WLFrame *frame = (struct WLFrame *) data;
    JNIEnv *env = getEnv();
    jobject nativeFramePeer = (*env)->NewLocalRef(env, frame->nativeFramePeer);
    if (nativeFramePeer) {
        static jclass wlFramePeerCID = NULL;
        if (!wlFramePeerCID) {
            wlFramePeerCID = (*env)->FindClass(env, "sun/awt/wl/WLFramePeer");
        }
        static jmethodID postWindowClosingMID = NULL;
        if (!postWindowClosingMID) {
            postWindowClosingMID = (*env)->GetMethodID(env, wlFramePeerCID, "postWindowClosing", "()V");
        }
        (*env)->CallVoidMethod(env, nativeFramePeer, postWindowClosingMID);
        (*env)->DeleteLocalRef(env, nativeFramePeer);
    }
}

static const struct xdg_toplevel_listener xdg_toplevel_listener = {
        .configure = xdg_toplevel_configure,
        .close = xdg_toplevel_close,
};


JNIEXPORT void JNICALL
Java_sun_awt_wl_WLFramePeer_initIDs
        (JNIEnv *env, jclass clazz)
{
    CHECK_NULL(nativePtrID = (*env)->GetFieldID(env, clazz, "nativePtr", "J"));
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLFramePeer_nativeCreateFrame
  (JNIEnv *env, jobject obj)
{
    struct WLFrame *frame = (struct WLFrame *) malloc(sizeof(struct WLFrame));
    frame->nativeFramePeer = (*env)->NewWeakGlobalRef(env, obj);
    frame->wl_surface = NULL;
    frame->xdg_surface = NULL;
    frame->xdg_toplevel = NULL;
    return (jlong)frame;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLFramePeer_nativeShowFrame
  (JNIEnv *env, jobject obj, jlong ptr, jint width, jint height)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    if (frame->wl_surface) return;
    frame->wl_surface = wl_compositor_create_surface(wl_compositor);
    frame->xdg_surface = xdg_wm_base_get_xdg_surface(xdg_wm_base, frame->wl_surface);
    xdg_surface_add_listener(frame->xdg_surface, &xdg_surface_listener, NULL);
    frame->xdg_toplevel = xdg_surface_get_toplevel(frame->xdg_surface);
    xdg_toplevel_add_listener(frame->xdg_toplevel, &xdg_toplevel_listener, frame);
    wl_surface_commit(frame->wl_surface);
    wl_display_roundtrip(wl_display); // this should process 'configure' event, and send 'ack_configure' in response
}

static void doHide(struct WLFrame *frame) {
    if (frame->wl_surface) {
        xdg_toplevel_destroy(frame->xdg_toplevel);
        xdg_surface_destroy(frame->xdg_surface);
        wl_surface_destroy(frame->wl_surface);
        frame->wl_surface = NULL;
        frame->xdg_surface = NULL;
        frame->xdg_toplevel = NULL;
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLFramePeer_nativeHideFrame
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    doHide(frame);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLFramePeer_nativeDisposeFrame
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    doHide(frame);
    (*env)->DeleteWeakGlobalRef(env, frame->nativeFramePeer);
    free(frame);
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_WLFramePeer_getWLSurface
  (JNIEnv *env, jobject obj)
{
    return (jlong)((struct WLFrame*)(*env)->GetLongField(env, obj, nativePtrID))->wl_surface;
}