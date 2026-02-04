/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
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

#ifndef _JAW_UTIL_H_
#define _JAW_UTIL_H_

#include <AtkInterface.h>
#include <atk/atk.h>
#include <jni.h>
#include <time.h>
#include <unistd.h>

extern int jaw_debug;
extern FILE *jaw_log_file;
extern time_t jaw_start_time;

#define PRINT_AND_FLUSH(fmt, ...)                                              \
    do {                                                                       \
        fprintf(jaw_log_file, "[%lu] %s" fmt "\n",                             \
                (unsigned long)(time(NULL) - jaw_start_time), __func__,        \
                ##__VA_ARGS__);                                                \
        fflush(jaw_log_file);                                                  \
    } while (0)

#define JAW_DEBUG(fmt, ...)                                                    \
    do {                                                                       \
        if (jaw_debug) {                                                       \
            PRINT_AND_FLUSH("(" fmt ")", ##__VA_ARGS__);                       \
        }                                                                      \
    } while (0)

G_BEGIN_DECLS

#define JAW_TYPE_UTIL (jaw_util_get_type())
#define JAW_UTIL(obj)                                                          \
    (G_TYPE_CHECK_INSTANCE_CAST((obj), JAW_TYPE_UTIL, JawUtil))
#define JAW_UTIL_CLASS(klass)                                                  \
    (G_TYPE_CHECK_CLASS_CAST((klass), JAW_TYPE_UTIL, JawUtilClass))
#define JAW_IS_UTIL(obj) (G_TYPE_CHECK_INSTANCE_TYPE((obj), JAW_TYPE_UTIL))
#define JAW_IS_UTIL_CLASS(klass)                                               \
    (G_TYPE_CHECK_CLASS_TYPE((klass), JAW_TYPE_UTIL))
#define JAW_UTIL_GET_CLASS(obj)                                                \
    (G_TYPE_INSTANCE_GET_CLASS((obj), JAW_TYPE_UTIL, JawUtilClass))

typedef struct _JawUtil JawUtil;
typedef struct _JawUtilClass JawUtilClass;

struct _JawUtil {
    AtkUtil parent;
};

GType jaw_util_get_type(void);

struct _JawUtilClass {
    AtkUtilClass parent_class;
};

guint jaw_util_get_tflag_from_jobj(JNIEnv *jniEnv, jobject jObj);
JNIEnv *jaw_util_get_jni_env(void);
AtkRole jaw_util_get_atk_role_from_AccessibleContext(jobject jobj);
AtkStateType jaw_util_get_atk_state_type_from_java_state(JNIEnv *jniEnv,
                                                         jobject jobj);
void jaw_util_get_rect_info(JNIEnv *jniEnv, jobject jrect, gint *x, gint *y,
                            gint *width, gint *height);
gboolean jaw_util_dispatch_key_event(AtkKeyEventStruct *event);

void jaw_util_detach(void);

#define JAW_DEFAULT_LOCAL_FRAME_SIZE 10

#define JAW_GET_OBJ_IFACE(o, iface, Data, field, env, name, def_ret)           \
    JawObject *jaw_obj = JAW_OBJECT(o);                                        \
    if (!jaw_obj) {                                                            \
        g_warning("%s: jaw_obj == NULL in JAW_GET_OBJ_IFACE", G_STRFUNC);                                        \
        return def_ret;                                                        \
    }                                                                          \
    Data *data = jaw_object_get_interface_data(jaw_obj, iface);                \
    JNIEnv *env = jaw_util_get_jni_env();                                      \
    if (!env) {                                                                \
        g_warning(#env " == NULL");                                             \
        return def_ret;                                                        \
    }                                                                          \
    jobject name = (*env)->NewLocalRef(env, data->field);                     \
    if (!name) {                                                               \
        g_warning(#name " == NULL");                                         \
        return def_ret;                                                        \
    }

#define JAW_GET_OBJ(o, CAST, JawObject, object_name, field, env, name,         \
                    def_ret)                                                   \
    JawObject *object_name = CAST(o);                                          \
    if (!object_name) {                                                        \
        g_warning(#object_name " == NULL");                                  \
        return def_ret;                                                        \
    }                                                                          \
    JNIEnv *env = jaw_util_get_jni_env();                                      \
    jobject name = (*env)->NewLocalRef(env, object_name->field);              \
    if (!name) {                                                               \
        g_warning(#name " == NULL");                                         \
        return def_ret;                                                        \
    }

static inline void jaw_jni_clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

G_END_DECLS

#endif
