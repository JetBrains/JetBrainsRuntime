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
#include <jni_util.h>
#include <Trace.h>
#include <assert.h>

#include "JNIUtilities.h"
#include "WLToolkit.h"
#include "WLRobotPeer.h"
#include "WLGraphicsEnvironment.h"

#ifdef WAKEFIELD_ROBOT
#include "wakefield.h"
#endif

#ifdef HAVE_GTK_SHELL1
#include <gtk-shell.h>
#endif

static jmethodID postWindowClosingMID;
static jmethodID notifyConfiguredMID;
static jmethodID notifyPopupDoneMID;

struct WLFrame {
    jobject nativeFramePeer; // weak reference
    struct xdg_surface *xdg_surface;
    struct gtk_surface1 *gtk_surface;
    struct WLFrame *parent;
    struct xdg_positioner *xdg_positioner;
    jboolean toplevel;
    union {
        struct xdg_toplevel *xdg_toplevel;
        struct xdg_popup *xdg_popup;
    };
    jboolean configuredPending;
    int32_t configuredX;
    int32_t configuredY;
    int32_t configuredWidth;
    int32_t configuredHeight;
    jboolean configuredActive;
    jboolean configuredMaximized;
    jboolean configuredFullscreen;
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
                                   wlFrame->configuredX, wlFrame->configuredY,
                                   wlFrame->configuredWidth, wlFrame->configuredHeight,
                                   wlFrame->configuredActive, wlFrame->configuredMaximized, wlFrame->configuredFullscreen);
            (*env)->DeleteLocalRef(env, nativeFramePeer);
            JNU_CHECK_EXCEPTION(env);
        }
    }
}

static const struct xdg_surface_listener xdg_surface_listener = {
        .configure = xdg_surface_configure,
};

static void
xdg_toplevel_configure(void *data,
                       struct xdg_toplevel *xdg_toplevel,
                       int32_t width,
                       int32_t height,
                       struct wl_array *states)
{
    J2dTrace(J2D_TRACE_INFO, "WLComponentPeer: xdg_toplevel_configure(%p, %d, %d)\n",
             xdg_toplevel, width, height);

    struct WLFrame *wlFrame = (struct WLFrame*)data;
    assert(wlFrame);

