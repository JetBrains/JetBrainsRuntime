/*
 * Copyright (c) 2022-2024, Oracle and/or its affiliates. All rights reserved.
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

#ifdef HEADLESS
    #error This file should not be included in headless library
#endif

#include <WLToolkit.h>
#include <WLKeyboard.h>

#include <stdbool.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include <poll.h>
#include <errno.h>
#include <Trace.h>
#include <assert.h>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>
#include <dlfcn.h>

#include "jvm_md.h"
#include "JNIUtilities.h"
#include "awt.h"
#include "sun_awt_wl_WLToolkit.h"
#include "sun_awt_wl_WLDisplay.h"
#include "WLRobotPeer.h"
#include "WLGraphicsEnvironment.h"
#include "memory_utils.h"
#include "java_awt_event_KeyEvent.h"

#ifdef WAKEFIELD_ROBOT
#include "wakefield.h"
#include "sun_awt_wl_WLRobotPeer.h"
#endif

#ifdef HAVE_GTK_SHELL1
#include <gtk-shell.h>
#endif

#define CHECK_WL_INTERFACE(var, name) if (!(var)) { JNU_ThrowByName(env, "java/awt/AWTError", "Can't bind to the " name " interface"); }

extern JavaVM *jvm;

struct wl_display *wl_display = NULL;
struct wl_shm *wl_shm = NULL;
struct wl_compositor *wl_compositor = NULL;
struct xdg_wm_base *xdg_wm_base = NULL;
struct wp_viewporter *wp_viewporter = NULL;
struct xdg_activation_v1 *xdg_activation_v1 = NULL; // optional, check for NULL before use
struct wl_seat     *wl_seat = NULL;
#ifdef HAVE_GTK_SHELL1
struct gtk_shell1* gtk_shell1 = NULL;
#endif
struct wl_keyboard *wl_keyboard; // optional, check for NULL before use
struct wl_pointer  *wl_pointer; // optional, check for NULL before use

#define MAX_CURSOR_SCALE 100
struct wl_cursor_theme *cursor_themes[MAX_CURSOR_SCALE] = {NULL};

struct wl_data_device_manager *wl_ddm = NULL;
struct zwp_primary_selection_device_manager_v1 *zwp_selection_dm = NULL; // optional, check for NULL before use
struct zxdg_output_manager_v1 *zxdg_output_manager_v1 = NULL; // optional, check for NULL before use

static uint32_t num_of_outstanding_sync = 0;

// This group of definitions corresponds to declarations from awt.h
jclass    tkClass = NULL;
jmethodID awtLockMID = NULL;
jmethodID awtUnlockMID = NULL;
jmethodID awtWaitMID = NULL;
jmethodID awtNotifyMID = NULL;
jmethodID awtNotifyAllMID = NULL;
jboolean  awtLockInited = JNI_FALSE;

static jmethodID dispatchPointerEventMID;
static jclass pointerEventClass;
static jmethodID pointerEventFactoryMID;
static jfieldID hasEnterEventFID;
static jfieldID hasLeaveEventFID;
static jfieldID hasMotionEventFID;
static jfieldID hasButtonEventFID;
static jfieldID serialFID;
static jfieldID surfaceFID;
static jfieldID timestampFID;
static jfieldID surfaceXFID;
static jfieldID surfaceYFID;
static jfieldID buttonCodeFID;
static jfieldID isButtonPressedFID;
static jfieldID xAxis_hasVectorValueFID;
static jfieldID xAxis_hasStopEventFID;
static jfieldID xAxis_hasSteps120ValueFID;
static jfieldID xAxis_vectorValueFID;
static jfieldID xAxis_steps120ValueFID;
static jfieldID yAxis_hasVectorValueFID;
static jfieldID yAxis_hasStopEventFID;
static jfieldID yAxis_hasSteps120ValueFID;
static jfieldID yAxis_vectorValueFID;
static jfieldID yAxis_steps120ValueFID;

static jmethodID dispatchKeyboardKeyEventMID;
static jmethodID dispatchKeyboardModifiersEventMID;
static jmethodID dispatchKeyboardEnterEventMID;
static jmethodID dispatchKeyboardLeaveEventMID;

JNIEnv *getEnv() {
    JNIEnv *env;
    // assuming we're always called from a Java thread
    (*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_2);
    return env;
}

static void xdg_wm_base_ping(void *data, struct xdg_wm_base *xdg_wm_base, uint32_t serial) {
    xdg_wm_base_pong(xdg_wm_base, serial);
}

static const struct xdg_wm_base_listener xdg_wm_base_listener = {
        .ping = xdg_wm_base_ping,
};

/**
 * Accumulates all pointer events between two frame events.
 */
struct pointer_event_cumulative {
    bool has_enter_event         : 1;
    bool has_leave_event         : 1;
    bool has_motion_event        : 1;
    bool has_button_event        : 1;
    bool has_axis_source_event   : 1;

    uint32_t   time;
    uint32_t   serial;
    struct wl_surface* surface;

    wl_fixed_t surface_x;
    wl_fixed_t surface_y;

    uint32_t   button;
    uint32_t   state;

