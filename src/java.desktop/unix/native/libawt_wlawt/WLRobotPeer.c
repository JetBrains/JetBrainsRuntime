/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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
#include <string.h>
#include <sys/mman.h>
#include <pthread.h>

#include <Trace.h>
#include <jni_util.h>
#include <java_awt_event_KeyEvent.h>
#include <java_awt_event_InputEvent.h>

#include "sun_awt_wl_WLRobotPeer.h"
#include "WLToolkit.h"

#include "wakefield-client-protocol.h"

#ifdef WAKEFIELD_ROBOT
struct wakefield      *wakefield;
struct wl_event_queue *robot_queue;
#endif

#ifndef HEADLESS
static struct wl_buffer*
allocate_buffer(JNIEnv *env, int width, int height, uint32_t **buffer_data, size_t* size);
#endif

#ifdef WAKEFIELD_ROBOT
// These structs are used to transfer data between the thread that made the request
// and the thread where the event handler was invoked in a race-free manner.
struct pixel_color_request_t {
    pthread_mutex_t mutex;
    pthread_cond_t  cond;

    bool            is_data_available;
    uint32_t        error_code;

    uint32_t        rgb;
} pixel_color_request;

struct screen_capture_request_t {
    pthread_mutex_t mutex;
    pthread_cond_t  cond;

    bool            is_data_available;
    uint32_t        error_code;
} screen_capture_request;

struct surface_location_request_t {
    pthread_mutex_t mutex;
    pthread_cond_t  cond;

    bool            is_data_available;
    uint32_t        error_code;

    int32_t         x;
    int32_t         y;
} surface_location_request;

#define WAKEFIELD_REQUEST_INIT(r)       (r).is_data_available = false

#define WAKEFIELD_REQUEST_WAIT_START(r) pthread_mutex_lock(&(r).mutex);              \
                                        while (!(r).is_data_available) {             \
                                            pthread_cond_wait(&(r).cond, &(r).mutex);\
                                        }

#define WAKEFIELD_REQUEST_WAIT_END(r)   pthread_mutex_unlock(&(r).mutex)

#define WAKEFIELD_EVENT_NOTIFY_START(r) pthread_mutex_lock(&(r).mutex)

#define WAKEFIELD_EVENT_NOTIFY_END(r)   (r).is_data_available = true; \
                                        pthread_cond_broadcast(&(r).cond); \
                                        pthread_mutex_unlock(&(r).mutex)

static int
init_mutex_and_cond(JNIEnv *env, pthread_mutex_t *mutex, pthread_cond_t *cond)
{
    int rc = 0;

    rc = pthread_mutex_init(mutex, NULL);
    if (rc != 0) {
        JNU_ThrowInternalError(env, "pthread_mutex_init() failed");
        return rc;
    }

    rc = pthread_cond_init(cond, NULL);
    if (rc != 0) {
        JNU_ThrowInternalError(env, "pthread_cond_init() failed");
        return rc;
    }

    return rc;
}

static void
handle_wakefield_error(JNIEnv *env, uint32_t error_code);

struct wayland_keycode_map_item {
    int java_key_code;
    int wayland_key_code;
};

