/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 * Copyright (C) 2015 Magdalen Berns <m.berns@thismagpie.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA
 */

#include "AtkWrapper.h"
#include "AtkSignal.h"
#include "jawcache.h"
#include "jawimpl.h"
#include "jawtoplevel.h"
#include "jawutil.h"
#include <X11/Xlib.h>
#include <atk-bridge.h>
#include <glib.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

int jaw_debug = 0;
FILE *jaw_log_file;
time_t jaw_start_time;

#ifdef __cplusplus
extern "C" {
#endif

#define KEY_DISPATCH_NOT_DISPATCHED 0
#define KEY_DISPATCH_CONSUMED 1
#define KEY_DISPATCH_NOT_CONSUMED 2

#define GDK_SHIFT_MASK (1 << 0)
#define GDK_CONTROL_MASK (1 << 2)
#define GDK_MOD1_MASK (1 << 3)
#define GDK_MOD5_MASK (1 << 7)
#define GDK_META_MASK (1 << 28)

#define JAW_LOG_FILE "jaw_log.txt"
#define JAW_LOG_FILE2 "/tmp/" JAW_LOG_FILE

#define ATSPI_CHECK_VERSION(major, minor, micro)                               \
    (((ATSPI_MAJOR_VERSION) > (major)) ||                                      \
     ((ATSPI_MAJOR_VERSION) == (major) && (ATSPI_MINOR_VERSION) > (minor)) ||  \
     ((ATSPI_MAJOR_VERSION) == (major) && (ATSPI_MINOR_VERSION) == (minor) &&  \
      (ATSPI_MICRO_VERSION) >= (micro)))

gboolean jaw_accessibility_init(void);
void jaw_accessibility_shutdown(void);

static GMainLoop *jni_main_loop;
static GMainContext *jni_main_context;

static gboolean jaw_initialized = FALSE;

/*
 * OpenJDK seems to be sending flurries of visible data changed events, which
 * overloads us. They are however usually just for the same object, so we can
 * compact them: there is no need to queue another one if the previous hasn't
 * even been sent!
 */
static pthread_mutex_t jaw_vdc_dup_mutex = PTHREAD_MUTEX_INITIALIZER;
static jobject jaw_vdc_last_ac = NULL;

static void jaw_vdc_clear_last_ac(JNIEnv *jniEnv) {
    if (jaw_vdc_last_ac != NULL && jniEnv != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_vdc_last_ac);
    }
    jaw_vdc_last_ac = NULL;
}

gboolean jaw_accessibility_init(void) {
    JAW_DEBUG_ALL("");
    if (atk_bridge_adaptor_init(NULL, NULL) < 0) {
        g_warning("%s: atk_bridge_adaptor_init failed", G_STRFUNC);
        return FALSE;
    }
    JAW_DEBUG_I("Atk Bridge Initialized");
    return TRUE;
}

void jaw_accessibility_shutdown(void) {
    JAW_DEBUG_ALL("");

    pthread_mutex_lock(&jaw_vdc_dup_mutex);
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    jaw_vdc_clear_last_ac(jniEnv);
    pthread_mutex_unlock(&jaw_vdc_dup_mutex);

    atk_bridge_adaptor_cleanup();
}