    struct {
        // wl_pointer::axis
        bool has_vector_value   : 1;
        // wl_pointer::axis_stop
        bool has_stop_event     : 1;
        // wl_pointer::axis_discrete or wl_pointer::axis_value120
        bool has_steps120_value : 1;

        // wl_pointer::axis
        wl_fixed_t vector_value;

        // wl_pointer::axis_discrete or wl_pointer::axis_value120
        // In the former case, the value is multiplied by 120 for compatibility with wl_pointer::axis_value120
        int32_t    steps120_value;
    } axes[2];
    uint32_t axis_source;
};

struct pointer_event_cumulative pointer_event;

static void
wl_pointer_enter(void *data, struct wl_pointer *wl_pointer,
                 uint32_t serial, struct wl_surface *surface,
                 wl_fixed_t surface_x, wl_fixed_t surface_y)
{
    pointer_event.has_enter_event = true;
    pointer_event.serial          = serial;
    pointer_event.surface         = surface;
    pointer_event.surface_x       = surface_x,
    pointer_event.surface_y       = surface_y;
}

static void
wl_pointer_leave(void *data, struct wl_pointer *wl_pointer,
                 uint32_t serial, struct wl_surface *surface)
{
    pointer_event.has_leave_event = true;
    pointer_event.serial          = serial;
    pointer_event.surface         = surface;
}

static void
wl_pointer_motion(void *data, struct wl_pointer *wl_pointer, uint32_t time,
                  wl_fixed_t surface_x, wl_fixed_t surface_y)
{
    pointer_event.has_motion_event = true;
    pointer_event.time             = time;
    pointer_event.surface_x        = surface_x,
    pointer_event.surface_y        = surface_y;
}

static void
wl_pointer_button(void *data, struct wl_pointer *wl_pointer, uint32_t serial,
                  uint32_t time, uint32_t button, uint32_t state)
{
    pointer_event.has_button_event = true;
    pointer_event.time             = time;
    pointer_event.serial           = serial;
    pointer_event.button           = button,
    pointer_event.state            = state;
}

static void
wl_pointer_axis(void *data, struct wl_pointer *wl_pointer, uint32_t time,
                uint32_t axis, wl_fixed_t value)
{
    assert(axis < sizeof(pointer_event.axes)/sizeof(pointer_event.axes[0]));

    pointer_event.axes[axis].has_vector_value = true;
    pointer_event.time                        = time;
    pointer_event.axes[axis].vector_value     = value;
}

static void
wl_pointer_axis_source(void *data, struct wl_pointer *wl_pointer,
                       uint32_t axis_source)
{
    pointer_event.has_axis_source_event = true;
    pointer_event.axis_source           = axis_source;
}

static void
wl_pointer_axis_stop(void *data, struct wl_pointer *wl_pointer,
                     uint32_t time, uint32_t axis)
{
    assert(axis < sizeof(pointer_event.axes)/sizeof(pointer_event.axes[0]));

    pointer_event.axes[axis].has_stop_event = true;
    pointer_event.time                      = time;
}

static void
wl_pointer_axis_discrete(void *data, struct wl_pointer *wl_pointer,
                         uint32_t axis, int32_t discrete)
{
    assert(axis < sizeof(pointer_event.axes)/sizeof(pointer_event.axes[0]));

    // wl_pointer::axis_discrete event is deprecated with wl_pointer version 8 - this event is not sent to clients
    //   supporting version 8 or later.
    // It's just an additional check to work around possible bugs in compositors when they send both
    //   wl_pointer::axis_discrete and wl_pointer::axis_value120 events within the same frame.
    // In this case wl_pointer::axis_value120 would be preferred.
    if (!pointer_event.axes[axis].has_steps120_value) {
        pointer_event.axes[axis].has_steps120_value = true;
        pointer_event.axes[axis].steps120_value     = discrete * 120;
    }
}

static void
wl_pointer_axis_value120(void *data, struct wl_pointer *wl_pointer,
                         uint32_t axis, int32_t value120)
{
    assert(axis < sizeof(pointer_event.axes)/sizeof(pointer_event.axes[0]));

    pointer_event.axes[axis].has_steps120_value = true;
    pointer_event.axes[axis].steps120_value     = value120;
}

static inline void
resetPointerEvent(struct pointer_event_cumulative *e)
{
    memset(e, 0, sizeof(struct pointer_event_cumulative));
}

