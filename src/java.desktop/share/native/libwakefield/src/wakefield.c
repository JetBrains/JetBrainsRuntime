/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#include <weston/weston.h>
#include <libweston/weston-log.h>

#include <pixman.h>
#include <assert.h>
#include <string.h>

#include "wakefield-server-protocol.h"

#ifndef container_of
#define container_of(ptr, type, member) ({                              \
        const __typeof__( ((type *)0)->member ) *__mptr = (ptr);        \
        (type *)( (char *)__mptr - offsetof(type,member) );})
#endif

struct wakefield {
    struct weston_compositor *compositor;
    struct wl_listener destroy_listener;

    struct weston_log_scope *log;
};

#define DEFAULT_AXIS_STEP_DISTANCE 10

// These functions are part of Weston's private backend API (libweston/backend.h)
void
notify_axis(struct weston_seat *seat, const struct timespec *time,
            struct weston_pointer_axis_event *event);

void
notify_axis_source(struct weston_seat *seat, uint32_t source);

void
notify_button(struct weston_seat *seat, const struct timespec *time,
              int32_t button, enum wl_pointer_button_state state);

void
notify_key(struct weston_seat *seat, const struct timespec *time, uint32_t key,
           enum wl_keyboard_key_state state,
           enum weston_key_state_update update_state);

void
notify_motion(struct weston_seat *seat, const struct timespec *time,
              struct weston_pointer_motion_event *event);

void
notify_motion_absolute(struct weston_seat *seat, const struct timespec *time,
                       double x, double y);

void
notify_pointer_frame(struct weston_seat *seat);


static struct weston_output*
get_output_for_point(struct wakefield* wakefield, int32_t x, int32_t y)
{
    struct weston_output *o;
    wl_list_for_each(o, &wakefield->compositor->output_list, link) {
        if (o->destroying)
            continue;

        if (pixman_region32_contains_point(&o->region, x, y, NULL)) {
            return o;
        }
    }

    return NULL;
}

static void
wakefield_get_pixel_color(struct wl_client *client,
                          struct wl_resource *resource,
                          int32_t x,
                          int32_t y)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_compositor *compositor = wakefield->compositor;

    weston_log_scope_printf(wakefield->log, "WAKEFIELD: get_pixel_color at (%d, %d)\n", x, y);

    const unsigned int byte_per_pixel = (PIXMAN_FORMAT_BPP(compositor->read_format) / 8);
    uint32_t pixel = 0;
    if (byte_per_pixel > sizeof(pixel)) {
        weston_log_scope_printf(wakefield->log,
                                "WAKEFIELD: compositor pixel format (%d) exceeds allocated storage (%d > %ld)\n",
                                compositor->read_format,
                                byte_per_pixel,
                                sizeof(pixel));
        wakefield_send_pixel_color(resource, x, y, 0, WAKEFIELD_ERROR_FORMAT);
        return;
    }

    struct weston_output *output = get_output_for_point(wakefield, x, y);
    if (output == NULL) {
        weston_log_scope_printf(wakefield->log,
                                "WAKEFIELD: pixel location (%d, %d) doesn't map to any output\n", x, y);
        wakefield_send_pixel_color(resource, x, y, 0, WAKEFIELD_ERROR_INVALID_COORDINATES);
        return;
    }
    
    const int output_x = x - output->x;
    const int output_y = y - output->y;
    weston_log_scope_printf(wakefield->log,
                            "WAKEFIELD: reading pixel color at (%d, %d) of '%s'\n",
                            output_x, output_y, output->name);
    compositor->renderer->read_pixels(output,
                                      compositor->read_format, &pixel,
                                      output_x, output_y, 1, 1);

    uint32_t rgb = 0;
    switch (compositor->read_format) {
        case PIXMAN_a8r8g8b8:
        case PIXMAN_x8r8g8b8:
        case PIXMAN_r8g8b8:
            rgb = pixel & 0x00ffffffu;
            break;

        default:
            weston_log_scope_printf(wakefield->log,
                                    "WAKEFIELD: compositor pixel format %d (see pixman.h) not supported\n",
                                    compositor->read_format);
            wakefield_send_pixel_color(resource, x, y, 0, WAKEFIELD_ERROR_FORMAT);
            return;
    }
    weston_log_scope_printf(wakefield->log, "WAKEFIELD: color is 0x%08x\n", rgb);

    wakefield_send_pixel_color(resource, x, y, rgb, WAKEFIELD_ERROR_NO_ERROR);
}