static gpointer jni_loop_callback(void *data) {
    JAW_DEBUG_C("%p", data);
    if (!g_main_loop_is_running((GMainLoop *)data))
        g_main_loop_run((GMainLoop *)data);
    else {
        JAW_DEBUG_I("Running JNI already");
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_org_GNOME_Accessibility_AtkWrapper_initNativeLibrary(void) {
    const gchar *debug_env = g_getenv("JAW_DEBUG");
    if (debug_env != NULL) {
        int val_debug = atoi(debug_env);
        if (val_debug > 4)
            jaw_debug = 4;
        else
            jaw_debug = val_debug;
    }
    if (jaw_debug != 0) {
        jaw_log_file = fopen(JAW_LOG_FILE, "w+");
        if (!jaw_log_file) {
            perror("Error opening log file " JAW_LOG_FILE
                   ", trying " JAW_LOG_FILE2);
            jaw_log_file = fopen(JAW_LOG_FILE2, "w+");
        }

        if (!jaw_log_file) {
            perror("Error opening log file " JAW_LOG_FILE2);
            return JNI_FALSE;
        }
        jaw_start_time = time(NULL);
    }
    JAW_DEBUG_JNI("");

    // Java app with GTK Look And Feel will load gail
    // Set NO_GAIL to "1" to prevent gail from executing

    g_setenv("NO_GAIL", "1", TRUE);

    // Disable ATK Bridge temporarily to aoid the loading
    // of ATK Bridge by GTK look and feel
    g_setenv("NO_AT_BRIDGE", "1", TRUE);

    g_type_class_unref(g_type_class_ref(JAW_TYPE_UTIL));
    // Force to invoke base initialization function of each ATK interfaces
    g_type_class_unref(g_type_class_ref(ATK_TYPE_NO_OP_OBJECT));

    return JNI_TRUE;
}

static guint jni_main_idle_add(GSourceFunc function, gpointer data) {
    JAW_DEBUG_C("%p, %p", function, data);
    GSource *source;
    guint id;

    source = g_idle_source_new();
    g_source_set_callback(source, function, data, NULL);
    id = g_source_attach(source, jni_main_context);
    g_source_unref(source);

    return id;
}

JNIEXPORT jboolean JNICALL
Java_org_GNOME_Accessibility_AtkWrapper_loadAtkBridge(void) {
    JAW_DEBUG_JNI("");
    // Enable ATK Bridge so we can load it now
    g_unsetenv("NO_AT_BRIDGE");

    GThread *thread;
    GError *err;
    const char *message;
    message = "JavaAtkWrapper-MainLoop";
    err = NULL;

    jaw_initialized = jaw_accessibility_init();
    JAW_DEBUG_I("Jaw Initialization STATUS = %d", jaw_initialized);
    if (!jaw_initialized) {
        g_warning("loadAtkBridge: jaw_initialized == NULL");
        return JNI_FALSE;
    }

#if ATSPI_CHECK_VERSION(2, 33, 1)
    jni_main_context = g_main_context_new();
    jni_main_loop =
        g_main_loop_new(jni_main_context, FALSE); /*main loop NOT running*/
    atk_bridge_set_event_context(jni_main_context);
#else
    jni_main_loop = g_main_loop_new(NULL, FALSE);
#endif

    thread = g_thread_try_new(message, jni_loop_callback, (void *)jni_main_loop,
                              &err);
    if (thread == NULL) {
        g_warning("%s: g_thread_try_new failed: %s", G_STRFUNC, err->message);
        g_main_loop_unref(jni_main_loop);
#if ATSPI_CHECK_VERSION(2, 33, 1)
        atk_bridge_set_event_context(NULL); // set default context
        g_main_context_unref(jni_main_context);
#endif
        g_error_free(err);
        jaw_accessibility_shutdown();
        return JNI_FALSE;
    } else {
        /* We won't join it */
        g_thread_unref(thread);
    }
    return JNI_TRUE;
}

typedef struct _CallbackPara {
    jobject global_ac;
    JawImpl *jaw_impl;
    JawImpl *child_impl;
    gboolean is_toplevel;
    gint signal_id;
    jobjectArray args;
    AtkStateType atk_state;
    gboolean state_value;
} CallbackPara;

typedef struct _CallbackParaEvent {
    jobject global_event;
} CallbackParaEvent;

JNIEXPORT jlong JNICALL
Java_org_GNOME_Accessibility_AtkWrapper_createNativeResources(JNIEnv *jniEnv,
                                                              jclass jClass,
                                                              jobject ac) {
    JawImpl *jaw_impl = jaw_impl_create_instance(jniEnv, ac);
    JAW_DEBUG_C("%p", jaw_impl);
    if (jaw_impl == NULL) {
        g_warning("%s: jaw_impl_create_instance failed", G_STRFUNC);
        return -1;
    }
    return (jlong)jaw_impl;
}

JNIEXPORT void JNICALL
Java_org_GNOME_Accessibility_AtkWrapper_releaseNativeResources(
    JNIEnv *jniEnv, jclass jClass, jlong reference) {
    JawImpl *jaw_impl = (JawImpl *)reference;
    JAW_DEBUG_C("%p", jaw_impl);
    if (jaw_impl == NULL) {
        g_warning("%s: jaw_impl is NULL", G_STRFUNC);
        return;
    }
    g_object_unref(G_OBJECT(jaw_impl));
}

static CallbackPara *alloc_callback_para(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);
    if (ac == NULL) {
        g_warning("%s: ac is NULL", G_STRFUNC);
        return NULL;
    }
    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, ac);
    if (jaw_impl == NULL) {
        g_warning("%s: jaw_impl_find_instance failed", G_STRFUNC);
        return NULL;
    }
    g_object_ref(G_OBJECT(jaw_impl));
    CallbackPara *para = g_new(CallbackPara, 1);
    para->global_ac = ac;
    para->jaw_impl = jaw_impl;
    para->child_impl = NULL;
    para->args = NULL;

    return para;
}

static CallbackParaEvent *alloc_callback_para_event(JNIEnv *jniEnv,
                                                    jobject event) {
    JAW_DEBUG_C("%p, %p", jniEnv, event);
    if (event == NULL) {
        g_warning("%s: event is NULL", G_STRFUNC);
        return NULL;
    }
    CallbackParaEvent *para = g_new(CallbackParaEvent, 1);
    para->global_event = event;
    return para;
}

static void free_callback_para(CallbackPara *para) {
    JAW_DEBUG_C("%p", para);

    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_warning("%s: jaw_util_get_jni_env returned NULL", G_STRFUNC);
    }

    if (jniEnv != NULL) {
        if (para->global_ac != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, para->global_ac);
        } else {
            JAW_DEBUG_I("para->global_ac == NULL");
        }
    }

    if (para->jaw_impl != NULL) {
        g_object_unref(G_OBJECT(para->jaw_impl));
    } else {
        JAW_DEBUG_I("para->jaw_impl == NULL");
    }

    if (para->child_impl != NULL) {
        g_object_unref(G_OBJECT(para->child_impl));
    }

    if (jniEnv != NULL) {
        if (para->args != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, para->args);
        } else {
            JAW_DEBUG_I("para->args == NULL");
        }
    }

    g_free(para);
}

