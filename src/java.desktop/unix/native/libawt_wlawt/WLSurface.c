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
#include "WLGraphicsEnvironment.h"

#include "sun_awt_wl_WLSurface.h"

static jmethodID notifyEnteredOutputMID;
static jmethodID notifyLeftOutputMID;

struct activation_token_list_item {
    struct xdg_activation_token_v1 *token;
    struct activation_token_list_item *next_item;
};

struct WLSurfaceDescr {
    struct wl_surface* wlSurface;
    struct wp_viewport* viewport;
    jobject javaSurface; // a global reference to WLSurface
    struct activation_token_list_item *activation_token_list;
};

static struct activation_token_list_item *add_token
        (JNIEnv* env, struct activation_token_list_item *list, struct xdg_activation_token_v1* token_to_add)
{
    struct activation_token_list_item *new_item =
        (struct activation_token_list_item *) calloc(1, sizeof(struct activation_token_list_item));
    CHECK_NULL_THROW_OOME_RETURN(env, new_item, "Failed to allocate a Wayland activation token", NULL);
    new_item->token = token_to_add;
    new_item->next_item = list;
    return new_item;
}

static struct activation_token_list_item *delete_last_token
        (struct activation_token_list_item *list)
{
    assert(list);
    xdg_activation_token_v1_destroy(list->token);
    struct activation_token_list_item *next_item = list->next_item;
    free(list);
    return next_item;
}

static struct activation_token_list_item *delete_token
        (struct activation_token_list_item *list, struct xdg_activation_token_v1 *token_to_delete)
{
    if (list == NULL) {
        return NULL;
    } else if (list->token == token_to_delete) {
        return delete_last_token(list);
    } else {
        list->next_item = delete_token(list->next_item, token_to_delete);
        return list;
    }
}

static void delete_all_tokens
        (struct activation_token_list_item *list)
{
    while (list) {
        list = delete_last_token(list);
    }
}

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
wl_surface_entered_output
        (void *data, struct wl_surface *wl_surface, struct wl_output *output)
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
wl_surface_left_output
    (void *data, struct wl_surface *wl_surface, struct wl_output *output)
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
    struct wp_viewport* viewport = wp_viewporter_get_viewport(wp_viewporter, surface);

    if (surface && viewport && javaObjRef) {
        struct WLSurfaceDescr* data = calloc(1, sizeof(struct WLSurfaceDescr));

        data->wlSurface = surface;
        data->viewport = viewport;
        data->javaSurface = javaObjRef;
        wl_surface_add_listener(surface, &wl_surface_listener, data);
        return ptr_to_jlong(data);
    } else {
        if (surface) wl_surface_destroy(surface);
        if (viewport) wp_viewport_destroy(viewport);
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
    wp_viewport_destroy(sd->viewport);
    delete_all_tokens(sd->activation_token_list);
    sd->activation_token_list = NULL;
    (*env)->DeleteGlobalRef(env, sd->javaSurface);
    free(sd);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSurface_nativeHideWlSurface
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    wl_surface_attach(sd->wlSurface, NULL, 0, 0);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSurface_nativeCommitWlSurface
        (JNIEnv *env, jobject obj, jlong ptr)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    wl_surface_commit(sd->wlSurface);
}

/**
 * Specifies the size of the Wayland's surface in surface units.
 * For the resulting image on the screen to look sharp this size should be
 * multiple of backing buffer's size with the ratio matching the display scale.
 */
JNIEXPORT void JNICALL Java_sun_awt_wl_WLSurface_nativeSetSize
        (JNIEnv *env, jobject obj, jlong ptr, jint width, jint height)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    wp_viewport_set_destination(sd->viewport, width, height);
    // Neigher flush nor commit here as this update needs to be committed together with the change
    // of the buffer's size and scale, if any.
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

static void
xdg_activation_token_v1_done
        (void *data, struct xdg_activation_token_v1 *xdg_activation_token_v1, const char *token)
{
    struct WLSurfaceDescr* sd = data;
    assert (sd);
	struct wl_surface *surface = sd->wlSurface;
    xdg_activation_v1_activate(xdg_activation_v1, token, surface);
    sd->activation_token_list = delete_token(sd->activation_token_list, xdg_activation_token_v1);

    JNIEnv* env = getEnv();
    wlFlushToServer(env);
}

static const struct xdg_activation_token_v1_listener xdg_activation_token_v1_listener = {
        .done = xdg_activation_token_v1_done,
};

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLMainSurface_nativeActivate
        (JNIEnv *env, jobject obj, jlong ptr, jlong serial, jlong activatingSurfacePtr)
{
    struct WLSurfaceDescr* sd = jlong_to_ptr(ptr);
    assert (sd);

    if (xdg_activation_v1 && wl_seat) {
        struct xdg_activation_token_v1 *token = xdg_activation_v1_get_activation_token(xdg_activation_v1);
        CHECK_NULL(token);
        xdg_activation_token_v1_add_listener(token, &xdg_activation_token_v1_listener, sd);
        xdg_activation_token_v1_set_serial(token, serial, wl_seat);
        if (activatingSurfacePtr) {
            struct wl_surface* surface = jlong_to_ptr(activatingSurfacePtr);
            xdg_activation_token_v1_set_surface(token, surface);
        }
        xdg_activation_token_v1_commit(token);
        sd->activation_token_list = add_token(env, sd->activation_token_list, token);
        wlFlushToServer(env);
    }
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLSubSurface_nativeCreateWlSubSurface
        (JNIEnv *env, jobject obj, jlong surfacePtr, jlong parentSurfacePtr)
{
    struct wl_surface* parentSurface = jlong_to_ptr(parentSurfacePtr);
    struct wl_surface* surface = jlong_to_ptr(surfacePtr);
    struct wl_subsurface* subSurface = wl_subcompositor_get_subsurface(wl_subcompositor, surface, parentSurface);
    wl_subsurface_place_below(subSurface, parentSurface);

    // Do not accept any input, we must be able to click through any sub-surface
	struct wl_region* input_region = wl_compositor_create_region(wl_compositor);
    wl_surface_set_input_region(surface, input_region);
    wl_region_destroy(input_region);

    return ptr_to_jlong(subSurface);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSubSurface_nativeDestroyWlSubSurface
        (JNIEnv *env, jobject obj, jlong subSurfacePtr)
{
    struct wl_subsurface* subSurface = jlong_to_ptr(subSurfacePtr);
    wl_subsurface_destroy(subSurface);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLSubSurface_nativeSetPosition
        (JNIEnv *env, jobject obj, jlong subSurfacePtr, jint x, jint y)
{

    struct wl_subsurface* subSurface = jlong_to_ptr(subSurfacePtr);

    wl_subsurface_set_position(subSurface, x, y);
}

