/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 *
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

#include "splashscreen_impl.h"

#include <fcntl.h>
#include <wayland-client.h>
#include <wayland-cursor.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include "memory_utils.h"
#include "xdg-shell-client-protocol.h"

static const int BUFFERS_COUNT = 3;
static bool is_cursor_animated = false;

#define NULL_CHECK_CLEANUP(val, message)  if (val == NULL) { fprintf(stderr, "%s\n", message); goto cleanup; }
#define NULL_CHECK(val, message)  if (val == NULL) { fprintf(stderr, "%s\n", message); return false; }
#define DESTROY_NOT_NULL(val, destructor)  if (val != NULL) { destructor(val); }

bool SplashReconfigureNow(Splash * splash);
void SplashRedrawWindow(Splash * splash);
void SplashReconfigure(Splash * splash);

static bool
alloc_buffer(int width, int height, struct wl_shm *wl_shm, Buffer *buffer, int format, int format_size) {
    int size = width * height * format_size;
    int fd = AllocateSharedMemoryFile(size, "splashscreen");
    buffer->size = size;
    if (fd == -1) {
        return false;
    }

    buffer->data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (buffer->data == MAP_FAILED) {
        close(fd);
        return false;
    }

    struct wl_shm_pool *pool = wl_shm_create_pool(wl_shm, fd, size);
    buffer->wl_buffer = wl_shm_pool_create_buffer(pool, 0, width, height, width * format_size, format);
    if (buffer->wl_buffer == NULL) {
        return false;
    }
    wl_shm_pool_destroy(pool);
    close(fd);

    return true;
}

static void
destroy_buffer(Buffer *buffer) {
    if (buffer->data != MAP_FAILED && buffer->data != NULL) {
        munmap(buffer->data, buffer->size);
    }
    if (buffer->wl_buffer) {
        wl_buffer_destroy(buffer->wl_buffer);
    }
}

static void
registry_global(void *data, struct wl_registry *wl_registry, uint32_t name, const char *interface, uint32_t version) {
    wayland_state *state = data;

    if (strcmp(interface, wl_shm_interface.name) == 0) {
        state->wl_shm = wl_registry_bind(
                wl_registry, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, wl_compositor_interface.name) == 0) {
        state->wl_compositor = wl_registry_bind(
                wl_registry, name, &wl_compositor_interface, 4);
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        state->wl_seat = wl_registry_bind(
                wl_registry, name, &wl_seat_interface, 1);
    } else if (strcmp(interface, wl_subcompositor_interface.name) == 0) {
        state->wl_subcompositor = wl_registry_bind(
                wl_registry, name, &wl_subcompositor_interface, 1);
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        state->xdg_wm_base = wl_registry_bind(
                wl_registry, name, &xdg_wm_base_interface, 1);
    } else if (strcmp(interface, wl_output_interface.name) == 0) {
        state->wl_output = wl_registry_bind(
                wl_registry, name, &wl_output_interface, 2);
    }
}

static void
registry_global_remove(void *data, struct wl_registry *wl_registry, uint32_t name) {
}

static const struct wl_registry_listener wl_registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

static void
wl_buffer_release(void *data, struct wl_buffer *wl_buffer) {
    Buffer* buffer = data;
    buffer->available = true;
}

static const struct wl_buffer_listener wl_buffer_listener = {
    .release = wl_buffer_release,
};

static void
wl_output_geometry(void *data, struct wl_output * wl_output, int32_t x, int32_t y, int32_t physical_width,
        int32_t physical_height, int32_t subpixel, const char *make, const char *model, int32_t transform) {
}

static void
wl_output_scale(void *data, struct wl_output *wl_output, int32_t factor) {
    ScreenInfo *output = data;

    output->scale = factor;
}

static void
wl_output_mode(
        void *data,
        struct wl_output *wl_output,
        uint32_t flags,
        int32_t width,
        int32_t height,
        int32_t refresh) {
    ScreenInfo *output = data;

    output->width = width;
    output->height = height;
}