static void
fillJavaPointerEvent(JNIEnv* env, jobject pointerEventRef)
{
    (*env)->SetBooleanField(env, pointerEventRef, hasEnterEventFID, pointer_event.has_enter_event);
    (*env)->SetBooleanField(env, pointerEventRef, hasLeaveEventFID, pointer_event.has_leave_event);
    (*env)->SetBooleanField(env, pointerEventRef, hasMotionEventFID, pointer_event.has_motion_event);
    (*env)->SetBooleanField(env, pointerEventRef, hasButtonEventFID, pointer_event.has_button_event);

    (*env)->SetLongField(env, pointerEventRef, surfaceFID, (long)pointer_event.surface);
    (*env)->SetLongField(env, pointerEventRef, serialFID, pointer_event.serial);
    (*env)->SetLongField(env, pointerEventRef, timestampFID, pointer_event.time);

    (*env)->SetIntField(env, pointerEventRef, surfaceXFID, wl_fixed_to_int(pointer_event.surface_x));
    (*env)->SetIntField(env, pointerEventRef, surfaceYFID, wl_fixed_to_int(pointer_event.surface_y));

    (*env)->SetIntField(env, pointerEventRef, buttonCodeFID, (jint)pointer_event.button);
    (*env)->SetBooleanField(env, pointerEventRef, isButtonPressedFID,
                            (pointer_event.state == WL_POINTER_BUTTON_STATE_PRESSED));

    (*env)->SetBooleanField(env, pointerEventRef, xAxis_hasVectorValueFID,   pointer_event.axes[1].has_vector_value);
    (*env)->SetBooleanField(env, pointerEventRef, xAxis_hasStopEventFID,     pointer_event.axes[1].has_stop_event);
    (*env)->SetBooleanField(env, pointerEventRef, xAxis_hasSteps120ValueFID, pointer_event.axes[1].has_steps120_value);
    (*env)->SetDoubleField (env, pointerEventRef, xAxis_vectorValueFID,      wl_fixed_to_double(pointer_event.axes[1].vector_value));
    (*env)->SetIntField    (env, pointerEventRef, xAxis_steps120ValueFID,    pointer_event.axes[1].steps120_value);

    (*env)->SetBooleanField(env, pointerEventRef, yAxis_hasVectorValueFID,   pointer_event.axes[0].has_vector_value);
    (*env)->SetBooleanField(env, pointerEventRef, yAxis_hasStopEventFID,     pointer_event.axes[0].has_stop_event);
    (*env)->SetBooleanField(env, pointerEventRef, yAxis_hasSteps120ValueFID, pointer_event.axes[0].has_steps120_value);
    (*env)->SetDoubleField (env, pointerEventRef, yAxis_vectorValueFID,      wl_fixed_to_double(pointer_event.axes[0].vector_value));
    (*env)->SetIntField    (env, pointerEventRef, yAxis_steps120ValueFID,    pointer_event.axes[0].steps120_value);
}

static void
wl_pointer_frame(void *data, struct wl_pointer *wl_pointer)
{
    J2dTrace(J2D_TRACE_INFO, "WLToolkit: pointer_frame event for surface %p\n", wl_pointer);

    JNIEnv* env = getEnv();
    jobject pointerEventRef = (*env)->CallStaticObjectMethod(env,
                                                             pointerEventClass,
                                                             pointerEventFactoryMID);
    JNU_CHECK_EXCEPTION(env);

    fillJavaPointerEvent(env, pointerEventRef);
    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchPointerEventMID,
                                 pointerEventRef);
    JNU_CHECK_EXCEPTION(env);

    resetPointerEvent(&pointer_event);
}

static const struct wl_pointer_listener wl_pointer_listener = {
        .enter         = wl_pointer_enter,
        .leave         = wl_pointer_leave,
        .motion        = wl_pointer_motion,
        .button        = wl_pointer_button,
        .axis          = wl_pointer_axis,
        .frame         = wl_pointer_frame,
        .axis_source   = wl_pointer_axis_source,
        .axis_stop     = wl_pointer_axis_stop,
        .axis_discrete = wl_pointer_axis_discrete/*,
        This is only supported if the libwayland-client supports version 8 of the wl_pointer interface
        .axis_value120 = wl_pointer_axis_value120*/
};


static void
wl_keyboard_keymap(void *data, struct wl_keyboard *wl_keyboard, uint32_t format,
                   int32_t fd, uint32_t size)
{
    JNIEnv* env = getEnv();
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) {
        JNU_ThrowInternalError(env, "wl_keyboard_keymap supplied unknown keymap format");
        return;
    }

    char *serializedKeymap = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    if (serializedKeymap == MAP_FAILED) {
        JNU_ThrowInternalError(env, "wl_keyboard_keymap: failed to memory-map keymap");
        return;
    }

    wlSetKeymap(serializedKeymap);

    munmap(serializedKeymap, size);
    close(fd);
}

static void
wl_keyboard_enter(void *data, struct wl_keyboard *wl_keyboard,
                  uint32_t serial, struct wl_surface *surface, struct wl_array *keys)
{
    JNIEnv* env = getEnv();
    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchKeyboardEnterEventMID,
                                 serial, jlong_to_ptr(surface));
    JNU_CHECK_EXCEPTION(env);
}

static void
wl_keyboard_key(void *data, struct wl_keyboard *wl_keyboard,
                uint32_t serial, uint32_t time, uint32_t keycode, uint32_t state)
{
    wlSetKeyState(serial, time, keycode, state ? true : false);
}

static void
wl_keyboard_leave(void *data, struct wl_keyboard *wl_keyboard,
                  uint32_t serial, struct wl_surface *surface)
{
    JNIEnv* env = getEnv();
    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchKeyboardLeaveEventMID,
                                 serial,
                                 jlong_to_ptr(surface));
    JNU_CHECK_EXCEPTION(env);
}

