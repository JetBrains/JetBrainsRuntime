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

#ifdef HEADLESS
    #error This file should not be included in headless library
#endif

#include <stdbool.h>
#include <dlfcn.h>
#include <string.h>
#include <poll.h>
#include <errno.h>
#include <Trace.h>
#include <assert.h>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>

#include "gtk-shell1-client-protocol.h"

#include "jvm_md.h"
#include "JNIUtilities.h"
#include "awt.h"
#include "sun_awt_wl_WLToolkit.h"
#include "WLToolkit.h"
#include "WLRobotPeer.h"
#include "WLGraphicsEnvironment.h"
#include "memory_utils.h"

#ifdef WAKEFIELD_ROBOT
#include "wakefield-client-protocol.h"
#include "sun_awt_wl_WLRobotPeer.h"
#endif

extern JavaVM *jvm;

struct wl_display *wl_display = NULL;
struct wl_shm *wl_shm = NULL;
struct wl_compositor *wl_compositor = NULL;
struct xdg_wm_base *xdg_wm_base = NULL;
struct xdg_activation_v1 *xdg_activation_v1 = NULL;
struct gtk_shell1* gtk_shell1 = NULL;
struct wl_seat     *wl_seat = NULL;

struct wl_keyboard *wl_keyboard;
struct wl_pointer  *wl_pointer;

struct wl_cursor_theme *wl_cursor_theme = NULL;

struct wl_surface *wl_surface_in_focus = NULL;
struct wl_data_device_manager *wl_ddm = NULL;
struct zwp_primary_selection_device_manager_v1 *zwp_selection_dm = NULL;

uint32_t last_mouse_pressed_serial = 0;
uint32_t last_pointer_enter_serial = 0;
uint32_t last_input_or_focus_serial = 0;

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
static jfieldID hasAxisEventFID;
static jfieldID serialFID;
static jfieldID surfaceFID;
static jfieldID timestampFID;
static jfieldID surfaceXFID;
static jfieldID surfaceYFID;
static jfieldID buttonCodeFID;
static jfieldID isButtonPressedFID;
static jfieldID axis_0_validFID;
static jfieldID axis_0_valueFID;

static jfieldID keyRepeatRateFID;
static jfieldID keyRepeatDelayFID;

static jmethodID dispatchKeyboardKeyEventMID;
static jmethodID dispatchKeyboardModifiersEventMID;
static jmethodID dispatchKeyboardEnterEventMID;
static jmethodID dispatchKeyboardLeaveEventMID;

struct xkb_state   *xkb_state;
struct xkb_context *xkb_context;
struct xkb_keymap  *xkb_keymap;

typedef uint32_t xkb_mod_mask_t;
typedef uint32_t xkb_layout_index_t;
typedef uint32_t xkb_keycode_t;
typedef uint32_t xkb_keysym_t;

enum xkb_state_component {
    XKB_STATE_MODS_DEPRESSED = (1 << 0),
    XKB_STATE_MODS_LATCHED = (1 << 1),
    XKB_STATE_MODS_LOCKED = (1 << 2),
    XKB_STATE_MODS_EFFECTIVE = (1 << 3),
    XKB_STATE_LAYOUT_DEPRESSED = (1 << 4),
    XKB_STATE_LAYOUT_LATCHED = (1 << 5),
    XKB_STATE_LAYOUT_LOCKED = (1 << 6),
    XKB_STATE_LAYOUT_EFFECTIVE = (1 << 7),
    XKB_STATE_LEDS = (1 << 8)
};

enum xkb_keymap_format {
    XKB_KEYMAP_FORMAT_TEXT_V1 = 1
};

#define XKB_MOD_NAME_SHIFT      "Shift"
#define XKB_MOD_NAME_CAPS       "Lock"
#define XKB_MOD_NAME_CTRL       "Control"
#define XKB_MOD_NAME_ALT        "Mod1"
#define XKB_MOD_NAME_NUM        "Mod2"
#define XKB_MOD_NAME_LOGO       "Mod4"

#define XKB_LED_NAME_CAPS       "Caps Lock"
#define XKB_LED_NAME_NUM        "Num Lock"
#define XKB_LED_NAME_SCROLL     "Scroll Lock"

static struct {
    void * handle;