static void free_callback_para_event(CallbackParaEvent *para) {
    JAW_DEBUG_C("%p", para);

    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_warning("%s: jaw_util_get_jni_env returned NULL", G_STRFUNC);
    }

    if (jniEnv != NULL) {
        if (para->global_event != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, para->global_event);
        } else {
            JAW_DEBUG_I("para->global_event == NULL");
        }
    }

    g_free(para);
}

/* List of callback params to be freed */
static GSList *callback_para_frees;
static GMutex callback_para_frees_mutex;
static GSList *callback_para_event_frees;
static GMutex callback_para_event_frees_mutex;

/* Add a note that this callback param should be freed from the application */
static void queue_free_callback_para(CallbackPara *para) {
    JAW_DEBUG_C("%p", para);
    g_mutex_lock(&callback_para_frees_mutex);
    callback_para_frees = g_slist_prepend(callback_para_frees, para);
    g_mutex_unlock(&callback_para_frees_mutex);
}

static void queue_free_callback_para_event(CallbackParaEvent *para) {
    JAW_DEBUG_C("%p", para);
    g_mutex_lock(&callback_para_event_frees_mutex);
    callback_para_event_frees =
        g_slist_prepend(callback_para_event_frees, para);
    g_mutex_unlock(&callback_para_event_frees_mutex);
}

/* Process the unreference requests */
static void callback_para_process_frees(void) {
    JAW_DEBUG_C("");
    GSList *list, *cur, *next;

    g_mutex_lock(&callback_para_frees_mutex);
    list = callback_para_frees;
    callback_para_frees = NULL;
    g_mutex_unlock(&callback_para_frees_mutex);

    for (cur = list; cur != NULL; cur = next) {
        free_callback_para(cur->data);
        next = g_slist_next(cur);
        g_slist_free_1(cur);
    }
}

static void callback_para_event_process_frees(void) {
    JAW_DEBUG_C("");
    GSList *list, *cur, *next;

    g_mutex_lock(&callback_para_event_frees_mutex);
    list = callback_para_event_frees;
    callback_para_event_frees = NULL;
    g_mutex_unlock(&callback_para_event_frees_mutex);

    for (cur = list; cur != NULL; cur = next) {
        free_callback_para_event(cur->data);
        next = g_slist_next(cur);
        g_slist_free_1(cur);
    }
}

static gboolean focus_notify_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    atk_object_notify_state_change(atk_obj, ATK_STATE_FOCUSED, 1);

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_focusNotify(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(focus_notify_handler, para);
}

static gboolean window_open_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    gboolean is_toplevel = para->is_toplevel;

    if (!g_strcmp0(atk_role_get_name(atk_object_get_role(atk_obj)),
                   "redundant object")) {
        queue_free_callback_para(para);
        return G_SOURCE_REMOVE;
    }

    if (atk_object_get_role(atk_obj) == ATK_ROLE_TOOL_TIP) {
        queue_free_callback_para(para);
        return G_SOURCE_REMOVE;
    }

    if (is_toplevel != FALSE) {
        gint n = jaw_toplevel_add_window(JAW_TOPLEVEL(atk_get_root()), atk_obj);
        if (n != -1) {
            g_object_notify(G_OBJECT(atk_get_root()), "accessible-name");
            g_signal_emit_by_name(ATK_OBJECT(atk_get_root()),
                                  "children-changed::add", n, atk_obj);
            g_signal_emit_by_name(atk_obj, "create");
        }
    }

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowOpen(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext, jboolean jIsToplevel) {
    JAW_DEBUG_JNI("%p, %p, %p, %d", jniEnv, jClass, jAccContext, jIsToplevel);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    para->is_toplevel = jIsToplevel;
    jni_main_idle_add(window_open_handler, para);
}

static gboolean window_close_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    gboolean is_toplevel = para->is_toplevel;

    if (!g_strcmp0(atk_role_get_name(atk_object_get_role(atk_obj)),
                   "redundant object")) {
        queue_free_callback_para(para);
        return G_SOURCE_REMOVE;
    }

    if (atk_object_get_role(atk_obj) == ATK_ROLE_TOOL_TIP) {
        queue_free_callback_para(para);
        return G_SOURCE_REMOVE;
    }

    if (is_toplevel != FALSE) {
        gint n =
            jaw_toplevel_remove_window(JAW_TOPLEVEL(atk_get_root()), atk_obj);
        if (n != -1) {
            g_object_notify(G_OBJECT(atk_get_root()), "accessible-name");
            g_signal_emit_by_name(ATK_OBJECT(atk_get_root()),
                                  "children-changed::remove", n, atk_obj);
            g_signal_emit_by_name(atk_obj, "destroy");
        }
    }

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowClose(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext, jboolean jIsToplevel) {
    JAW_DEBUG_JNI("%p, %p, %p, %d", jniEnv, jClass, jAccContext, jIsToplevel);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    para->is_toplevel = jIsToplevel;
    jni_main_idle_add(window_close_handler, para);
}

