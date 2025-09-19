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
    JAW_GET_OBJ_IFACE(obj,                                                     \
                      org_GNOME_Accessibility_AtkInterface_INTERFACE_VALUE,    \
                      ValueData, atk_value, jniEnv, atk_value, def_ret)

void jaw_value_interface_init(AtkValueIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    iface->get_current_value = jaw_value_get_current_value;
    iface->get_maximum_value = NULL; // deprecated: iface->get_maximum_value
    iface->get_minimum_value = NULL; // deprecated: iface->get_minimum_value
    iface->set_current_value = NULL; // deprecated: iface->set_current_value
    iface->get_minimum_increment =
        NULL; // deprecated: iface->get_minimum_increment
    iface->get_value_and_text = NULL; // TODO: get_value_and_text
    iface->get_range = jaw_value_get_range;
    iface->get_increment = jaw_value_get_increment;
    iface->get_sub_ranges =
        NULL; // missing java support for iface->get_sub_ranges
    iface->set_value = jaw_value_set_value;
}

gpointer jaw_value_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    ValueData *data = g_new0(ValueData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
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
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
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

static void private_get_g_value_from_java_number(JNIEnv *jniEnv,
                                                 jobject jnumber,
                                                 GValue *value) {
    JAW_DEBUG_C("%p, %p, %p", jniEnv, jnumber, value);

    if (!jniEnv || !value) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
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

/**
 * jaw_value_get_current_value:
 * @obj: a GObject instance that implements AtkValueIface
 * @value: (out): a #GValue representing the current accessible value
 *
 * Gets the value of this object.
 *
 * Deprecated in atk: Since 2.12. Use atk_value_get_value_and_text()
 * instead.
 **/
static void jaw_value_get_current_value(AtkValue *obj, GValue *value) {
    JAW_DEBUG_C("%p, %p", obj, value);

    if (!obj || !value) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    g_value_unset(value);
    JAW_GET_VALUE(obj, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
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

    private_get_g_value_from_java_number(jniEnv, jnumber, value);
}

/**
 * atk_value_set_value:
 * @obj: a GObject instance that implements AtkValueIface
 * @new_value: a double which is the desired new accessible value.
 *
 * Sets the value of this object.
 *
 * This method is intended to provide a way to change the value of the
 * object. In any case, it is possible that the value can't be
 * modified (ie: a read-only component). If the value changes due this
 * call, it is possible that the text could change, and will trigger
 * an #AtkValue::value-changed signal emission.
 *
 * Since: 2.12
 **/
static void jaw_value_set_value(AtkValue *obj, const gdouble value) {
    JAW_DEBUG_C("%p, %lf", obj, value);

    if (!obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_VALUE(obj, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
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

/**
 * jaw_value_get_range:
 * @obj: a GObject instance that implements AtkValueIface
 *
 * Gets the range of this object.
 *
 * Returns: (nullable) (transfer full): a newly allocated #AtkRange
 * that represents the minimum, maximum and descriptor (if available)
 * of @obj. NULL if that range is not defined.
 *
 * In Atk Since: 2.12
 **/
static AtkRange *jaw_value_get_range(AtkValue *obj) {
    JAW_DEBUG_C("%p", obj);

    if (!obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_VALUE(obj, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (!classAtkValue) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmidMin = (*jniEnv)->GetMethodID(jniEnv, classAtkValue,
                                               "get_minimum_value", "()D");
    if (!jmidMin) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmidMax = (*jniEnv)->GetMethodID(jniEnv, classAtkValue,
                                               "get_maximum_value", "()D");
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

/**
 * jaw_value_get_increment:
 * @obj: a GObject instance that implements AtkValueIface
 *
 * Gets the minimum increment by which the value of this object may be
 * changed.  If zero, the minimum increment is undefined, which may
 * mean that it is limited only by the floating point precision of the
 * platform.
 *
 * Return value: the minimum increment by which the value of this
 * object may be changed. zero if undefined.
 *
 * In atk Since: 2.12
 **/
static gdouble jaw_value_get_increment(AtkValue *obj) {
    JAW_DEBUG_C("%p", obj);

    if (!obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_VALUE(obj, 0.);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_value); // deleting ref that was created in JAW_GET_VALUE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
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

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ret;
}

#ifdef __cplusplus
}
#endif