    struct xkb_context * (*xkb_context_new)(int);
    struct xkb_keymap * (*xkb_keymap_new_from_string)(
            struct xkb_context *context,
            const char *string,
            int format,
            int flags);
    void (*xkb_keymap_unref)(struct xkb_keymap *keymap);
    void (*xkb_state_unref)(struct xkb_state *state);
    struct xkb_state * (*xkb_state_new)(struct xkb_keymap *keymap);
    xkb_keysym_t (*xkb_state_key_get_one_sym)(struct xkb_state *state, xkb_keycode_t key);
    uint32_t (*xkb_state_key_get_utf32)(struct xkb_state *state, xkb_keycode_t key);
    int (*xkb_state_update_mask)(
            struct xkb_state *state,
            xkb_mod_mask_t depressed_mods,
            xkb_mod_mask_t latched_mods,
            xkb_mod_mask_t locked_mods,
            xkb_layout_index_t depressed_layout,
            xkb_layout_index_t latched_layout,
            xkb_layout_index_t locked_layout);
    int (*xkb_state_mod_name_is_active)(
            struct xkb_state *state,
            const char *name,
            enum xkb_state_component type);
} xkb_ifs;

static inline void
clear_dlerror(void)
{
    (void)dlerror();
}

static void *
xkbcommon_bind_sym(JNIEnv *env, const char* sym_name)
{
    clear_dlerror();
    void * sym_addr = dlsym(xkb_ifs.handle, sym_name);
    if (!sym_addr) {
        const char *dlsym_error = dlerror();
        JNU_ThrowByName(env, "java/lang/UnsatisfiedLinkError", dlsym_error);
    }

    return sym_addr;
}

#define BIND_XKB_SYM(name)  xkb_ifs.name = xkbcommon_bind_sym(env, #name); \
                            if (!xkb_ifs.name) return false;

static bool
xkbcommon_load(JNIEnv *env)
{
    void * handle = dlopen(JNI_LIB_NAME("xkbcommon"), RTLD_LAZY | RTLD_LOCAL);
    if (!handle) {
        handle = dlopen(VERSIONED_JNI_LIB_NAME("xkbcommon", "0"), RTLD_LAZY | RTLD_LOCAL);
    }
    if (!handle) {
        JNU_ThrowByNameWithMessageAndLastError(env, "java/lang/UnsatisfiedLinkError",
                                               JNI_LIB_NAME("xkbcommon"));
        return false;
    }

    xkb_ifs.handle = handle;

    BIND_XKB_SYM(xkb_context_new);
    BIND_XKB_SYM(xkb_keymap_new_from_string);
    BIND_XKB_SYM(xkb_state_key_get_utf32);
    BIND_XKB_SYM(xkb_keymap_unref);
    BIND_XKB_SYM(xkb_state_unref);
    BIND_XKB_SYM(xkb_state_new);
    BIND_XKB_SYM(xkb_state_key_get_one_sym);
    BIND_XKB_SYM(xkb_state_key_get_utf32);
    BIND_XKB_SYM(xkb_state_update_mask);
    BIND_XKB_SYM(xkb_state_mod_name_is_active);

    return true;
}

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
    bool has_axis_event          : 1;
    bool has_axis_source_event   : 1;
    bool has_axis_stop_event     : 1;
    bool has_axis_discrete_event : 1;

    uint32_t   time;
    uint32_t   serial;
    struct wl_surface* surface;

    wl_fixed_t surface_x;
    wl_fixed_t surface_y;

    uint32_t   button;
    uint32_t   state;

    struct {
        bool       valid;
        wl_fixed_t value;
        int32_t    discrete;
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

    last_pointer_enter_serial = serial;
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
    if (state) {
        last_input_or_focus_serial = serial;
        last_mouse_pressed_serial = serial;
    }
}

static void
wl_pointer_axis(void *data, struct wl_pointer *wl_pointer, uint32_t time,
                uint32_t axis, wl_fixed_t value)
{
    assert(axis < sizeof(pointer_event.axes)/sizeof(pointer_event.axes[0]));

    pointer_event.has_axis_event   = true;
    pointer_event.time             = time;
    pointer_event.axes[axis].valid = true;
    pointer_event.axes[axis].value = value;
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

    pointer_event.has_axis_stop_event = true;
    pointer_event.time                = time;
    pointer_event.axes[axis].valid    = true;
}