static gboolean window_minimize_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    g_signal_emit_by_name(atk_obj, "minimize");

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowMinimize(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(window_minimize_handler, para);
}

static gboolean window_maximize_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);
    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);

    if (!atk_obj) {
        g_warning("%s: atk_obj is NULL", G_STRFUNC);
        queue_free_callback_para(para);
        return FALSE;
    }

    g_signal_emit_by_name(atk_obj, "maximize");

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowMaximize(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(window_maximize_handler, para);
}

static gboolean window_restore_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    g_signal_emit_by_name(atk_obj, "restore");

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowRestore(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(window_restore_handler, para);
}

static gboolean window_activate_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        g_warning("%s: atk_obj is NULL", G_STRFUNC);
        queue_free_callback_para(para);
        return FALSE;
    }

    g_signal_emit_by_name(atk_obj, "activate");

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowActivate(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(window_activate_handler, para);
}

static gboolean window_deactivate_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    g_signal_emit_by_name(atk_obj, "deactivate");

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_windowDeactivate(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(window_deactivate_handler, para);
}

static gboolean window_state_change_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    g_signal_emit_by_name(atk_obj, "state-change", 0, 0);

    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL
Java_org_GNOME_Accessibility_AtkWrapper_windowStateChange(JNIEnv *jniEnv,
                                                          jclass jClass,
                                                          jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(window_state_change_handler, para);
}