static void
wl_keyboard_modifiers(void *data, struct wl_keyboard *wl_keyboard,
                      uint32_t serial, uint32_t mods_depressed,
                      uint32_t mods_latched, uint32_t mods_locked,
                      uint32_t group)
{
    wlSetModifiers(mods_depressed, mods_latched, mods_locked, group);

    JNIEnv* env = getEnv();

    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchKeyboardModifiersEventMID,
                                 serial);
    JNU_CHECK_EXCEPTION(env);
}

static void
wl_keyboard_repeat_info(void *data, struct wl_keyboard *wl_keyboard,
                        int32_t rate, int32_t delay)
{
    wlSetRepeatInfo(rate, delay);
}

void
wlPostKeyEvent(const struct WLKeyEvent* event)
{
    JNIEnv* env = getEnv();
    (*env)->CallStaticVoidMethod(
            env,
            tkClass,
            dispatchKeyboardKeyEventMID,
            event->serial,
            event->timestamp,
            event->id,
            event->keyCode,
            event->keyLocation,
            event->rawCode,
            event->extendedKeyCode,
            event->keyChar,
            event->modifiers
    );
    JNU_CHECK_EXCEPTION(env);
}

static const struct wl_keyboard_listener wl_keyboard_listener = {
        .enter       = wl_keyboard_enter,
        .leave       = wl_keyboard_leave,
        .keymap      = wl_keyboard_keymap,
        .modifiers   = wl_keyboard_modifiers,
        .repeat_info = wl_keyboard_repeat_info,
        .key         = wl_keyboard_key
};

static void
wl_seat_capabilities(void *data, struct wl_seat *wl_seat, uint32_t capabilities)
{
    const bool has_pointer  = capabilities & WL_SEAT_CAPABILITY_POINTER;
    const bool has_keyboard = capabilities & WL_SEAT_CAPABILITY_KEYBOARD;

    if (has_pointer && wl_pointer == NULL) {
        wl_pointer = wl_seat_get_pointer(wl_seat);
        if (wl_pointer != NULL) {
            wl_pointer_add_listener(wl_pointer, &wl_pointer_listener, NULL);
        }
    } else if (!has_pointer && wl_pointer != NULL) {
        wl_pointer_release(wl_pointer);
        wl_pointer = NULL;
    }

    if (has_keyboard && wl_keyboard == NULL) {
        wl_keyboard = wl_seat_get_keyboard(wl_seat);
        if (wl_keyboard != NULL) {
            wl_keyboard_add_listener(wl_keyboard, &wl_keyboard_listener, NULL);
        }
    } else if (!has_keyboard && wl_keyboard != NULL) {
        wl_keyboard_release(wl_keyboard);
        wl_keyboard = NULL;
    }
}

static void
wl_seat_name(void *data, struct wl_seat *wl_seat, const char *name)
{
    J2dTrace(J2D_TRACE_INFO, "WLToolkit: seat name '%s'\n", name);
}

static void
display_sync_callback(void *data,
                      struct wl_callback *callback,
                      uint32_t time) {
    num_of_outstanding_sync--;
    wl_callback_destroy(callback);
}

static const struct wl_callback_listener display_sync_listener = {
        .done = display_sync_callback
};

static void
process_new_listener_before_end_of_init() {
    // "The sync request asks the server to emit the 'done' event on the returned
    // wl_callback object. Since requests are handled in-order and events
    // are delivered in-order, this can be used as a barrier to ensure all previous
    // requests and the resulting events have been handled."
    struct wl_callback *callback = wl_display_sync(wl_display);
    if (callback == NULL) return;

    wl_callback_add_listener(callback,
                             &display_sync_listener,
                             callback);
    num_of_outstanding_sync++;
}

static const struct wl_seat_listener wl_seat_listener = {
        .capabilities = wl_seat_capabilities,
        .name = wl_seat_name
};

