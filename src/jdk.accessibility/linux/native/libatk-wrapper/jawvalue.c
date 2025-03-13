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

#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

#ifdef __cplusplus
extern "C" {
#endif

static void jaw_value_get_current_value(AtkValue *obj, GValue *value);
static void jaw_value_set_value(AtkValue *obj, const gdouble value);
static gdouble jaw_value_get_increment(AtkValue *obj);
static AtkRange *jaw_value_get_range(AtkValue *obj);

typedef struct _ValueData {
    jobject atk_value;
} ValueData;

#define JAW_GET_VALUE(obj, def_ret)                                            \
    JAW_GET_OBJ_IFACE(obj, INTERFACE_VALUE, ValueData, atk_value, env,         \
                      atk_value, def_ret)

void jaw_value_interface_init(AtkValueIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function");
        return;
    }

    iface->get_current_value = jaw_value_get_current_value;
    // deprecated: iface->get_maximum_value
    // deprecated: iface->get_minimum_value
    // deprecated: iface->set_current_value
    // deprecated: iface->get_minimum_increment
    // TODO: get_value_and_text
    iface->get_range = jaw_value_get_range;
    iface->get_increment = jaw_value_get_increment;
    // TODO: missing java support for iface->get_sub_ranges
    iface->set_value = jaw_value_set_value;
}

gpointer jaw_value_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);
    ValueData *data = g_new0(ValueData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, NULL);
    jclass classValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    CHECK_NULL(classValue, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classValue, "create_atk_value",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkValue;");
    CHECK_NULL(jmid, NULL);
    jobject jatk_value =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classValue, jmid, ac);
    CHECK_NULL(jatk_value, NULL);
    data->atk_value = (*jniEnv)->NewGlobalRef(jniEnv, jatk_value);

    return data;
}

void jaw_value_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);
    ValueData *data = (ValueData *)p;
    CHECK_NULL(data, );

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, );

    if (data->atk_value) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_value);
        data->atk_value = NULL;
    }
}

static void get_g_value_from_java_number(JNIEnv *jniEnv, jobject jnumber,
                                         GValue *value) {
    JAW_DEBUG_C("%p, %p, %p", jniEnv, jnumber, value);

    if (!jniEnv || !value) {
        g_warning("Null argument passed to function");
        return;
    }

    jclass classByte = (*jniEnv)->FindClass(jniEnv, "java/lang/Byte");
    CHECK_NULL(classByte, );
    jclass classDouble = (*jniEnv)->FindClass(jniEnv, "java/lang/Double");
    CHECK_NULL(classDouble, );
    jclass classFloat = (*jniEnv)->FindClass(jniEnv, "java/lang/Float");
    CHECK_NULL(classFloat, );
    jclass classInteger = (*jniEnv)->FindClass(jniEnv, "java/lang/Integer");
    CHECK_NULL(classInteger, );
    jclass classLong = (*jniEnv)->FindClass(jniEnv, "java/lang/Long");
    CHECK_NULL(classLong, );
    jclass classShort = (*jniEnv)->FindClass(jniEnv, "java/lang/Short");
    CHECK_NULL(classShort, );

    jmethodID jmid;

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classByte)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classByte, "byteValue", "()B");
        CHECK_NULL(jmid, );
        g_value_init(value, G_TYPE_CHAR);
        g_value_set_schar(
            value, (gchar)(*jniEnv)->CallByteMethod(jniEnv, jnumber, jmid));

        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classDouble)) {
        jmid =
            (*jniEnv)->GetMethodID(jniEnv, classDouble, "doubleValue", "()D");
        CHECK_NULL(jmid, );
        g_value_init(value, G_TYPE_DOUBLE);
        g_value_set_double(
            value, (gdouble)(*jniEnv)->CallDoubleMethod(jniEnv, jnumber, jmid));

        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classFloat)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classFloat, "floatValue", "()F");
        CHECK_NULL(jmid, );
        g_value_init(value, G_TYPE_FLOAT);
        g_value_set_float(
            value, (gfloat)(*jniEnv)->CallFloatMethod(jniEnv, jnumber, jmid));

        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classInteger) ||
        (*jniEnv)->IsInstanceOf(jniEnv, jnumber, classShort)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classInteger, "intValue", "()I");
        CHECK_NULL(jmid, );
        g_value_init(value, G_TYPE_INT);
        g_value_set_int(value,
                        (gint)(*jniEnv)->CallIntMethod(jniEnv, jnumber, jmid));

        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classLong)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classLong, "longValue", "()J");
        CHECK_NULL(jmid, );
        g_value_init(value, G_TYPE_INT64);
        g_value_set_int64(
            value, (gint64)(*jniEnv)->CallLongMethod(jniEnv, jnumber, jmid));

        return;
    }
}

