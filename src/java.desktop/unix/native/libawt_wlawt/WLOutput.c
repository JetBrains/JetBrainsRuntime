/*
* Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

#include "WLOutput.h"

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "JNIUtilities.h"
#include "jvm_md.h"

#include "WLToolkit.h"

#include "sun_awt_wl_WLOutput.h"

static jclass wlOutputClass;
static jmethodID wlOutputConstructorMID;
static jmethodID handleGlobalRemoveMID;
static jmethodID handleConnectedMID;
static jmethodID handleGeometryMID;
static jmethodID handleModeMID;
static jmethodID handleDoneMID;
static jmethodID handleScaleMID;
static jmethodID handleNameMID;
static jmethodID handleDescriptionMID;
static jmethodID handleLogicalPositionMID;
static jmethodID handleLogicalSizeMID;
static jmethodID handleXdgDoneMID;
static jmethodID handleXdgNameMID;
static jmethodID handleXdgDescriptionMID;
static struct wl_registry *registry;

/*
 * Class:     sun_awt_wl_WLOutput
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_awt_wl_WLOutput_initIDs
  (JNIEnv *env, jclass clazz)
{
    CHECK_NULL_THROW_OOME(env, wlOutputClass = (*env)->NewGlobalRef(env, clazz), "Failed to create a reference to WLOutput class");
    CHECK_NULL_THROW_OOME(env, wlOutputConstructorMID = (*env)->GetMethodID(
        env, wlOutputClass, "<init>", "(JIII)V"), "Failed to create reference to WLOutput.<init>()");
    CHECK_NULL_THROW_OOME(env, handleGlobalRemoveMID = (*env)->GetStaticMethodID(
        env, wlOutputClass, "handleGlobalRemove", "(I)V"), "Failed to create reference to WLOutput.handleGlobalRemove()");
    CHECK_NULL_THROW_OOME(env, handleConnectedMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleConnected", "()V"), "Failed to create reference to WLOutput.handleConnected()");
    CHECK_NULL_THROW_OOME(env, handleGeometryMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleGeometry", "(IIIIILjava/lang/String;Ljava/lang/String;I)V"), "Failed to create reference to WLOutput.handleGeometry()");
    CHECK_NULL_THROW_OOME(env, handleModeMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleMode", "(IIII)V"), "Failed to create reference to WLOutput.handleMode()");
    CHECK_NULL_THROW_OOME(env, handleDoneMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleDone", "()V"), "Failed to create reference to WLOutput.handleDone()");
    CHECK_NULL_THROW_OOME(env, handleScaleMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleScale", "(I)V"), "Failed to create reference to WLOutput.handleScale()");
    CHECK_NULL_THROW_OOME(env, handleNameMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleName", "(Ljava/lang/String;)V"), "Failed to create reference to WLOutput.handleName()");
    CHECK_NULL_THROW_OOME(env, handleDescriptionMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleDescription", "(Ljava/lang/String;)V"), "Failed to create reference to WLOutput.handleDescription()");
    CHECK_NULL_THROW_OOME(env, handleLogicalPositionMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleLogicalPosition", "(II)V"), "Failed to create reference to WLOutput.handleLogicalPosition()");
    CHECK_NULL_THROW_OOME(env, handleLogicalSizeMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleLogicalSize", "(II)V"), "Failed to create reference to WLOutput.handleLogicalSize()");
    CHECK_NULL_THROW_OOME(env, handleXdgDoneMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleXdgDone", "()V"), "Failed to create reference to WLOutput.handleXdgDone()");
    CHECK_NULL_THROW_OOME(env, handleXdgNameMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleXdgName", "(Ljava/lang/String;)V"), "Failed to create reference to WLOutput.handleXdgName()");
    CHECK_NULL_THROW_OOME(env, handleXdgDescriptionMID = (*env)->GetMethodID(
        env, wlOutputClass, "handleXdgDescription", "(Ljava/lang/String;)V"), "Failed to create reference to WLOutput.handleXdgDescription()");
}

static void
wl_output_on_geometry(
    void *data, struct wl_output *wl_output, int32_t x, int32_t y,
    int32_t physical_width, int32_t physical_height,
    int32_t subpixel, const char *make, const char *model, int32_t transform)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    jstring makeString = (*env)->NewStringUTF(env, make);
    if (wlListenerCheckException(env)) return;
    jstring modelString = (*env)->NewStringUTF(env, model);
    if (wlListenerCheckException(env)) return;
    (*env)->CallVoidMethod(env, output->javaObject, handleGeometryMID,
        (jint)x, (jint)y, (jint)physical_width, (jint)physical_height, (jint)subpixel, makeString, modelString, (jint)transform);
    wlListenerCheckException(env);
}

static void
wl_output_on_mode(
    void *data, struct wl_output *wl_output, uint32_t flags,
    int32_t width, int32_t height, int32_t refresh)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    (*env)->CallVoidMethod(env, output->javaObject, handleModeMID,
        (jint)flags, (jint)width, (jint)height, (jint)refresh);
    wlListenerCheckException(env);
}

static void
wl_output_on_done(void *data, struct wl_output *wl_output)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    (*env)->CallVoidMethod(env, output->javaObject, handleDoneMID);
    wlListenerCheckException(env);
}

static void
wl_output_on_scale(void *data, struct wl_output *wl_output, int32_t factor)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    (*env)->CallVoidMethod(env, output->javaObject, handleScaleMID, (jint)factor);
    wlListenerCheckException(env);
}

static void
wl_output_on_name(void *data, struct wl_output *wl_output, const char *name)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    jstring nameString = (*env)->NewStringUTF(env, name);
    if (wlListenerCheckException(env)) return;
    (*env)->CallVoidMethod(env, output->javaObject, handleNameMID, nameString);
    wlListenerCheckException(env);
}

static void
wl_output_on_description(void *data, struct wl_output *wl_output, const char *description)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    jstring descriptionString = (*env)->NewStringUTF(env, description);
    if (wlListenerCheckException(env)) return;
    (*env)->CallVoidMethod(env, output->javaObject, handleDescriptionMID, descriptionString);
    wlListenerCheckException(env);
}

static const struct wl_output_listener wl_output_listener = {
    .geometry = wl_output_on_geometry,
    .mode = wl_output_on_mode,
    .done = wl_output_on_done,
    .scale = wl_output_on_scale,
    .name = wl_output_on_name,
    .description = wl_output_on_description,
};

static void
xdg_output_on_logical_position(
    void *data, struct zxdg_output_v1 *zxdg_output_v1, int32_t x, int32_t y)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    (*env)->CallVoidMethod(env, output->javaObject, handleLogicalPositionMID, (jint)x, (jint)y);
    wlListenerCheckException(env);
}

static void
xdg_output_on_logical_size(
    void *data, struct zxdg_output_v1 *zxdg_output_v1, int32_t width, int32_t height)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    (*env)->CallVoidMethod(env, output->javaObject, handleLogicalSizeMID, (jint)width, (jint)height);
    wlListenerCheckException(env);
}

static void
xdg_output_on_done(
    void *data, struct zxdg_output_v1 *zxdg_output_v1)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    (*env)->CallVoidMethod(env, output->javaObject, handleXdgDoneMID);
    wlListenerCheckException(env);
}

static void
xdg_output_on_name(void *data, struct zxdg_output_v1 *zxdg_output_v1, const char *name)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    jstring nameString = (*env)->NewStringUTF(env, name);
    if (wlListenerCheckException(env)) return;
    (*env)->CallVoidMethod(env, output->javaObject, handleXdgNameMID, nameString);
    wlListenerCheckException(env);
}

static void
xdg_output_on_description(void *data, struct zxdg_output_v1 *zxdg_output_v1, const char *description)
{
    JNIEnv *env = getEnv();
    struct WLOutput *output = data;
    jstring descriptionString = (*env)->NewStringUTF(env, description);
    if (wlListenerCheckException(env)) return;
    (*env)->CallVoidMethod(env, output->javaObject, handleXdgDescriptionMID, descriptionString);
    wlListenerCheckException(env);
}

static const struct zxdg_output_v1_listener xdg_output_listener = {
    .logical_position = xdg_output_on_logical_position,
    .logical_size = xdg_output_on_logical_size,
    .done = xdg_output_on_done,
    .name = xdg_output_on_name,
    .description = xdg_output_on_description,
};

static void
registry_on_global(void* data, struct wl_registry* wl_registry, uint32_t name, const char* interface, uint32_t version)
{
    if (strcmp(interface, "wl_output") != 0) {
        // ignore non-output globals
        return;
    }

    JNIEnv *env = getEnv();

    struct WLOutput *output = NULL;
    struct wl_output *wl_output = NULL;
    struct zxdg_output_v1 *xdg_output = NULL;
    jobject javaObject = NULL;

    output = calloc(1, sizeof *output);
    if (output == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Failed to allocate native WLOutput struct");
        goto error;
    }

    uint32_t wlOutputVersion = version >= 4 ? 4 : version;
    wl_output = wl_registry_bind(wl_registry, name, &wl_output_interface, wlOutputVersion);
    if (wl_output == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Failed to bind wl_output");
        goto error;
    }

    uint32_t xdgOutputVersion = 0;
    if (zxdg_output_manager_v1 != NULL) {
        xdgOutputVersion = zxdg_output_manager_v1_get_version(zxdg_output_manager_v1);
        xdg_output = zxdg_output_manager_v1_get_xdg_output(zxdg_output_manager_v1, wl_output);
        if (xdg_output == NULL) {
            JNU_ThrowOutOfMemoryError(env, "Failed to create zxdg_output_v1");
            goto error;
        }
    }

    jobject javaObjectLocal = (*env)->NewObject(env, wlOutputClass, wlOutputConstructorMID,
        ptr_to_jlong(output), (jint)name, (jint)wlOutputVersion, (jint)xdgOutputVersion);
    if ((*env)->ExceptionCheck(env)) {
        goto error;
    }
    javaObject = (*env)->NewGlobalRef(env, javaObjectLocal);
    if (javaObject == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Failed to create global reference to WLOutput");
        goto error;
    }

    output->javaObject = javaObject;
    output->registryId = name;
    output->wl_output = wl_output;
    output->xdg_output = xdg_output;

    wl_output_add_listener(wl_output, &wl_output_listener, output);
    if (xdg_output != NULL) {
        zxdg_output_v1_add_listener(xdg_output, &xdg_output_listener, output);
    }

    (*env)->CallVoidMethod(env, javaObject, handleConnectedMID);
    wlListenerCheckException(env);

    return;

error:
    if (javaObject != NULL) {
        (*env)->DeleteGlobalRef(env, javaObject);
    }
    if (xdg_output != NULL) {
        zxdg_output_v1_destroy(xdg_output);
    }
    if (wl_output != NULL) {
        if (wl_output_get_version(wl_output) >= 3) {
            wl_output_release(wl_output);
        } else {
            wl_output_destroy(wl_output);
        }
    }
    free(output);
    wlListenerCheckException(env);
}

static void
registry_on_global_remove(void *data, struct wl_registry *wl_registry, uint32_t name)
{
    JNIEnv *env = getEnv();
    (*env)->CallStaticVoidMethod(env, wlOutputClass, handleGlobalRemoveMID, (jint)name);
    wlListenerCheckException(env);
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_on_global,
    .global_remove = registry_on_global_remove,
};

/*
 * Class:     sun_awt_wl_WLOutput
 * Method:    nativeInitialize
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_awt_wl_WLOutput_nativeInitialize
  (JNIEnv *env, jclass clazz)
{
    registry = wl_display_get_registry(wl_display);
    CHECK_NULL_THROW_OOME(env, registry, "Failed to create registry");
    wl_registry_add_listener(registry, &registry_listener, NULL);
    if (wl_display_roundtrip(wl_display) < 0) {
        JNU_ThrowByName(env, "java/awt/AWTError", "wl_display_roundtrip() failed");
    }
}

/*
 * Class:     sun_awt_wl_WLOutput
 * Method:    nativeDestroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_awt_wl_WLOutput_nativeDestroy
  (JNIEnv *env, jclass clazz, jlong nativePtr)
{
    struct WLOutput *output = jlong_to_ptr(nativePtr);
    if (output == NULL) return;
    if (output->javaObject != NULL) {
        (*env)->DeleteGlobalRef(env, output->javaObject);
    }
    if (output->xdg_output != NULL) {
        zxdg_output_v1_destroy(output->xdg_output);
    }
    if (output->wl_output != NULL) {
        if (wl_output_get_version(output->wl_output) >= 3) {
            wl_output_release(output->wl_output);
        } else {
            wl_output_destroy(output->wl_output);
        }
    }
    free(output);
}

#endif // HEADLESS