// Key codes correspond to the Linux event codes:
// https://github.com/torvalds/linux/blob/master/include/uapi/linux/input-event-codes.h
static struct wayland_keycode_map_item wayland_keycode_map[] = {
        { java_awt_event_KeyEvent_VK_ESCAPE, 1 },
        { java_awt_event_KeyEvent_VK_1, 2 },
        { java_awt_event_KeyEvent_VK_2, 3 },
        { java_awt_event_KeyEvent_VK_3, 4 },
        { java_awt_event_KeyEvent_VK_4, 5 },
        { java_awt_event_KeyEvent_VK_5, 6 },
        { java_awt_event_KeyEvent_VK_6, 7 },
        { java_awt_event_KeyEvent_VK_7, 8 },
        { java_awt_event_KeyEvent_VK_8, 9 },
        { java_awt_event_KeyEvent_VK_9, 10 },
        { java_awt_event_KeyEvent_VK_0, 11 },
        { java_awt_event_KeyEvent_VK_MINUS, 12 },
        { java_awt_event_KeyEvent_VK_EQUALS, 13 },
        { java_awt_event_KeyEvent_VK_BACK_SPACE, 14 },
        { java_awt_event_KeyEvent_VK_TAB, 15 },
        { java_awt_event_KeyEvent_VK_Q, 16 },
        { java_awt_event_KeyEvent_VK_W, 17 },
        { java_awt_event_KeyEvent_VK_E, 18 },
        { java_awt_event_KeyEvent_VK_R, 19 },
        { java_awt_event_KeyEvent_VK_T, 20 },
        { java_awt_event_KeyEvent_VK_Y, 21 },
        { java_awt_event_KeyEvent_VK_U, 22 },
        { java_awt_event_KeyEvent_VK_I, 23 },
        { java_awt_event_KeyEvent_VK_O, 24 },
        { java_awt_event_KeyEvent_VK_P, 25 },
        { java_awt_event_KeyEvent_VK_OPEN_BRACKET, 26 },
        { java_awt_event_KeyEvent_VK_CLOSE_BRACKET, 27 },
        { java_awt_event_KeyEvent_VK_ENTER, 28 },
        { java_awt_event_KeyEvent_VK_CONTROL, 29 },
        { java_awt_event_KeyEvent_VK_A, 30 },
        { java_awt_event_KeyEvent_VK_S, 31 },
        { java_awt_event_KeyEvent_VK_D, 32 },
        { java_awt_event_KeyEvent_VK_F, 33 },
        { java_awt_event_KeyEvent_VK_G, 34 },
        { java_awt_event_KeyEvent_VK_H, 35 },
        { java_awt_event_KeyEvent_VK_J, 36 },
        { java_awt_event_KeyEvent_VK_K, 37 },
        { java_awt_event_KeyEvent_VK_L, 38 },
        { java_awt_event_KeyEvent_VK_SEMICOLON, 39 },
        { java_awt_event_KeyEvent_VK_QUOTE, 40 },
        { java_awt_event_KeyEvent_VK_BACK_QUOTE, 41 },
        { java_awt_event_KeyEvent_VK_SHIFT, 42 },
        { java_awt_event_KeyEvent_VK_BACK_SLASH, 43 },
        { java_awt_event_KeyEvent_VK_Z, 44 },
        { java_awt_event_KeyEvent_VK_X, 45 },
        { java_awt_event_KeyEvent_VK_C, 46 },
        { java_awt_event_KeyEvent_VK_V, 47 },
        { java_awt_event_KeyEvent_VK_B, 48 },
        { java_awt_event_KeyEvent_VK_N, 49 },
        { java_awt_event_KeyEvent_VK_M, 50 },
        { java_awt_event_KeyEvent_VK_COMMA, 51 },
        { java_awt_event_KeyEvent_VK_PERIOD, 52 },
        { java_awt_event_KeyEvent_VK_SLASH, 53 },
        { java_awt_event_KeyEvent_VK_MULTIPLY, 55 },
        { java_awt_event_KeyEvent_VK_ALT, 56 },
        { java_awt_event_KeyEvent_VK_SPACE, 57 },
        { java_awt_event_KeyEvent_VK_CAPS_LOCK, 58 },
        { java_awt_event_KeyEvent_VK_F1, 59 },
        { java_awt_event_KeyEvent_VK_F2, 60 },
        { java_awt_event_KeyEvent_VK_F3, 61 },
        { java_awt_event_KeyEvent_VK_F4, 62 },
        { java_awt_event_KeyEvent_VK_F5, 63 },
        { java_awt_event_KeyEvent_VK_F6, 64 },
        { java_awt_event_KeyEvent_VK_F7, 65 },
        { java_awt_event_KeyEvent_VK_F8, 66 },
        { java_awt_event_KeyEvent_VK_F9, 67 },
        { java_awt_event_KeyEvent_VK_F10, 68 },
        { java_awt_event_KeyEvent_VK_NUM_LOCK, 69 },
        { java_awt_event_KeyEvent_VK_SCROLL_LOCK, 70 },
        { java_awt_event_KeyEvent_VK_NUMPAD7, 71 },
        { java_awt_event_KeyEvent_VK_KP_UP, 72 },
        { java_awt_event_KeyEvent_VK_NUMPAD8, 72 },
        { java_awt_event_KeyEvent_VK_NUMPAD9, 73 },
        { java_awt_event_KeyEvent_VK_SUBTRACT, 74 },
        { java_awt_event_KeyEvent_VK_KP_LEFT, 75 },
        { java_awt_event_KeyEvent_VK_NUMPAD4, 75 },
        { java_awt_event_KeyEvent_VK_NUMPAD5, 76 },
        { java_awt_event_KeyEvent_VK_KP_RIGHT, 77 },
        { java_awt_event_KeyEvent_VK_NUMPAD6, 77 },
        { java_awt_event_KeyEvent_VK_ADD, 78 },
        { java_awt_event_KeyEvent_VK_NUMPAD1, 79 },
        { java_awt_event_KeyEvent_VK_KP_DOWN, 80 },
        { java_awt_event_KeyEvent_VK_NUMPAD2, 80 },
        { java_awt_event_KeyEvent_VK_NUMPAD3, 81 },
        { java_awt_event_KeyEvent_VK_NUMPAD0, 82 },
        { java_awt_event_KeyEvent_VK_DECIMAL, 83 },
        { java_awt_event_KeyEvent_VK_LESS, 86 },
        { java_awt_event_KeyEvent_VK_F11, 87 },
        { java_awt_event_KeyEvent_VK_F12, 88 },
        { java_awt_event_KeyEvent_VK_KATAKANA, 90 },
        { java_awt_event_KeyEvent_VK_HIRAGANA, 91 },
        { java_awt_event_KeyEvent_VK_INPUT_METHOD_ON_OFF, 92 },
        { java_awt_event_KeyEvent_VK_NONCONVERT, 94 },
        { java_awt_event_KeyEvent_VK_DIVIDE, 98 },
        { java_awt_event_KeyEvent_VK_PRINTSCREEN, 99 },
        { java_awt_event_KeyEvent_VK_ALT_GRAPH, 100 },
        { java_awt_event_KeyEvent_VK_HOME, 102 },
        { java_awt_event_KeyEvent_VK_UP, 103 },
        { java_awt_event_KeyEvent_VK_PAGE_UP, 104 },
        { java_awt_event_KeyEvent_VK_LEFT, 105 },
        { java_awt_event_KeyEvent_VK_RIGHT, 106 },
        { java_awt_event_KeyEvent_VK_END, 107 },
        { java_awt_event_KeyEvent_VK_DOWN, 108 },
        { java_awt_event_KeyEvent_VK_PAGE_DOWN, 109 },
        { java_awt_event_KeyEvent_VK_INSERT, 110 },
        { java_awt_event_KeyEvent_VK_DELETE, 111 },
        { java_awt_event_KeyEvent_VK_PAUSE, 119 },
        { java_awt_event_KeyEvent_VK_DECIMAL, 121 },
        { java_awt_event_KeyEvent_VK_META, 125 },
        { java_awt_event_KeyEvent_VK_WINDOWS, 125 },
        { java_awt_event_KeyEvent_VK_STOP, 128 },
        { java_awt_event_KeyEvent_VK_AGAIN, 129 },
        { java_awt_event_KeyEvent_VK_UNDO, 131 },
        { java_awt_event_KeyEvent_VK_FIND, 136 },
        { java_awt_event_KeyEvent_VK_HELP, 138 },
        { java_awt_event_KeyEvent_VK_LEFT_PARENTHESIS, 179 },
        { java_awt_event_KeyEvent_VK_RIGHT_PARENTHESIS, 180 },
        { java_awt_event_KeyEvent_VK_F13, 183 },
        { java_awt_event_KeyEvent_VK_F14, 184 },
        { java_awt_event_KeyEvent_VK_F15, 185 },
        { java_awt_event_KeyEvent_VK_F16, 186 },
        { java_awt_event_KeyEvent_VK_F17, 187 },
        { java_awt_event_KeyEvent_VK_F18, 188 },
        { java_awt_event_KeyEvent_VK_F19, 189 },
        { java_awt_event_KeyEvent_VK_F20, 190 },
        { java_awt_event_KeyEvent_VK_F21, 191 },
        { java_awt_event_KeyEvent_VK_F22, 192 },
        { java_awt_event_KeyEvent_VK_F23, 193 },
        { java_awt_event_KeyEvent_VK_F24, 194 },
        { java_awt_event_KeyEvent_VK_UNDEFINED, -1 }
};

