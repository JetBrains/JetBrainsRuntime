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
    JAW_GET_OBJ_IFACE(obj, INTERFACE_VALUE, ValueData, atk_value, jniEnv,         \
                      atk_value, def_ret)

void jaw_value_interface_init(AtkValueIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function jaw_value_interface_init");
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

    if (!ac) {
        g_warning("Null argument passed to function jaw_value_data_init");
        return NULL;
    }

    ValueData *data = g_new0(ValueData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (!classValue) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classValue, "create_atk_value",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkValue;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_value =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classValue, jmid, ac);
    if (!jatk_value) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->atk_value = (*jniEnv)->NewGlobalRef(jniEnv, jatk_value);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_value_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function jaw_value_data_finalize");
        return;
    }

    ValueData *data = (ValueData *)p;
    JAW_CHECK_NULL(data, );

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data->atk_value) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_value);
        data->atk_value = NULL;
    }
}

static void get_g_value_from_java_number(JNIEnv *jniEnv, jobject jnumber,
                                         GValue *value) {
    JAW_DEBUG_C("%p, %p, %p", jniEnv, jnumber, value);

    if (!jniEnv || !value) {
        g_warning(
            "Null argument passed to function get_g_value_from_java_number");
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classByte = (*jniEnv)->FindClass(jniEnv, "java/lang/Byte");
    if (!classByte) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classDouble = (*jniEnv)->FindClass(jniEnv, "java/lang/Double");
    if (!classDouble) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classFloat = (*jniEnv)->FindClass(jniEnv, "java/lang/Float");
    if (!classFloat) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classInteger = (*jniEnv)->FindClass(jniEnv, "java/lang/Integer");
    if (!classInteger) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classLong = (*jniEnv)->FindClass(jniEnv, "java/lang/Long");
    if (!classLong) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classShort = (*jniEnv)->FindClass(jniEnv, "java/lang/Short");
    if (!classShort) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jmethodID jmid;

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classByte)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classByte, "byteValue", "()B");
        if (!jmid) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_value_init(value, G_TYPE_CHAR);
        g_value_set_schar(
            value, (gchar)(*jniEnv)->CallByteMethod(jniEnv, jnumber, jmid));
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classDouble)) {
        jmid =
            (*jniEnv)->GetMethodID(jniEnv, classDouble, "doubleValue", "()D");
        if (!jmid) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_value_init(value, G_TYPE_DOUBLE);
        g_value_set_double(
            value, (gdouble)(*jniEnv)->CallDoubleMethod(jniEnv, jnumber, jmid));
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classFloat)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classFloat, "floatValue", "()F");
        if (!jmid) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_value_init(value, G_TYPE_FLOAT);
        g_value_set_float(
            value, (gfloat)(*jniEnv)->CallFloatMethod(jniEnv, jnumber, jmid));
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classInteger) ||
        (*jniEnv)->IsInstanceOf(jniEnv, jnumber, classShort)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classInteger, "intValue", "()I");
        if (!jmid) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_value_init(value, G_TYPE_INT);
        g_value_set_int(value,
                        (gint)(*jniEnv)->CallIntMethod(jniEnv, jnumber, jmid));
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classLong)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classLong, "longValue", "()J");
        if (!jmid) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return;
        }
        g_value_init(value, G_TYPE_INT64);
        g_value_set_int64(
            value, (gint64)(*jniEnv)->CallLongMethod(jniEnv, jnumber, jmid));
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static void jaw_value_get_current_value(AtkValue *obj, GValue *value) {
    JAW_DEBUG_C("%p, %p", obj, value);

    if (!obj || !value) {
        g_warning(
            "Null argument passed to function jaw_value_get_current_value");
        return;
    }

    g_value_unset(value);
    JAW_GET_VALUE(obj, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classAtkValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkValue, "get_current_value", "()Ljava/lang/Number;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject jnumber = (*jniEnv)->CallObjectMethod(jniEnv, atk_value, jmid);
    if (!jnumber) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    get_g_value_from_java_number(jniEnv, jnumber, value);
}

static void jaw_value_set_value(AtkValue *obj, const gdouble value) {
    JAW_DEBUG_C("%p, %lf", obj, value);

    if (!obj) {
        g_warning("Null argument passed to function jaw_value_set_value");
        return;
    }

    JAW_GET_VALUE(obj, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classAtkValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "set_value",
                                         "(Ljava/lang/Number;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_value, jmid, (jdouble)value);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static AtkRange *jaw_value_get_range(AtkValue *obj) {
    JAW_DEBUG_C("%p", obj);

    if (!obj) {
        g_warning("Null argument passed to function jaw_value_get_range");
        return NULL;
    }

    JAW_GET_VALUE(obj, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmidMin =
        (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "get_minimum_value", "()D");
    if (!jmidMin) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmidMax =
        (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "get_maximum_value", "()D");
    if (!jmidMax) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    AtkRange *ret = atk_range_new(
        (gdouble)(*jniEnv)->CallDoubleMethod(jniEnv, atk_value, jmidMin),
        (gdouble)(*jniEnv)->CallDoubleMethod(jniEnv, atk_value, jmidMax),
        NULL); // NULL description
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
    return ret;
}

/*
 * Gets the minimum increment by which the value of this object may be changed.
 * If zero, the minimum increment is undefined, which may mean that it is
 * limited only by the floating point precision of the platform.
 */
static gdouble jaw_value_get_increment(AtkValue *obj) {
    JAW_DEBUG_C("%p", obj);

    if (!obj) {
        g_warning("Null argument passed to function jaw_value_get_increment");
        return 0;
    }

    JAW_GET_VALUE(obj, 0.);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "get_increment", "()D");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    gdouble ret = (*jniEnv)->CallDoubleMethod(jniEnv, atk_value, jmid);
    if (!ret) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ret;
}

#ifdef __cplusplus
}
#endif
