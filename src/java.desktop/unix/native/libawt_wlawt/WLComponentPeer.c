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
#include <assert.h>

#include "jni_util.h"
#include "WLToolkit.h"
#include "WLRobotPeer.h"
#include "WLGraphicsEnvironment.h"

#ifdef WAKEFIELD_ROBOT
#include "wakefield-client-protocol.h"
#endif

static jfieldID nativePtrID;
static jmethodID postWindowClosingMID;
static jmethodID notifyConfiguredMID;
static jmethodID notifyEnteredOutputMID;
static jmethodID notifyLeftOutputMID;

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
    jboolean configuredPending;
    int32_t configuredWidth;
    int32_t configuredHeight;
    jboolean configuredActive;
    jboolean configuredMaximized;
};

static void
xdg_surface_configure(void *data,
                      struct xdg_surface *xdg_surface,
                      uint32_t serial)
{
    xdg_surface_ack_configure(xdg_surface, serial);

    struct WLFrame *wlFrame = (struct WLFrame*)data;
    assert(wlFrame);

    if (wlFrame->configuredPending) {
        wlFrame->configuredPending = JNI_FALSE;
        
        JNIEnv *env = getEnv();
        const jobject nativeFramePeer = (*env)->NewLocalRef(env, wlFrame->nativeFramePeer);
        if (nativeFramePeer) {
            (*env)->CallVoidMethod(env, nativeFramePeer, notifyConfiguredMID,
                                   wlFrame->configuredWidth, wlFrame->configuredHeight,
                                   wlFrame->configuredActive, wlFrame->configuredMaximized);
            (*env)->DeleteLocalRef(env, nativeFramePeer);
            JNU_CHECK_EXCEPTION(env);
        }
    }
}

static void
wl_surface_entered_output(void *data,
                          struct wl_surface *wl_surface,
                          struct wl_output *output)
{
    J2dTrace2(J2D_TRACE_INFO, "wl_surface %p entered output %p\n", wl_surface, output);
    struct WLFrame *wlFrame = (struct WLFrame*) data;
    uint32_t wlOutputID = WLOutputID(output);
    if (wlOutputID == 0) return;

    JNIEnv *env = getEnv();
    const jobject nativeFramePeer = (*env)->NewLocalRef(env, wlFrame->nativeFramePeer);
    if (nativeFramePeer) {
        (*env)->CallVoidMethod(env, nativeFramePeer, notifyEnteredOutputMID, wlOutputID);
        (*env)->DeleteLocalRef(env, nativeFramePeer);
        JNU_CHECK_EXCEPTION(env);
    }
}

static void
wl_surface_left_output(void *data,
                       struct wl_surface *wl_surface,
                       struct wl_output *output)
{
    J2dTrace2(J2D_TRACE_INFO, "wl_surface %p left output %p\n", wl_surface, output);
    struct WLFrame *wlFrame = (struct WLFrame*) data;
    uint32_t wlOutputID = WLOutputID(output);
    if (wlOutputID == 0) return;

    JNIEnv *env = getEnv();
    const jobject nativeFramePeer = (*env)->NewLocalRef(env, wlFrame->nativeFramePeer);
    if (nativeFramePeer) {
        (*env)->CallVoidMethod(env, nativeFramePeer, notifyLeftOutputMID, wlOutputID);
        (*env)->DeleteLocalRef(env, nativeFramePeer);
        JNU_CHECK_EXCEPTION(env);
    }

}

static const struct xdg_surface_listener xdg_surface_listener = {
        .configure = xdg_surface_configure,
};

static const struct wl_surface_listener wl_surface_listener = {
        .enter = wl_surface_entered_output,
        .leave = wl_surface_left_output
};

static void
xdg_toplevel_configure(void *data,
                       struct xdg_toplevel *xdg_toplevel,
                       int32_t width,
                       int32_t height,
                       struct wl_array *states)
{
    J2dTrace3(J2D_TRACE_INFO, "WLComponentPeer: xdg_toplevel_configure(%p, %d, %d)\n",
              xdg_toplevel, width, height);

    struct WLFrame *wlFrame = (struct WLFrame*)data;
    assert(wlFrame);

    jboolean active = JNI_FALSE;
    jboolean maximized = JNI_FALSE;
    uint32_t *p;
    wl_array_for_each(p, states) {
        uint32_t state = *p;
        switch (state) {
            case XDG_TOPLEVEL_STATE_ACTIVATED:
                active = JNI_TRUE;
                break;
            case XDG_TOPLEVEL_STATE_FULLSCREEN:
                break;
            case XDG_TOPLEVEL_STATE_MAXIMIZED:
                maximized = JNI_TRUE;
                break;
            default:
                break;
        }
    }