static void
wl_output_done(
        void *data,
        struct wl_output *wl_output) {
}

struct wl_output_listener wl_output_listener = {
    .geometry = &wl_output_geometry,
    .mode = &wl_output_mode,
    .done = &wl_output_done,
    .scale = &wl_output_scale
};

void
xdg_surface_configure(void *data, struct xdg_surface *xdg_surface, uint32_t serial) {
    struct Splash *splash = data;

    xdg_surface_ack_configure(xdg_surface, serial);
    SplashReconfigure(splash);
}

static const struct xdg_surface_listener xdg_surface_listener = {
    .configure = xdg_surface_configure,
};

static void
handle_toplevel_configure(void *data, struct xdg_toplevel *toplevel, int32_t width, int32_t height,
                          struct wl_array *states) {
    struct Splash *splash = data;

    if (width > 0 && height > 0 && (splash->window_width == 0 || splash->window_height == 0)) {
        splash->window_width = width;
        splash->window_height = height;
    }

    SplashReconfigure(splash);
}

static const struct xdg_toplevel_listener xdg_toplevel_listener = {
    .configure = handle_toplevel_configure,
};

static void
pointer_handle_enter(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface,
                     wl_fixed_t sx, wl_fixed_t sy) {
    wayland_state *state = data;

    is_cursor_animated = true;
    struct wl_cursor_image *image = state->default_cursor->images[0];
    wl_pointer_set_cursor(pointer, serial, state->cursor_surface, image->hotspot_x, image->hotspot_y);
}

static void
pointer_handle_leave(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface) {
    is_cursor_animated = false;
}

static void
pointer_handle_motion(void *data, struct wl_pointer *pointer, uint32_t time, wl_fixed_t sx, wl_fixed_t sy) {
}

static void
pointer_handle_button(void *data, struct wl_pointer *wl_pointer,
                      uint32_t serial, uint32_t time, uint32_t button, uint32_t state) {
}

static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_handle_enter,
    .leave = pointer_handle_leave,
    .motion = pointer_handle_motion,
    .button = pointer_handle_button,
};

static void
seat_handle_capabilities(void *data, struct wl_seat *seat, enum wl_seat_capability caps) {
    wayland_state *state = data;

    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !state->pointer) {
        state->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(state->pointer, &pointer_listener, state);
    } else if (!(caps & WL_SEAT_CAPABILITY_POINTER) && state->pointer) {
        wl_pointer_destroy(state->pointer);
        state->pointer = NULL;
    }
}

static const struct wl_seat_listener wl_seat_listener = {
    .capabilities = seat_handle_capabilities,
};

static void
xdg_wm_base_ping(void *data, struct xdg_wm_base *xdg_wm_base, uint32_t serial) {
    xdg_wm_base_pong(xdg_wm_base, serial);
}

static const struct xdg_wm_base_listener xdg_wm_base_listener = {
    .ping = xdg_wm_base_ping,
};