struct wayland_button_map_item {
    int java_button_mask;
    int wayland_button_code;
};

static struct wayland_button_map_item wayland_button_map[] = {
        { java_awt_event_InputEvent_BUTTON1_DOWN_MASK | java_awt_event_InputEvent_BUTTON1_MASK, 0x110 },
        { java_awt_event_InputEvent_BUTTON2_DOWN_MASK | java_awt_event_InputEvent_BUTTON2_MASK, 0x112 },
        { java_awt_event_InputEvent_BUTTON3_DOWN_MASK | java_awt_event_InputEvent_BUTTON3_MASK, 0x111 },
        { -1, -1 },
};
#endif

static jclass    pointClass;          // java.awt.Point
static jmethodID pointClassConstrMID; // java.awt.Point(int, int)

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLRobotPeer_initIDs(JNIEnv *env, jclass clazz)
{
#ifdef WAKEFIELD_ROBOT
    int rc = init_mutex_and_cond(env, &pixel_color_request.mutex, &pixel_color_request.cond);
    if (rc != 0) return;

    rc = init_mutex_and_cond(env, &screen_capture_request.mutex, &screen_capture_request.cond);
    if (rc != 0) return;

    rc = init_mutex_and_cond(env, &surface_location_request.mutex, &surface_location_request.cond);
    if (rc != 0) return;
#endif

    const jclass pointClassLocal = (*env)->FindClass(env, "java/awt/Point");
    if (pointClassLocal == NULL) {
        JNU_ThrowInternalError(env, "cannot find class java.awt.Point");
        return;
    }
    pointClass = (*env)->NewGlobalRef(env, pointClassLocal); // never free'ed

    pointClassConstrMID  = (*env)->GetMethodID(env, pointClass, "<init>", "(II)V");
    if (pointClassConstrMID == NULL) {
        JNU_ThrowInternalError(env, "cannot find java.awt.Point(int, int)");
        return;
    }
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_wl_WLRobotPeer_isRobotExtensionPresentImpl(JNIEnv *env, jclass clazz)
{
    if (!wl_display) {
        J2dTrace(J2D_TRACE_ERROR, "WLRobotPeer: isRobotExtensionPresent can't work without a Wayland display\n");
        return JNI_FALSE;
    }

#ifdef WAKEFIELD_ROBOT
    return (wakefield != NULL);
#else
    J2dTrace(J2D_TRACE_ERROR, "WLToolkit: robot extension was not enabled at build time \n");
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_sun_awt_wl_WLRobotPeer_getRGBPixelImpl(JNIEnv *env, jclass clazz, jint x, jint y)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return 0;
    }

    WAKEFIELD_REQUEST_INIT(pixel_color_request);

    wakefield_get_pixel_color(wakefield, x, y);
    wlFlushToServer(env); // the event will be delivered on a dedicated thread, see wakefield_pixel_color()

    WAKEFIELD_REQUEST_WAIT_START(pixel_color_request);
    const uint32_t error_code = pixel_color_request.error_code;
    const uint32_t rgb        = pixel_color_request.rgb;
    WAKEFIELD_REQUEST_WAIT_END(pixel_color_request);

    if (error_code != WAKEFIELD_ERROR_NO_ERROR) {
        handle_wakefield_error(env, error_code);
        return 0;
    } else {
        return (jint)rgb;
    }
#endif
    return 0;
}