static void
wakefield_get_surface_location(struct wl_client *client,
                               struct wl_resource *resource,
                               struct wl_resource *surface_resource)
{
    // See also weston-test.c`move_surface() and the corresponding protocol

    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_surface *surface = wl_resource_get_user_data(surface_resource);
    struct weston_view *view = container_of(surface->views.next, struct weston_view, surface_link);

    if (!view) {
        weston_log_scope_printf(wakefield->log, "WAKEFIELD: get_location error\n");
        wakefield_send_surface_location(resource, surface_resource, 0, 0,
                                        WAKEFIELD_ERROR_INTERNAL);
        return;
    }

    float fx;
    float fy;
    weston_view_to_global_float(view, 0, 0, &fx, &fy);
    const int32_t x = (int32_t)fx;
    const int32_t y = (int32_t)fy;
    weston_log_scope_printf(wakefield->log, "WAKEFIELD: get_location: %d, %d\n", x, y);

    wakefield_send_surface_location(resource, surface_resource, x, y,
                                    WAKEFIELD_ERROR_NO_ERROR);
}

static void
wakefield_move_surface(struct wl_client *client,
                       struct wl_resource *resource,
                       struct wl_resource *surface_resource,
                       int32_t x,
                       int32_t y)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_surface *surface = wl_resource_get_user_data(surface_resource);
    struct weston_view *view = container_of(surface->views.next, struct weston_view, surface_link);

    if (!view) {
        weston_log_scope_printf(wakefield->log, "WAKEFIELD: move_surface error\n");
        return;
    }

    weston_view_set_position(view, (float)x, (float)y);
    weston_view_update_transform(view);

    weston_log_scope_printf(wakefield->log, "WAKEFIELD: move_surface to (%d, %d)\n", x, y);
}

static pixman_format_code_t
wl_shm_format_to_pixman(uint32_t wl_shm_format)
{
    pixman_format_code_t rc;

    switch (wl_shm_format) {
        case WL_SHM_FORMAT_ARGB8888:
            rc = PIXMAN_a8r8g8b8;
            break;
        case WL_SHM_FORMAT_XRGB8888:
            rc = PIXMAN_x8r8g8b8;
            break;
        default:
            assert(false);
    }

    return rc;
}

/**
 * Returns the number of pixels in the given non-empty region.
 */
static uint64_t
size_in_pixels(pixman_region32_t *region)
{
    const pixman_box32_t * const e = pixman_region32_extents(region);
    assert (e->x2 >= e->x1);
    assert (e->y2 >= e->y1);

    return ((uint64_t)(e->x2 - e->x1))*(e->y2 - e->y1);
}

/**
 * Get the number of pixels of the largest portion that the given region occupies on
 * any of the compositor's outputs.
 *
 * @param fits_entirely (OUT) set to true iff the entire region fits as a whole on just one output
 * @return largest area size in pixels (could be zero).
 */
static uint64_t
get_largest_area_in_one_output(struct weston_compositor *compositor, pixman_region32_t *region, bool *fits_entirely)
{
    uint64_t area = 0; // in pixels
    *fits_entirely = false;

    pixman_region32_t region_in_output;
    pixman_region32_init(&region_in_output);

    struct weston_output *output;
    wl_list_for_each(output, &compositor->output_list, link) {
        if (output->destroying)
            continue;

        pixman_region32_intersect(&region_in_output, region, &output->region);
        if (pixman_region32_not_empty(&region_in_output)) {
            const uint64_t this_area = size_in_pixels(&region_in_output);
            if (this_area > area) {
                area = this_area;
            }
            if (pixman_region32_equal(&region_in_output, region)) {
                *fits_entirely = true;
                break;
            }
        }
    }

    pixman_region32_fini(&region_in_output);

    return area;
}

/**
 * Sets every pixel in the given buffer to 0.
 */
static void
clear_buffer(struct wl_shm_buffer *buffer)
{
    const int32_t  height           = wl_shm_buffer_get_height(buffer);
    const size_t   stride           = wl_shm_buffer_get_stride(buffer);
    const size_t   buffer_byte_size = height*stride;

    wl_shm_buffer_begin_access(buffer);
    {
        uint32_t *data = wl_shm_buffer_get_data(buffer);
        memset(data, 0, buffer_byte_size);
    }
    wl_shm_buffer_end_access(buffer);
}