bool
SplashCreateWindow(Splash * splash) {
    splash->native_scale = getNativeScaleFactor(NULL, 1);
    if (splash->native_scale == -1.0) {
        splash->native_scale = 1.0;
    }

    wl_output_add_listener(splash->state->wl_output, &wl_output_listener, &splash->screenInfo);

    splash->state->wl_surface = wl_compositor_create_surface(splash->state->wl_compositor);
    NULL_CHECK(splash->state->wl_surface, "Cannot create surface\n")
    splash->state->wl_subsurfaces_surface = wl_compositor_create_surface(splash->state->wl_compositor);
    NULL_CHECK(splash->state->wl_subsurfaces_surface, "Cannot create surface\n")
    wl_surface_set_buffer_scale(splash->state->wl_subsurfaces_surface, (int) splash->scaleFactor);
    wl_surface_set_buffer_scale(splash->state->wl_surface, splash->native_scale);

    xdg_wm_base_add_listener(splash->state->xdg_wm_base, &xdg_wm_base_listener, splash->state);
    splash->state->xdg_surface = xdg_wm_base_get_xdg_surface(splash->state->xdg_wm_base, splash->state->wl_surface);
    NULL_CHECK(splash->state->xdg_surface, "Cannot get xdg_surface\n")
    xdg_surface_add_listener(splash->state->xdg_surface, &xdg_surface_listener, splash);

    splash->state->xdg_toplevel = xdg_surface_get_toplevel(splash->state->xdg_surface);
    NULL_CHECK(splash->state->xdg_toplevel, "Cannot get xdg_toplevel\n")
    xdg_toplevel_set_maximized(splash->state->xdg_toplevel);
    xdg_toplevel_add_listener(splash->state->xdg_toplevel, &xdg_toplevel_listener, splash);

    splash->state->cursor_surface = wl_compositor_create_surface(splash->state->wl_compositor);
    NULL_CHECK(splash->state->cursor_surface, "Cannot get cursor_surface\n")
    wl_seat_add_listener(splash->state->wl_seat, &wl_seat_listener, splash->state);

    splash->state->wl_subsurfaces_subsurface = wl_subcompositor_get_subsurface(
            splash->state->wl_subcompositor, splash->state->wl_subsurfaces_surface, splash->state->wl_surface);
    NULL_CHECK(splash->state->wl_subsurfaces_subsurface, "Cannot create subsurface\n")
    wl_subsurface_set_desync(splash->state->wl_subsurfaces_subsurface);

    splash->state->cursor_theme = wl_cursor_theme_load(NULL, 32, splash->state->wl_shm);
    NULL_CHECK(splash->state->cursor_theme, "unable to load default theme\n")
    splash->state->default_cursor = wl_cursor_theme_get_cursor(splash->state->cursor_theme, "watch");
    NULL_CHECK(splash->state->default_cursor, "unable to load pointer\n")

    return true;
}

int
SplashInitPlatform(Splash * splash) {
#if (defined DEBUG)
    _Xdebug = 1;
#endif

    pthread_mutex_init(&splash->lock, NULL);

    splash->buffers = 0;
    splash->window_width = 0;
    splash->window_height = 0;
    splash->state = malloc(sizeof(wayland_state));
    NULL_CHECK_CLEANUP(splash->state, "Cannot allocate enough memory\n")
    splash->buffers = malloc(sizeof(Buffer) * BUFFERS_COUNT);
    NULL_CHECK_CLEANUP(splash->buffers, "Cannot allocate enough memory\n")

    splash->state->wl_display = NULL;
    splash->state->wl_registry = NULL;

    splash->state->wl_shm = NULL;
    splash->state->wl_compositor = NULL;
    splash->state->wl_subcompositor = NULL;
    splash->state->wl_seat = NULL;
    splash->state->xdg_wm_base = NULL;
    splash->state->wl_subsurfaces_subsurface = NULL;

    splash->state->wl_surface = NULL;
    splash->state->wl_subsurfaces_surface = NULL;
    splash->state->xdg_surface = NULL;
    splash->state->xdg_toplevel = NULL;
    splash->state->pointer = NULL;
    splash->state->cursor_surface = NULL;

    splash->main_buffer.wl_buffer = NULL;
    splash->main_buffer.data = NULL;
    for (int i = 0; i < BUFFERS_COUNT; i++) {
        splash->buffers[i].wl_buffer = NULL;
        splash->buffers[i].data = NULL;
        splash->buffers[i].available = false;
    }

    splash->byteAlignment = 1;
    splash->maskRequired = 0;
    initFormat(&splash->screenFormat, 0xff0000, 0xff00, 0xff, 0xff000000);
    splash->screenFormat.byteOrder = BYTE_ORDER_LSBFIRST;
    splash->screenFormat.depthBytes = 4;

    splash->state->wl_display = wl_display_connect(NULL);
    NULL_CHECK_CLEANUP(splash->state->wl_display, "Cannot connect to display\n")

    splash->state->wl_registry = wl_display_get_registry(splash->state->wl_display);
    NULL_CHECK_CLEANUP(splash->state->wl_registry, "Cannot get display's registry\n")
    wl_registry_add_listener(splash->state->wl_registry, &wl_registry_listener, splash->state);
    wl_display_roundtrip(splash->state->wl_display);

    NULL_CHECK_CLEANUP(splash->state->wl_shm, "wl_shm not initialized\n")
    NULL_CHECK_CLEANUP(splash->state->wl_compositor, "wl_compositor not initialized\n")
    NULL_CHECK_CLEANUP(splash->state->wl_subcompositor, "wl_subcompositor not initialized\n")
    NULL_CHECK_CLEANUP(splash->state->wl_seat, "wl_seat not initialized\n")
    NULL_CHECK_CLEANUP(splash->state->xdg_wm_base, "xdg_wm_base not initialized\n")
    NULL_CHECK_CLEANUP(splash->state->wl_output, "wl_output not initialized\n")

    return true;
cleanup:
    SplashDonePlatform(splash);
    return false;
}

