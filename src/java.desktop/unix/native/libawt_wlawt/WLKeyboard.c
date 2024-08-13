// Copyright 2023 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#ifdef HEADLESS
#error This file should not be included in headless library
#endif

#include "WLKeyboard.h"
#include <sun_awt_wl_WLKeyboard.h>

#include <stdint.h>
#include <stdbool.h>
#include <dlfcn.h>

#include <java_awt_event_KeyEvent.h>
#include <jni_util.h>
#include <stdlib.h>

//#define WL_KEYBOARD_DEBUG

extern JNIEnv *getEnv();

#define XKB_MOD_NAME_SHIFT      "Shift"
#define XKB_MOD_NAME_CAPS       "Lock"
#define XKB_MOD_NAME_CTRL       "Control"
#define XKB_MOD_NAME_ALT        "Mod1"
#define XKB_MOD_NAME_NUM        "Mod2"
#define XKB_MOD_NAME_LOGO       "Mod4"

#define XKB_LED_NAME_CAPS       "Caps Lock"
#define XKB_LED_NAME_NUM        "Num Lock"
#define XKB_LED_NAME_SCROLL     "Scroll Lock"

#define MAX_COMPOSE_UTF8_LENGTH 256


typedef uint32_t xkb_keycode_t;
typedef uint32_t xkb_keysym_t;
typedef uint32_t xkb_layout_index_t;
typedef uint32_t xkb_layout_mask_t;
typedef uint32_t xkb_level_index_t;
typedef uint32_t xkb_mod_index_t;
typedef uint32_t xkb_mod_mask_t;
typedef uint32_t xkb_led_index_t;
typedef uint32_t xkb_led_mask_t;

enum xkb_keysym_flags {
    XKB_KEYSYM_NO_FLAGS = 0,
    XKB_KEYSYM_CASE_INSENSITIVE = (1 << 0)
};

enum xkb_context_flags {
    XKB_CONTEXT_NO_FLAGS = 0,
    XKB_CONTEXT_NO_DEFAULT_INCLUDES = (1 << 0),
    XKB_CONTEXT_NO_ENVIRONMENT_NAMES = (1 << 1),
    XKB_CONTEXT_NO_SECURE_GETENV = (1 << 2)
};

enum xkb_log_level {
    XKB_LOG_LEVEL_CRITICAL = 10,
    XKB_LOG_LEVEL_ERROR = 20,
    XKB_LOG_LEVEL_WARNING = 30,
    XKB_LOG_LEVEL_INFO = 40,
    XKB_LOG_LEVEL_DEBUG = 50
};

enum xkb_keymap_compile_flags {
    XKB_KEYMAP_COMPILE_NO_FLAGS = 0
};

enum xkb_keymap_format {
    XKB_KEYMAP_FORMAT_TEXT_V1 = 1
};

enum xkb_key_direction {
    XKB_KEY_UP,
    XKB_KEY_DOWN
};

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

enum xkb_state_match {
    XKB_STATE_MATCH_ANY = (1 << 0),
    XKB_STATE_MATCH_ALL = (1 << 1),
    XKB_STATE_MATCH_NON_EXCLUSIVE = (1 << 16)
};

enum xkb_consumed_mode {
    XKB_CONSUMED_MODE_XKB,
    XKB_CONSUMED_MODE_GTK
};

enum xkb_compose_compile_flags {
    XKB_COMPOSE_COMPILE_NO_FLAGS = 0
};

enum xkb_compose_format {
    XKB_COMPOSE_FORMAT_TEXT_V1 = 1
};

enum xkb_compose_state_flags {
    XKB_COMPOSE_STATE_NO_FLAGS = 0
};

enum xkb_compose_status {
    XKB_COMPOSE_NOTHING,
    XKB_COMPOSE_COMPOSING,
    XKB_COMPOSE_COMPOSED,
    XKB_COMPOSE_CANCELLED
};

enum xkb_compose_feed_result {
    XKB_COMPOSE_FEED_IGNORED,
    XKB_COMPOSE_FEED_ACCEPTED
};


struct xkb_context;
struct xkb_keymap;
struct xkb_state;

struct xkb_rule_names {
    const char *rules;
    const char *model;
    const char *layout;
    const char *variant;
    const char *options;
};

struct xkb_compose_table;
struct xkb_compose_state;

typedef void
(*xkb_keymap_key_iter_t)(struct xkb_keymap *keymap, xkb_keycode_t key, void *data);

static jclass keyboardClass;         // sun.awt.wl.WLKeyboard
static jclass keyRepeatManagerClass; // sun.awt.wl.WLKeyboard.KeyRepeatManager
static jmethodID setRepeatInfoMID;   // sun.awt.wl.WLKeyboard.KeyRepeatManager.setRepeatInfo
static jmethodID startRepeatMID;     // sun.awt.wl.WLKeyboard.KeyRepeatManager.startRepeat
static jmethodID cancelRepeatMID;    // sun.awt.wl.WLKeyboard.KeyRepeatManager.cancelRepeat

static bool
initJavaRefs(JNIEnv *env) {
    CHECK_NULL_RETURN(keyboardClass = (*env)->FindClass(env, "sun/awt/wl/WLKeyboard"), false);
    CHECK_NULL_RETURN(keyRepeatManagerClass = (*env)->FindClass(env, "sun/awt/wl/WLKeyboard$KeyRepeatManager"), false);
    CHECK_NULL_RETURN(setRepeatInfoMID = (*env)->GetMethodID(env, keyRepeatManagerClass, "setRepeatInfo", "(II)V"),
                      false);
    CHECK_NULL_RETURN(startRepeatMID = (*env)->GetMethodID(env, keyRepeatManagerClass, "startRepeat", "(JI)V"), false);
    CHECK_NULL_RETURN(cancelRepeatMID = (*env)->GetMethodID(env, keyRepeatManagerClass, "cancelRepeat", "()V"), false);
    return true;
}

static struct WLKeyboardState {
    jobject instance;         // instance of sun.awt.wl.WLKeyboard
    jobject keyRepeatManager; // instance of sun.awt.wl.WLKeyboard.KeyRepeatManager

    struct xkb_context *context;
    struct xkb_state *state;
    struct xkb_state *tmpState;
    struct xkb_keymap *keymap;

    struct xkb_keymap *qwertyKeymap;
    struct xkb_state *tmpQwertyState;

    struct xkb_compose_table *composeTable;
    struct xkb_compose_state *composeState;

    bool asciiCapable;

    // Remap F13-F24 to proper XKB keysyms (and therefore to proper Java keycodes)
    bool remapExtraKeycodes;

    // Report KEY_PRESS/KEY_RELEASE events on non-ASCII capable layouts
    // as if they happen on the QWERTY layout
    bool useNationalLayouts;

    // Report dead keys not as KeyEvent.VK_DEAD_something, but as the corresponding 'normal' Java keycode
    bool reportDeadKeysAsNormal;

    // When this option is true, KeyEvent.keyCode() will be set to the corresponding key code on the
    // active layout (taking into account the setting for national layouts), instead of the default Java
    // behavior of setting it to the key code of the key on the QWERTY layout.
    bool reportJavaKeyCodeForActiveLayout;
} keyboard;

// Symbols for runtime linking with libxkbcommon
static struct {
    int (*keysym_get_name)(xkb_keysym_t keysym, char *buffer, size_t size);

    xkb_keysym_t (*keysym_from_name)(const char *name, enum xkb_keysym_flags flags);

    int (*keysym_to_utf8)(xkb_keysym_t keysym, char *buffer, size_t size);

    uint32_t (*keysym_to_utf32)(xkb_keysym_t keysym);

    xkb_keysym_t (*keysym_to_upper)(xkb_keysym_t ks);

    xkb_keysym_t (*keysym_to_lower)(xkb_keysym_t ks);

    struct xkb_context *(*context_new)(enum xkb_context_flags flags);

    struct xkb_context *(*context_ref)(struct xkb_context *context);

    void (*context_unref)(struct xkb_context *context);

    void (*context_set_user_data)(struct xkb_context *context, void *user_data);

    void *(*context_get_user_data)(struct xkb_context *context);

    int (*context_include_path_append)(struct xkb_context *context, const char *path);

    int (*context_include_path_append_default)(struct xkb_context *context);

    int (*context_include_path_reset_defaults)(struct xkb_context *context);

    void (*context_include_path_clear)(struct xkb_context *context);

    unsigned int (*context_num_include_paths)(struct xkb_context *context);

    const char *(*context_include_path_get)(struct xkb_context *context, unsigned int index);

    void (*context_set_log_level)(struct xkb_context *context,
                                  enum xkb_log_level level);

    enum xkb_log_level (*context_get_log_level)(struct xkb_context *context);

    void (*context_set_log_verbosity)(struct xkb_context *context, int verbosity);

    int (*context_get_log_verbosity)(struct xkb_context *context);

    void (*context_set_log_fn)(struct xkb_context *context,
                               void (*log_fn)(struct xkb_context *context,
                                              enum xkb_log_level level,
                                              const char *format, va_list args));

    struct xkb_keymap *(*keymap_new_from_names)(struct xkb_context *context,
                                                const struct xkb_rule_names *names,
                                                enum xkb_keymap_compile_flags flags);

    struct xkb_keymap *(*keymap_new_from_file)(struct xkb_context *context, FILE *file,
                                               enum xkb_keymap_format format,
                                               enum xkb_keymap_compile_flags flags);

    struct xkb_keymap *(*keymap_new_from_string)(struct xkb_context *context, const char *string,
                                                 enum xkb_keymap_format format,
                                                 enum xkb_keymap_compile_flags flags);

    struct xkb_keymap *(*keymap_new_from_buffer)(struct xkb_context *context, const char *buffer,
                                                 size_t length, enum xkb_keymap_format format,
                                                 enum xkb_keymap_compile_flags flags);

    struct xkb_keymap *(*keymap_ref)(struct xkb_keymap *keymap);

    void (*keymap_unref)(struct xkb_keymap *keymap);

    char *(*keymap_get_as_string)(struct xkb_keymap *keymap,
                                  enum xkb_keymap_format format);

    xkb_keycode_t (*keymap_min_keycode)(struct xkb_keymap *keymap);

    xkb_keycode_t (*keymap_max_keycode)(struct xkb_keymap *keymap);