static void
wl_pointer_axis_discrete(void *data, struct wl_pointer *wl_pointer,
                         uint32_t axis, int32_t discrete)
{
    pointer_event.has_axis_discrete_event = true;
    pointer_event.axes[axis].valid        = true;
    pointer_event.axes[axis].discrete     = discrete;
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
    (*env)->SetBooleanField(env, pointerEventRef, hasAxisEventFID, pointer_event.has_axis_event);

    (*env)->SetLongField(env, pointerEventRef, surfaceFID, (long)pointer_event.surface);
    (*env)->SetLongField(env, pointerEventRef, serialFID, pointer_event.serial);
    (*env)->SetLongField(env, pointerEventRef, timestampFID, pointer_event.time);

    (*env)->SetIntField(env, pointerEventRef, surfaceXFID, wl_fixed_to_int(pointer_event.surface_x));
    (*env)->SetIntField(env, pointerEventRef, surfaceYFID, wl_fixed_to_int(pointer_event.surface_y));

    (*env)->SetIntField(env, pointerEventRef, buttonCodeFID, (jint)pointer_event.button);
    (*env)->SetBooleanField(env, pointerEventRef, isButtonPressedFID,
                            (pointer_event.state == WL_POINTER_BUTTON_STATE_PRESSED));

    (*env)->SetBooleanField(env, pointerEventRef, axis_0_validFID, pointer_event.axes[0].valid);
    (*env)->SetIntField(env, pointerEventRef, axis_0_valueFID, wl_fixed_to_int(pointer_event.axes[0].value));
}

static void
wl_pointer_frame(void *data, struct wl_pointer *wl_pointer)
{
    J2dTrace1(J2D_TRACE_INFO, "WLToolkit: pointer_frame event for surface %p\n", wl_pointer);

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
        .axis_discrete = wl_pointer_axis_discrete
};

static void
wl_keyboard_keymap(void *data, struct wl_keyboard *wl_keyboard, uint32_t format,
        int32_t fd, uint32_t size)
{
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) {
        JNU_ThrowInternalError(getEnv(),
                               "wl_keyboard_keymap supplied unknown keymap format");
        return;
    }

    char *mapped_data = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped_data == MAP_FAILED) {
        JNU_ThrowInternalError(getEnv(),
                               "wl_keyboard_keymap: failed to memory-map keymap");
        return;
    }

    struct xkb_keymap *new_xkb_keymap = xkb_ifs.xkb_keymap_new_from_string(
            xkb_context, mapped_data, XKB_KEYMAP_FORMAT_TEXT_V1, 0);
    munmap(mapped_data, size);
    close(fd);

    xkb_ifs.xkb_keymap_unref(xkb_keymap);
    xkb_ifs.xkb_state_unref(xkb_state);

    struct xkb_state *new_xkb_state = xkb_ifs.xkb_state_new(new_xkb_keymap);
    xkb_state = new_xkb_state;
    xkb_keymap = new_xkb_keymap;
}

static void
wl_keyboard_enter(void *data, struct wl_keyboard *wl_keyboard,
                  uint32_t serial, struct wl_surface *surface, struct wl_array *keys)
{
    wl_surface_in_focus = surface;
    last_input_or_focus_serial = serial;

    JNIEnv* env = getEnv();
    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchKeyboardEnterEventMID,
                                 serial, jlong_to_ptr(surface));
    JNU_CHECK_EXCEPTION(env);

    uint32_t *key;
    wl_array_for_each(key, keys) {
        const uint32_t scancode = *key + 8;
        const uint32_t keychar32 = xkb_ifs.xkb_state_key_get_utf32(xkb_state, scancode);
        const xkb_keysym_t keysym = xkb_ifs.xkb_state_key_get_one_sym(xkb_state, scancode);
        (*env)->CallStaticVoidMethod(env,
                                     tkClass,
                                     dispatchKeyboardKeyEventMID,
                                     serial,
                                     0,
                                     keysym,
                                     keychar32,
                                     JNI_TRUE);
        JNU_CHECK_EXCEPTION(env);
    }

}

