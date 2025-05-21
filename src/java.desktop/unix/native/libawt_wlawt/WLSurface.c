/*
 * Copyright (c) 2022-2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022-2025, JetBrains s.r.o.. All rights reserved.
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
#include <jni.h>
#include <jni_util.h>
#include <assert.h>

#include <wayland-client-protocol.h>

#include "JNIUtilities.h"
#include "WLToolkit.h"
#include "WLRobotPeer.h"
#include "WLGraphicsEnvironment.h"

#ifdef WAKEFIELD_ROBOT
#include "wakefield.h"
#endif

#include "sun_awt_wl_WLSurface.h"

static jmethodID notifyEnteredOutputMID;
static jmethodID notifyLeftOutputMID;

struct WLSurfaceDescr {
    struct wl_surface* wlSurface;
    jobject javaSurface; // a global reference to WLSurface
};

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSurface_initIDs
        (JNIEnv *env, jclass clazz)
{
    CHECK_NULL_THROW_IE(env,
                        notifyEnteredOutputMID = (*env)->GetMethodID(env, clazz, "notifyEnteredOutput", "(I)V"),
                        "Failed to find method WLSurface.notifyEnteredOutput");
    CHECK_NULL_THROW_IE(env,
                        notifyLeftOutputMID = (*env)->GetMethodID(env, clazz, "notifyLeftOutput", "(I)V"),
                        "Failed to find method WLSurface.notifyLeftOutput");
}

static void
wl_surface_entered_output(void *data,
                          struct wl_surface *wl_surface,
                          struct wl_output *output)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(data);
    assert (sd);

    uint32_t wlOutputID = WLOutputID(output);
    if (wlOutputID == 0) return;

    JNIEnv *env = getEnv();
    const jobject nativeFramePeer = (*env)->NewLocalRef(env, sd->javaSurface);
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
    struct WLSurfaceDescr* sd = jlong_to_ptr(data);
    assert (sd);
    uint32_t wlOutputID = WLOutputID(output);
    if (wlOutputID == 0) return;

    JNIEnv *env = getEnv();
    const jobject nativeFramePeer = (*env)->NewLocalRef(env, sd->javaSurface);
    if (nativeFramePeer) {
        (*env)->CallVoidMethod(env, nativeFramePeer, notifyLeftOutputMID, wlOutputID);
        (*env)->DeleteLocalRef(env, nativeFramePeer);
        JNU_CHECK_EXCEPTION(env);
    }
}

static const struct wl_surface_listener wl_surface_listener = {
        .enter = wl_surface_entered_output,
        .leave = wl_surface_left_output
};

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLSurface_nativeCreateWlSurface
        (JNIEnv *env, jobject obj)
{
    jobject javaObjRef = (*env)->NewGlobalRef(env, obj);
    CHECK_NULL_THROW_OOME_RETURN(env, javaObjRef, "Couldn't create a global reference to WLSurface", 0);

    struct wl_surface* surface = wl_compositor_create_surface(wl_compositor);

    if (surface && javaObjRef) {
        struct WLSurfaceDescr* data = calloc(1, sizeof(struct WLSurfaceDescr));

        data->wlSurface = surface;
        data->javaSurface = javaObjRef;
        wl_surface_add_listener(surface, &wl_surface_listener, data);
        return ptr_to_jlong(data);
    }

    return 0;
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLSurface_wlSurfacePtr
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    return ptr_to_jlong(sd->wlSurface);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSurface_nativeDestroyWlSurface
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    wl_surface_destroy(sd->wlSurface);
    (*env)->DeleteGlobalRef(env, sd->javaSurface);
    free(sd);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSurface_nativeCommitWlSurface
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    wl_surface_commit(sd->wlSurface);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLSurface_nativeSetOpaqueRegion
        (JNIEnv *env, jobject obj, jlong ptr, jint x, jint y, jint width, jint height)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    struct wl_region* region = wl_compositor_create_region(wl_compositor);
    wl_region_add(region, x, y, width, height);
    wl_surface_set_opaque_region(sd->wlSurface, region);
    wl_region_destroy(region);
    // Do not flush here as this update needs to be committed together with the change
    // of the buffer's size and scale, if any.
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLSurface_nativeMoveSurface
        (JNIEnv *env, jobject obj, jlong ptr, jint x, jint y)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

#ifdef WAKEFIELD_ROBOT
        if (wakefield) {
            // TODO: this doesn't work quite as expected for some reason
            wakefield_move_surface(wakefield, sd->wlSurface, x, y);
        }
#endif
}