    void (*keymap_key_for_each)(struct xkb_keymap *keymap, xkb_keymap_key_iter_t iter,
                                void *data);

    const char *(*keymap_key_get_name)(struct xkb_keymap *keymap, xkb_keycode_t key);

    xkb_keycode_t (*keymap_key_by_name)(struct xkb_keymap *keymap, const char *name);

    xkb_mod_index_t (*keymap_num_mods)(struct xkb_keymap *keymap);

    const char *(*keymap_mod_get_name)(struct xkb_keymap *keymap, xkb_mod_index_t idx);

    xkb_mod_index_t (*keymap_mod_get_index)(struct xkb_keymap *keymap, const char *name);

    xkb_layout_index_t (*keymap_num_layouts)(struct xkb_keymap *keymap);

    const char *(*keymap_layout_get_name)(struct xkb_keymap *keymap, xkb_layout_index_t idx);

    xkb_layout_index_t (*keymap_layout_get_index)(struct xkb_keymap *keymap, const char *name);

    xkb_led_index_t (*keymap_num_leds)(struct xkb_keymap *keymap);

    const char *(*keymap_led_get_name)(struct xkb_keymap *keymap, xkb_led_index_t idx);

    xkb_led_index_t (*keymap_led_get_index)(struct xkb_keymap *keymap, const char *name);

    xkb_layout_index_t (*keymap_num_layouts_for_key)(struct xkb_keymap *keymap, xkb_keycode_t key);

    xkb_level_index_t (*keymap_num_levels_for_key)(struct xkb_keymap *keymap, xkb_keycode_t key,
                                                   xkb_layout_index_t layout);

    int
    (*keymap_key_get_syms_by_level)(struct xkb_keymap *keymap,
                                    xkb_keycode_t key,
                                    xkb_layout_index_t layout,
                                    xkb_level_index_t level,
                                    const xkb_keysym_t **syms_out);

    int (*keymap_key_repeats)(struct xkb_keymap *keymap, xkb_keycode_t key);

    struct xkb_state *(*state_new)(struct xkb_keymap *keymap);

    struct xkb_state *(*state_ref)(struct xkb_state *state);

    void (*state_unref)(struct xkb_state *state);

    struct xkb_keymap *(*state_get_keymap)(struct xkb_state *state);

    enum xkb_state_component (*state_update_key)(struct xkb_state *state, xkb_keycode_t key,
                                                 enum xkb_key_direction direction);

    enum xkb_state_component (*state_update_mask)(struct xkb_state *state,
                                                  xkb_mod_mask_t depressed_mods,
                                                  xkb_mod_mask_t latched_mods,
                                                  xkb_mod_mask_t locked_mods,
                                                  xkb_layout_index_t depressed_layout,
                                                  xkb_layout_index_t latched_layout,
                                                  xkb_layout_index_t locked_layout);

    int (*state_key_get_syms)(struct xkb_state *state, xkb_keycode_t key,
                              const xkb_keysym_t **syms_out);

    int (*state_key_get_utf8)(struct xkb_state *state, xkb_keycode_t key,
                              char *buffer, size_t size);

    uint32_t (*state_key_get_utf32)(struct xkb_state *state, xkb_keycode_t key);

    xkb_keysym_t (*state_key_get_one_sym)(struct xkb_state *state, xkb_keycode_t key);

    xkb_layout_index_t (*state_key_get_layout)(struct xkb_state *state, xkb_keycode_t key);

    xkb_level_index_t (*state_key_get_level)(struct xkb_state *state, xkb_keycode_t key,
                                             xkb_layout_index_t layout);

    xkb_mod_mask_t (*state_serialize_mods)(struct xkb_state *state,
                                           enum xkb_state_component components);

    xkb_layout_index_t (*state_serialize_layout)(struct xkb_state *state,
                                                 enum xkb_state_component components);

    int (*state_mod_name_is_active)(struct xkb_state *state, const char *name,
                                    enum xkb_state_component type);

    int (*state_mod_names_are_active)(struct xkb_state *state,
                                      enum xkb_state_component type,
                                      enum xkb_state_match match,
                                      ...);

    int (*state_mod_index_is_active)(struct xkb_state *state, xkb_mod_index_t idx,
                                     enum xkb_state_component type);

    int (*state_mod_indices_are_active)(struct xkb_state *state,
                                        enum xkb_state_component type,
                                        enum xkb_state_match match,
                                        ...);

    xkb_mod_mask_t (*state_key_get_consumed_mods2)(struct xkb_state *state, xkb_keycode_t key,
                                                   enum xkb_consumed_mode mode);

    xkb_mod_mask_t (*state_key_get_consumed_mods)(struct xkb_state *state, xkb_keycode_t key);

    int (*state_mod_index_is_consumed2)(struct xkb_state *state,
                                        xkb_keycode_t key,
                                        xkb_mod_index_t idx,
                                        enum xkb_consumed_mode mode);

    int (*state_mod_index_is_consumed)(struct xkb_state *state, xkb_keycode_t key,
                                       xkb_mod_index_t idx);

    xkb_mod_mask_t
    (*state_mod_mask_remove_consumed)(struct xkb_state *state, xkb_keycode_t key,
                                      xkb_mod_mask_t mask);

    int (*state_layout_name_is_active)(struct xkb_state *state, const char *name,
                                       enum xkb_state_component type);

    int (*state_layout_index_is_active)(struct xkb_state *state,
                                        xkb_layout_index_t idx,
                                        enum xkb_state_component type);

    int (*state_led_name_is_active)(struct xkb_state *state, const char *name);

    int (*state_led_index_is_active)(struct xkb_state *state, xkb_led_index_t idx);

    struct xkb_compose_table *
    (*compose_table_new_from_locale)(struct xkb_context *context,
                                     const char *locale,
                                     enum xkb_compose_compile_flags flags);

    struct xkb_compose_table *
    (*compose_table_new_from_file)(struct xkb_context *context,
                                   FILE *file,
                                   const char *locale,
                                   enum xkb_compose_format format,
                                   enum xkb_compose_compile_flags flags);

    struct xkb_compose_table *
    (*compose_table_new_from_buffer)(struct xkb_context *context,
                                     const char *buffer, size_t length,
                                     const char *locale,
                                     enum xkb_compose_format format,
                                     enum xkb_compose_compile_flags flags);

    struct xkb_compose_table *
    (*compose_table_ref)(struct xkb_compose_table *table);

    void
    (*compose_table_unref)(struct xkb_compose_table *table);

    struct xkb_compose_state *
    (*compose_state_new)(struct xkb_compose_table *table,
                         enum xkb_compose_state_flags flags);

    struct xkb_compose_state *
    (*compose_state_ref)(struct xkb_compose_state *state);

    void
    (*compose_state_unref)(struct xkb_compose_state *state);

    struct xkb_compose_table *
    (*compose_state_get_compose_table)(struct xkb_compose_state *state);

    enum xkb_compose_feed_result
    (*compose_state_feed)(struct xkb_compose_state *state,
                          xkb_keysym_t keysym);

    void
    (*compose_state_reset)(struct xkb_compose_state *state);

    enum xkb_compose_status
    (*compose_state_get_status)(struct xkb_compose_state *state);

    int (*compose_state_get_utf8)(struct xkb_compose_state *state,
                                  char *buffer, size_t size);

    xkb_keysym_t
    (*compose_state_get_one_sym)(struct xkb_compose_state *state);
} xkb;

static inline void
clearDLError(void) {
    (void) dlerror();
}

static void *
xkbcommonBindSym(JNIEnv *env, void *handle, const char *sym_name) {
    clearDLError();
    void *addr = dlsym(handle, sym_name);
    if (!addr) {
        const char *errorMessage = dlerror();
        JNU_ThrowByName(env, "java/lang/UnsatisfiedLinkError", errorMessage);
    }

    return addr;
}