    wlFrame->configuredPending = JNI_TRUE;
    wlFrame->configuredWidth = width;
    wlFrame->configuredHeight = height;
    wlFrame->configuredActive = active;
    wlFrame->configuredMaximized = maximized;
}

static void
xdg_popup_configure(void *data,
                                struct xdg_popup *xdg_popup,
                                int32_t x,
                                int32_t y,
                                int32_t width,
                                int32_t height)
{
    J2dTrace5(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_configure(%p, %d, %d, %d, %d)\n",
              xdg_popup, x, y, width, height);

    struct WLFrame *wlFrame = (struct WLFrame*)data;
    assert(wlFrame);

    wlFrame->configuredPending = JNI_TRUE;
    wlFrame->configuredWidth = width;
    wlFrame->configuredHeight = height;
}

static void
xdg_toplevel_close(void *data,
		           struct xdg_toplevel *xdg_toplevel)
{
    struct WLFrame *frame = (struct WLFrame *) data;
    JNIEnv *env = getEnv();
    const jobject nativeFramePeer = (*env)->NewLocalRef(env, frame->nativeFramePeer);
    if (nativeFramePeer) {
        (*env)->CallVoidMethod(env, nativeFramePeer, postWindowClosingMID);
        (*env)->DeleteLocalRef(env, nativeFramePeer);
        JNU_CHECK_EXCEPTION(env);
    }
}