static gint get_int_value(JNIEnv *jniEnv, jobject o) {
    JAW_DEBUG_C("%p, %p", jniEnv, o);
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classInteger = (*jniEnv)->FindClass(jniEnv, "java/lang/Integer");
    if (!classInteger) {
        g_warning("%s: FindClass for java/lang/Integer failed", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classInteger, "intValue", "()I");
    if (!jmid) {
        g_warning("%s: GetMethodID for intValue failed", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
    return (gint)(*jniEnv)->CallIntMethod(jniEnv, o, jmid);
}

static gchar *get_string_value(JNIEnv *jniEnv, jobject o) {
    JAW_DEBUG_C("%p, %p", jniEnv, o);
    JAW_CHECK_NULL(o, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 4) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass objClass = (*jniEnv)->GetObjectClass(jniEnv, o);
    if (!objClass) {
        g_warning("%s: GetObjectClass failed", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, objClass, "toString",
                                            "()Ljava/lang/String;");
    if (!jmid) {
        g_warning("%s: GetMethodID for toString failed", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jstring jstr = (jstring)(*jniEnv)->CallObjectMethod(jniEnv, o, jmid);
    if (!jstr) {
        g_warning("%s: CallObjectMethod failed", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    const char *nativeStr = (*jniEnv)->GetStringUTFChars(jniEnv, jstr, NULL);
    if (!nativeStr) {
        g_warning("%s: nativeStr is NULL", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    gchar *result = g_strdup(nativeStr);
    (*jniEnv)->ReleaseStringUTFChars(jniEnv, jstr, nativeStr);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

static gboolean signal_emit_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (!jniEnv) {
        g_warning("%s: jaw_util_get_jni_env failed", G_STRFUNC);
        queue_free_callback_para(para);
        return FALSE;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        queue_free_callback_para(para);
        return FALSE;
    }

    jobjectArray args = para->args;
    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);

    if (para->signal_id ==
        org_GNOME_Accessibility_AtkSignal_OBJECT_VISIBLE_DATA_CHANGED) {
        pthread_mutex_lock(&jaw_vdc_dup_mutex);
        if ((*jniEnv)->IsSameObject(jniEnv, jaw_vdc_last_ac, para->global_ac)) {
            /* So we will be sending the visible data changed event. If any
             * other comes, we will want to send it  */
            jaw_vdc_clear_last_ac(jniEnv);
        }
        pthread_mutex_unlock(&jaw_vdc_dup_mutex);
    }

    switch (para->signal_id) {
    case org_GNOME_Accessibility_AtkSignal_TEXT_CARET_MOVED: {
        jobject objectArrayElement =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement) {
            break;
        }
        gint cursor_pos = get_int_value(jniEnv, objectArrayElement);

        g_signal_emit_by_name(atk_obj, "text_caret_moved", cursor_pos);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED_INSERT: {
        jobject objectArrayElement0 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement0) {
            break;
        }
        gint insert_position = get_int_value(jniEnv, objectArrayElement0);

        jobject objectArrayElement1 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 1);
        if (!objectArrayElement1) {
            break;
        }
        gint insert_length = get_int_value(jniEnv, objectArrayElement1);

        jobject objectArrayElement2 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 2);
        if (!objectArrayElement2) {
            break;
        }
        gchar *insert_text = get_string_value(jniEnv, objectArrayElement2);

        g_signal_emit_by_name(atk_obj, "text_insert", insert_position,
                              insert_length, insert_text);

        g_free(insert_text);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED_DELETE: {
        jobject objectArrayElement0 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement0) {
            break;
        }
        gint delete_position = get_int_value(jniEnv, objectArrayElement0);

        jobject objectArrayElement1 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 1);
        if (!objectArrayElement1) {
            break;
        }
        gint delete_length = get_int_value(jniEnv, objectArrayElement1);

        jobject objectArrayElement2 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 2);
        if (!objectArrayElement2) {
            break;
        }
        gchar *delete_text = get_string_value(jniEnv, objectArrayElement2);

        g_signal_emit_by_name(atk_obj, "text_remove", delete_position,
                              delete_length, delete_text);

        g_free(delete_text);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_CHILDREN_CHANGED_ADD: {
        jobject objectArrayElement0 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement0) {
            break;
        }
        gint child_index = get_int_value(jniEnv, objectArrayElement0);

        g_signal_emit_by_name(atk_obj, "children_changed::add", child_index,
                              para->child_impl);

        if (atk_obj != NULL) {
            g_object_ref(G_OBJECT(atk_obj));
        }
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_CHILDREN_CHANGED_REMOVE: {
        jobject objectArrayElement0 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement0) {
            break;
        }
        gint child_index = get_int_value(jniEnv, objectArrayElement0);

        jobject child_ac = (*jniEnv)->GetObjectArrayElement(jniEnv, args, 1);
        if (!child_ac) {
            break;
        }
        JawImpl *child_impl = jaw_impl_find_instance(jniEnv, child_ac);
        if (!child_impl) {
            break;
        }

        g_signal_emit_by_name(atk_obj, "children_changed::remove", child_index,
                              child_impl);

        if (atk_obj != NULL) {
            g_object_unref(G_OBJECT(atk_obj));
        }
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_ACTIVE_DESCENDANT_CHANGED: {
        g_signal_emit_by_name(atk_obj, "active_descendant_changed",
                              para->child_impl);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_SELECTION_CHANGED: {
        g_signal_emit_by_name(atk_obj, "selection_changed");
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_VISIBLE_DATA_CHANGED: {
        g_signal_emit_by_name(atk_obj, "visible_data_changed");
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_ACTIONS: {
        jobject objectArrayElement0 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement0) {
            break;
        }
        gint oldValue = get_int_value(jniEnv, objectArrayElement0);
        jobject objectArrayElement1 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 1);
        if (!objectArrayElement1) {
            break;
        }
        gint newValue = get_int_value(jniEnv, objectArrayElement1);

        AtkPropertyValues values = {NULL};

        // GValues must be initialized
        g_assert(!G_VALUE_HOLDS_INT(&values.old_value));
        g_value_init(&values.old_value, G_TYPE_INT);
        g_assert(G_VALUE_HOLDS_INT(&values.old_value));
        g_value_set_int(&values.old_value, oldValue);
        if (jaw_debug)
            printf("%d\n", g_value_get_int(&values.old_value));

        g_assert(!G_VALUE_HOLDS_INT(&values.new_value));
        g_value_init(&values.new_value, G_TYPE_INT);
        g_assert(G_VALUE_HOLDS_INT(&values.new_value));
        g_value_set_int(&values.new_value, newValue);
        if (jaw_debug)
            printf("%d\n", g_value_get_int(&values.new_value));

        values.property_name = "accessible-actions";

        g_signal_emit_by_name(atk_obj, "property_change::accessible-actions",
                              &values);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_VALUE: {
        g_object_notify(G_OBJECT(atk_obj), "accessible-value");
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_DESCRIPTION: {
        g_object_notify(G_OBJECT(atk_obj), "accessible-description");
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_NAME: {
        g_object_notify(G_OBJECT(atk_obj), "accessible-name");
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_HYPERTEXT_OFFSET: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-hypertext-offset", NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_CAPTION: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-table-caption", NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_SUMMARY: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-table-summary", NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_HEADER: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-table-column-header", NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_DESCRIPTION: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-table-column-description",
            NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_HEADER: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-table-row-header", NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_DESCRIPTION: {
        g_signal_emit_by_name(
            atk_obj, "property_change::accessible-table-row-description", NULL);
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_TABLE_MODEL_CHANGED: {
        g_signal_emit_by_name(atk_obj, "model_changed");
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED: {
        JawObject *jaw_obj = JAW_OBJECT(atk_obj);

        jobject objectArrayElement0 =
            (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!objectArrayElement0) {
            break;
        }
        gint newValue = get_int_value(jniEnv, objectArrayElement0);

        gint prevCount = GPOINTER_TO_INT(
            g_hash_table_lookup(jaw_obj->storedData, "Previous_Count"));
        gint curCount = atk_text_get_character_count(ATK_TEXT(jaw_obj));

        g_hash_table_insert(jaw_obj->storedData, (gpointer) "Previous_Count",
                            GINT_TO_POINTER(curCount));

        /*
         * The "text_changed" signal was deprecated, but only for performance
         * reasons:
         * https://mail.gnome.org/archives/gnome-accessibility-devel/2010-December/msg00007.html.
         *
         * Since there is no information about the string in this case, we
         * cannot use "AtkObject::text-insert" or "AtkObject::text-remove", so
         * we continue using the "text_changed" signal.
         */
        if (curCount > prevCount) {
            g_signal_emit_by_name(atk_obj, "text_changed::insert", newValue,
                                  curCount - prevCount);
        } else if (curCount < prevCount) {
            g_signal_emit_by_name(atk_obj, "text_changed::delete", newValue,
                                  prevCount - curCount);
        }
        break;
    }
    default:
        break;
    }

    queue_free_callback_para(para);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_emitSignal(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext, jint id,
    jobjectArray args) {
    JAW_DEBUG_JNI("%p, %p, %p, %d, %p", jniEnv, jClass, jAccContext, id, args);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return;
    }

    pthread_mutex_lock(&jaw_vdc_dup_mutex);
    if (id != org_GNOME_Accessibility_AtkSignal_OBJECT_VISIBLE_DATA_CHANGED) {
        /* Something may have happened since the last visible data changed
         * event, so we want to sent it again */
        jaw_vdc_clear_last_ac(jniEnv);
    } else {
        if ((*jniEnv)->IsSameObject(jniEnv, jaw_vdc_last_ac, jAccContext)) {
            /* We have already queued to send one and nothing happened in
             * between, this one is really useless */
            pthread_mutex_unlock(&jaw_vdc_dup_mutex);
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }

        jaw_vdc_clear_last_ac(jniEnv);
        jaw_vdc_last_ac = (*jniEnv)->NewGlobalRef(jniEnv, jAccContext);
    }
    pthread_mutex_unlock(&jaw_vdc_dup_mutex);

    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jobject global_ac = (*jniEnv)->NewGlobalRef(
        jniEnv,
        jAccContext); // `free_callback_para` is responsible for deleting it
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobjectArray global_args =
        (jobjectArray)(*jniEnv)->NewGlobalRef(jniEnv, args);
    para->signal_id = (gint)id;
    para->args = global_args;

    switch (para->signal_id) {
    case org_GNOME_Accessibility_AtkSignal_TEXT_CARET_MOVED:
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED_INSERT:
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED_DELETE:
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED_REPLACE:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_CHILDREN_CHANGED_REMOVE:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_SELECTION_CHANGED:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_VISIBLE_DATA_CHANGED:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_ACTIONS:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_VALUE:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_DESCRIPTION:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_NAME:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_HYPERTEXT_OFFSET:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_CAPTION:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_SUMMARY:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_HEADER:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_COLUMN_DESCRIPTION:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_HEADER:
    case org_GNOME_Accessibility_AtkSignal_OBJECT_PROPERTY_CHANGE_ACCESSIBLE_TABLE_ROW_DESCRIPTION:
    case org_GNOME_Accessibility_AtkSignal_TABLE_MODEL_CHANGED:
    case org_GNOME_Accessibility_AtkSignal_TEXT_PROPERTY_CHANGED:
    default:
        break;
    case org_GNOME_Accessibility_AtkSignal_OBJECT_CHILDREN_CHANGED_ADD: {
        jobject child_ac = (*jniEnv)->GetObjectArrayElement(jniEnv, args, 1);
        if (!child_ac) {
            g_warning("%s: GetObjectArrayElement failed for child_ac",
                      G_STRFUNC);
            queue_free_callback_para(para);
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        JawImpl *child_impl = jaw_impl_find_instance(jniEnv, child_ac);
        if (child_impl == NULL) {
            g_warning("%s: child_impl == NULL, return NULL", G_STRFUNC);
            queue_free_callback_para(para);
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_object_ref(G_OBJECT(child_impl));
        para->child_impl = child_impl;
        break;
    }
    case org_GNOME_Accessibility_AtkSignal_OBJECT_ACTIVE_DESCENDANT_CHANGED: {
        jobject child_ac = (*jniEnv)->GetObjectArrayElement(jniEnv, args, 0);
        if (!child_ac) {
            g_warning("%s: child_ac == NULL, return NULL", G_STRFUNC);
            queue_free_callback_para(para);
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        JawImpl *child_impl = jaw_impl_find_instance(jniEnv, child_ac);
        if (child_impl == NULL) {
            g_warning("%s: child_impl == NULL, return NULL", G_STRFUNC);
            queue_free_callback_para(para);
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_object_ref(G_OBJECT(child_impl));
        para->child_impl = child_impl;
        break;
    }
    }

    jni_main_idle_add(signal_emit_handler,
                      para); // calls `queue_free_callback_para`
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static gboolean object_state_change_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        g_warning("%s: atk_obj is NULL", G_STRFUNC);
        queue_free_callback_para(para);
        return FALSE;
    }

    atk_object_notify_state_change(atk_obj, para->atk_state, para->state_value);

    queue_free_callback_para(para);
    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL
Java_org_GNOME_Accessibility_AtkWrapper_objectStateChange(JNIEnv *jniEnv,
                                                          jclass jClass,
                                                          jobject jAccContext,
                                                          jobject state,
                                                          jboolean value) {
    JAW_DEBUG_JNI("%p, %p, %p, %p, %d", jniEnv, jClass, jAccContext, state,
                  value);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(jniEnv, jAccContext);
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    AtkStateType state_type =
        jaw_util_get_atk_state_type_from_java_state(jniEnv, state);
    para->atk_state = state_type;
    para->state_value = value;
    jni_main_idle_add(object_state_change_handler, para);
}

static gboolean component_added_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    if (atk_object_get_role(atk_obj) == ATK_ROLE_TOOL_TIP) {
        atk_object_notify_state_change(atk_obj, ATK_STATE_SHOWING, 1);
    }

    queue_free_callback_para(para);
    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_componentAdded(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(jniEnv, jAccContext);
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(component_added_handler, para);
}

static gboolean component_removed_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        g_warning("%s: atk_obj is NULL", G_STRFUNC);
        queue_free_callback_para(para);
        return FALSE;
    }

    if (atk_object_get_role(atk_obj) == ATK_ROLE_TOOL_TIP)
        atk_object_notify_state_change(atk_obj, ATK_STATE_SHOWING, FALSE);
    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_componentRemoved(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(jniEnv, jAccContext);
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(component_removed_handler, para);
}

/**
 * Signal is emitted when the position or size of the component changes.
 */
static gboolean bounds_changed_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackPara *para = (CallbackPara *)p;
    JAW_CHECK_NULL(para, FALSE);

    AtkObject *atk_obj = ATK_OBJECT(para->jaw_impl);
    if (!atk_obj) {
        queue_free_callback_para(para);
        return FALSE;
    }

    AtkRectangle rect;
    rect.x = -1;
    rect.y = -1;
    rect.width = -1;
    rect.height = -1;
    g_signal_emit_by_name(atk_obj, "bounds_changed", &rect);
    queue_free_callback_para(para);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_boundsChanged(
    JNIEnv *jniEnv, jclass jClass, jobject jAccContext) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAccContext);
    if (!jAccContext) {
        g_warning("%s: jAccContext is NULL", G_STRFUNC);
        return;
    }
    jobject global_ac = (*jniEnv)->NewGlobalRef(jniEnv, jAccContext);
    callback_para_process_frees();
    CallbackPara *para = alloc_callback_para(jniEnv, global_ac);
    if (para == NULL) {
        g_warning("%s: para is NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_ac);
        return;
    }
    jni_main_idle_add(bounds_changed_handler, para);
}

static gboolean key_dispatch_handler(gpointer p) {
    JAW_DEBUG_C("%p", p);
    CallbackParaEvent *para = (CallbackParaEvent *)p;
    JAW_CHECK_NULL(para, FALSE);
    jobject jAtkKeyEvent = para->global_event;
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_warning("%s: jaw_util_get_jni_env == NULL", G_STRFUNC);
        queue_free_callback_para_event(para);
        return G_SOURCE_REMOVE;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 30) < 0) {
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    AtkKeyEventStruct *event = g_new0(AtkKeyEventStruct, 1);

    jclass classAtkKeyEvent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkKeyEvent");
    if (!classAtkKeyEvent) {
        // Unknown event type: clean up and exit
        g_warning(
            "%s: FindClass for org/GNOME/Accessibility/AtkKeyEvent failed",
            G_STRFUNC);
        g_free(event);
        queue_free_callback_para_event(para);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return G_SOURCE_REMOVE;
    }

    jfieldID jfidType =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "type", "I");
    if (!jfidType) {
        // Unknown event type: clean up and exit
        g_warning("%s: GetFieldID for type failed", G_STRFUNC);
        g_free(event);
        queue_free_callback_para_event(para);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return G_SOURCE_REMOVE;
    }

    jint type = (*jniEnv)->GetIntField(jniEnv, jAtkKeyEvent, jfidType);
    if (type == -1) {
        g_warning("%s: Unknown key event type (-1) received; cleaning up and "
                  "removing source",
                  G_STRFUNC);
        g_free(event);
        queue_free_callback_para_event(para);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return G_SOURCE_REMOVE;
    }

    jfieldID jfidTypePressed = (*jniEnv)->GetStaticFieldID(
        jniEnv, classAtkKeyEvent, "ATK_KEY_EVENT_PRESSED", "I");
    if (!jfidTypePressed) {
        // Unknown event type: clean up and exit
        g_warning("%s: GetStaticFieldID for ATK_KEY_EVENT_PRESSED failed",
                  G_STRFUNC);
        g_free(event);
        queue_free_callback_para_event(para);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return G_SOURCE_REMOVE;
    }

    jfieldID jfidTypeReleased = (*jniEnv)->GetStaticFieldID(
        jniEnv, classAtkKeyEvent, "ATK_KEY_EVENT_RELEASED", "I");
    if (!jfidTypeReleased) {
        // Unknown event type: clean up and exit
        g_warning("%s: GetStaticFieldID for ATK_KEY_EVENT_RELEASED failed",
                  G_STRFUNC);
        g_free(event);
        queue_free_callback_para_event(para);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return G_SOURCE_REMOVE;
    }

    jint type_pressed =
        (*jniEnv)->GetStaticIntField(jniEnv, classAtkKeyEvent, jfidTypePressed);

    jint type_released = (*jniEnv)->GetStaticIntField(jniEnv, classAtkKeyEvent,
                                                      jfidTypeReleased);

    if (type == type_pressed) {
        event->type = ATK_KEY_EVENT_PRESS;
    } else if (type == type_released) {
        event->type = ATK_KEY_EVENT_RELEASE;
    } else {
        g_warning("%s: Unknown key event type (%d) received; cleaning up and "
                  "removing source",
                  G_STRFUNC, type);
        g_free(event);
        queue_free_callback_para_event(para);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return G_SOURCE_REMOVE;
    }

    // state: shift
    jfieldID jfidShift =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "isShiftKeyDown", "Z");
    if (jfidShift != NULL) {
        jboolean jShiftKeyDown =
            (*jniEnv)->GetBooleanField(jniEnv, jAtkKeyEvent, jfidShift);
        if (jShiftKeyDown != FALSE) {
            event->state |= GDK_SHIFT_MASK;
        }
    }

    // state: ctrl
    jfieldID jfidCtrl =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "isCtrlKeyDown", "Z");
    if (jfidCtrl != NULL) {
        jboolean jCtrlKeyDown =
            (*jniEnv)->GetBooleanField(jniEnv, jAtkKeyEvent, jfidCtrl);
        if (jCtrlKeyDown != FALSE) {
            event->state |= GDK_CONTROL_MASK;
        }
    }

    // state: alt
    jfieldID jfidAlt =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "isAltKeyDown", "Z");
    if (jfidAlt != NULL) {
        jboolean jAltKeyDown =
            (*jniEnv)->GetBooleanField(jniEnv, jAtkKeyEvent, jfidAlt);
        if (jAltKeyDown != FALSE) {
            event->state |= GDK_MOD1_MASK;
        }
    }

    // state: meta
    jfieldID jfidMeta =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "isMetaKeyDown", "Z");
    if (jfidMeta != NULL) {
        jboolean jMetaKeyDown =
            (*jniEnv)->GetBooleanField(jniEnv, jAtkKeyEvent, jfidMeta);
        if (jMetaKeyDown != FALSE) {
            event->state |= GDK_META_MASK;
        }
    }

    // state: alt gr
    jfieldID jfidAltGr =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "isAltGrKeyDown", "Z");
    if (jfidAltGr != NULL) {
        jboolean jAltGrKeyDown =
            (*jniEnv)->GetBooleanField(jniEnv, jAtkKeyEvent, jfidAltGr);
        if (jAltGrKeyDown != FALSE) {
            event->state |= GDK_MOD5_MASK;
        }
    }

    // keyval
    jfieldID jfidKeyval =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "keyval", "I");
    if (jfidKeyval != NULL) {
        event->keyval =
            (*jniEnv)->GetIntField(jniEnv, jAtkKeyEvent, jfidKeyval);
    }

    // string
    jfieldID jfidString = (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent,
                                                "string", "Ljava/lang/String;");
    if (jfidString != NULL) {
        jstring jstr = (jstring)(*jniEnv)->GetObjectField(jniEnv, jAtkKeyEvent,
                                                          jfidString);
        if (jstr != NULL) {
            event->length = (gint)(*jniEnv)->GetStringLength(jniEnv, jstr);
            const gchar *tmp_string =
                (const gchar *)(*jniEnv)->GetStringUTFChars(jniEnv, jstr, 0);
            event->string = g_strdup(tmp_string);
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jstr, tmp_string);
        }
    }

    // keycode
    jfieldID jfidKeycode =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "keycode", "J");
    if (jfidKeycode != NULL) {
        event->keycode =
            (gint)(*jniEnv)->GetIntField(jniEnv, jAtkKeyEvent, jfidKeycode);
    }

    // timestamp
    jfieldID jfidTimestamp =
        (*jniEnv)->GetFieldID(jniEnv, classAtkKeyEvent, "timestamp", "J");
    if (jfidTimestamp != NULL) {
        event->timestamp = (guint32)(*jniEnv)->GetIntField(jniEnv, jAtkKeyEvent,
                                                           jfidTimestamp);
    }

    jaw_util_dispatch_key_event(event);

    // clean up
    g_free(event);
    queue_free_callback_para_event(para);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return G_SOURCE_REMOVE;
}

JNIEXPORT void JNICALL Java_org_GNOME_Accessibility_AtkWrapper_dispatchKeyEvent(
    JNIEnv *jniEnv, jclass jClass, jobject jAtkKeyEvent) {
    JAW_DEBUG_JNI("%p, %p, %p", jniEnv, jClass, jAtkKeyEvent);
    jobject global_key_event = (*jniEnv)->NewGlobalRef(jniEnv, jAtkKeyEvent);
    callback_para_event_process_frees();
    CallbackParaEvent *para =
        alloc_callback_para_event(jniEnv, global_key_event);
    if (para == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, global_key_event);
        return;
    }
    jni_main_idle_add(key_dispatch_handler, para);
}

#ifdef __cplusplus
}
#endif