/**
 * Copies 4-byte pixels from the given array to the given buffer
 * at the given offset into the buffer.
 *
 * @param buffer   target buffer (format is assumed to be 4 byte per pixel)
 * @param data     array of 4-byte pixels of size (width*height)
 * @param target_x horizontal coordinate of the top-left corner in the buffer
 *                 where the given data should be placed
 * @param target_y vertical coordinate of the top-left corner in the buffer
 *                 where the given data should be placed
 * @param width    the source image width in pixels
 * @param height   the source image height in pixels
 */
static void
copy_pixels_to_shm_buffer(struct wl_shm_buffer *buffer, uint32_t *data,
                          int32_t target_x, int32_t target_y,
                          int32_t width, int32_t height)
{
    assert (target_x >= 0 && target_y >= 0);
    assert (data);

    const int32_t buffer_width = wl_shm_buffer_get_width(buffer);

    wl_shm_buffer_begin_access(buffer);
    {
        uint32_t * const buffer_data = wl_shm_buffer_get_data(buffer);
        assert (buffer_data);

        for (int32_t y = 0; y < height; y++) {
            const uint32_t * const src_line = &data[y*width];
            uint32_t * const       dst_line = &buffer_data[(target_y + y)*buffer_width];
            for (int32_t x = 0; x < width; x++) {
                dst_line[target_x + x] = src_line[x];
            }
        }
    }
    wl_shm_buffer_end_access(buffer);
}

/**
 * Verifies that the given buffer format is supported and sends the "capture ready" event
 * with the appropriate error code if it wasn't.
 */
static bool
check_buffer_format_supported(struct wakefield *wakefield, struct wl_resource *resource,
                              struct wl_resource *buffer_resource, uint32_t buffer_format)
{
    if (buffer_format != WL_SHM_FORMAT_ARGB8888
        && buffer_format != WL_SHM_FORMAT_XRGB8888) {
        weston_log_scope_printf(wakefield->log,
                                "WAKEFIELD: buffer for image capture has unsupported format %d, "
                                "check codes in enum 'format' in wayland.xml\n",
                                buffer_format);
        wakefield_send_capture_ready(resource, buffer_resource, WAKEFIELD_ERROR_FORMAT);
        return false;
    }

    return true;
}

/**
 * Verifies that the given buffer type is shm and sends the "capture ready" event
 * with the appropriate error code if it wasn't.
 */
static bool
check_buffer_type_supported(struct wakefield *wakefield, struct wl_resource *resource,
                            struct wl_resource *buffer_resource)
{
    struct wl_shm_buffer *buffer = wl_shm_buffer_get(buffer_resource);

    if (!buffer) {
        weston_log_scope_printf(wakefield->log, "WAKEFIELD: buffer for image capture not from wl_shm\n");
        wakefield_send_capture_ready(resource, buffer_resource, WAKEFIELD_ERROR_INTERNAL);
        return false;
    }

    return true;
}

/**
 * Verifies that the given capture area is not empty and sends the successful "capture ready" event
 * if it was.
 */
static bool
capture_is_empty(struct wakefield *wakefield, struct wl_resource *resource,
                    struct wl_resource *buffer_resource, uint64_t largest_capture_area)
{
    if (largest_capture_area == 0) {
        // All outputs might've just disappeared
        weston_log_scope_printf(wakefield->log,
                                "WAKEFIELD: captured area size on all outputs is zero.\n");
        wakefield_send_capture_ready(resource, buffer_resource, WAKEFIELD_ERROR_NO_ERROR);
        return true;
    }

    return false;
}