static void
xdg_popup_done(void *data,
               struct xdg_popup *xdg_popup)
{
    J2dTrace1(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_done(%p)\n", xdg_popup);
    // TODO
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
    CHECK_NULL_THROW_IE(env,
                        notifyConfiguredMID = (*env)->GetMethodID(env, clazz, "notifyConfigured", "(IIZZ)V"),
                        "Failed to find method WLComponentPeer.notifyConfigured");
    CHECK_NULL_THROW_IE(env,
                        notifyEnteredOutputMID = (*env)->GetMethodID(env, clazz, "notifyEnteredOutput", "(I)V"),
                        "Failed to find method WLComponentPeer.notifyEnteredOutput");
    CHECK_NULL_THROW_IE(env,
                        notifyLeftOutputMID = (*env)->GetMethodID(env, clazz, "notifyLeftOutput", "(I)V"),
                        "Failed to find method WLComponentPeer.notifyLeftOutput");
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDecoratedPeer_initIDs
        (JNIEnv *env, jclass clazz)
{
    CHECK_NULL_THROW_IE(env,
                        postWindowClosingMID = (*env)->GetMethodID(env, clazz, "postWindowClosing", "()V"),
                        "Failed to find method WLDecoratedPeer.postWindowClosing");
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeCreateFrame
  (JNIEnv *env, jobject obj)
{
    struct WLFrame *frame = (struct WLFrame *) calloc(1, sizeof(struct WLFrame));
    frame->nativeFramePeer = (*env)->NewWeakGlobalRef(env, obj);
    return (jlong)frame;
}

static void
FrameSetTitle
        (JNIEnv* env, struct WLFrame *frame, jstring title)
{
    if (!frame->xdg_toplevel) return;

    jboolean iscopy = JNI_FALSE;
    const char *title_c_str = JNU_GetStringPlatformChars(env, title, &iscopy);
    if (title_c_str) {
        xdg_toplevel_set_title(frame->xdg_toplevel, title_c_str);
        if (iscopy) {
            JNU_ReleaseStringPlatformChars(env, title, title_c_str);
        }
    }
}

static void
FrameSetAppID
        (JNIEnv* env, struct WLFrame *frame, jstring appid)
{
    if (!frame->xdg_toplevel) return;

    jboolean iscopy = JNI_FALSE;
    const char *id_c_str = JNU_GetStringPlatformChars(env, appid, &iscopy);
    if (id_c_str) {
        xdg_toplevel_set_app_id(frame->xdg_toplevel, id_c_str);
        if (iscopy) {
            JNU_ReleaseStringPlatformChars(env, appid, id_c_str);
        }
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeSetTitle
        (JNIEnv *env, jobject obj, jlong ptr, jstring title)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    FrameSetTitle(env, frame, title);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestMinimized
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_set_minimized(frame->xdg_toplevel);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestMaximized
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_set_maximized(frame->xdg_toplevel);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestUnmaximized
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_unset_maximized(frame->xdg_toplevel);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestFullScreen
        (JNIEnv *env, jobject obj, jlong ptr, jint wlID)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        struct wl_output *wl_output = WLOutputByID((uint32_t)wlID);
        xdg_toplevel_set_fullscreen(frame->xdg_toplevel, wl_output);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestUnsetFullScreen
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_unset_fullscreen(frame->xdg_toplevel);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeCreateWLSurface
  (JNIEnv *env, jobject obj, jlong ptr, jlong parentPtr, jboolean isPopup,
   jint x, jint y, jstring title, jstring appid)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    struct WLFrame *parentFrame = (struct WLFrame*) parentPtr;
    if (frame->wl_surface) return;
    frame->wl_surface = wl_compositor_create_surface(wl_compositor);
    frame->xdg_surface = xdg_wm_base_get_xdg_surface(xdg_wm_base, frame->wl_surface);

    wl_surface_add_listener(frame->wl_surface, &wl_surface_listener, frame);
    xdg_surface_add_listener(frame->xdg_surface, &xdg_surface_listener, frame);
    frame->toplevel = !isPopup;
    if (frame->toplevel) {
        frame->xdg_toplevel = xdg_surface_get_toplevel(frame->xdg_surface);
        xdg_toplevel_add_listener(frame->xdg_toplevel, &xdg_toplevel_listener, frame);
        if (title) {
            FrameSetTitle(env, frame, title);
        }
        if (appid) {
            FrameSetAppID(env, frame, appid);
        }
        if (parentFrame) {
            xdg_toplevel_set_parent(frame->xdg_toplevel, parentFrame->xdg_toplevel);
        }
    } else {
        assert(parentFrame);
        struct xdg_positioner *xdg_positioner =
                xdg_wm_base_create_positioner(xdg_wm_base);
        xdg_positioner_set_offset(xdg_positioner, x, y);
        frame->xdg_popup = xdg_surface_get_popup(frame->xdg_surface, parentFrame->xdg_surface, xdg_positioner);
        xdg_popup_add_listener(frame->xdg_popup, &xdg_popup_listener, frame);
    }
#ifdef WAKEFIELD_ROBOT
        if (wakefield) {
            // TODO: this doesn't work quite as expected for some reason
            wakefield_move_surface(wakefield, frame->wl_surface, x, y);
        }
#endif
    // From xdg-shell.xml: "After creating a role-specific object and
    // setting it up, the client must perform an initial commit
    // without any buffer attached"
    wl_surface_commit(frame->wl_surface);
}

static void
DoHide(struct WLFrame *frame)
{
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
    struct WLFrame *frame = jlong_to_ptr(ptr);
    DoHide(frame);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeDisposeFrame
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    DoHide(frame);
    (*env)->DeleteWeakGlobalRef(env, frame->nativeFramePeer);
    free(frame);
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_WLComponentPeer_getWLSurface
  (JNIEnv *env, jobject obj, jlong ptr)
{
    return ptr_to_jlong(((struct WLFrame*)jlong_to_ptr(ptr))->wl_surface);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeStartDrag
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel && wl_seat) {
        xdg_toplevel_move(frame->xdg_toplevel, wl_seat, last_mouse_pressed_serial);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeStartResize
  (JNIEnv *env, jobject obj, jlong ptr, jint edges)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel && wl_seat) {
        xdg_toplevel_resize(frame->xdg_toplevel, wl_seat, last_mouse_pressed_serial, edges);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetWindowGeometry
        (JNIEnv *env, jobject obj, jlong ptr, jint x, jint y, jint width, jint height)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_surface) {
        xdg_surface_set_window_geometry(frame->xdg_surface, x, y, width, height);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetMinimumSize
        (JNIEnv *env, jobject obj, jlong ptr, jint width, jint height)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel) {
        xdg_toplevel_set_min_size(frame->xdg_toplevel, width, height);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetMaximumSize
        (JNIEnv *env, jobject obj, jlong ptr, jint width, jint height)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel) {
        xdg_toplevel_set_max_size(frame->xdg_toplevel, width, height);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeShowWindowMenu
        (JNIEnv *env, jobject obj, jlong ptr, jint x, jint y)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel) {
        xdg_toplevel_show_window_menu(frame->xdg_toplevel, wl_seat, last_mouse_pressed_serial, x, y);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetBufferScale
        (JNIEnv *env, jobject obj, jlong ptr, jint scale)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->wl_surface) {
        wl_surface_set_buffer_scale(frame->wl_surface, scale);
    }
}
