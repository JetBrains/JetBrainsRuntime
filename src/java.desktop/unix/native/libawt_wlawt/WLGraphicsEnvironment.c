/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

#include <string.h>
#include <stdlib.h>
#include <Trace.h>

#include "jni_util.h"
#include "WLToolkit.h"
#include "VKBase.h"

typedef struct WLOutput {
    struct WLOutput *  next;

    struct wl_output * wl_output;
    uint32_t id;

    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;

    uint32_t subpixel;
    uint32_t transform;
    uint32_t scale;

    char *   name;
} WLOutput;

static jclass geClass;
static jmethodID notifyOutputConfiguredMID;
static jmethodID notifyOutputDestroyedMID;
static jmethodID getSingleInstanceMID;

WLOutput * outputList;

static void
wl_output_geometry(
         void *data,
         struct wl_output * wl_output,
         int32_t x,
         int32_t y,
         int32_t physical_width,
         int32_t physical_height,
         int32_t subpixel,
         const char *make,
         const char *model,
         int32_t transform)
{
    WLOutput *output = data;

    // TODO: there's also a recommended, but unstable interface xdg_output;
    //  we may want to switch to that one day.
    output->x = x;
    output->y = y;
    output->subpixel = subpixel;
    output->transform = transform;
}

static void
wl_output_mode(
        void *data,
        struct wl_output *wl_output,
        uint32_t flags,
        int32_t width,
        int32_t height,
        int32_t refresh)
{
    if (flags & WL_OUTPUT_MODE_CURRENT) {
        WLOutput *output = data;
        output->width = width;
        output->height = height;
    }
}

static void
wl_output_name(
        void *data,
        struct wl_output *wl_output,
        const char *name)
{
    WLOutput * output = data;
    free(output->name);
    if (name) {
        output->name = strdup(name);
    }
}

static void
wl_output_description(
        void *data,
        struct wl_output *wl_output,
        const char *description)
{
    // intentionally empty
}

static void
wl_output_scale(
        void *data,
        struct wl_output *wl_output,
        int32_t factor)
{
    WLOutput * output = data;
    output->scale = factor;
}

static void
wl_output_done(
        void *data,
        struct wl_output *wl_output)
{
    WLOutput * output = data;

    JNIEnv *env = getEnv();
    jobject obj = (*env)->CallStaticObjectMethod(env, geClass, getSingleInstanceMID);
    CHECK_NULL_THROW_IE(env, obj, "WLGraphicsEnvironment.getSingleInstance() returned null");
    jstring outputName = output->name ? JNU_NewStringPlatform(env, output->name) : NULL;
    JNU_CHECK_EXCEPTION(env);
    (*env)->CallVoidMethod(env, obj, notifyOutputConfiguredMID,
                           outputName,
                           output->id,
                           output->x,
                           output->y,
                           output->width,
                           output->height,
                           (jint)output->subpixel,
                           (jint)output->transform,
                           (jint)output->scale);
    JNU_CHECK_EXCEPTION(env);
}

struct wl_output_listener wl_output_listener = {
        .geometry = &wl_output_geometry,
        .mode = &wl_output_mode,
#ifdef WL_OUTPUT_NAME_SINCE_VERSION
        .name = &wl_output_name,
#endif
#ifdef WL_OUTPUT_DESCRIPTION_SINCE_VERSION
        .description = &wl_output_description,
#endif
        .done = &wl_output_done,
        .scale = &wl_output_scale
};

jboolean
WLGraphicsEnvironment_initIDs
    (JNIEnv *env, jclass clazz)
{
    CHECK_NULL_RETURN(
                    geClass = (jclass)(*env)->NewGlobalRef(env, clazz),
                    JNI_FALSE);
    CHECK_NULL_RETURN(
                    getSingleInstanceMID = (*env)->GetStaticMethodID(env, clazz,
                                                                     "getSingleInstance",
                                                                     "()Lsun/awt/wl/WLGraphicsEnvironment;"),
                    JNI_FALSE);
    CHECK_NULL_RETURN(
                    notifyOutputConfiguredMID = (*env)->GetMethodID(env, clazz,
                                                                    "notifyOutputConfigured",
                                                                    "(Ljava/lang/String;IIIIIIII)V"),
                    JNI_FALSE);
    CHECK_NULL_RETURN(
                    notifyOutputDestroyedMID = (*env)->GetMethodID(env, clazz,
                                                                   "notifyOutputDestroyed", "(I)V"),
                    JNI_FALSE);

    return JNI_TRUE;
}

void
WLOutputRegister(struct wl_registry *wl_registry, uint32_t id)
{
    WLOutput * output = calloc(1, sizeof(WLOutput));

    output->id = id;
    output->wl_output = wl_registry_bind(wl_registry, id, &wl_output_interface, 2);
    wl_output_add_listener(output->wl_output, &wl_output_listener, output);

    output->next = outputList;
    outputList = output;
}

void
WLOutputDeregister(struct wl_registry *wl_registry, uint32_t id)
{
    WLOutput * cur = outputList;
    WLOutput * prev = NULL;

    while (cur) {
        if (cur->id == id) {
            if (prev) {
                prev->next = cur->next;
            } else {
                outputList = cur->next;
            }
            wl_output_destroy(cur->wl_output);
            WLOutput * next = cur->next;
            free(cur->name);
            free(cur);
            cur = next;
        } else {
            prev = cur;
            cur = cur->next;
        }
    }

    JNIEnv *env = getEnv();
    jobject obj = (*env)->CallStaticObjectMethod(env, geClass, getSingleInstanceMID);
    CHECK_NULL_THROW_IE(env, obj, "WLGraphicsEnvironment.getSingleInstance() returned null");
    (*env)->CallVoidMethod(env, obj, notifyOutputDestroyedMID, id);
    JNU_CHECK_EXCEPTION(env);
}

uint32_t
WLOutputID(struct wl_output *wlOutput)
{
    for(WLOutput * cur = outputList; cur; cur = cur->next) {
        if (cur->wl_output == wlOutput) {
            return cur->id;
        }
    }

    return 0;
}

struct wl_output*
WLOutputByID(uint32_t id)
{
    for(WLOutput * cur = outputList; cur; cur = cur->next) {
        if (cur->id == id) {
            return cur->wl_output;
        }
    }

    return NULL;
}

/*
 * Class:     sun_awt_wl_WLGraphicsEnvironment
 * Method:    initVKWL
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_awt_wl_WLGraphicsEnvironment_initVKWL(JNIEnv *env, jclass wlge, jboolean verbose, jint requestedDevice)
{
    jboolean vkwlAvailable = JNI_FALSE;
    /* TODO: The following sequence lead to uninitialized awt lock
       BufferedImage.getGraphics()
       BufferedImage.createGraphics()
       GraphicsEnvironment.getLocalGraphicsEnvironment()
       GraphicsEnvironment$LocalGE.createGE()
       PlatformGraphicsInfo.createGE()
       WLGraphicsEnvironment.initVKWL()
    */
    //AWT_LOCK();
    vkwlAvailable = VK_Init(verbose, requestedDevice);
    //AWT_UNLOCK();
    return vkwlAvailable;
}
#endif // #ifndef HEADLESS
