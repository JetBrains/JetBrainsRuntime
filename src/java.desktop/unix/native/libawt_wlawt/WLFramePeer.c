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
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <jni.h>

#include "WLToolkit.h"

struct WLFrame {
    jobject nativeFramePeer; // weak reference
    struct wl_surface *wl_surface;
    struct xdg_surface *xdg_surface;
    struct xdg_toplevel *xdg_toplevel;
};

/* Shared memory support code (from  https://wayland-book.com/) */
static void randname(char *buf) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long r = ts.tv_nsec;
    for (int i = 0; i < 6; ++i) {
        buf[i] = 'A' + (r & 15) + (r & 16) * 2;
        r >>= 5;
    }
}

static int create_shm_file() {
    int retries = 100;
    do {
        char name[] = "/wl_shm-XXXXXX";
        randname(name + sizeof(name) - 7);
        --retries;
        int fd = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0600);
        if (fd >= 0) {
            shm_unlink(name);
            return fd;
        }
    } while (retries > 0 && errno == EEXIST);
    return -1;
}

static int allocate_shm_file(size_t size) {
    int fd = create_shm_file();
    if (fd < 0)
        return -1;
    int ret;
    do {
        ret = ftruncate(fd, size);
    } while (ret < 0 && errno == EINTR);
    if (ret < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void
wl_buffer_release(void *data, struct wl_buffer *wl_buffer) {
    /* Sent by the compositor when it's no longer using this buffer */
    wl_buffer_destroy(wl_buffer);
}

static const struct wl_buffer_listener wl_buffer_listener = {
        .release = wl_buffer_release,
};

static struct wl_buffer *createBuffer(int width, int height) {
    if (width <= 0) {
        width = 1;
    }
    if (height <= 0) {
        height = 1;
    }
    int stride = width * 4;
    int size = stride * height;

    int fd = allocate_shm_file(size);
    if (fd == -1) {
        return NULL;
    }

    struct wl_shm_pool *pool = wl_shm_create_pool(wl_shm, fd, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, stride, WL_SHM_FORMAT_XRGB8888);
    wl_shm_pool_destroy(pool);
    close(fd);

    wl_buffer_add_listener(buffer, &wl_buffer_listener, NULL);
    return buffer;
}

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
    wl_surface_attach(frame->wl_surface, createBuffer(width, height), 0, 0);
    wl_surface_commit(frame->wl_surface);
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