JNIEXPORT jobject JNICALL
Java_sun_awt_wl_WLRobotPeer_getLocationOfWLSurfaceImpl
        (JNIEnv *env, jclass clazz, jlong wlSurfacePtr)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return NULL;
    }

    WAKEFIELD_REQUEST_INIT(surface_location_request);

    struct wl_surface * const surface = (struct wl_surface*) wlSurfacePtr;
    wakefield_get_surface_location(wakefield, surface);
    wlFlushToServer(env); // the event will be delivered on a dedicated thread, see wakefield_surface_location()

    WAKEFIELD_REQUEST_WAIT_START(surface_location_request);
    const uint32_t error_code = surface_location_request.error_code;
    const int32_t           x = surface_location_request.x;
    const int32_t           y = surface_location_request.y;
    WAKEFIELD_REQUEST_WAIT_END(surface_location_request);

    if (error_code != WAKEFIELD_ERROR_NO_ERROR) {
        handle_wakefield_error(env, error_code);
        return NULL;
    } else {
        return (*env)->NewObject(env, pointClass, pointClassConstrMID, x, y);
    }
#else
    return NULL;
#endif
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLRobotPeer_setLocationOfWLSurfaceImpl
        (JNIEnv *env, jclass clazz, jlong wlSurfacePtr, jint x, jint y)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return;
    }

    J2dTrace(J2D_TRACE_INFO, "WLRobotPeer: sending move_surface request to wakefield %d, %d\n",
             x, y);

    struct wl_surface * const surface = (struct wl_surface*) wlSurfacePtr;
    wakefield_move_surface(wakefield, surface, x, y);
    wl_surface_commit(surface);
    wlFlushToServer(env);