static void
registry_global(void *data, struct wl_registry *wl_registry,
                uint32_t name, const char *interface, uint32_t version)
{
    if (strcmp(interface, wl_shm_interface.name) == 0) {
        wl_shm = wl_registry_bind( wl_registry, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, wl_compositor_interface.name) == 0) {
        wl_compositor = wl_registry_bind(wl_registry, name, &wl_compositor_interface, 4);
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        // Need version 3, but can work with version 1.
        // The version will be checked at the point of use.
        int wm_base_version = MIN(3, version);
        xdg_wm_base = wl_registry_bind(wl_registry, name, &xdg_wm_base_interface, wm_base_version);
        if (xdg_wm_base != NULL) {
            xdg_wm_base_add_listener(xdg_wm_base, &xdg_wm_base_listener, NULL);
            process_new_listener_before_end_of_init();
        }
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        wl_seat = wl_registry_bind(wl_registry, name, &wl_seat_interface, 5);
        if (wl_seat != NULL) {
            wl_seat_add_listener(wl_seat, &wl_seat_listener, NULL);
            process_new_listener_before_end_of_init();
        }
    } else if (strcmp(interface, wl_output_interface.name) == 0) {
        WLOutputRegister(wl_registry, name);
        process_new_listener_before_end_of_init();
    } else if (strcmp(interface, xdg_activation_v1_interface.name) == 0) {
        xdg_activation_v1 = wl_registry_bind(wl_registry, name, &xdg_activation_v1_interface, 1);
    }
#ifdef HAVE_GTK_SHELL1
    else if (strcmp(interface, gtk_shell1_interface.name) == 0) {
        gtk_shell1 = wl_registry_bind(wl_registry, name, &gtk_shell1_interface, 1);
    }
#endif
    else if (strcmp(interface, wl_data_device_manager_interface.name) == 0) {
      wl_ddm = wl_registry_bind(wl_registry, name,&wl_data_device_manager_interface, 3);
    } else if (strcmp(interface, zwp_primary_selection_device_manager_v1_interface.name) == 0) {
        zwp_selection_dm = wl_registry_bind(wl_registry, name, &zwp_primary_selection_device_manager_v1_interface, 1);
    } else if (strcmp(interface, wp_viewporter_interface.name) == 0) {
        wp_viewporter = wl_registry_bind(wl_registry, name, &wp_viewporter_interface, 1);
    } else if (strcmp(interface, zxdg_output_manager_v1_interface.name) == 0) {
        zxdg_output_manager_v1 = wl_registry_bind(wl_registry, name, &zxdg_output_manager_v1_interface, 2);
        if (zxdg_output_manager_v1 != NULL) {
            WLOutputXdgOutputManagerBecameAvailable();
            process_new_listener_before_end_of_init();
        }
    }

#ifdef WAKEFIELD_ROBOT
    else if (strcmp(interface, wakefield_interface.name) == 0) {
        wakefield = wl_registry_bind(wl_registry, name, &wakefield_interface, 1);
        if (wakefield != NULL) {
            wakefield_add_listener(wakefield, &wakefield_listener, NULL);
            robot_queue = wl_display_create_queue(wl_display);
            if (robot_queue == NULL) {
                J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Failed to create wakefield robot queue\n");
                wakefield_destroy(wakefield);
                wakefield = NULL;
            } else {
                wl_proxy_set_queue((struct wl_proxy*)wakefield, robot_queue);
            }
            // TODO: call before destroying the display:
            //  wl_event_queue_destroy(robot_queue);
        }
    }
#endif
}

static void
registry_global_remove(void *data, struct wl_registry *wl_registry, uint32_t name)
{
    WLOutputDeregister(wl_registry, name);
    // TODO: also handle wl_seat removal
}

static const struct wl_registry_listener wl_registry_listener = {
        .global = registry_global,
        .global_remove = registry_global_remove,
};

