/*
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

#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>

#include "JNIUtilities.h"
#include "WLToolkit.h"
#include "wayland-client-protocol.h"
#include "sun_awt_wl_WLDragSourceContextPeer.h"


extern struct wl_data_device *wl_data_device;

static void
wl_data_source_target(void *data,
               struct wl_data_source *wl_data_source,
               const char *mime_type)
{
    // TODO
}
static void
wl_data_source_handle_send(
        void *data,
        struct wl_data_source *source,
        const char *mime_type,
        int fd)
{

}

static void
wl_data_source_handle_cancelled(
        void *data,
        struct wl_data_source *source)
{

}
static const struct wl_data_source_listener data_source_listener = {
    .target = wl_data_source_target,
    .send = wl_data_source_handle_send,
    .cancelled = wl_data_source_handle_cancelled
};

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDragSourceContextPeer_startDragNative(
        JNIEnv *env,
        jobject obj,
        jlong eventSerial,
        jlong windowSurfacePtr,
        jobjectArray mimeTypes,
        jobject content)
{
    struct wl_data_source* data_source = wl_data_device_manager_create_data_source(wl_ddm);
    wl_data_source_add_listener(data_source, &data_source_listener, NULL);
    wl_data_source_offer(data_source, "text/plain");
    // TODO: announceMimeTypesForSource()
    wl_data_source_set_actions(data_source,
        WL_DATA_DEVICE_MANAGER_DND_ACTION_MOVE | WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);

    printf("data source OK, starting drag\n");

    struct wl_surface *icon = NULL; // TODO
    wl_data_device_start_drag(wl_data_device, data_source, jlong_to_ptr(windowSurfacePtr), icon, eventSerial);
}
