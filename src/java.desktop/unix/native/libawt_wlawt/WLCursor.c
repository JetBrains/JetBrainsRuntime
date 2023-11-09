/*
 * Copyright 2022 JetBrains s.r.o.
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

#include <stdbool.h>
#include <stdlib.h>
#include <jni.h>
#include <jni_util.h>

#include "WLToolkit.h"
#include "WLGraphicsEnvironment.h"

struct WLCursor {
    struct wl_buffer *buffer;
    bool managed;
    int32_t width;
    int32_t height;
    int32_t hotspot_x;
    int32_t hotspot_y;
};

JNIEXPORT void JNICALL
Java_java_awt_Cursor_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Cursor_finalizeImpl
  (JNIEnv *env, jclass clazz, jlong pData)
{
    if (pData != -1) {
        struct WLCursor *cursor = jlong_to_ptr(pData);
        if (cursor->managed)
            wl_buffer_destroy(cursor->buffer);
        free(cursor);
    }
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_WLComponentPeer_nativeGetPredefinedCursor
  (JNIEnv *env, jclass cls, jstring name)
{
    initCursors();

    if (!wl_cursor_theme)
        return 0;

    jboolean isCopy = JNI_FALSE;
    const char *name_c_str = JNU_GetStringPlatformChars(env, name, &isCopy);
    if (!name_c_str)
        return 0;
    struct wl_cursor *wl_cursor = wl_cursor_theme_get_cursor(wl_cursor_theme, name_c_str);
    if (isCopy) {
        JNU_ReleaseStringPlatformChars(env, name, name_c_str);
    }

    if (!wl_cursor || !wl_cursor->image_count)
        return 0;
    struct wl_cursor_image *wl_cursor_image = wl_cursor->images[0]; // animated cursors aren't currently supported

    struct WLCursor *cursor = (struct WLCursor*) malloc(sizeof(struct WLCursor));
    if (cursor) {
        cursor->buffer = wl_cursor_image_get_buffer(wl_cursor_image);
        cursor->managed = false;
        cursor->width = wl_cursor_image->width;
        cursor->height = wl_cursor_image->height;
        cursor->hotspot_x = wl_cursor_image->hotspot_x;
        cursor->hotspot_y = wl_cursor_image->hotspot_y;
    }
    return ptr_to_jlong(cursor);
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_WLCustomCursor_nativeCreateCustomCursor
  (JNIEnv *env, jclass cls, jintArray pixels, jint width, jint height, jint xHotSpot, jint yHotSpot)
{
    int pixelCount = (*env)->GetArrayLength(env, pixels);
    if (pixelCount == 0 || pixelCount >= 0x20000000 /* byte size won't fit into int32_t */)
        return 0;
    int32_t byteSize = pixelCount * 4;
    jint *sharedBuffer;
    struct wl_shm_pool *pool = CreateShmPool(byteSize, "customCursor", (void**)&sharedBuffer);
    if (!pool)
        return 0;

    (*env)->GetIntArrayRegion(env, pixels, 0, pixelCount, sharedBuffer);
#if defined(__BYTE_ORDER__) && defined(__ORDER_BIG_ENDIAN__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
    // Wayland requires little-endian data
    for (int i = 0; i < pixelCount; i++) {
        jint value = sharedBuffer[i];
        sharedBuffer[i] = (value & 0xFF) << 24 |
                          (value & 0xFF00) << 8 |
                          (value & 0xFF0000) >> 8 |
                          (value & 0xFF000000) >> 24 & 0xFF;
    }
#endif

    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, width * 4, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);

    struct WLCursor *cursor = (struct WLCursor*) malloc(sizeof(struct WLCursor));
    if (!cursor) {
        JNU_ThrowOutOfMemoryError(env, "Failed to allocate WLCursor");
        wl_buffer_destroy(buffer);
        return 0;
    }
    cursor->buffer = buffer;
    cursor->managed = true;
    cursor->width = width;
    cursor->height = height;
    cursor->hotspot_x = xHotSpot;
    cursor->hotspot_y = yHotSpot;
    return ptr_to_jlong(cursor);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLComponentPeer_nativeSetCursor
  (JNIEnv *env, jclass cls, jlong pData)
{
    struct wl_buffer *buffer = NULL;
    int32_t width = 0;
    int32_t height = 0;
    int32_t hotspot_x = 0;
    int32_t hotspot_y = 0;

    if (pData != -1) {
        struct WLCursor *cursor = jlong_to_ptr(pData);
        buffer = cursor->buffer;
        width = cursor->width;
        height = cursor->height;
        hotspot_x = cursor->hotspot_x;
        hotspot_y = cursor->hotspot_y;
    }

    static struct wl_surface *wl_cursor_surface = NULL;
    static struct wl_buffer *last_buffer = NULL;
    static uint32_t last_serial = 0;
    static int32_t last_hotspot_x = 0;
    static int32_t last_hotspot_y = 0;

    if (!wl_cursor_surface)
        wl_cursor_surface = wl_compositor_create_surface(wl_compositor);

    CHECK_NULL(wl_cursor_surface);
    if (buffer != last_buffer) {
        last_buffer = buffer;
        wl_surface_attach(wl_cursor_surface, buffer, 0, 0);
        wl_surface_set_buffer_scale(wl_cursor_surface, getCurrentScale());
        wl_surface_damage_buffer(wl_cursor_surface, 0, 0, width, height);
        wl_surface_commit(wl_cursor_surface);
    }

    if (last_pointer_enter_serial != last_serial || hotspot_x != last_hotspot_x || hotspot_y != last_hotspot_y) {
        last_serial = last_pointer_enter_serial;
        last_hotspot_x = hotspot_x;
        last_hotspot_y = hotspot_y;
        wl_pointer_set_cursor(wl_pointer, last_pointer_enter_serial, wl_cursor_surface,
                              hotspot_x / getCurrentScale(), hotspot_y / getCurrentScale());
    }
}
