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
#include <Trace.h>

#include "jni_util.h"
#include "WLToolkit.h"

jfieldID nativePtrID;

struct WLFrame {
    jobject nativeFramePeer; // weak reference
    struct wl_surface *wl_surface;
    struct xdg_surface *xdg_surface;
    struct WLFrame *parent;
    struct xdg_positioner *xdg_positioner;
    jboolean toplevel;
    union {
        struct xdg_toplevel *xdg_toplevel;
        struct xdg_popup *xdg_popup;
    };
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
    J2dTrace3(J2D_TRACE_INFO, "WLComponentPeer: xdg_toplevel_configure(%p, %d, %d)\n", xdg_toplevel, width, height);
}

static void xdg_popup_configure(void *data,
                  struct xdg_popup *xdg_popup,
                  int32_t x,
                  int32_t y,
                  int32_t width,
                  int32_t height) {
    J2dTrace5(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_configure(%p, %d, %d, %d, %d)\n",
              xdg_popup, x, y, width, height);
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

static void xdg_popup_done(void *data,
                   struct xdg_popup *xdg_popup) {
    J2dTrace1(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_done(%p)\n", xdg_popup);
}

static const struct xdg_toplevel_listener xdg_toplevel_listener = {
        .configure = xdg_toplevel_configure,
        .close = xdg_toplevel_close,
};

static const struct xdg_popup_listener xdg_popup_listener = {
        .configure = xdg_popup_configure,
        .popup_done = xdg_popup_done,
};

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_initIDs
        (JNIEnv *env, jclass clazz)
{
    CHECK_NULL(nativePtrID = (*env)->GetFieldID(env, clazz, "nativePtr", "J"));
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeCreateFrame
  (JNIEnv *env, jobject obj)
{
    struct WLFrame *frame = (struct WLFrame *) malloc(sizeof(struct WLFrame));
    frame->nativeFramePeer = (*env)->NewWeakGlobalRef(env, obj);
    frame->wl_surface = NULL;
    frame->xdg_surface = NULL;
    frame->toplevel = JNI_FALSE;
    frame->xdg_popup = NULL;
    return (jlong)frame;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeShowComponent
  (JNIEnv *env, jobject obj, jlong ptr, jlong parentPtr, jint width, jint height)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    struct WLFrame *parentFrame = (struct WLFrame*) parentPtr;
    if (frame->wl_surface) return;
    frame->wl_surface = wl_compositor_create_surface(wl_compositor);
    frame->xdg_surface = xdg_wm_base_get_xdg_surface(xdg_wm_base, frame->wl_surface);

    xdg_surface_add_listener(frame->xdg_surface, &xdg_surface_listener, NULL);
    frame->toplevel = parentFrame == NULL;
    if (frame->toplevel) {
        frame->xdg_toplevel = xdg_surface_get_toplevel(frame->xdg_surface);
        xdg_toplevel_add_listener(frame->xdg_toplevel, &xdg_toplevel_listener, frame);
    } else {
        struct xdg_positioner *xdg_positioner =
                xdg_wm_base_create_positioner(xdg_wm_base);
        xdg_positioner_set_size(xdg_positioner, width, height);
        xdg_positioner_set_offset(xdg_positioner, 0, 0);
        xdg_positioner_set_anchor_rect(xdg_positioner, 0, 0, 1, 1);
        xdg_positioner_set_anchor(xdg_positioner, XDG_POSITIONER_ANCHOR_TOP_LEFT);
        frame->xdg_popup = xdg_surface_get_popup(frame->xdg_surface, parentFrame->xdg_surface, xdg_positioner);
        xdg_popup_add_listener(frame->xdg_popup, &xdg_popup_listener, frame);
    }

    wl_surface_commit(frame->wl_surface);
    wl_display_roundtrip(wl_display); // this should process 'configure' event, and send 'ack_configure' in response
}

static void doHide(struct WLFrame *frame) {
    if (frame->wl_surface) {
        if(frame->toplevel) {
            xdg_toplevel_destroy(frame->xdg_toplevel);
        } else {
            xdg_popup_destroy(frame->xdg_popup);
        }
        xdg_surface_destroy(frame->xdg_surface);
        wl_surface_destroy(frame->wl_surface);
        frame->wl_surface = NULL;
        frame->xdg_surface = NULL;
        frame->xdg_toplevel = NULL;
        frame->xdg_popup = NULL;
        frame->toplevel = JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeHideFrame
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    doHide(frame);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeDisposeFrame
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    doHide(frame);
    (*env)->DeleteWeakGlobalRef(env, frame->nativeFramePeer);
    free(frame);
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_WLComponentPeer_getWLSurface
  (JNIEnv *env, jobject obj)
{
    return (jlong)((struct WLFrame*)(*env)->GetLongField(env, obj, nativePtrID))->wl_surface;
}