bool
SplashReconfigureNow(Splash * splash) {
    int splash_scale = (int) splash->scaleFactor;
    int offsetX = splash->screenInfo.width - splash->window_width * splash->screenInfo.scale;
    int offsetY = splash->screenInfo.height - splash->window_height * splash->screenInfo.scale;
    splash->x = (splash->screenInfo.width - splash->width / splash_scale) / 2;
    splash->y = (splash->screenInfo.height - splash->height / splash_scale) / 2;
    int localX = (splash->x - offsetX) / splash->screenInfo.scale;
    int localY = (splash->y - offsetY) / splash->screenInfo.scale;
    wl_subsurface_set_position(splash->state->wl_subsurfaces_subsurface, localX, localY);

    destroy_buffer(&splash->main_buffer);
    for (int i = 0; i < BUFFERS_COUNT; i++) {
        destroy_buffer(&splash->buffers[i]);
        splash->buffers[i].available = false;
    }

    struct wl_region *region = wl_compositor_create_region(splash->state->wl_compositor);
    wl_region_subtract(region, 0, 0, splash->window_width, splash->window_height);
    wl_region_add(region, localX, localY, splash->width / splash_scale, splash->height / splash_scale);
    wl_surface_set_input_region(splash->state->wl_surface, region);
    wl_surface_set_opaque_region(splash->state->wl_surface, region);
    wl_region_destroy(region);

    for (int i = 0; i < BUFFERS_COUNT; i++) {
        if (!alloc_buffer(splash->width, splash->height, splash->state->wl_shm, &splash->buffers[i],
                          WL_SHM_FORMAT_XRGB8888, 4)) {
            fprintf(stderr, "%s\n", "Cannot allocate enough memory");
            return false;
        }
        wl_buffer_add_listener(splash->buffers[i].wl_buffer, &wl_buffer_listener, &splash->buffers[i]);
        splash->buffers[i].available = true;
    }

    if (!alloc_buffer(splash->window_width * splash->native_scale, splash->window_height * splash->native_scale,
                      splash->state->wl_shm, &splash->main_buffer, WL_SHM_FORMAT_ARGB8888, 4)) {
        fprintf(stderr, "%s\n", "Cannot allocate enough memory");
        return false;
    }
    memset(splash->main_buffer.data, 0, splash->window_width * splash->window_height * 4);
    wl_surface_attach(splash->state->wl_surface, splash->main_buffer.wl_buffer, 0, 0);
    wl_surface_damage(splash->state->wl_surface, 0, 0, splash->window_width, splash->window_height);
    wl_surface_commit(splash->state->wl_surface);

    SplashRedrawWindow(splash);

    return true;
}

