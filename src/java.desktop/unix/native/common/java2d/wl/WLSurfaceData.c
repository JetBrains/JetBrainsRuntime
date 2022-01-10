/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
#include <wayland-client.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <jni.h>
#include "jni_util.h"
#include "WLSurfaceData.h"
extern struct wl_shm *wl_shm;

JNIEXPORT WLSDOps * JNICALL
WLSurfaceData_GetOps(JNIEnv *env, jobject sData)
{
#ifdef HEADLESS
    return NULL;
#else
    SurfaceDataOps *ops = SurfaceData_GetOps(env, sData);
    if (ops == NULL) {
        SurfaceData_ThrowInvalidPipeException(env, "not an valid WLSurfaceData");
    }
    return (WLSDOps *) ops;
#endif /* !HEADLESS */
}
#ifndef HEADLESS
static void
wl_buffer_release(void *data, struct wl_buffer *wl_buffer) {
    /* Sent by the compositor when it's no longer using this buffer */
    wl_buffer_destroy(wl_buffer);
}

static const struct wl_buffer_listener wl_buffer_listener = {
        .release = wl_buffer_release,
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

static struct wl_buffer *createBuffer(int rgb, int width, int height) {
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
    uint32_t *data = (uint32_t *)(mmap(NULL, size,
                                       PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0));
    if (data == MAP_FAILED) {
        close(fd);
        return NULL;
    }

    struct wl_shm_pool *pool = wl_shm_create_pool(wl_shm, fd, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, stride, WL_SHM_FORMAT_XRGB8888);
    wl_shm_pool_destroy(pool);
    close(fd);
    /* Draw checkerboxed background */

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            data[y * width + x] = rgb;
        }
    }
    munmap(data, size);
    wl_buffer_add_listener(buffer, &wl_buffer_listener, NULL);
    return buffer;
}
#endif

/*
 * Class:     sun_java2d_wl_WLSurfaceData
 * Method:    initSurface
 * Signature: (IIIJ)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_initSurface(JNIEnv *env, jobject wsd,
                                             jobject peer,
                                             jint rgb,
                                             jint width, jint height)
{
#ifndef HEADLESS
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }
    jboolean hasException;
    if (!wsdo->wl_surface) {
        wsdo->wl_surface = JNU_CallMethodByName(env, &hasException, peer, "getWLSurface", "()J").j;
    }
    wl_surface_attach((struct wl_surface*)wsdo->wl_surface, createBuffer(rgb, width, height), 0, 0);
    wl_surface_commit((struct wl_surface*)wsdo->wl_surface);

#endif /* !HEADLESS */
}


/*
 * Class:     sun_java2d_wl_WLSurfaceData
 * Method:    initOps
 * Signature: (Ljava/lang/Object;Ljava/lang/Object;I)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_initOps(JNIEnv *env, jobject wsd,
                                         jobject peer,
                                         jobject graphicsConfig, jint depth)
{
#ifndef HEADLESS

    WLSDOps *wsdo = (WLSDOps*)SurfaceData_InitOps(env, wsd, sizeof(WLSDOps));
    jboolean hasException;
    if (wsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    wsdo->wl_surface = 0;
    wsdo->bgPixel = 0;
    wsdo->isBgInitialized = JNI_FALSE;

#endif /* !HEADLESS */
}