static void
wakefield_capture_create(struct wl_client *client,
                         struct wl_resource *resource,
                         struct wl_resource *buffer_resource,
                         int32_t x,
                         int32_t y)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);

    if (!check_buffer_type_supported(wakefield, resource, buffer_resource)) {
        return;
    }

    struct wl_shm_buffer *buffer = wl_shm_buffer_get(buffer_resource);
    assert (buffer); // actually, verified earlier
    const uint32_t buffer_format = wl_shm_buffer_get_format(buffer);
    if (!check_buffer_format_supported(wakefield, resource, buffer_resource, buffer_format)) {
        return;
    }

    clear_buffer(buffer); // in case some outputs disappear mid-flight or a part of the capture is out of screen

    const int32_t width  = wl_shm_buffer_get_width(buffer);
    const int32_t height = wl_shm_buffer_get_height(buffer);

    pixman_region32_t region_global;    // capture region in global coordinates
    pixman_region32_t region_in_output; // capture region in a particular output in that output's coordinates

    pixman_region32_init_rect(&region_global, x, y, width, height);
    pixman_region32_init(&region_in_output);

    bool fits_entirely;
    const uint64_t largest_capture_area = get_largest_area_in_one_output(wakefield->compositor, &region_global, &fits_entirely);
    if (capture_is_empty(wakefield, resource, buffer_resource, largest_capture_area)) {
        return;
    }

    const size_t bpp = 4; // byte-per-pixel
    void *per_output_buffer = NULL;
    if (!fits_entirely) {
        // Can't read screen pixels directly into the resulting buffer, have to use an intermediate storage.
        per_output_buffer = malloc(largest_capture_area * bpp);
        if (per_output_buffer == NULL) {
            weston_log_scope_printf(wakefield->log,
                                    "WAKEFIELD: failed to allocate %ld bytes for temporary capture buffer.\n",
                                    largest_capture_area);
            wakefield_send_capture_ready(resource, buffer_resource, WAKEFIELD_ERROR_OUT_OF_MEMORY);
            return;
        }
    }

    const pixman_format_code_t buffer_format_pixman = wl_shm_format_to_pixman(buffer_format);
    struct weston_output *output;
    wl_list_for_each(output, &wakefield->compositor->output_list, link) {
        if (output->destroying)
            continue;

        pixman_region32_intersect(&region_in_output, &region_global, &output->region);
        if (pixman_region32_not_empty(&region_in_output)) {
            const pixman_box32_t * const e = pixman_region32_extents(&region_in_output);

            const int32_t region_x_in_global = e->x1;
            const int32_t region_y_in_global = e->y1;
            const int32_t width_in_output    = e->x2 - e->x1;
            const int32_t height_in_output   = e->y2 - e->y1;
            weston_log_scope_printf(wakefield->log, "WAKEFIELD: output '%s' has a chunk of the image at (%d, %d) sized (%d, %d)\n",
                                    output->name,
                                    e->x1, e->y1,
                                    width_in_output, height_in_output);

            // Better, but not available in the current libweston:
            // weston_output_region_from_global(output, &region_in_output);

            // Now convert region_in_output from global to output-local coordinates.
            pixman_region32_translate(&region_in_output, -output->x, -output->y);

            const pixman_box32_t * const e_in_output = pixman_region32_extents(&region_in_output);
            const int32_t x_in_output = e_in_output->x1;
            const int32_t y_in_output = e_in_output->y1;

            weston_log_scope_printf(wakefield->log,
                                    "WAKEFIELD: grabbing pixels at (%d, %d) of size %dx%d, format %s\n",
                                    x_in_output, y_in_output,
                                    width_in_output, height_in_output,
                                    buffer_format_pixman == PIXMAN_a8r8g8b8 ? "ARGB8888" : "XRGB8888");

            if (per_output_buffer) {
                wakefield->compositor->renderer->read_pixels(output,
                                                             buffer_format_pixman, // TODO: may not work with all renderers, check screenshooter_frame_notify() in libweston
                                                             per_output_buffer,
                                                             x_in_output, y_in_output,
                                                             width_in_output, height_in_output);

                copy_pixels_to_shm_buffer(buffer, per_output_buffer,
                                          region_x_in_global - x, region_y_in_global - y,
                                          width_in_output, height_in_output);
            } else {
                wl_shm_buffer_begin_access(buffer);
                {
                    void *data = wl_shm_buffer_get_data(buffer);
                    wakefield->compositor->renderer->read_pixels(output,
                                                                 buffer_format_pixman,
                                                                 data,
                                                                 x_in_output, y_in_output,
                                                                 width, height);
                }
                wl_shm_buffer_end_access(buffer);
                // This is the case of the entire region located on just one output,
                // and we have just processed it, so can exit immediately.
                break;
            }
        }

        pixman_region32_fini(&region_in_output);
        pixman_region32_fini(&region_global);
    }

    if (per_output_buffer) {
        free(per_output_buffer);
    }

    wakefield_send_capture_ready(resource, buffer_resource, WAKEFIELD_ERROR_NO_ERROR);
}