static jboolean
initJavaRefs(JNIEnv *env, jclass clazz)
{
    CHECK_NULL_THROW_OOME_RETURN(env,
                                 tkClass = (jclass)(*env)->NewGlobalRef(env, clazz),
                                 "Allocation of a global reference to WLToolkit class failed",
                                 JNI_FALSE);

    CHECK_NULL_RETURN(awtLockMID = (*env)->GetStaticMethodID(env, tkClass,
                                                             "awtLock",
                                                             "()V"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(awtUnlockMID = (*env)->GetStaticMethodID(env, tkClass,
                                                             "awtUnlock",
                                                             "()V"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(awtWaitMID = (*env)->GetStaticMethodID(env, tkClass,
                                                             "awtLockWait",
                                                             "(J)V"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(awtNotifyMID = (*env)->GetStaticMethodID(env, tkClass,
                                                               "awtLockNotify",
                                                               "()V"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(awtNotifyMID = (*env)->GetStaticMethodID(env, tkClass,
                                                               "awtLockNotifyAll",
                                                               "()V"),
                      JNI_FALSE);

    awtLockInited = JNI_TRUE;

    CHECK_NULL_RETURN(dispatchPointerEventMID = (*env)->GetStaticMethodID(env, tkClass,
                                                                          "dispatchPointerEvent",
                                                                          "(Lsun/awt/wl/WLPointerEvent;)V"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(pointerEventClass = (*env)->FindClass(env,
                                                            "sun/awt/wl/WLPointerEvent"),
                      JNI_FALSE);

    CHECK_NULL_THROW_OOME_RETURN(env,
                                 pointerEventClass = (jclass)(*env)->NewGlobalRef(env, pointerEventClass),
                                 "Allocation of a global reference to PointerEvent class failed",
                                 JNI_FALSE);

    CHECK_NULL_RETURN(pointerEventFactoryMID = (*env)->GetStaticMethodID(env, pointerEventClass,
                                                                         "newInstance",
                                                                         "()Lsun/awt/wl/WLPointerEvent;"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(hasEnterEventFID = (*env)->GetFieldID(env, pointerEventClass, "has_enter_event", "Z"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(hasLeaveEventFID = (*env)->GetFieldID(env, pointerEventClass, "has_leave_event", "Z"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(hasMotionEventFID = (*env)->GetFieldID(env, pointerEventClass, "has_motion_event", "Z"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(hasButtonEventFID = (*env)->GetFieldID(env, pointerEventClass, "has_button_event", "Z"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(serialFID = (*env)->GetFieldID(env, pointerEventClass, "serial", "J"), JNI_FALSE);
    CHECK_NULL_RETURN(surfaceFID = (*env)->GetFieldID(env, pointerEventClass, "surface", "J"), JNI_FALSE);
    CHECK_NULL_RETURN(timestampFID = (*env)->GetFieldID(env, pointerEventClass, "timestamp", "J"), JNI_FALSE);
    CHECK_NULL_RETURN(surfaceXFID = (*env)->GetFieldID(env, pointerEventClass, "surface_x", "I"), JNI_FALSE);
    CHECK_NULL_RETURN(surfaceYFID = (*env)->GetFieldID(env, pointerEventClass, "surface_y", "I"), JNI_FALSE);
    CHECK_NULL_RETURN(buttonCodeFID = (*env)->GetFieldID(env, pointerEventClass, "buttonCode", "I"), JNI_FALSE);
    CHECK_NULL_RETURN(isButtonPressedFID = (*env)->GetFieldID(env, pointerEventClass, "isButtonPressed", "Z"), JNI_FALSE);

    CHECK_NULL_RETURN(xAxis_hasVectorValueFID = (*env)->GetFieldID(env, pointerEventClass, "xAxis_hasVectorValue", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(xAxis_hasStopEventFID = (*env)->GetFieldID(env, pointerEventClass, "xAxis_hasStopEvent", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(xAxis_hasSteps120ValueFID = (*env)->GetFieldID(env, pointerEventClass, "xAxis_hasSteps120Value", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(xAxis_vectorValueFID = (*env)->GetFieldID(env, pointerEventClass, "xAxis_vectorValue", "D"), JNI_FALSE);
    CHECK_NULL_RETURN(xAxis_steps120ValueFID = (*env)->GetFieldID(env, pointerEventClass, "xAxis_steps120Value", "I"), JNI_FALSE);

    CHECK_NULL_RETURN(yAxis_hasVectorValueFID = (*env)->GetFieldID(env, pointerEventClass, "yAxis_hasVectorValue", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(yAxis_hasStopEventFID = (*env)->GetFieldID(env, pointerEventClass, "yAxis_hasStopEvent", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(yAxis_hasSteps120ValueFID = (*env)->GetFieldID(env, pointerEventClass, "yAxis_hasSteps120Value", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(yAxis_vectorValueFID = (*env)->GetFieldID(env, pointerEventClass, "yAxis_vectorValue", "D"), JNI_FALSE);
    CHECK_NULL_RETURN(yAxis_steps120ValueFID = (*env)->GetFieldID(env, pointerEventClass, "yAxis_steps120Value", "I"), JNI_FALSE);

    CHECK_NULL_RETURN(dispatchKeyboardEnterEventMID = (*env)->GetStaticMethodID(env, tkClass,
                                                                                "dispatchKeyboardEnterEvent",
                                                                                "(JJ)V"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(dispatchKeyboardLeaveEventMID = (*env)->GetStaticMethodID(env, tkClass,
                                                                                "dispatchKeyboardLeaveEvent",
                                                                                "(JJ)V"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(dispatchKeyboardKeyEventMID = (*env)->GetStaticMethodID(env, tkClass,
                                                                              "dispatchKeyboardKeyEvent",
                                                                              "(JJIIIIICI)V"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(dispatchKeyboardModifiersEventMID = (*env)->GetStaticMethodID(env, tkClass,
                                                                                    "dispatchKeyboardModifiersEvent",
                                                                                    "(J)V"),
                      JNI_FALSE);

    jclass wlgeClass = (*env)->FindClass(env, "sun/awt/wl/WLGraphicsEnvironment");
    CHECK_NULL_RETURN(wlgeClass, JNI_FALSE);

    return WLGraphicsEnvironment_initIDs(env, wlgeClass);
}

// Reading cursor theme/size using 'gsettings' command line tool proved to be faster than initializing GTK and reading
// those values using corresponding GLib API (like e.g. com.sun.java.swing.plaf.gtk.GTKEngine.getSetting does). If GTK
// will be required by WLToolkit anyway due to some reason, this code would probably need to be removed.
static char*
readDesktopProperty(const char* name, char *output, int outputSize) {
#define CMD_PREFIX "gsettings get org.gnome.desktop.interface "
    char command[128] = CMD_PREFIX;
    strncat(command, name, sizeof(command) - sizeof(CMD_PREFIX));
    FILE *fd = popen(command, "r");
    if (!fd)
        return NULL;
    char *res = fgets(output, outputSize, fd);
    return pclose(fd) ? NULL : res;
}

struct wl_cursor_theme*
getCursorTheme(int scale) {
    if (cursor_themes[scale]) {
        return cursor_themes[scale];
    }

    char *theme_name;
    int theme_size = 0;
    char buffer[256];

    char *size_str = getenv("XCURSOR_SIZE");
    if (!size_str)
        size_str = readDesktopProperty("cursor-size", buffer, sizeof(buffer));
    if (size_str)
        theme_size = atoi(size_str);
    if (theme_size <= 0)
        theme_size = 24;

    theme_name = getenv("XCURSOR_THEME");
    if (!theme_name) {
        theme_name = readDesktopProperty("cursor-theme", buffer, sizeof(buffer));
        if (theme_name) {
            // drop surrounding quotes and trailing line break
            int len = strlen(theme_name);
            if (len > 2) {
                theme_name[len - 2] = 0;
                theme_name++;
            } else {
                theme_name = NULL;
            }
        }
    }

    if (scale >= MAX_CURSOR_SCALE) {
        J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Reach the maximum scale for cursor theme\n");
        return NULL;
    }

    cursor_themes[scale] = wl_cursor_theme_load(theme_name, theme_size * scale, wl_shm);
    if (!cursor_themes[scale]) {
        J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Failed to load cursor theme\n");
    }
    
    return cursor_themes[scale];
}

static void
finalizeInit(JNIEnv *env) {
    // NB: we are NOT on EDT here so shouldn't dispatch EDT-sensitive stuff
    while (num_of_outstanding_sync > 0) {
        // There are outstanding events that carry information essential for the toolkit
        // to be fully operational, such as, for example, the number of outputs.
        // Those events were subscribed to when handling globals in registry_global().
        // Now we let the server process those events and signal us that their corresponding
        // handlers have been executed by calling display_sync_callback() (see).
        if (wl_display_dispatch(wl_display) < 0) {
            JNU_ThrowByName(env, "java/awt/AWTError", "wl_display_dispatch() failed");
            return;
        }
    }
}

static void
checkInterfacesPresent(JNIEnv *env)
{
    // Check that all non-optional interfaces have been bound to and throw an appropriate error otherwise
    CHECK_WL_INTERFACE(wl_shm, "wl_shm");
    CHECK_WL_INTERFACE(wl_seat, "wl_seat");
    CHECK_WL_INTERFACE(wl_display, "wl_display");
    CHECK_WL_INTERFACE(wl_compositor, "wl_compositor");
    CHECK_WL_INTERFACE(xdg_wm_base, "xdg_wm_base");
    CHECK_WL_INTERFACE(wp_viewporter, "wp_viewporter");
    CHECK_WL_INTERFACE(wl_ddm, "wl_data_device_manager");
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLDisplay_connect(JNIEnv *env, jobject obj)
{
    return ptr_to_jlong(wl_display_connect(NULL));
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_initIDs(JNIEnv *env, jclass clazz, jlong displayPtr)
{
    assert (displayPtr != 0);
    wl_display = jlong_to_ptr(displayPtr);

    if (!initJavaRefs(env, clazz)) {
        JNU_ThrowInternalError(env, "Failed to find Wayland toolkit internal classes");
        return;
    }

    struct wl_registry *wl_registry = wl_display_get_registry(wl_display);
    if (wl_registry == NULL) {
        JNU_ThrowByName(env, "java/awt/AWTError", "Failed to obtain Wayland registry");
        return;
    }

    wl_registry_add_listener(wl_registry, &wl_registry_listener, NULL);
    // Process info about Wayland globals here; maybe register more handlers that
    // will have to be processed later in finalize_init().
    if (wl_display_roundtrip(wl_display) < 0) {
        JNU_ThrowByName(env, "java/awt/AWTError", "wl_display_roundtrip() failed");
        return;
    }

    J2dTrace(J2D_TRACE_INFO, "WLToolkit: Connection to display(%p) established\n", wl_display);

    finalizeInit(env);

    checkInterfacesPresent(env);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_dispatchEventsOnEDT
  (JNIEnv *env, jobject obj)
{
    // Dispatch all the events on the display's default event queue.
    // The handlers of those events will be called from here, i.e. on EDT,
    // and therefore must not block indefinitely.
    wl_display_dispatch_pending(wl_display);
}

/**
 * Waits for poll_timeout ms for an event on the Wayland server socket.
 * Returns -1 in case of error and 'revents' (see poll(2)) otherwise.
 */
static int
wlDisplayPoll(struct wl_display *display, int events, int poll_timeout)
{
    int rc = 0;
    struct pollfd pfd[1] = { {.fd = wl_display_get_fd(display), .events = events} };
    do {
        errno = 0;
        rc = poll(pfd, 1, poll_timeout);
    } while (rc == -1 && errno == EINTR);

    return rc == -1 ? -1 : (pfd[0].revents & 0xffff);
}

int
wlFlushToServer(JNIEnv *env)
{
    int rc = 0;

    while (true) {
        errno = 0;
        // From Wayland code: "if all data could not be written, errno will be set
        // to EAGAIN and -1 returned. In that case, use poll on the display
        // file descriptor to wait for it to become writable again."
        rc = wl_display_flush(wl_display);
        if (rc != -1 || errno != EAGAIN) {
            break;
        }

        rc = wlDisplayPoll(wl_display, POLLOUT, -1);
        if (rc == -1) {
            JNU_ThrowByName(env, "java/awt/AWTError", "Wayland display error polling out to the server");
            return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
        }
    }

    if (rc < 0 && errno != EPIPE) {
        JNU_ThrowByName(env, "java/awt/AWTError", "Wayland display error flushing data out to the server");
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    return 0;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_flushImpl
  (JNIEnv *env, jobject obj)
{
    (void) wlFlushToServer(env);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_dispatchNonDefaultQueuesImpl
  (JNIEnv *env, jobject obj)
{
#ifdef WAKEFIELD_ROBOT
    if (!robot_queue) {
        return;
    }

    int rc = 0;

    while (rc >= 0) {
        // Dispatch pending events on the wakefield queue
        rc = wl_display_dispatch_queue(wl_display, robot_queue);
    }

    // Simply return in case of any error; the actual error reporting (exception)
    // and/or shutdown will happen on the "main" toolkit thread AWT-Wayland,
    // see readEvents() below.
#endif
}

JNIEXPORT jint JNICALL
Java_sun_awt_wl_WLToolkit_readEvents
  (JNIEnv *env, jobject obj)
{
    // NB: this method should be modeled after wl_display_dispatch_queue() from the Wayland code
    int rc = 0;

    // Check if there's anything in the default event queue already *and*
    // lock the queue for this thread.
    rc = wl_display_prepare_read(wl_display);
    if (rc != 0) {
        // There are existing events on the default queue
        return sun_awt_wl_WLToolkit_READ_RESULT_FINISHED_WITH_EVENTS;
    }

    rc = wlFlushToServer(env);
    if (rc != 0) {
        wl_display_cancel_read(wl_display);
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    // Wait for new data *from* the server.
    // Specify some timeout because otherwise 'flush' above that sends data
    // to the server will have to wait too long.
    rc = wlDisplayPoll(wl_display, POLLIN,
                         sun_awt_wl_WLToolkit_WAYLAND_DISPLAY_INTERACTION_TIMEOUT_MS);
    if (rc == -1) {
        wl_display_cancel_read(wl_display);
        JNU_ThrowByName(env, "java/awt/AWTError", "Wayland display error polling for data from the server");
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    const bool hasMoreData = (rc & POLLIN);
    if (!hasMoreData) {
        wl_display_cancel_read(wl_display);
        return sun_awt_wl_WLToolkit_READ_RESULT_FINISHED_NO_EVENTS;
    }

    // Read new data from Wayland and transform them into events
    // on the corresponding queues of the display.
    rc = wl_display_read_events(wl_display);
    if (rc == -1) { // display disconnect has likely happened
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    rc = wl_display_prepare_read(wl_display);
    if (rc != 0) {
        return sun_awt_wl_WLToolkit_READ_RESULT_FINISHED_WITH_EVENTS;
    } else {
        wl_display_cancel_read(wl_display);
        return sun_awt_wl_WLToolkit_READ_RESULT_FINISHED_NO_EVENTS;
    }
}

JNIEXPORT jint JNICALL
DEF_JNI_OnLoad(JavaVM *vm, void *reserved)
{
    jvm = vm;

    return JNI_VERSION_1_2;
}


JNIEXPORT void JNICALL
Java_java_awt_Component_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL
Java_java_awt_Container_initIDs
  (JNIEnv *env, jclass cls)
{

}


JNIEXPORT void JNICALL
Java_java_awt_Button_initIDs
  (JNIEnv *env, jclass cls)
{

}

JNIEXPORT void JNICALL
Java_java_awt_Scrollbar_initIDs
  (JNIEnv *env, jclass cls)
{

}


JNIEXPORT void JNICALL
Java_java_awt_Window_initIDs
  (JNIEnv *env, jclass cls)
{

}

JNIEXPORT void JNICALL
Java_java_awt_Frame_initIDs
  (JNIEnv *env, jclass cls)
{

}


JNIEXPORT void JNICALL
Java_java_awt_MenuComponent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL Java_java_awt_MenuItem_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL Java_java_awt_Menu_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_TextArea_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL
Java_java_awt_Checkbox_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL Java_java_awt_ScrollPane_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_TextField_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL Java_java_awt_Dialog_initIDs (JNIEnv *env, jclass cls)
{
}



/*
 * Class:     java_awt_TrayIcon
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_java_awt_TrayIcon_initIDs(JNIEnv *env , jclass clazz)
{
}

JNIEXPORT void JNICALL
Java_java_awt_FileDialog_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_AWTEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Insets_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_KeyboardFocusManager_initIDs
    (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Font_initIDs(JNIEnv *env, jclass cls) {
}

JNIEXPORT void JNICALL
Java_java_awt_event_InputEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_event_KeyEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_AWTEvent_nativeSetSource(JNIEnv *env, jobject self,
                                       jobject newSource)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Event_initIDs(JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL
Java_sun_awt_SunToolkit_closeSplashScreen(JNIEnv *env, jclass cls)
{
    typedef void (*SplashClose_t)();
    SplashClose_t splashClose;
    void* hSplashLib = dlopen(0, RTLD_LAZY);
    if (!hSplashLib) {
        return;
    }
    splashClose = (SplashClose_t)dlsym(hSplashLib,
        "SplashClose");
    if (splashClose) {
        splashClose();
    }
    dlclose(hSplashLib);
}

void awt_output_flush()
{
    wlFlushToServer(getEnv());
}

struct wl_shm_pool *CreateShmPool(size_t size, const char *name, void **data, int* poolFDPtr) {
    if (size <= 0)
        return NULL;
    int poolFD = AllocateSharedMemoryFile(size, name);
    if (poolFD < 0)
        return NULL;
    void *memPtr = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, poolFD, 0);
    if (memPtr == MAP_FAILED) {
        close(poolFD);
        return NULL;
    }
    *data = memPtr;
    struct wl_shm_pool *pool = wl_shm_create_pool(wl_shm, poolFD, size);
    if (poolFDPtr != NULL) {
        *poolFDPtr = poolFD;
    } else {
        close(poolFD);
    }
    return pool;
}