static void jaw_value_get_current_value(AtkValue *obj, GValue *value) {
    JAW_DEBUG_C("%p, %p", obj, value);

    if (!obj || !value) {
        g_warning("Null argument passed to function");
        return;
    }

    g_value_unset(value);
    JAW_GET_VALUE(obj, );

    jclass classAtkValue =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkValue");
    if(!classAtkValue) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return;
    }
    jmethodID jmid = (*env)->GetMethodID(
        env, classAtkValue, "get_current_value", "()Ljava/lang/Number;");
    if(!jmid) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return;
    }
    jobject jnumber = (*env)->CallObjectMethod(env, atk_value, jmid);
    (*env)->DeleteGlobalRef(env, atk_value);
    CHECK_NULL(jnumber, );

    get_g_value_from_java_number(env, jnumber, value);
}

static void jaw_value_set_value(AtkValue *obj, const gdouble value) {
    JAW_DEBUG_C("%p, %lf", obj, value);

    if (!obj) {
        g_warning("Null argument passed to function");
        return;
    }

    JAW_GET_VALUE(obj, );

    jclass classAtkValue =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return;
    }
    jmethodID jmid = (*env)->GetMethodID(env, classAtkValue, "set_value",
                                         "(Ljava/lang/Number;)V");
    if (!jmid) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return;
    }
    (*env)->CallVoidMethod(env, atk_value, jmid, (jdouble)value);
    (*env)->DeleteGlobalRef(env, atk_value);
}

static AtkRange *jaw_value_get_range(AtkValue *obj) {
    JAW_DEBUG_C("%p", obj);

    if (!obj) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_VALUE(obj, NULL);

    jclass classAtkValue =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return NULL;
    }
    jmethodID jmidMin =
        (*env)->GetMethodID(env, classAtkValue, "get_minimum_value", "()D");
    if (!jmidMin) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return NULL;
    }
    jmethodID jmidMax =
        (*env)->GetMethodID(env, classAtkValue, "get_maximum_value", "()D");
    if (!jmidMax) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return NULL;
    }
    AtkRange *ret = atk_range_new(
        (gdouble)(*env)->CallDoubleMethod(env, atk_value, jmidMin),
        (gdouble)(*env)->CallDoubleMethod(env, atk_value, jmidMax),
        NULL); // NULL description
    (*env)->DeleteGlobalRef(env, atk_value);
    return ret;
}

/*
 * Gets the minimum increment by which the value of this object may be changed.
 * If zero, the minimum increment is undefined, which may mean that it is limited
 * only by the floating point precision of the platform.
 */
static gdouble jaw_value_get_increment(AtkValue *obj) {
    JAW_DEBUG_C("%p", obj);

    if (!obj) {
        g_warning("Null argument passed to function");
        return 0;
    }

    JAW_GET_VALUE(obj, 0.);

    jclass classAtkValue =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return 0;
    }
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkValue, "get_increment", "()D");
    if (!jmid) {
      (*env)->DeleteGlobalRef(env, atk_value);
      return 0;
    }
    gdouble ret = (*env)->CallDoubleMethod(env, atk_value, jmid);
    (*env)->DeleteGlobalRef(env, atk_value);
    CHECK_NULL(ret, 0);
    return ret;
}

#ifdef __cplusplus
}
#endif