#define BIND_XKB_SYM(name)                                      \
    do {                                                        \
        xkb.name = xkbcommonBindSym(env, handle, "xkb_" #name); \
        if (!xkb.name) return false;                            \
    } while (0)

static bool
xkbcommonLoad(JNIEnv *env) {
    void *handle = dlopen(JNI_LIB_NAME("xkbcommon"), RTLD_LAZY | RTLD_LOCAL);
    if (!handle) {
        handle = dlopen(VERSIONED_JNI_LIB_NAME("xkbcommon", "0"), RTLD_LAZY | RTLD_LOCAL);
    }
    if (!handle) {
        JNU_ThrowByNameWithMessageAndLastError(env, "java/lang/UnsatisfiedLinkError",
                                               JNI_LIB_NAME("xkbcommon"));
        return false;
    }

    // These symbols are present in libxkbcommon 0.8.2, which is a version
    // distributed with Debian 9, the oldest supported Debian release at the time of writing
    // The following symbols are missing compared to libxkbcommon 1.5.0:
    //   - xkb_utf32_to_keysym
    //   - xkb_keymap_key_get_mods_for_level

    BIND_XKB_SYM(keysym_get_name);
    BIND_XKB_SYM(keysym_from_name);
    BIND_XKB_SYM(keysym_to_utf8);
    BIND_XKB_SYM(keysym_to_utf32);
    BIND_XKB_SYM(keysym_to_upper);
    BIND_XKB_SYM(keysym_to_lower);
    BIND_XKB_SYM(context_new);
    BIND_XKB_SYM(context_ref);
    BIND_XKB_SYM(context_unref);
    BIND_XKB_SYM(context_set_user_data);
    BIND_XKB_SYM(context_get_user_data);
    BIND_XKB_SYM(context_include_path_append);
    BIND_XKB_SYM(context_include_path_append_default);
    BIND_XKB_SYM(context_include_path_reset_defaults);
    BIND_XKB_SYM(context_include_path_clear);
    BIND_XKB_SYM(context_num_include_paths);
    BIND_XKB_SYM(context_include_path_get);
    BIND_XKB_SYM(context_set_log_level);
    BIND_XKB_SYM(context_get_log_level);
    BIND_XKB_SYM(context_set_log_verbosity);
    BIND_XKB_SYM(context_get_log_verbosity);
    BIND_XKB_SYM(context_set_log_fn);
    BIND_XKB_SYM(keymap_new_from_names);
    BIND_XKB_SYM(keymap_new_from_file);
    BIND_XKB_SYM(keymap_new_from_string);
    BIND_XKB_SYM(keymap_new_from_buffer);
    BIND_XKB_SYM(keymap_ref);
    BIND_XKB_SYM(keymap_unref);
    BIND_XKB_SYM(keymap_get_as_string);
    BIND_XKB_SYM(keymap_min_keycode);
    BIND_XKB_SYM(keymap_max_keycode);
    BIND_XKB_SYM(keymap_key_for_each);
    BIND_XKB_SYM(keymap_key_get_name);
    BIND_XKB_SYM(keymap_key_by_name);
    BIND_XKB_SYM(keymap_num_mods);
    BIND_XKB_SYM(keymap_mod_get_name);
    BIND_XKB_SYM(keymap_mod_get_index);
    BIND_XKB_SYM(keymap_num_layouts);
    BIND_XKB_SYM(keymap_layout_get_name);
    BIND_XKB_SYM(keymap_layout_get_index);
    BIND_XKB_SYM(keymap_num_leds);
    BIND_XKB_SYM(keymap_led_get_name);
    BIND_XKB_SYM(keymap_led_get_index);
    BIND_XKB_SYM(keymap_num_layouts_for_key);
    BIND_XKB_SYM(keymap_num_levels_for_key);
    BIND_XKB_SYM(keymap_key_get_syms_by_level);
    BIND_XKB_SYM(keymap_key_repeats);
    BIND_XKB_SYM(state_new);
    BIND_XKB_SYM(state_ref);
    BIND_XKB_SYM(state_unref);
    BIND_XKB_SYM(state_get_keymap);
    BIND_XKB_SYM(state_update_key);
    BIND_XKB_SYM(state_update_mask);
    BIND_XKB_SYM(state_key_get_syms);
    BIND_XKB_SYM(state_key_get_utf8);
    BIND_XKB_SYM(state_key_get_utf32);
    BIND_XKB_SYM(state_key_get_one_sym);
    BIND_XKB_SYM(state_key_get_layout);
    BIND_XKB_SYM(state_key_get_level);
    BIND_XKB_SYM(state_serialize_mods);
    BIND_XKB_SYM(state_serialize_layout);
    BIND_XKB_SYM(state_mod_name_is_active);
    BIND_XKB_SYM(state_mod_names_are_active);
    BIND_XKB_SYM(state_mod_index_is_active);
    BIND_XKB_SYM(state_mod_indices_are_active);
    BIND_XKB_SYM(state_key_get_consumed_mods2);
    BIND_XKB_SYM(state_key_get_consumed_mods);
    BIND_XKB_SYM(state_mod_index_is_consumed2);
    BIND_XKB_SYM(state_mod_index_is_consumed);
    BIND_XKB_SYM(state_mod_mask_remove_consumed);
    BIND_XKB_SYM(state_layout_name_is_active);
    BIND_XKB_SYM(state_layout_index_is_active);
    BIND_XKB_SYM(state_led_name_is_active);
    BIND_XKB_SYM(state_led_index_is_active);
    BIND_XKB_SYM(compose_table_new_from_locale);
    BIND_XKB_SYM(compose_table_new_from_file);
    BIND_XKB_SYM(compose_table_new_from_buffer);
    BIND_XKB_SYM(compose_table_ref);
    BIND_XKB_SYM(compose_table_unref);
    BIND_XKB_SYM(compose_state_new);
    BIND_XKB_SYM(compose_state_ref);
    BIND_XKB_SYM(compose_state_unref);
    BIND_XKB_SYM(compose_state_get_compose_table);
    BIND_XKB_SYM(compose_state_feed);
    BIND_XKB_SYM(compose_state_reset);
    BIND_XKB_SYM(compose_state_get_status);
    BIND_XKB_SYM(compose_state_get_utf8);
    BIND_XKB_SYM(compose_state_get_one_sym);

    return true;
}

static const struct KeysymToJavaKeycodeMapItem {
    xkb_keysym_t keysym;
    int keycode;
    int location;
} keysymtoJavaKeycodeMap[] = {
        {0x0020 /* XKB_KEY_space */,                  java_awt_event_KeyEvent_VK_SPACE,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0021 /* XKB_KEY_exclam */,                 java_awt_event_KeyEvent_VK_EXCLAMATION_MARK,          java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0022 /* XKB_KEY_quotedbl */,               java_awt_event_KeyEvent_VK_QUOTEDBL,                  java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0023 /* XKB_KEY_numbersign */,             java_awt_event_KeyEvent_VK_NUMBER_SIGN,               java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0024 /* XKB_KEY_dollar */,                 java_awt_event_KeyEvent_VK_DOLLAR,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0026 /* XKB_KEY_ampersand */,              java_awt_event_KeyEvent_VK_AMPERSAND,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0027 /* XKB_KEY_apostrophe */,             java_awt_event_KeyEvent_VK_QUOTE,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0028 /* XKB_KEY_parenleft */,              java_awt_event_KeyEvent_VK_LEFT_PARENTHESIS,          java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0029 /* XKB_KEY_parenright */,             java_awt_event_KeyEvent_VK_RIGHT_PARENTHESIS,         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x002a /* XKB_KEY_asterisk */,               java_awt_event_KeyEvent_VK_ASTERISK,                  java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x002b /* XKB_KEY_plus */,                   java_awt_event_KeyEvent_VK_PLUS,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x002c /* XKB_KEY_comma */,                  java_awt_event_KeyEvent_VK_COMMA,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x002d /* XKB_KEY_minus */,                  java_awt_event_KeyEvent_VK_MINUS,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x002e /* XKB_KEY_period */,                 java_awt_event_KeyEvent_VK_PERIOD,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x002f /* XKB_KEY_slash */,                  java_awt_event_KeyEvent_VK_SLASH,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0030 /* XKB_KEY_0 */,                      java_awt_event_KeyEvent_VK_0,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0031 /* XKB_KEY_1 */,                      java_awt_event_KeyEvent_VK_1,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0032 /* XKB_KEY_2 */,                      java_awt_event_KeyEvent_VK_2,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0033 /* XKB_KEY_3 */,                      java_awt_event_KeyEvent_VK_3,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0034 /* XKB_KEY_4 */,                      java_awt_event_KeyEvent_VK_4,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0035 /* XKB_KEY_5 */,                      java_awt_event_KeyEvent_VK_5,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0036 /* XKB_KEY_6 */,                      java_awt_event_KeyEvent_VK_6,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0037 /* XKB_KEY_7 */,                      java_awt_event_KeyEvent_VK_7,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0038 /* XKB_KEY_8 */,                      java_awt_event_KeyEvent_VK_8,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0039 /* XKB_KEY_9 */,                      java_awt_event_KeyEvent_VK_9,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x003a /* XKB_KEY_colon */,                  java_awt_event_KeyEvent_VK_COLON,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x003b /* XKB_KEY_semicolon */,              java_awt_event_KeyEvent_VK_SEMICOLON,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x003c /* XKB_KEY_less */,                   java_awt_event_KeyEvent_VK_LESS,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x003d /* XKB_KEY_equal */,                  java_awt_event_KeyEvent_VK_EQUALS,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x003e /* XKB_KEY_greater */,                java_awt_event_KeyEvent_VK_GREATER,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0040 /* XKB_KEY_at */,                     java_awt_event_KeyEvent_VK_AT,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x005b /* XKB_KEY_bracketleft */,            java_awt_event_KeyEvent_VK_OPEN_BRACKET,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x005c /* XKB_KEY_backslash */,              java_awt_event_KeyEvent_VK_BACK_SLASH,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x005d /* XKB_KEY_bracketright */,           java_awt_event_KeyEvent_VK_CLOSE_BRACKET,             java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x005e /* XKB_KEY_asciicircum */,            java_awt_event_KeyEvent_VK_CIRCUMFLEX,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x005f /* XKB_KEY_underscore */,             java_awt_event_KeyEvent_VK_UNDERSCORE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0060 /* XKB_KEY_grave */,                  java_awt_event_KeyEvent_VK_BACK_QUOTE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0061 /* XKB_KEY_a */,                      java_awt_event_KeyEvent_VK_A,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0062 /* XKB_KEY_b */,                      java_awt_event_KeyEvent_VK_B,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0063 /* XKB_KEY_c */,                      java_awt_event_KeyEvent_VK_C,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0064 /* XKB_KEY_d */,                      java_awt_event_KeyEvent_VK_D,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0065 /* XKB_KEY_e */,                      java_awt_event_KeyEvent_VK_E,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0066 /* XKB_KEY_f */,                      java_awt_event_KeyEvent_VK_F,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0067 /* XKB_KEY_g */,                      java_awt_event_KeyEvent_VK_G,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0068 /* XKB_KEY_h */,                      java_awt_event_KeyEvent_VK_H,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0069 /* XKB_KEY_i */,                      java_awt_event_KeyEvent_VK_I,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x006a /* XKB_KEY_j */,                      java_awt_event_KeyEvent_VK_J,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x006b /* XKB_KEY_k */,                      java_awt_event_KeyEvent_VK_K,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x006c /* XKB_KEY_l */,                      java_awt_event_KeyEvent_VK_L,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x006d /* XKB_KEY_m */,                      java_awt_event_KeyEvent_VK_M,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x006e /* XKB_KEY_n */,                      java_awt_event_KeyEvent_VK_N,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x006f /* XKB_KEY_o */,                      java_awt_event_KeyEvent_VK_O,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0070 /* XKB_KEY_p */,                      java_awt_event_KeyEvent_VK_P,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0071 /* XKB_KEY_q */,                      java_awt_event_KeyEvent_VK_Q,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0072 /* XKB_KEY_r */,                      java_awt_event_KeyEvent_VK_R,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0073 /* XKB_KEY_s */,                      java_awt_event_KeyEvent_VK_S,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0074 /* XKB_KEY_t */,                      java_awt_event_KeyEvent_VK_T,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0075 /* XKB_KEY_u */,                      java_awt_event_KeyEvent_VK_U,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0076 /* XKB_KEY_v */,                      java_awt_event_KeyEvent_VK_V,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0077 /* XKB_KEY_w */,                      java_awt_event_KeyEvent_VK_W,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0078 /* XKB_KEY_x */,                      java_awt_event_KeyEvent_VK_X,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x0079 /* XKB_KEY_y */,                      java_awt_event_KeyEvent_VK_Y,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x007a /* XKB_KEY_z */,                      java_awt_event_KeyEvent_VK_Z,                         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x007b /* XKB_KEY_braceleft */,              java_awt_event_KeyEvent_VK_BRACELEFT,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x007d /* XKB_KEY_braceright */,             java_awt_event_KeyEvent_VK_BRACERIGHT,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x00a1 /* XKB_KEY_exclamdown */,             java_awt_event_KeyEvent_VK_INVERTED_EXCLAMATION_MARK, java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe03 /* XKB_KEY_ISO_Level3_Shift */,       java_awt_event_KeyEvent_VK_ALT_GRAPH,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe20 /* XKB_KEY_ISO_Left_Tab */,           java_awt_event_KeyEvent_VK_TAB,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe50 /* XKB_KEY_dead_grave */,             java_awt_event_KeyEvent_VK_DEAD_GRAVE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe51 /* XKB_KEY_dead_acute */,             java_awt_event_KeyEvent_VK_DEAD_ACUTE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe52 /* XKB_KEY_dead_circumflex */,        java_awt_event_KeyEvent_VK_DEAD_CIRCUMFLEX,           java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe53 /* XKB_KEY_dead_tilde */,             java_awt_event_KeyEvent_VK_DEAD_TILDE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe54 /* XKB_KEY_dead_macron */,            java_awt_event_KeyEvent_VK_DEAD_MACRON,               java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe55 /* XKB_KEY_dead_breve */,             java_awt_event_KeyEvent_VK_DEAD_BREVE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe56 /* XKB_KEY_dead_abovedot */,          java_awt_event_KeyEvent_VK_DEAD_ABOVEDOT,             java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe57 /* XKB_KEY_dead_diaeresis */,         java_awt_event_KeyEvent_VK_DEAD_DIAERESIS,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe58 /* XKB_KEY_dead_abovering */,         java_awt_event_KeyEvent_VK_DEAD_ABOVERING,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe59 /* XKB_KEY_dead_doubleacute */,       java_awt_event_KeyEvent_VK_DEAD_DOUBLEACUTE,          java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe5a /* XKB_KEY_dead_caron */,             java_awt_event_KeyEvent_VK_DEAD_CARON,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe5b /* XKB_KEY_dead_cedilla */,           java_awt_event_KeyEvent_VK_DEAD_CEDILLA,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe5c /* XKB_KEY_dead_ogonek */,            java_awt_event_KeyEvent_VK_DEAD_OGONEK,               java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe5d /* XKB_KEY_dead_iota */,              java_awt_event_KeyEvent_VK_DEAD_IOTA,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe5e /* XKB_KEY_dead_voiced_sound */,      java_awt_event_KeyEvent_VK_DEAD_VOICED_SOUND,         java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xfe5f /* XKB_KEY_dead_semivoiced_sound */,  java_awt_event_KeyEvent_VK_DEAD_SEMIVOICED_SOUND,     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff08 /* XKB_KEY_BackSpace */,              java_awt_event_KeyEvent_VK_BACK_SPACE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff09 /* XKB_KEY_Tab */,                    java_awt_event_KeyEvent_VK_TAB,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff0a /* XKB_KEY_Linefeed */,               java_awt_event_KeyEvent_VK_ENTER,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff0b /* XKB_KEY_Clear */,                  java_awt_event_KeyEvent_VK_CLEAR,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff0d /* XKB_KEY_Return */,                 java_awt_event_KeyEvent_VK_ENTER,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff13 /* XKB_KEY_Pause */,                  java_awt_event_KeyEvent_VK_PAUSE,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff14 /* XKB_KEY_Scroll_Lock */,            java_awt_event_KeyEvent_VK_SCROLL_LOCK,               java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff1b /* XKB_KEY_Escape */,                 java_awt_event_KeyEvent_VK_ESCAPE,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff20 /* XKB_KEY_Multi_key */,              java_awt_event_KeyEvent_VK_COMPOSE,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff21 /* XKB_KEY_Kanji */,                  java_awt_event_KeyEvent_VK_CONVERT,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff22 /* XKB_KEY_Muhenkan */,               java_awt_event_KeyEvent_VK_NONCONVERT,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff23 /* XKB_KEY_Henkan_Mode */,            java_awt_event_KeyEvent_VK_INPUT_METHOD_ON_OFF,       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff24 /* XKB_KEY_Romaji */,                 java_awt_event_KeyEvent_VK_JAPANESE_ROMAN,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff25 /* XKB_KEY_Hiragana */,               java_awt_event_KeyEvent_VK_HIRAGANA,                  java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff26 /* XKB_KEY_Katakana */,               java_awt_event_KeyEvent_VK_KATAKANA,                  java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff28 /* XKB_KEY_Zenkaku */,                java_awt_event_KeyEvent_VK_FULL_WIDTH,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff29 /* XKB_KEY_Hankaku */,                java_awt_event_KeyEvent_VK_HALF_WIDTH,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff2d /* XKB_KEY_Kana_Lock */,              java_awt_event_KeyEvent_VK_KANA_LOCK,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff2e /* XKB_KEY_Kana_Shift */,             java_awt_event_KeyEvent_VK_KANA,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff2f /* XKB_KEY_Eisu_Shift */,             java_awt_event_KeyEvent_VK_ALPHANUMERIC,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff30 /* XKB_KEY_Eisu_toggle */,            java_awt_event_KeyEvent_VK_ALPHANUMERIC,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff37 /* XKB_KEY_Kanji_Bangou */,           java_awt_event_KeyEvent_VK_CODE_INPUT,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff3d /* XKB_KEY_Zen_Koho */,               java_awt_event_KeyEvent_VK_ALL_CANDIDATES,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff3e /* XKB_KEY_Mae_Koho */,               java_awt_event_KeyEvent_VK_PREVIOUS_CANDIDATE,        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff50 /* XKB_KEY_Home */,                   java_awt_event_KeyEvent_VK_HOME,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff51 /* XKB_KEY_Left */,                   java_awt_event_KeyEvent_VK_LEFT,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff52 /* XKB_KEY_Up */,                     java_awt_event_KeyEvent_VK_UP,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff53 /* XKB_KEY_Right */,                  java_awt_event_KeyEvent_VK_RIGHT,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff54 /* XKB_KEY_Down */,                   java_awt_event_KeyEvent_VK_DOWN,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff55 /* XKB_KEY_Page_Up */,                java_awt_event_KeyEvent_VK_PAGE_UP,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff56 /* XKB_KEY_Page_Down */,              java_awt_event_KeyEvent_VK_PAGE_DOWN,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff57 /* XKB_KEY_End */,                    java_awt_event_KeyEvent_VK_END,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff58 /* XKB_KEY_Begin */,                  java_awt_event_KeyEvent_VK_BEGIN,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff61 /* XKB_KEY_Print */,                  java_awt_event_KeyEvent_VK_PRINTSCREEN,               java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff62 /* XKB_KEY_Execute */,                java_awt_event_KeyEvent_VK_ACCEPT,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff63 /* XKB_KEY_Insert */,                 java_awt_event_KeyEvent_VK_INSERT,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff65 /* XKB_KEY_Undo */,                   java_awt_event_KeyEvent_VK_UNDO,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff66 /* XKB_KEY_Redo */,                   java_awt_event_KeyEvent_VK_AGAIN,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff67 /* XKB_KEY_Menu */,                   java_awt_event_KeyEvent_VK_CONTEXT_MENU,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff68 /* XKB_KEY_Find */,                   java_awt_event_KeyEvent_VK_FIND,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff69 /* XKB_KEY_Cancel */,                 java_awt_event_KeyEvent_VK_CANCEL,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff6a /* XKB_KEY_Help */,                   java_awt_event_KeyEvent_VK_HELP,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff7e /* XKB_KEY_Mode_switch */,            java_awt_event_KeyEvent_VK_ALT_GRAPH,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xff7f /* XKB_KEY_Num_Lock */,               java_awt_event_KeyEvent_VK_NUM_LOCK,                  java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff80 /* XKB_KEY_KP_Space */,               java_awt_event_KeyEvent_VK_SPACE,                     java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff89 /* XKB_KEY_KP_Tab */,                 java_awt_event_KeyEvent_VK_TAB,                       java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff8d /* XKB_KEY_KP_Enter */,               java_awt_event_KeyEvent_VK_ENTER,                     java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff95 /* XKB_KEY_KP_Home */,                java_awt_event_KeyEvent_VK_HOME,                      java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff96 /* XKB_KEY_KP_Left */,                java_awt_event_KeyEvent_VK_KP_LEFT,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff97 /* XKB_KEY_KP_Up */,                  java_awt_event_KeyEvent_VK_KP_UP,                     java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff98 /* XKB_KEY_KP_Right */,               java_awt_event_KeyEvent_VK_KP_RIGHT,                  java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff99 /* XKB_KEY_KP_Down */,                java_awt_event_KeyEvent_VK_KP_DOWN,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff9a /* XKB_KEY_KP_Page_Up */,             java_awt_event_KeyEvent_VK_PAGE_UP,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff9b /* XKB_KEY_KP_Page_Down */,           java_awt_event_KeyEvent_VK_PAGE_DOWN,                 java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff9c /* XKB_KEY_KP_End */,                 java_awt_event_KeyEvent_VK_END,                       java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff9d /* XKB_KEY_KP_Begin */,               java_awt_event_KeyEvent_VK_BEGIN,                     java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff9e /* XKB_KEY_KP_Insert */,              java_awt_event_KeyEvent_VK_INSERT,                    java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xff9f /* XKB_KEY_KP_Delete */,              java_awt_event_KeyEvent_VK_DELETE,                    java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffaa /* XKB_KEY_KP_Multiply */,            java_awt_event_KeyEvent_VK_MULTIPLY,                  java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffab /* XKB_KEY_KP_Add */,                 java_awt_event_KeyEvent_VK_ADD,                       java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffac /* XKB_KEY_KP_Separator */,           java_awt_event_KeyEvent_VK_SEPARATOR,                 java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffad /* XKB_KEY_KP_Subtract */,            java_awt_event_KeyEvent_VK_SUBTRACT,                  java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffae /* XKB_KEY_KP_Decimal */,             java_awt_event_KeyEvent_VK_DECIMAL,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffaf /* XKB_KEY_KP_Divide */,              java_awt_event_KeyEvent_VK_DIVIDE,                    java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb0 /* XKB_KEY_KP_0 */,                   java_awt_event_KeyEvent_VK_NUMPAD0,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb1 /* XKB_KEY_KP_1 */,                   java_awt_event_KeyEvent_VK_NUMPAD1,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb2 /* XKB_KEY_KP_2 */,                   java_awt_event_KeyEvent_VK_NUMPAD2,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb3 /* XKB_KEY_KP_3 */,                   java_awt_event_KeyEvent_VK_NUMPAD3,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb4 /* XKB_KEY_KP_4 */,                   java_awt_event_KeyEvent_VK_NUMPAD4,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb5 /* XKB_KEY_KP_5 */,                   java_awt_event_KeyEvent_VK_NUMPAD5,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb6 /* XKB_KEY_KP_6 */,                   java_awt_event_KeyEvent_VK_NUMPAD6,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb7 /* XKB_KEY_KP_7 */,                   java_awt_event_KeyEvent_VK_NUMPAD7,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb8 /* XKB_KEY_KP_8 */,                   java_awt_event_KeyEvent_VK_NUMPAD8,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffb9 /* XKB_KEY_KP_9 */,                   java_awt_event_KeyEvent_VK_NUMPAD9,                   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffbd /* XKB_KEY_KP_Equal */,               java_awt_event_KeyEvent_VK_EQUALS,                    java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffbe /* XKB_KEY_F1 */,                     java_awt_event_KeyEvent_VK_F1,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffbf /* XKB_KEY_F2 */,                     java_awt_event_KeyEvent_VK_F2,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc0 /* XKB_KEY_F3 */,                     java_awt_event_KeyEvent_VK_F3,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc1 /* XKB_KEY_F4 */,                     java_awt_event_KeyEvent_VK_F4,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc2 /* XKB_KEY_F5 */,                     java_awt_event_KeyEvent_VK_F5,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc3 /* XKB_KEY_F6 */,                     java_awt_event_KeyEvent_VK_F6,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc4 /* XKB_KEY_F7 */,                     java_awt_event_KeyEvent_VK_F7,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc5 /* XKB_KEY_F8 */,                     java_awt_event_KeyEvent_VK_F8,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc6 /* XKB_KEY_F9 */,                     java_awt_event_KeyEvent_VK_F9,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc7 /* XKB_KEY_F10 */,                    java_awt_event_KeyEvent_VK_F10,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc8 /* XKB_KEY_F11 */,                    java_awt_event_KeyEvent_VK_F11,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffc9 /* XKB_KEY_F12 */,                    java_awt_event_KeyEvent_VK_F12,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffca /* XKB_KEY_F13 */,                    java_awt_event_KeyEvent_VK_F13,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffcb /* XKB_KEY_F14 */,                    java_awt_event_KeyEvent_VK_F14,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffcc /* XKB_KEY_F15 */,                    java_awt_event_KeyEvent_VK_F15,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffcd /* XKB_KEY_F16 */,                    java_awt_event_KeyEvent_VK_F16,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffce /* XKB_KEY_F17 */,                    java_awt_event_KeyEvent_VK_F17,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffcf /* XKB_KEY_F18 */,                    java_awt_event_KeyEvent_VK_F18,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd0 /* XKB_KEY_F19 */,                    java_awt_event_KeyEvent_VK_F19,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd1 /* XKB_KEY_F20 */,                    java_awt_event_KeyEvent_VK_F20,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd2 /* XKB_KEY_F21 */,                    java_awt_event_KeyEvent_VK_F21,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd3 /* XKB_KEY_F22 */,                    java_awt_event_KeyEvent_VK_F22,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd4 /* XKB_KEY_F23 */,                    java_awt_event_KeyEvent_VK_F23,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd5 /* XKB_KEY_F24 */,                    java_awt_event_KeyEvent_VK_F24,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffd6 /* XKB_KEY_F25 */,                    java_awt_event_KeyEvent_VK_DIVIDE,                    java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffd7 /* XKB_KEY_F26 */,                    java_awt_event_KeyEvent_VK_MULTIPLY,                  java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD},
        {0xffd8 /* XKB_KEY_R7 */,                     java_awt_event_KeyEvent_VK_HOME,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffda /* XKB_KEY_R9 */,                     java_awt_event_KeyEvent_VK_PAGE_UP,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffde /* XKB_KEY_R13 */,                    java_awt_event_KeyEvent_VK_END,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffe0 /* XKB_KEY_R15 */,                    java_awt_event_KeyEvent_VK_PAGE_DOWN,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffe1 /* XKB_KEY_Shift_L */,                java_awt_event_KeyEvent_VK_SHIFT,                     java_awt_event_KeyEvent_KEY_LOCATION_LEFT},
        {0xffe2 /* XKB_KEY_Shift_R */,                java_awt_event_KeyEvent_VK_SHIFT,                     java_awt_event_KeyEvent_KEY_LOCATION_RIGHT},
        {0xffe3 /* XKB_KEY_Control_L */,              java_awt_event_KeyEvent_VK_CONTROL,                   java_awt_event_KeyEvent_KEY_LOCATION_LEFT},
        {0xffe4 /* XKB_KEY_Control_R */,              java_awt_event_KeyEvent_VK_CONTROL,                   java_awt_event_KeyEvent_KEY_LOCATION_RIGHT},
        {0xffe5 /* XKB_KEY_Caps_Lock */,              java_awt_event_KeyEvent_VK_CAPS_LOCK,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffe6 /* XKB_KEY_Shift_Lock */,             java_awt_event_KeyEvent_VK_CAPS_LOCK,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffe7 /* XKB_KEY_Meta_L */,                 java_awt_event_KeyEvent_VK_META,                      java_awt_event_KeyEvent_KEY_LOCATION_LEFT},
        {0xffe8 /* XKB_KEY_Meta_R */,                 java_awt_event_KeyEvent_VK_META,                      java_awt_event_KeyEvent_KEY_LOCATION_RIGHT},
        {0xffe9 /* XKB_KEY_Alt_L */,                  java_awt_event_KeyEvent_VK_ALT,                       java_awt_event_KeyEvent_KEY_LOCATION_LEFT},
        {0xffea /* XKB_KEY_Alt_R */,                  java_awt_event_KeyEvent_VK_ALT,                       java_awt_event_KeyEvent_KEY_LOCATION_RIGHT},
        {0xffeb /* XKB_KEY_Super_L */,                java_awt_event_KeyEvent_VK_WINDOWS,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffec /* XKB_KEY_Super_R */,                java_awt_event_KeyEvent_VK_WINDOWS,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0xffff /* XKB_KEY_Delete */,                 java_awt_event_KeyEvent_VK_DELETE,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x100000a8 /* XKB_KEY_hpmute_acute */,       java_awt_event_KeyEvent_VK_DEAD_ACUTE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x100000a9 /* XKB_KEY_hpmute_grave */,       java_awt_event_KeyEvent_VK_DEAD_GRAVE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x100000aa /* XKB_KEY_hpmute_asciicircum */, java_awt_event_KeyEvent_VK_DEAD_CIRCUMFLEX,           java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x100000ab /* XKB_KEY_hpmute_diaeresis */,   java_awt_event_KeyEvent_VK_DEAD_DIAERESIS,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x100000ac /* XKB_KEY_hpmute_asciitilde */,  java_awt_event_KeyEvent_VK_DEAD_TILDE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000fe22 /* XKB_KEY_Ddiaeresis */,         java_awt_event_KeyEvent_VK_DEAD_DIAERESIS,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000fe27 /* XKB_KEY_Dacute_accent */,      java_awt_event_KeyEvent_VK_DEAD_ACUTE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000fe2c /* XKB_KEY_Dcedilla_accent */,    java_awt_event_KeyEvent_VK_DEAD_CEDILLA,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000fe5e /* XKB_KEY_Dcircumflex_accent */, java_awt_event_KeyEvent_VK_DEAD_CIRCUMFLEX,           java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000fe60 /* XKB_KEY_Dgrave_accent */,      java_awt_event_KeyEvent_VK_DEAD_GRAVE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000fe7e /* XKB_KEY_Dtilde */,             java_awt_event_KeyEvent_VK_DEAD_TILDE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000feb0 /* XKB_KEY_Dring_accent */,       java_awt_event_KeyEvent_VK_DEAD_ABOVERING,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000ff02 /* XKB_KEY_apCopy */,             java_awt_event_KeyEvent_VK_COPY,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000ff03 /* XKB_KEY_apCut */,              java_awt_event_KeyEvent_VK_CUT,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1000ff04 /* XKB_KEY_apPaste */,            java_awt_event_KeyEvent_VK_PASTE,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff02 /* XKB_KEY_osfCopy */,            java_awt_event_KeyEvent_VK_COPY,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff03 /* XKB_KEY_osfCut */,             java_awt_event_KeyEvent_VK_CUT,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff04 /* XKB_KEY_osfPaste */,           java_awt_event_KeyEvent_VK_PASTE,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff08 /* XKB_KEY_osfBackSpace */,       java_awt_event_KeyEvent_VK_BACK_SPACE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff0b /* XKB_KEY_osfClear */,           java_awt_event_KeyEvent_VK_CLEAR,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff1b /* XKB_KEY_osfEscape */,          java_awt_event_KeyEvent_VK_ESCAPE,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff41 /* XKB_KEY_osfPageUp */,          java_awt_event_KeyEvent_VK_PAGE_UP,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff42 /* XKB_KEY_osfPageDown */,        java_awt_event_KeyEvent_VK_PAGE_DOWN,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff51 /* XKB_KEY_osfLeft */,            java_awt_event_KeyEvent_VK_LEFT,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff52 /* XKB_KEY_osfUp */,              java_awt_event_KeyEvent_VK_UP,                        java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff53 /* XKB_KEY_osfRight */,           java_awt_event_KeyEvent_VK_RIGHT,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff54 /* XKB_KEY_osfDown */,            java_awt_event_KeyEvent_VK_DOWN,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff55 /* XKB_KEY_osfPrior */,           java_awt_event_KeyEvent_VK_PAGE_UP,                   java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff56 /* XKB_KEY_osfNext */,            java_awt_event_KeyEvent_VK_PAGE_DOWN,                 java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff57 /* XKB_KEY_osfEndLine */,         java_awt_event_KeyEvent_VK_END,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff63 /* XKB_KEY_osfInsert */,          java_awt_event_KeyEvent_VK_INSERT,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff65 /* XKB_KEY_osfUndo */,            java_awt_event_KeyEvent_VK_UNDO,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff69 /* XKB_KEY_osfCancel */,          java_awt_event_KeyEvent_VK_CANCEL,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ff6a /* XKB_KEY_osfHelp */,            java_awt_event_KeyEvent_VK_HELP,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1004ffff /* XKB_KEY_osfDelete */,          java_awt_event_KeyEvent_VK_DELETE,                    java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff00 /* XKB_KEY_SunFA_Grave */,        java_awt_event_KeyEvent_VK_DEAD_GRAVE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff01 /* XKB_KEY_SunFA_Circum */,       java_awt_event_KeyEvent_VK_DEAD_CIRCUMFLEX,           java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff02 /* XKB_KEY_SunFA_Tilde */,        java_awt_event_KeyEvent_VK_DEAD_TILDE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff03 /* XKB_KEY_SunFA_Acute */,        java_awt_event_KeyEvent_VK_DEAD_ACUTE,                java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff04 /* XKB_KEY_SunFA_Diaeresis */,    java_awt_event_KeyEvent_VK_DEAD_DIAERESIS,            java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff05 /* XKB_KEY_SunFA_Cedilla */,      java_awt_event_KeyEvent_VK_DEAD_CEDILLA,              java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff10 /* XKB_KEY_SunF36 */,             java_awt_event_KeyEvent_VK_F11,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff11 /* XKB_KEY_SunF37 */,             java_awt_event_KeyEvent_VK_F12,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff70 /* XKB_KEY_SunProps */,           java_awt_event_KeyEvent_VK_PROPS,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff72 /* XKB_KEY_SunCopy */,            java_awt_event_KeyEvent_VK_COPY,                      java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff74 /* XKB_KEY_SunPaste */,           java_awt_event_KeyEvent_VK_PASTE,                     java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0x1005ff75 /* XKB_KEY_SunCut */,             java_awt_event_KeyEvent_VK_CUT,                       java_awt_event_KeyEvent_KEY_LOCATION_STANDARD},
        {0, 0, 0}
};


// These override specific _physical_ key codes with custom XKB keysyms on any layout.
// This is only currently used to fix the handling of F13-F24
static const struct ExtraKeycodeToKeysymMapItem {
    xkb_keycode_t keycode;
    xkb_keysym_t keysym;
} extraKeycodeToKeysymMap[] = {
        {183 /* KEY_F13 */, 0xffca /* XKB_KEY_F13 */ },
        {184 /* KEY_F14 */, 0xffcb /* XKB_KEY_F14 */ },
        {185 /* KEY_F15 */, 0xffcc /* XKB_KEY_F15 */ },
        {186 /* KEY_F16 */, 0xffcd /* XKB_KEY_F16 */ },
        {187 /* KEY_F17 */, 0xffce /* XKB_KEY_F17 */ },
        {188 /* KEY_F18 */, 0xffcf /* XKB_KEY_F18 */ },
        {189 /* KEY_F19 */, 0xffd0 /* XKB_KEY_F19 */ },
        {190 /* KEY_F20 */, 0xffd1 /* XKB_KEY_F20 */ },
        {191 /* KEY_F21 */, 0xffd2 /* XKB_KEY_F21 */ },
        {192 /* KEY_F22 */, 0xffd3 /* XKB_KEY_F22 */ },
        {193 /* KEY_F23 */, 0xffd4 /* XKB_KEY_F23 */},
        {194 /* KEY_F24 */, 0xffd5 /* XKB_KEY_F24 */ },
        {0,                 0}
};

// There's no reliable way to convert a dead XKB keysym to its corresponding Unicode character.
// This is a lookup table of all the dead keys present in the default Compose file.
static const struct DeadKeysymValuesMapItem {
    xkb_keysym_t keysym;
    uint16_t noncombining;
    uint16_t combining;
} deadKeysymValuesMap[] = {
        {0xfe50 /* XKB_KEY_dead_grave */,            0x0060 /* GRAVE ACCENT */,        0x0300 /* COMBINING GRAVE ACCENT */ },
        {0xfe51 /* XKB_KEY_dead_acute */,            0x0027 /* APOSTROPHE */,          0x0301 /* COMBINING ACUTE ACCENT */ },
        {0xfe52 /* XKB_KEY_dead_circumflex */,       0x005e /* CIRCUMFLEX ACCENT */,   0x0302 /* COMBINING CIRCUMFLEX ACCENT */ },
        {0xfe53 /* XKB_KEY_dead_tilde */,            0x007e /* TILDE */,               0x0303 /* COMBINING TILDE */ },
        {0xfe54 /* XKB_KEY_dead_macron */,           0x00af /* MACRON */,              0x0304 /* COMBINING MACRON */ },
        {0xfe55 /* XKB_KEY_dead_breve */,            0x02d8 /* BREVE */,               0x0306 /* COMBINING BREVE */ },
        {0xfe56 /* XKB_KEY_dead_abovedot */,         0x02d9 /* DOT ABOVE */,           0x0307 /* COMBINING DOT ABOVE */ },
        {0xfe57 /* XKB_KEY_dead_diaeresis */,        0x0022 /* QUOTATION MARK */,      0x0308 /* COMBINING DIAERESIS */ },
        {0xfe58 /* XKB_KEY_dead_abovering */,        0x00b0 /* DEGREE SIGN */,         0x030a /* COMBINING RING ABOVE */ },
        {0xfe59 /* XKB_KEY_dead_doubleacute */,      0x02dd /* DOUBLE ACUTE ACCENT */, 0x030b /* COMBINING DOUBLE ACUTE ACCENT */ },
        {0xfe5a /* XKB_KEY_dead_caron */,            0x02c7 /* CARON */,               0x030c /* COMBINING CARON */ },
        {0xfe5b /* XKB_KEY_dead_cedilla */,          0x00b8 /* CEDILLA */,             0x0327 /* COMBINING CEDILLA */ },
        {0xfe5c /* XKB_KEY_dead_ogonek */,           0x02db /* OGONEK */,              0x0328 /* COMBINING OGONEK */ },
        {0xfe5d /* XKB_KEY_dead_iota */,             0x037a /* GREEK YPOGEGRAMMENI */, 0x0345 /* COMBINING GREEK YPOGEGRAMMENI */ },
        {0xfe5e /* XKB_KEY_dead_voiced_sound */,     0,                                0x3099 /* COMBINING KATAKANA-HIRAGANA VOICED SOUND MARK */ },
        {0xfe5f /* XKB_KEY_dead_semivoiced_sound */, 0,                                0x309a /* COMBINING KATAKANA-HIRAGANA SEMI-VOICED SOUND MARK */ },
        {0xfe60 /* XKB_KEY_dead_belowdot */,         0,                                0x0323 /* COMBINING DOT BELOW */ },
        {0xfe61 /* XKB_KEY_dead_hook */,             0,                                0x0309 /* COMBINING HOOK ABOVE */ },
        {0xfe62 /* XKB_KEY_dead_horn */,             0,                                0x031b /* COMBINING HORN */ },
        {0xfe63 /* XKB_KEY_dead_stroke */,           0x002f /* SOLIDUS */,             0x0338 /* COMBINING LONG SOLIDUS OVERLAY */ },
        {0xfe64 /* XKB_KEY_dead_psili */,            0,                                0x0313 /* COMBINING COMMA ABOVE */ },
        {0xfe65 /* XKB_KEY_dead_dasia */,            0,                                0x0314 /* COMBINING REVERSED COMMA ABOVE */ },
        {0xfe66 /* XKB_KEY_dead_doublegrave */,      0,                                0x030f /* COMBINING DOUBLE GRAVE ACCENT */ },
        {0xfe67 /* XKB_KEY_dead_belowring */,        0,                                0x0325 /* COMBINING RING BELOW */ },
        {0xfe68 /* XKB_KEY_dead_belowmacron */,      0,                                0x0331 /* COMBINING MACRON BELOW */ },
        {0xfe69 /* XKB_KEY_dead_belowcircumflex */,  0,                                0x032d /* COMBINING CIRCUMFLEX ACCENT BELOW */ },
        {0xfe6a /* XKB_KEY_dead_belowtilde */,       0,                                0x0330 /* COMBINING TILDE BELOW */ },
        {0xfe6b /* XKB_KEY_dead_belowbreve */,       0,                                0x032e /* COMBINING BREVE BELOW */ },
        {0xfe6c /* XKB_KEY_dead_belowdiaeresis */,   0,                                0x0324 /* COMBINING DIAERESIS BELOW */ },
        {0xfe6d /* XKB_KEY_dead_invertedbreve */,    0,                                0x0311 /* COMBINING INVERTED BREVE */ },
        {0xfe6e /* XKB_KEY_dead_belowcomma */,       0x002c /* COMMA */,               0x0326 /* COMBINING COMMA BELOW */ },
        {0xfe6f /* XKB_KEY_dead_currency */,         0,                                0x00a4 /* CURRENCY SIGN */ },
        {0xfe8c /* XKB_KEY_dead_greek */,            0,                                0x00b5 /* MICRO SIGN */ },
        {0,                                          0,                                0}
};

static xkb_layout_index_t
getKeyboardLayoutIndex(void) {
    xkb_layout_index_t num = xkb.keymap_num_layouts(keyboard.keymap);
    for (xkb_layout_index_t i = 0; i < num; ++i) {
        if (xkb.state_layout_index_is_active(keyboard.state, i, XKB_STATE_LAYOUT_EFFECTIVE)) {
            return i;
        }
    }

    return 0;
}

// Compose rules may depend on the system locale.
static const char *
getComposeLocale(void) {
    const char *locale = getenv("LC_ALL");

    if (!locale || !*locale) {
        locale = getenv("LC_CTYPE");
    }

    if (!locale || !*locale) {
        locale = getenv("LANG");
    }

    if (!locale || !*locale) {
        locale = "C";
    }

    return locale;
}

// This is called whenever either the XKB keymap is updated, or the 'group' (layout) is changed
static void
onKeyboardLayoutChanged(void) {
    // Determine, whether the current keyboard layout is ASCII-capable.
    // To do this, create a state, configure it to use the current layout
    // and iterate over all the keys to check for latin keysyms

    xkb_layout_index_t layoutIndex = getKeyboardLayoutIndex();

#ifdef WL_KEYBOARD_DEBUG
    fprintf(stderr, "onKeyboardLayoutChanged: %s\n", xkb.keymap_layout_get_name(keyboard.keymap, layoutIndex));
#endif

    // bitmask of the latin letters encountered in the layout
    uint32_t latin_letters_seen = 0;
    int latin_letters_seen_count = 0;
    xkb_keycode_t min_keycode = xkb.keymap_min_keycode(keyboard.keymap);
    xkb_keycode_t max_keycode = xkb.keymap_max_keycode(keyboard.keymap);
    if (max_keycode > 255) {
        // All keys that we are interested in should lie within this range.
        // The other keys are usually something like XFLaunch* or something of this sort.
        // Since we are looking for alphanumeric keys here, let's just guard against
        // layouts that might attempt to remap a lot of keycodes.
        max_keycode = 255;
    }

    for (xkb_keycode_t keycode = min_keycode; keycode <= max_keycode; ++keycode) {
        uint32_t num_levels = xkb.keymap_num_levels_for_key(keyboard.keymap, keycode, layoutIndex);

        for (uint32_t lvl = 0; lvl < num_levels; ++lvl) {
            const xkb_keysym_t *syms;
            int n_syms = xkb.keymap_key_get_syms_by_level(keyboard.keymap, keycode, layoutIndex, lvl, &syms);
            if (n_syms == 1 && syms[0] >= 'a' && syms[0] <= 'z') {
                int idx = (int) syms[0] - 'a';
                if (!(latin_letters_seen & (1 << idx))) {
                    latin_letters_seen |= 1 << idx;
                    ++latin_letters_seen_count;
                }
            }
        }
    }

    // some keyboard layouts are considered ascii-capable by default, even though not all the
    // latin letters can be typed without using modifiers
    keyboard.asciiCapable = latin_letters_seen_count >= 20;

#ifdef WL_KEYBOARD_DEBUG
    fprintf(stderr, "asciiCapable: %s\n", keyboard.asciiCapable ? "true" : "false");
#endif
}

enum ConvertDeadKeyType {
    CONVERT_TO_NON_COMBINING,
    CONVERT_TO_COMBINING
};

static xkb_keysym_t
convertDeadKey(xkb_keysym_t keysym, enum ConvertDeadKeyType type) {
    for (const struct DeadKeysymValuesMapItem *item = deadKeysymValuesMap; item->keysym; ++item) {
        if (item->keysym == keysym) {
            if (type == CONVERT_TO_NON_COMBINING && item->noncombining != 0) {
                return item->noncombining;
            } else {
                return item->combining;
            }
        }
    }

    return 0;
}

enum TranslateKeycodeType {
    TRANSLATE_USING_ACTIVE_LAYOUT,
    TRANSLATE_USING_QWERTY,
};

static xkb_keysym_t
translateKeycodeToKeysym(uint32_t keycode, enum TranslateKeycodeType type) {
    if (keyboard.remapExtraKeycodes) {
        for (const struct ExtraKeycodeToKeysymMapItem *item = extraKeycodeToKeysymMap; item->keysym; ++item) {
            if (item->keycode == keycode) {
                return item->keysym;
            }
        }
    }

    const uint32_t xkbKeycode = keycode + 8;

    struct xkb_state *state;
    xkb_layout_index_t group;

    if (keyboard.qwertyKeymap && type == TRANSLATE_USING_QWERTY) {
        state = keyboard.tmpQwertyState;
        group = 0;
    } else {
        state = keyboard.tmpState;
        group = getKeyboardLayoutIndex();
    }

    bool numLock = xkb.state_mod_name_is_active(keyboard.state, XKB_MOD_NAME_NUM, XKB_STATE_MODS_EFFECTIVE) == 1;
    xkb.state_update_mask(state, 0, 0, numLock ? sun_awt_wl_WLKeyboard_XKB_NUM_LOCK_MASK : 0, 0, 0, group);

    return xkb.state_key_get_one_sym(state, xkbKeycode);
}

static void
convertKeysymToJavaCode(xkb_keysym_t keysym, int *javaKeyCode, int *javaKeyLocation) {
    if (javaKeyCode) {
        *javaKeyCode = java_awt_event_KeyEvent_VK_UNDEFINED;
    }

    if (javaKeyLocation) {
        *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_UNKNOWN;
    }

    for (const struct KeysymToJavaKeycodeMapItem *item = keysymtoJavaKeycodeMap; item->keysym; ++item) {
        if (item->keysym == keysym) {
            if (javaKeyCode) {
                *javaKeyCode = item->keycode;
            }

            if (javaKeyLocation) {
                *javaKeyLocation = item->location;
            }

            return;
        }
    }

    uint32_t codepoint = xkb.keysym_to_utf32(keysym);
    if (codepoint != 0) {
        if (javaKeyCode) {
            // This might not be an actual Java extended key code,
            // since this doesn't deal with lowercase/uppercase characters.
            // It later needs to be passed to KeyEvent.getExtendedKeyCodeForChar().
            // This is done in WLToolkit.java
            *javaKeyCode = (int)(0x1000000 + codepoint);
        }

        if (javaKeyLocation) {
            *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_STANDARD;
        }
    }
}

// Posts one UTF-16 code unit as a KEY_TYPED event
static void
postKeyTypedJavaChar(long timestamp, uint16_t javaChar) {
#ifdef WL_KEYBOARD_DEBUG
    fprintf(stderr, "postKeyTypedJavaChar(0x%04x)\n", (int) javaChar);
#endif

    struct WLKeyEvent event = {
            .timestamp = timestamp,
            .id = java_awt_event_KeyEvent_KEY_TYPED,
            .keyCode = java_awt_event_KeyEvent_VK_UNDEFINED,
            .keyLocation = java_awt_event_KeyEvent_KEY_LOCATION_UNKNOWN,
            .rawCode = 0,
            .extendedKeyCode = 0,
            .keyChar = javaChar
    };

    wlPostKeyEvent(&event);
}

// Posts one Unicode code point as KEY_TYPED events
static void
postKeyTypedCodepoint(long timestamp, uint32_t codePoint) {
    if (codePoint >= 0x10000) {
        // break the codepoint into surrogates

        codePoint -= 0x10000;

        uint16_t highSurrogate = (uint16_t) (0xD800 + ((codePoint >> 10) & 0x3ff));
        uint16_t lowSurrogate = (uint16_t) (0xDC00 + (codePoint & 0x3ff));

        postKeyTypedJavaChar(timestamp, highSurrogate);
        postKeyTypedJavaChar(timestamp, lowSurrogate);
    } else {
        postKeyTypedJavaChar(timestamp, (uint16_t) codePoint);
    }
}

// Posts a UTF-8 encoded string as KEY_TYPED events
static void
postKeyTypedEvents(long timestamp, const char *string) {
#ifdef WL_KEYBOARD_DEBUG
    fprintf(stderr, "postKeyTypedEvents(b\"");
    for (const char *c = string; *c; ++c) {
        uint8_t byte = (uint8_t) *c;
        if (byte < 0x20 || byte >= 0x80) {
            fprintf(stderr, "\\x%02x", byte);
        } else if (byte == '\\' || byte == '"') {
            fprintf(stderr, "\\%c", byte);
        } else {
            fprintf(stderr, "%c", byte);
        }
    }
    fprintf(stderr, "\")\n");
#endif

    const uint8_t *utf8 = (const uint8_t *) string;
    uint32_t curCodePoint = 0;
    int remaining = 0;
    for (const uint8_t *ptr = utf8; *ptr; ++ptr) {
        if ((*ptr & 0xf8) == 0xf0) {
            // start of sequence of length 4
            remaining = 3;
            curCodePoint = *ptr & 0x07;
        } else if ((*ptr & 0xf0) == 0xe0) {
            // start of sequence of length 3
            remaining = 2;
            curCodePoint = *ptr & 0x0f;
        } else if ((*ptr & 0xe0) == 0xc0) {
            // start of sequence of length 2
            remaining = 1;
            curCodePoint = *ptr & 0x1f;
        } else if ((*ptr & 0x80) == 0x00) {
            // a single codepoint in range U+0000 to U+007F
            remaining = 0;
            curCodePoint = 0;
            postKeyTypedCodepoint(timestamp, *ptr & 0x7f);
        } else if ((*ptr & 0xc0) == 0x80) {
            // continuation byte
            curCodePoint = (curCodePoint << 6u) | (uint32_t) (*ptr & 0x3f);
            --remaining;
            if (remaining == 0) {
                postKeyTypedCodepoint(timestamp, curCodePoint);
                curCodePoint = 0;
            }
        } else {
            // invalid UTF-8, let's abort
            return;
        }
    }
}

static uint16_t
getJavaKeyCharForKeycode(xkb_keycode_t xkbKeycode) {
    uint32_t codepoint = xkb.state_key_get_utf32(keyboard.state, xkbKeycode);
    if (codepoint == 0 || codepoint >= 0xffff) {
        return java_awt_event_KeyEvent_CHAR_UNDEFINED;
    }
    return (uint16_t)codepoint;
}

// Posts an XKB keysym as KEY_TYPED events, without consulting the current compose state.
static void
handleKeyTypeNoCompose(long timestamp, xkb_keycode_t xkbKeycode) {
    int bufSize = xkb.state_key_get_utf8(keyboard.state, xkbKeycode, NULL, 0) + 1;
    char buf[bufSize];
    xkb.state_key_get_utf8(keyboard.state, xkbKeycode, buf, bufSize);
    postKeyTypedEvents(timestamp, buf);
}

// Handles generating KEY_TYPED events for an XKB keysym, translating it using the active compose state
static void
handleKeyType(long timestamp, xkb_keycode_t xkbKeycode) {
    xkb_keysym_t keysym = xkb.state_key_get_one_sym(keyboard.state, xkbKeycode);

    if (!keyboard.composeState ||
        (xkb.compose_state_feed(keyboard.composeState, keysym) == XKB_COMPOSE_FEED_IGNORED)) {
        handleKeyTypeNoCompose(timestamp, xkbKeycode);
        return;
    }

    switch (xkb.compose_state_get_status(keyboard.composeState)) {
        case XKB_COMPOSE_NOTHING:
            xkb.compose_state_reset(keyboard.composeState);
            handleKeyTypeNoCompose(timestamp, xkbKeycode);
            break;
        case XKB_COMPOSE_COMPOSING:
            break;
        case XKB_COMPOSE_COMPOSED: {
            char buf[MAX_COMPOSE_UTF8_LENGTH];
            xkb.compose_state_get_utf8(keyboard.composeState, buf, sizeof buf);
            postKeyTypedEvents(timestamp, buf);
            xkb.compose_state_reset(keyboard.composeState);
            break;
        }
        case XKB_COMPOSE_CANCELLED:
            xkb.compose_state_reset(keyboard.composeState);
            break;
    }
}

// Handles a key press or release, identified by the evdev key code.
// This is called:
//   1. As the wl_keyboard_key Wayland event handler. In this case, isRepeat = false,
//      and this function is responsible for setting up the key repeat manager state
//      by either starting the timer if isPressed = true and the key may be repeated,
//      or stopping it if isPressed = false.
//   2. From the key repeat manager. In this case, isRepeat = true and isPressed = true.
static void
handleKey(long timestamp, uint32_t keycode, bool isPressed, bool isRepeat) {
    JNIEnv *env = getEnv();

    xkb_keycode_t xkbKeycode = keycode + 8;
    xkb_keysym_t keysym = translateKeycodeToKeysym(keycode, TRANSLATE_USING_ACTIVE_LAYOUT);
    xkb_keysym_t qwertyKeysym = translateKeycodeToKeysym(keycode, TRANSLATE_USING_QWERTY);

    int javaKeyCode, javaExtendedKeyCode, javaKeyLocation;

    // If the national layouts support is enabled, and the current keyboard is not ascii-capable,
    // we need to set the extended key code properly.
    //
    // To do this, there is a specific logic that only runs on the alphanumeric keys.
    // We need to check for this, since we otherwise have no way of emulating various
    // XKB options that the user's layout has selected. For instance, if the user
    // has swapped their Left Ctrl and Caps Lock keys using ctrl:swapcaps, then this
    // swap will be lost when attempting to translate what they're typing on the non-ascii-capable
    // layout to the QWERTY key map. Hence, the 'qwertyKeysym <= 0x7f' check.

    if (keyboard.useNationalLayouts && !keyboard.asciiCapable && qwertyKeysym <= 0x7f) {
        convertKeysymToJavaCode(qwertyKeysym, &javaKeyCode, &javaKeyLocation);
        javaExtendedKeyCode = javaKeyCode;
    } else {
        xkb_keysym_t report = keysym;
        if (keyboard.reportDeadKeysAsNormal) {
            xkb_keysym_t converted = convertDeadKey(keysym, CONVERT_TO_NON_COMBINING);
            if (converted != 0) {
                report = converted;
            }
        }

        convertKeysymToJavaCode(report, &javaExtendedKeyCode, &javaKeyLocation);
        if (javaExtendedKeyCode >= 0x1000000 && !keyboard.reportJavaKeyCodeForActiveLayout) {
            convertKeysymToJavaCode(qwertyKeysym, &javaKeyCode, NULL);
        } else {
            javaKeyCode = javaExtendedKeyCode;
        }
    }

    struct WLKeyEvent event = {
            .timestamp = timestamp,
            .id = isPressed ? java_awt_event_KeyEvent_KEY_PRESSED : java_awt_event_KeyEvent_KEY_RELEASED,
            .keyCode = javaKeyCode,
            .keyLocation = javaKeyLocation,
            .rawCode = (int)xkbKeycode,
            .extendedKeyCode = javaExtendedKeyCode,
            .keyChar = getJavaKeyCharForKeycode(xkbKeycode),
    };

    wlPostKeyEvent(&event);

    if (isPressed) {
        handleKeyType(timestamp, xkbKeycode);

        if (!isRepeat && xkb.keymap_key_repeats(keyboard.keymap, xkbKeycode)) {
            (*env)->CallVoidMethod(env, keyboard.keyRepeatManager, startRepeatMID, timestamp, keycode);
            JNU_CHECK_EXCEPTION(env);
        }
    } else {
        (*env)->CallVoidMethod(env, keyboard.keyRepeatManager, cancelRepeatMID);
        JNU_CHECK_EXCEPTION(env);
    }
}

static void
freeXKB(void) {
    xkb.compose_state_unref(keyboard.composeState);
    keyboard.composeState = NULL;

    xkb.compose_table_unref(keyboard.composeTable);
    keyboard.composeTable = NULL;

    xkb.state_unref(keyboard.tmpQwertyState);
    keyboard.tmpQwertyState = NULL;

    xkb.keymap_unref(keyboard.qwertyKeymap);
    keyboard.qwertyKeymap = NULL;

    xkb.state_unref(keyboard.tmpState);
    keyboard.tmpState = NULL;

    xkb.state_unref(keyboard.state);
    keyboard.state = NULL;

    xkb.keymap_unref(keyboard.keymap);
    keyboard.keymap = NULL;

    xkb.context_unref(keyboard.context);
    keyboard.context = NULL;
}

static bool
initXKB(JNIEnv *env) {
    if (!xkbcommonLoad(env)) {
        // xkbcommonLoad has thrown
        return false;
    }

    keyboard.context = xkb.context_new(XKB_CONTEXT_NO_FLAGS);

    if (!keyboard.context) {
        JNU_ThrowInternalError(env, "Failed to create an XKB context");
        return false;
    }

    struct xkb_rule_names qwertyRuleNames = {
            .rules = "evdev",
            .model = "pc105",
            .layout = "us",
            .variant = "",
            .options = ""
    };

    keyboard.qwertyKeymap = xkb.keymap_new_from_names(keyboard.context, &qwertyRuleNames, 0);
    if (!keyboard.qwertyKeymap) {
        freeXKB();
        JNU_ThrowInternalError(getEnv(), "Failed to create XKB layout 'us'");
        return false;
    }

    keyboard.tmpQwertyState = xkb.state_new(keyboard.qwertyKeymap);
    if (!keyboard.tmpQwertyState) {
        freeXKB();
        JNU_ThrowInternalError(getEnv(), "Failed to create XKB state");
        return false;
    }

    keyboard.composeTable = xkb.compose_table_new_from_locale(keyboard.context, getComposeLocale(),
                                                              XKB_COMPOSE_COMPILE_NO_FLAGS);
    if (keyboard.composeTable) {
        keyboard.composeState = xkb.compose_state_new(keyboard.composeTable, XKB_COMPOSE_STATE_NO_FLAGS);
    }

    return true;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLKeyboard_initialize(JNIEnv *env, jobject instance, jobject keyRepeatManager) {
    if (keyboard.instance != NULL) {
        JNU_ThrowInternalError(getEnv(),
                               "WLKeyboard.initialize called twice");
        return;
    }

    if (!initJavaRefs(env)) {
        JNU_ThrowInternalError(getEnv(),
                               "WLKeyboard initJavaRefs failed");
        return;
    }

    if (!initXKB(env)) {
        // Already thrown
        return;
    }

    keyboard.useNationalLayouts = true;
    keyboard.remapExtraKeycodes = true;
    keyboard.reportDeadKeysAsNormal = false;
    keyboard.reportJavaKeyCodeForActiveLayout = true;

    keyboard.instance = (*env)->NewGlobalRef(env, instance);
    keyboard.keyRepeatManager = (*env)->NewGlobalRef(env, keyRepeatManager);
    if (!keyboard.instance || !keyboard.keyRepeatManager) {
        if (keyboard.instance) (*env)->DeleteGlobalRef(env, keyboard.instance);
        if (keyboard.keyRepeatManager) (*env)->DeleteGlobalRef(env, keyboard.keyRepeatManager);
        freeXKB();
        JNU_ThrowOutOfMemoryError(env, "Failed to create reference");
        return;
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLKeyboard_handleKeyPress(JNIEnv *env, jobject instance, jlong timestamp, jint keycode,
                                          jboolean isRepeat) {
    handleKey(timestamp, keycode, true, isRepeat);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLKeyboard_cancelCompose(JNIEnv *env, jobject instance) {
    if (keyboard.composeState) {
        xkb.compose_state_reset(keyboard.composeState);
    }
}

JNIEXPORT jint JNICALL
Java_sun_awt_wl_WLKeyboard_getXKBModifiersMask(JNIEnv *env, jobject instance) {
    xkb_mod_mask_t mods = xkb.state_serialize_mods(keyboard.state, XKB_STATE_MODS_EFFECTIVE);
    return (jint) mods;
}

void
wlSetKeymap(const char *serializedKeymap) {
    struct xkb_keymap *newKeymap = xkb.keymap_new_from_string(
            keyboard.context, serializedKeymap, XKB_KEYMAP_FORMAT_TEXT_V1, 0);

    if (!newKeymap) {
        JNU_ThrowInternalError(getEnv(), "Failed to create XKB keymap");
        return;
    }

    struct xkb_state *newState = xkb.state_new(newKeymap);
    struct xkb_state *newTmpState = xkb.state_new(newKeymap);
    if (!newState || !newTmpState) {
        JNU_ThrowInternalError(getEnv(), "Failed to create XKB state");
        return;
    }

    xkb.keymap_unref(keyboard.keymap);
    xkb.state_unref(keyboard.state);
    xkb.state_unref(keyboard.tmpState);

    keyboard.state = newState;
    keyboard.tmpState = newTmpState;
    keyboard.keymap = newKeymap;
    onKeyboardLayoutChanged();
}

void
wlSetKeyState(long timestamp, uint32_t keycode, bool isPressed) {
    handleKey(timestamp, keycode, isPressed, false);
}

void
wlSetRepeatInfo(int charsPerSecond, int delayMillis) {
    JNIEnv *env = getEnv();
    (*env)->CallVoidMethod(env, keyboard.keyRepeatManager, setRepeatInfoMID, charsPerSecond, delayMillis);
    JNU_CHECK_EXCEPTION(env);
}

void
wlSetModifiers(uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group) {
    xkb_layout_index_t oldLayoutIndex = getKeyboardLayoutIndex();

    xkb.state_update_mask(keyboard.state, depressed, latched, locked, 0, 0, group);

    if (group != oldLayoutIndex) {
        onKeyboardLayoutChanged();
    }
}