#endif
}

JNIEXPORT jintArray JNICALL
Java_sun_awt_wl_WLRobotPeer_getRGBPixelsImpl
        (JNIEnv *env, jclass clazz, jint x, jint y, jint width, jint height)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return NULL;
    }

    uint32_t *buffer_data;
    size_t    buffer_size_in_bytes;
    struct wl_buffer *buffer = allocate_buffer(env, width, height, &buffer_data, &buffer_size_in_bytes);
    if (!buffer) {
        return NULL;
    }

    WAKEFIELD_REQUEST_INIT(screen_capture_request);

    wakefield_capture_create(wakefield, buffer, x, y);
    wl_display_flush(wl_display); // event will be delivered on EDT, see wakefield_capture_ready()

    WAKEFIELD_REQUEST_WAIT_START(screen_capture_request);
    const uint32_t error_code = screen_capture_request.error_code;
    WAKEFIELD_REQUEST_WAIT_END(screen_capture_request);

    jintArray arrayObj = NULL;

    if (error_code != WAKEFIELD_ERROR_NO_ERROR) {
        handle_wakefield_error(env, error_code);
    } else {
        arrayObj = (*env)->NewIntArray(env, buffer_size_in_bytes / 4);
        if (arrayObj != NULL) {
            jint *array = (*env)->GetPrimitiveArrayCritical(env, arrayObj, NULL);
            if (array == NULL) {
                JNU_ThrowOutOfMemoryError(env, "Wayland robot screen capture");
            } else {
                memcpy(array, buffer_data, buffer_size_in_bytes);
                (*env)->ReleasePrimitiveArrayCritical(env, arrayObj, array, 0);
            }
        }
    }

    wl_buffer_destroy(buffer);
    munmap(buffer_data, buffer_size_in_bytes);
    return arrayObj;
#else
    return NULL;
#endif
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLRobotPeer_sendJavaKeyImpl
        (JNIEnv *env, jclass clazz, jint javaKeyCode, jboolean pressed)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return;
    }

    uint32_t key = 0;

    for (struct wayland_keycode_map_item* item = wayland_keycode_map; item->wayland_key_code != -1; ++item) {
        if (item->java_key_code == javaKeyCode) {
            key = item->wayland_key_code;
            break;
        }
    }

    if (!key) {
        return;
    }

    wakefield_send_key(wakefield, key, pressed ? 1 : 0);
#endif
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLRobotPeer_mouseMoveImpl
        (JNIEnv *env, jclass clazz, jint x, jint y)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return;
    }

    wakefield_send_cursor(wakefield, x, y);
#endif
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLRobotPeer_sendMouseButtonImpl
        (JNIEnv *env, jclass clazz, jint buttons, jboolean pressed)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return;
    }

    for (struct wayland_button_map_item* item = wayland_button_map; item->wayland_button_code != -1; ++item) {
        if (item->java_button_mask & buttons) {
            wakefield_send_button(wakefield, item->wayland_button_code, pressed ? 1 : 0);
        }
    }