static void
wl_keyboard_key(void *data, struct wl_keyboard *wl_keyboard,
                uint32_t serial, uint32_t time, uint32_t evdev_key, uint32_t state)
{
    last_input_or_focus_serial = serial;

    const uint32_t scancode = evdev_key + 8;
    const uint32_t keychar32 = xkb_ifs.xkb_state_key_get_utf32(xkb_state, scancode);
    const xkb_keysym_t keysym = xkb_ifs.xkb_state_key_get_one_sym(xkb_state, scancode);

    JNIEnv* env = getEnv();
    const bool pressed
            = (state == WL_KEYBOARD_KEY_STATE_PRESSED ? JNI_TRUE : JNI_FALSE);
    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchKeyboardKeyEventMID,
                                 serial,
                                 time,
                                 keysym,
                                 keychar32,
                                 pressed);
    JNU_CHECK_EXCEPTION(env);
}

static void
wl_keyboard_leave(void *data, struct wl_keyboard *wl_keyboard,
                  uint32_t serial, struct wl_surface *surface)
{
    wl_surface_in_focus = NULL;

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
    last_input_or_focus_serial = serial;

    xkb_ifs.xkb_state_update_mask(xkb_state,
                                  mods_depressed,
                                  mods_latched,
                                  mods_locked,
                                  0,
                                  0,
                                  group);

    JNIEnv* env = getEnv();

    const bool is_shift_active
        = xkb_ifs.xkb_state_mod_name_is_active(xkb_state,
                                               XKB_MOD_NAME_SHIFT,
                                               XKB_STATE_MODS_EFFECTIVE);
    // This event for ALT gets delivered only after the key has been released already.
    const bool is_alt_active
        = xkb_ifs.xkb_state_mod_name_is_active(xkb_state,
                                               XKB_MOD_NAME_ALT,
                                               XKB_STATE_MODS_EFFECTIVE);
    const bool is_ctrl_active
        = xkb_ifs.xkb_state_mod_name_is_active(xkb_state,
                                               XKB_MOD_NAME_CTRL,
                                               XKB_STATE_MODS_EFFECTIVE);
    const bool is_meta_active
        = xkb_ifs.xkb_state_mod_name_is_active(xkb_state,
                                               XKB_MOD_NAME_LOGO,
                                               XKB_STATE_MODS_EFFECTIVE);

    const bool is_caps_active
        = xkb_ifs.xkb_state_mod_name_is_active(xkb_state,
                                               XKB_MOD_NAME_CAPS,
                                               XKB_STATE_MODS_EFFECTIVE);

    const bool is_num_active
            = xkb_ifs.xkb_state_mod_name_is_active(xkb_state,
                                                   XKB_MOD_NAME_NUM,
                                                   XKB_STATE_MODS_EFFECTIVE);

    (*env)->CallStaticVoidMethod(env,
                                 tkClass,
                                 dispatchKeyboardModifiersEventMID,
                                 serial,
                                 is_shift_active,
                                 is_alt_active,
                                 is_ctrl_active,
                                 is_meta_active,
                                 is_caps_active,
                                 is_num_active);
    JNU_CHECK_EXCEPTION(env);
}

static void
wl_keyboard_repeat_info(void *data, struct wl_keyboard *wl_keyboard,
                        int32_t rate, int32_t delay)
{
    JNIEnv* env = getEnv();
    if (rate > 0 && delay > 0) {
        (*env)->SetStaticIntField(env, tkClass, keyRepeatRateFID, rate);
        (*env)->SetStaticIntField(env, tkClass, keyRepeatDelayFID, delay);
    }
    J2dTrace2(J2D_TRACE_INFO, "WLToolkit: set keyboard repeat rate %d and delay %d\n", rate, delay);
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
        wl_pointer_add_listener(wl_pointer, &wl_pointer_listener, NULL);
    } else if (!has_pointer && wl_pointer != NULL) {
        wl_pointer_release(wl_pointer);
        wl_pointer = NULL;
    }

    if (has_keyboard && wl_keyboard == NULL) {
        wl_keyboard = wl_seat_get_keyboard(wl_seat);
        wl_keyboard_add_listener(wl_keyboard, &wl_keyboard_listener, NULL);
    } else if (!has_keyboard && wl_keyboard != NULL) {
        wl_keyboard_release(wl_keyboard);
        wl_keyboard = NULL;
    }
}