void
SplashRedrawWindow(Splash * splash) {
    for (int i = 0; i < BUFFERS_COUNT; i++) {
        if (splash->buffers[i].available) {
            splash->screenData = splash->buffers[i].data;
            SplashUpdateScreenData(splash, true);
            wl_surface_attach(splash->state->wl_subsurfaces_surface, splash->buffers[i].wl_buffer, 0, 0);
            wl_surface_damage(splash->state->wl_subsurfaces_surface, 0, 0, splash->window_width, splash->window_height);
            wl_surface_commit(splash->state->wl_subsurfaces_surface);
            splash->buffers[i].available = false;
            break;
        }
    }
}

bool
FlushEvents(Splash * splash) {
    return wl_display_flush(splash->state->wl_display) != -1;
}

bool
DispatchEvents(Splash * splash) {
    return wl_display_dispatch(splash->state->wl_display) != -1;
}

int
GetDisplayFD(Splash * splash) {
    return wl_display_get_fd(splash->state->wl_display);
}

void
SplashUpdateCursor(Splash * splash) {
    static int index = 0;

    if (is_cursor_animated) {
        wayland_state *state = splash->state;
        struct wl_buffer *buffer;
        struct wl_cursor *cursor = state->default_cursor;
        struct wl_cursor_image *image;

        if (cursor) {
            image = state->default_cursor->images[index];
            index = (index + 1) % (state->default_cursor->image_count);
            buffer = wl_cursor_image_get_buffer(image);
            if (!buffer)
                return;
            wl_surface_attach(state->cursor_surface, buffer, 0, 0);
            wl_surface_damage(state->cursor_surface, 0, 0, image->width, image->height);
            wl_surface_commit(state->cursor_surface);
        }
    }
}

void
SplashCleanupPlatform(Splash * splash) {
}

void
SplashDonePlatform(Splash * splash) {
    pthread_mutex_destroy(&splash->lock);

    if (splash == NULL) {
        return;
    }

    DESTROY_NOT_NULL(splash->state->wl_shm, wl_shm_destroy)
    DESTROY_NOT_NULL(splash->state->wl_compositor, wl_compositor_destroy)
    DESTROY_NOT_NULL(splash->state->wl_subcompositor, wl_subcompositor_destroy)
    DESTROY_NOT_NULL(splash->state->wl_seat, wl_seat_destroy)
    DESTROY_NOT_NULL(splash->state->xdg_wm_base, xdg_wm_base_destroy)
    DESTROY_NOT_NULL(splash->state->wl_subsurfaces_subsurface, wl_subsurface_destroy)

    DESTROY_NOT_NULL(splash->state->wl_surface, wl_surface_destroy)
    DESTROY_NOT_NULL(splash->state->wl_subsurfaces_surface, wl_surface_destroy)
    DESTROY_NOT_NULL(splash->state->xdg_surface, xdg_surface_destroy)
    DESTROY_NOT_NULL(splash->state->xdg_toplevel, xdg_toplevel_destroy)
    DESTROY_NOT_NULL(splash->state->pointer, wl_pointer_destroy)
    DESTROY_NOT_NULL(splash->state->cursor_surface, wl_surface_destroy)

    destroy_buffer(&splash->main_buffer);
    if (splash->buffers) {
        for (int i = 0; i < BUFFERS_COUNT; i++) {
            destroy_buffer(&splash->buffers[i]);
        }
        free(splash->buffers);
    }

    if (splash->state->wl_display) {
        wl_display_flush(splash->state->wl_display);
        wl_display_disconnect(splash->state->wl_display);
    }

    free(splash->state);
}

void
SplashSetup(Splash * splash) {
}

void
SplashUpdateShape(Splash * splash) {
}

void
SplashInitFrameShape(Splash * splash, int imageIndex) {
}