#endif
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLRobotPeer_mouseWheelImpl
        (JNIEnv *env, jclass clazz, jint amount)
{
#ifdef WAKEFIELD_ROBOT
    if (!wakefield) {
        JNU_ThrowByName(env, "java/awt/AWTError", "no 'wakefield' protocol extension");
        return;
    }

    wakefield_send_wheel(wakefield, amount);
#endif
}

#ifdef WAKEFIELD_ROBOT

static void wakefield_surface_location(void *data, struct wakefield *wakefield,
                                       struct wl_surface *surface, int32_t x, int32_t y,
                                       uint32_t error_code)
{
    J2dTrace(J2D_TRACE_INFO, "WLRobotPeer: event wakefield_surface_location: coordinates %d, %d (error %d)\n",
             x, y, error_code);

    WAKEFIELD_EVENT_NOTIFY_START(surface_location_request);
    surface_location_request.error_code = error_code;
    surface_location_request.x = x;
    surface_location_request.y = y;
    WAKEFIELD_EVENT_NOTIFY_END(surface_location_request);
}

static void wakefield_pixel_color(void *data, struct wakefield *wakefield,
                                  int32_t x, int32_t y, uint32_t rgb,
                                  uint32_t error_code)
{
    J2dTrace(J2D_TRACE_INFO, "WLRobotPeer: event wakefield_pixel_color: %d, %d color 0x%08x (error %d)\n",
             x, y, rgb, error_code);

    WAKEFIELD_EVENT_NOTIFY_START(pixel_color_request);
    pixel_color_request.error_code = error_code;
    pixel_color_request.rgb = rgb;
    WAKEFIELD_EVENT_NOTIFY_END(pixel_color_request);
}

static void wakefield_capture_ready(void *data, struct wakefield *wakefield,
                                    struct wl_buffer *buffer,
                                    uint32_t error_code)
{
    J2dTrace(J2D_TRACE_INFO, "WLRobotPeer: event wakefield_capture_ready: buffer %p (error %d)\n",
             buffer, error_code);

    WAKEFIELD_EVENT_NOTIFY_START(screen_capture_request);
    screen_capture_request.error_code = error_code;
    WAKEFIELD_EVENT_NOTIFY_END(screen_capture_request);
}

static void
handle_wakefield_error(JNIEnv *env, uint32_t error_code)
{
    J2dTrace(J2D_TRACE_ERROR, "WLRobotPeer: error code %d\n", error_code);

    switch (error_code) {
        case WAKEFIELD_ERROR_OUT_OF_MEMORY:
            JNU_ThrowOutOfMemoryError(env, "Wayland robot");
            break;

        case WAKEFIELD_ERROR_FORMAT:
            JNU_ThrowInternalError(env, "Wayland robot unsupported buffer format");
            break;

        case WAKEFIELD_ERROR_INTERNAL:
            JNU_ThrowInternalError(env, "Wayland robot");
            break;

        case WAKEFIELD_ERROR_INVALID_COORDINATES:
            // not really an error, but a reason to return something default
            break;

        default:
            // not all errors warrant an exception
            break;
    }
}

struct wakefield_listener wakefield_listener = {
        .surface_location = wakefield_surface_location,
        .pixel_color      = wakefield_pixel_color,
        .capture_ready    = wakefield_capture_ready
};
#endif // WAKEFIELD_ROBOT

#ifndef HEADLESS

static struct wl_buffer*
allocate_buffer(JNIEnv *env, int width, int height, uint32_t **buffer_data, size_t* size)
{
    const int stride = width * 4;
    *size            = height * stride;
    *buffer_data     = NULL;

    if (*size == 0) {
        JNU_ThrowByName(env, "java/awt/AWTError", "buffer size overflow");
        return NULL;
    }

    struct wl_shm_pool *pool = CreateShmPool(*size, "wl_shm_robot", (void**)buffer_data);
    if (!pool) {
        JNU_ThrowByName(env, "java/awt/AWTError", "couldn't create shared memory pool");
        return NULL;
    }
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(
            (struct wl_shm_pool*)pool, 0, width, height, stride, WL_SHM_FORMAT_XRGB8888);
    wl_shm_pool_destroy(pool); // buffers keep references to the pool, can "destroy" now

    return buffer;
}
#endif  // HEADLESS