static void
wl_seat_name(void *data, struct wl_seat *wl_seat, const char *name)
{
    J2dTrace1(J2D_TRACE_INFO, "WLToolkit: seat name '%s'\n", name);
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
    } else if (strcmp(interface, gtk_shell1_interface.name) == 0) {
        gtk_shell1 = wl_registry_bind(wl_registry, name, &gtk_shell1_interface, 1);
    } else if (strcmp(interface, wl_data_device_manager_interface.name) == 0) {
      wl_ddm = wl_registry_bind(wl_registry, name,&wl_data_device_manager_interface, 3);
    } else if (strcmp(interface, zwp_primary_selection_device_manager_v1_interface.name) == 0) {
        zwp_selection_dm = wl_registry_bind(wl_registry, name, &zwp_primary_selection_device_manager_v1_interface, 1);
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
    CHECK_NULL_RETURN(hasAxisEventFID = (*env)->GetFieldID(env, pointerEventClass, "has_axis_event", "Z"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(serialFID = (*env)->GetFieldID(env, pointerEventClass, "serial", "J"), JNI_FALSE);
    CHECK_NULL_RETURN(surfaceFID = (*env)->GetFieldID(env, pointerEventClass, "surface", "J"), JNI_FALSE);
    CHECK_NULL_RETURN(timestampFID = (*env)->GetFieldID(env, pointerEventClass, "timestamp", "J"), JNI_FALSE);
    CHECK_NULL_RETURN(surfaceXFID = (*env)->GetFieldID(env, pointerEventClass, "surface_x", "I"), JNI_FALSE);
    CHECK_NULL_RETURN(surfaceYFID = (*env)->GetFieldID(env, pointerEventClass, "surface_y", "I"), JNI_FALSE);
    CHECK_NULL_RETURN(buttonCodeFID = (*env)->GetFieldID(env, pointerEventClass, "buttonCode", "I"), JNI_FALSE);
    CHECK_NULL_RETURN(isButtonPressedFID = (*env)->GetFieldID(env, pointerEventClass, "isButtonPressed", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(axis_0_validFID = (*env)->GetFieldID(env, pointerEventClass, "axis_0_valid", "Z"), JNI_FALSE);
    CHECK_NULL_RETURN(axis_0_valueFID = (*env)->GetFieldID(env, pointerEventClass, "axis_0_value", "I"), JNI_FALSE);

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
                                                                              "(JJJIZ)V"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(dispatchKeyboardModifiersEventMID = (*env)->GetStaticMethodID(env, tkClass,
                                                                                    "dispatchKeyboardModifiersEvent",
                                                                                    "(JZZZZZZ)V"),
                      JNI_FALSE);

    CHECK_NULL_RETURN(keyRepeatRateFID = (*env)->GetStaticFieldID(env, tkClass,
                                                                  "keyRepeatRate", "I"),
                      JNI_FALSE);
    CHECK_NULL_RETURN(keyRepeatDelayFID = (*env)->GetStaticFieldID(env, tkClass,
                                                                   "keyRepeatDelay", "I"),
                      JNI_FALSE);

    jclass wlgeClass = (*env)->FindClass(env, "sun/awt/wl/WLGraphicsEnvironment");
    CHECK_NULL_RETURN(wlgeClass, JNI_FALSE);

    return WLGraphicsEnvironment_initIDs(env, wlgeClass);
}

static bool
initXKB(JNIEnv* env)
{
    if (!xkbcommon_load(env)) {
        return false;
    }

    xkb_context = xkb_ifs.xkb_context_new(0);
    return true;
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

void
initCursors(int scale) {
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

    wl_cursor_theme = wl_cursor_theme_load(theme_name, theme_size * scale, wl_shm);
    if (!wl_cursor_theme) {
        J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Failed to load cursor theme\n");
    }
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

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_initIDs
  (JNIEnv *env, jclass clazz)
{
    wl_display = wl_display_connect(NULL);
    if (!wl_display) {
        J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Failed to connect to Wayland display\n");
        JNU_ThrowByName(env, "java/awt/AWTError", "Can't connect to the Wayland server");
        return;
    }

    if (!initJavaRefs(env, clazz)) {
        JNU_ThrowInternalError(env, "Failed to find Wayland toolkit internal classes");
        return;
    }

    if (!initXKB(env)) {
        return;  // an exception has been thrown already
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

    J2dTrace1(J2D_TRACE_INFO, "WLToolkit: Connection to display(%p) established\n", wl_display);

    finalizeInit(env);
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

struct wl_shm_pool *CreateShmPool(size_t size, const char *name, void **data) {
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
    close(poolFD);
    return pool;
}