static void
wakefield_send_key(struct wl_client *client,
                   struct wl_resource *resource,
                   uint32_t key,
                   uint32_t state)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_compositor *compositor = wakefield->compositor;

    struct timespec time;
    weston_compositor_get_time(&time);

    struct weston_seat *seat;
    wl_list_for_each(seat, &compositor->seat_list, link) {
        notify_key(seat, &time, key,
                   state ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED,
                   STATE_UPDATE_AUTOMATIC);

    }
}

static void wakefield_send_cursor(struct wl_client* client,
                                  struct wl_resource* resource,
                                  int32_t x, int32_t y)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_compositor *compositor = wakefield->compositor;

    struct timespec time;
    weston_compositor_get_time(&time);

    struct weston_seat *seat;
    wl_list_for_each(seat, &compositor->seat_list, link) {
        notify_motion_absolute(seat, &time, (double)x, (double)y);
        notify_pointer_frame(seat);
    }
}

static void wakefield_send_button(struct wl_client* client,
                                  struct wl_resource* resource,
                                  uint32_t button,
                                  uint32_t state)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_compositor *compositor = wakefield->compositor;

    struct timespec time;
    weston_compositor_get_time(&time);

    struct weston_seat *seat;
    wl_list_for_each(seat, &compositor->seat_list, link) {
        notify_button(seat, &time, (int32_t)button,
                      state ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
        notify_pointer_frame(seat);
    }
}

static void wakefield_send_wheel(struct wl_client* client,
                                 struct wl_resource* resource,
                                 int32_t amount)
{
    struct wakefield *wakefield = wl_resource_get_user_data(resource);
    struct weston_compositor *compositor = wakefield->compositor;

    struct timespec time;
    weston_compositor_get_time(&time);

    struct weston_pointer_axis_event event = {
            .axis = WL_POINTER_AXIS_VERTICAL_SCROLL,
            .value = DEFAULT_AXIS_STEP_DISTANCE * amount,
            .has_discrete = true,
            .discrete = amount
    };

    struct weston_seat *seat;
    wl_list_for_each(seat, &compositor->seat_list, link) {
        notify_axis(seat, &time, &event);
        notify_pointer_frame(seat);
    }
}

static const struct wakefield_interface wakefield_implementation = {
        .get_surface_location = wakefield_get_surface_location,
        .move_surface = wakefield_move_surface,
        .get_pixel_color = wakefield_get_pixel_color,
        .capture_create = wakefield_capture_create,
        .send_key = wakefield_send_key,
        .send_cursor = wakefield_send_cursor,
        .send_button = wakefield_send_button,
        .send_wheel = wakefield_send_wheel,
};

static void
wakefield_bind(struct wl_client *client, void *data, uint32_t version, uint32_t id)
{
    struct wakefield *wakefield = data;

    struct wl_resource *resource = wl_resource_create(client, &wakefield_interface, 1, id);
    if (resource) {
        wl_resource_set_implementation(resource, &wakefield_implementation, wakefield, NULL);
    }

    weston_log_scope_printf(wakefield->log, "WAKEFIELD: bind\n");
}

static void
wakefield_destroy(struct wl_listener *listener, void *data)
{
    struct wakefield *wakefield = container_of(listener, struct wakefield, destroy_listener);

    weston_log_scope_printf(wakefield->log, "WAKEFIELD: destroy\n");

    wl_list_remove(&wakefield->destroy_listener.link);

    weston_log_scope_destroy(wakefield->log);
    free(wakefield);
}

WL_EXPORT int
wet_module_init(struct weston_compositor *wc, int *argc, char *argv[])
{
    struct wakefield *wakefield = zalloc(sizeof(struct wakefield));
    if (wakefield == NULL)
        return -1;

    if (!weston_compositor_add_destroy_listener_once(wc, &wakefield->destroy_listener,
                                                     wakefield_destroy)) {
        free(wakefield);
        return 0;
    }


    wakefield->compositor = wc;
    // Log scope; add this to weston option list to subscribe: `--logger-scopes=wakefield`
    // See https://wayland.pages.freedesktop.org/weston/toc/libweston/log.html for more info.
    wakefield->log = weston_compositor_add_log_scope(wc, "wakefield",
                                                     "wakefield plugin own actions",
                                                     NULL, NULL, NULL);

    if (wl_global_create(wc->wl_display, &wakefield_interface,
                         1, wakefield, wakefield_bind) == NULL) {
        wl_list_remove(&wakefield->destroy_listener.link);
        return -1;
    }

    return 0;
}