    jboolean active = JNI_FALSE;
    jboolean maximized = JNI_FALSE;
    jboolean fullscreen = JNI_FALSE;
    uint32_t *p;
    wl_array_for_each(p, states) {
        uint32_t state = *p;
        switch (state) {
            case XDG_TOPLEVEL_STATE_ACTIVATED:
                active = JNI_TRUE;
                break;
            case XDG_TOPLEVEL_STATE_FULLSCREEN:
                fullscreen = JNI_TRUE;
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
    wlFrame->configuredFullscreen= fullscreen;
}

static void
xdg_popup_configure(void *data,
                    struct xdg_popup *xdg_popup,
                    int32_t x,
                    int32_t y,
                    int32_t width,
                    int32_t height)
{
    J2dTrace(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_configure(%p, %d, %d, %d, %d)\n",
             xdg_popup, x, y, width, height);

    struct WLFrame *wlFrame = data;
    assert(wlFrame);

    wlFrame->configuredPending = JNI_TRUE;
    wlFrame->configuredX = x;
    wlFrame->configuredY = y;
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
    J2dTrace(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_done(%p)\n", xdg_popup);
    struct WLFrame *frame = data;
    JNIEnv *env = getEnv();
    const jobject nativeFramePeer = (*env)->NewLocalRef(env, frame->nativeFramePeer);
    if (nativeFramePeer) {
        (*env)->CallVoidMethod(env, nativeFramePeer, notifyPopupDoneMID);
        (*env)->DeleteLocalRef(env, nativeFramePeer);
        JNU_CHECK_EXCEPTION(env);
    }
}

static void
xdg_popup_repositioned(void *data, struct xdg_popup *xdg_popup, uint32_t token)
{
    J2dTrace(J2D_TRACE_INFO, "WLComponentPeer: xdg_popup_repositioned(%d)\n", token);
}

static const struct xdg_toplevel_listener xdg_toplevel_listener = {
        .configure = xdg_toplevel_configure,
        .close = xdg_toplevel_close,
};

static const struct xdg_popup_listener xdg_popup_listener = {
        .configure = xdg_popup_configure,
        .popup_done = xdg_popup_done,
        .repositioned = xdg_popup_repositioned
};

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_initIDs
        (JNIEnv *env, jclass clazz)
{
    CHECK_NULL_THROW_IE(env,
                        notifyConfiguredMID = (*env)->GetMethodID(env, clazz, "notifyConfigured", "(IIIIZZZ)V"),
                        "Failed to find method WLComponentPeer.notifyConfigured");
    CHECK_NULL_THROW_IE(env,
                        notifyPopupDoneMID = (*env)->GetMethodID(env, clazz, "notifyPopupDone", "()V"),
                        "Failed to find method WLComponentPeer.notifyPopupDone");
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
    struct WLFrame *frame = calloc(1, sizeof(struct WLFrame));
    frame->nativeFramePeer = (*env)->NewWeakGlobalRef(env, obj);
    return ptr_to_jlong(frame);
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
        wlFlushToServer(env);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestMaximized
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_set_maximized(frame->xdg_toplevel);
        wlFlushToServer(env);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestUnmaximized
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_unset_maximized(frame->xdg_toplevel);
        wlFlushToServer(env);
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
        wlFlushToServer(env);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRequestUnsetFullScreen
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_toplevel) {
        xdg_toplevel_unset_fullscreen(frame->xdg_toplevel);
        wlFlushToServer(env);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeCreateWindow
      (JNIEnv *env, jobject obj, jlong ptr, jlong parentPtr, jlong wlSurfacePtr,
       jboolean isModal, jboolean isMaximized, jboolean isMinimized,
       jstring title, jstring appid)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    struct WLFrame *parentFrame = jlong_to_ptr(parentPtr);
    struct wl_surface *wl_surface = jlong_to_ptr(wlSurfacePtr);

    frame->xdg_surface = xdg_wm_base_get_xdg_surface(xdg_wm_base, wl_surface);
    CHECK_NULL(frame->xdg_surface);
#ifdef HAVE_GTK_SHELL1
    if (gtk_shell1 != NULL) {
        frame->gtk_surface = gtk_shell1_get_gtk_surface(gtk_shell1, wl_surface);
        CHECK_NULL(frame->gtk_surface);
    }
#endif
    xdg_surface_add_listener(frame->xdg_surface, &xdg_surface_listener, frame);
    frame->toplevel = JNI_TRUE;
    frame->xdg_toplevel = xdg_surface_get_toplevel(frame->xdg_surface);
    CHECK_NULL(frame->xdg_toplevel);
    xdg_toplevel_add_listener(frame->xdg_toplevel, &xdg_toplevel_listener, frame);
    if (isMaximized) {
        xdg_toplevel_set_maximized(frame->xdg_toplevel);
    }
    if (isMinimized) {
        xdg_toplevel_set_minimized(frame->xdg_toplevel);
    }
    if (title) {
        FrameSetTitle(env, frame, title);
    }
    if (appid) {
        FrameSetAppID(env, frame, appid);
    }
    if (parentFrame && parentFrame->toplevel) {
        xdg_toplevel_set_parent(frame->xdg_toplevel, parentFrame->xdg_toplevel);
    }

#ifdef HAVE_GTK_SHELL1
    if (isModal && frame->gtk_surface != NULL) {
        gtk_surface1_set_modal(frame->gtk_surface);
    }
#endif
}

static struct xdg_positioner *
newPositioner
        (jint width, jint height, jint offsetX, jint offsetY)
{
    struct xdg_positioner *xdg_positioner = xdg_wm_base_create_positioner(xdg_wm_base);
    CHECK_NULL_RETURN(xdg_positioner, NULL);

    // "For an xdg_positioner object to be considered complete, it must have
    // a non-zero size set by set_size, and a non-zero anchor rectangle
    // set by set_anchor_rect."
    xdg_positioner_set_size(xdg_positioner, width, height);
    xdg_positioner_set_anchor_rect(xdg_positioner, offsetX, offsetY, 1, 1);
    xdg_positioner_set_offset(xdg_positioner, 0, 0);
    xdg_positioner_set_anchor(xdg_positioner, XDG_POSITIONER_ANCHOR_TOP_LEFT);
    xdg_positioner_set_gravity(xdg_positioner, XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT);
    xdg_positioner_set_constraint_adjustment(xdg_positioner,
        XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_Y
        | XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_X
        | XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_Y);
    return xdg_positioner;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeCreatePopup
        (JNIEnv *env, jobject obj, jlong ptr, jlong parentPtr, jlong wlSurfacePtr,
         jint width, jint height,
         jint offsetX, jint offsetY)
{
    struct WLFrame *frame = (struct WLFrame *) ptr;
    struct WLFrame *parentFrame = (struct WLFrame*) parentPtr;
    struct wl_surface* wl_surface = jlong_to_ptr(wlSurfacePtr);
    frame->xdg_surface = xdg_wm_base_get_xdg_surface(xdg_wm_base, wl_surface);
    CHECK_NULL(frame->xdg_surface);

    xdg_surface_add_listener(frame->xdg_surface, &xdg_surface_listener, frame);
    frame->toplevel = JNI_FALSE;

    assert(parentFrame);
    struct xdg_positioner *xdg_positioner = newPositioner(width, height, offsetX, offsetY);
    CHECK_NULL(xdg_positioner);
    JNU_RUNTIME_ASSERT(env, parentFrame->toplevel, "Popup's parent surface must be a toplevel");
    frame->xdg_popup = xdg_surface_get_popup(frame->xdg_surface, parentFrame->xdg_surface, xdg_positioner);
    CHECK_NULL(frame->xdg_popup);
    xdg_popup_add_listener(frame->xdg_popup, &xdg_popup_listener, frame);
    xdg_positioner_destroy(xdg_positioner);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeRepositionWLPopup
        (JNIEnv *env, jobject obj, jlong ptr,
         jint width, jint height,
         jint offsetX, jint offsetY)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    assert (!frame->toplevel);

    if (wl_proxy_get_version((struct wl_proxy *)xdg_wm_base) >= 3) {
        struct xdg_positioner *xdg_positioner = newPositioner(width, height, offsetX, offsetY);
        CHECK_NULL(xdg_positioner);
        static int token = 42; // This will be received by xdg_popup_repositioned(); unused for now.
        xdg_popup_reposition(frame->xdg_popup, xdg_positioner, token++);
        xdg_positioner_destroy(xdg_positioner);
        wlFlushToServer(env);
    }
}

static void
DoHide(JNIEnv *env, struct WLFrame *frame)
{
    if(frame->toplevel) {
        xdg_toplevel_destroy(frame->xdg_toplevel);
    } else {
        xdg_popup_destroy(frame->xdg_popup);
    }
#ifdef HAVE_GTK_SHELL1
    if (frame->gtk_surface != NULL) {
        gtk_surface1_destroy(frame->gtk_surface);
    }
#endif
    xdg_surface_destroy(frame->xdg_surface);

    frame->xdg_surface = NULL;
    frame->xdg_toplevel = NULL;
    frame->xdg_popup = NULL;
    frame->gtk_surface = NULL;
    frame->toplevel = JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeHideFrame
  (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    DoHide(env, frame);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLComponentPeer_nativeDisposeFrame
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    (*env)->DeleteWeakGlobalRef(env, frame->nativeFramePeer);
    free(frame);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeStartDrag
        (JNIEnv *env, jobject obj, jlong serial, jlong ptr)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel && wl_seat) {
        xdg_toplevel_move(frame->xdg_toplevel, wl_seat, serial);
        wlFlushToServer(env);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeStartResize
        (JNIEnv *env, jobject obj, jlong serial, jlong ptr, jint edges)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel && wl_seat && frame->xdg_toplevel != NULL) {
        xdg_toplevel_resize(frame->xdg_toplevel, wl_seat, serial, edges);
        wlFlushToServer(env);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetWindowGeometry
        (JNIEnv *env, jobject obj, jlong ptr, jint x, jint y, jint width, jint height)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->xdg_surface) {
        xdg_surface_set_window_geometry(frame->xdg_surface, x, y, width, height);
        // Do not flush here as this update needs to be committed together with the change
        // of the buffer's size and scale, if any.
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetMinimumSize
        (JNIEnv *env, jobject obj, jlong ptr, jint width, jint height)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel) {
        xdg_toplevel_set_min_size(frame->xdg_toplevel, width, height);
        // Do not flush here as this update needs to be committed together with the change
        // of the buffer's size and scale, if any.
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetMaximumSize
        (JNIEnv *env, jobject obj, jlong ptr, jint width, jint height)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel) {
        xdg_toplevel_set_max_size(frame->xdg_toplevel, width, height);
        // Do not flush here as this update needs to be committed together with the change
        // of the buffer's size and scale, if any.
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeShowWindowMenu
        (JNIEnv *env, jobject obj, jlong serial, jlong ptr, jint x, jint y)
{
    struct WLFrame *frame = jlong_to_ptr(ptr);
    if (frame->toplevel) {
        xdg_toplevel_show_window_menu(frame->xdg_toplevel, wl_seat, serial, x, y);
        wlFlushToServer(env);
    }
}

