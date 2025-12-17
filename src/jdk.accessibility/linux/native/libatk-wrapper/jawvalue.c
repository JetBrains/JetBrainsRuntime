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

    if (iface == NULL) {
        g_warning("%s: Null argument iface passed to the function",
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

    if (ac == NULL) {
        g_warning("%s: Null argument ac passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if (classValue == NULL) {
        g_warning("%s: Failed to find AtkValue class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classValue, "create_atk_value",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkValue;");
    if (jmid == NULL) {
        g_warning("%s: Failed to find create_atk_value method", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_value =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classValue, jmid, ac);
    if (jatk_value == NULL) {
        g_warning("%s: Failed to call create_atk_value method and create jatk_value", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    ValueData *data = g_new0(ValueData, 1);
    data->atk_value = (*jniEnv)->NewGlobalRef(jniEnv, jatk_value);
    if (data->atk_value == NULL) {
        g_warning("%s: Failed to create global ref for atk_value", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_value_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning("%s: Null argument p passed to the function", G_STRFUNC);
        return;
    }

    ValueData *data = (ValueData *)p;
    if (data == NULL) {
        g_warning("%s: data is null", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->atk_value != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_value);
            data->atk_value = NULL;
        }
    }

    g_free(data);
}

static void private_get_g_value_from_java_number(JNIEnv *jniEnv,
                                                 jobject jnumber,
                                                 GValue *value) {
    JAW_DEBUG_C("%p, %p, %p", jniEnv, jnumber, value);

    if (jniEnv == NULL || value == NULL) {
        g_warning("%s: Null argument passed to the function (jniEnv=%p, value=%p)",
                  G_STRFUNC,
                  (void*)jniEnv,
                  (void*)value);
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classByte = (*jniEnv)->FindClass(jniEnv, "java/lang/Byte");
    if (classByte == NULL) {
        g_warning("%s: Failed to find java/lang/Byte class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classDouble = (*jniEnv)->FindClass(jniEnv, "java/lang/Double");
    if (classDouble == NULL) {
        g_warning("%s: Failed to find java/lang/Double class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classFloat = (*jniEnv)->FindClass(jniEnv, "java/lang/Float");
    if (classFloat == NULL) {
        g_warning("%s: Failed to find java/lang/Float class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classInteger = (*jniEnv)->FindClass(jniEnv, "java/lang/Integer");
    if (classInteger == NULL) {
        g_warning("%s: Failed to find java/lang/Integer class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classLong = (*jniEnv)->FindClass(jniEnv, "java/lang/Long");
    if (classLong == NULL) {
        g_warning("%s: Failed to find java/lang/Long class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classShort = (*jniEnv)->FindClass(jniEnv, "java/lang/Short");
    if (classShort == NULL) {
        g_warning("%s: Failed to find java/lang/Short class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jmethodID jmid;

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, classByte)) {
        jmid = (*jniEnv)->GetMethodID(jniEnv, classByte, "byteValue", "()B");
        if (jmid == NULL) {
            g_warning("%s: Failed to find byteValue method", G_STRFUNC);
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
        if (jmid == NULL) {
            g_warning("%s: Failed to find doubleValue method", G_STRFUNC);
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
        if (jmid == NULL) {
            g_warning("%s: Failed to find floatValue method", G_STRFUNC);
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
        if (jmid == NULL) {
            g_warning("%s: Failed to find intValue method", G_STRFUNC);
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
        if (jmid == NULL) {
            g_warning("%s: Failed to find longValue method", G_STRFUNC);
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

    if (obj == NULL || value == NULL) {
        g_warning("%s: Null argument passed to the function (obj=%p, value=%p)",
                  G_STRFUNC,
                  (void*)obj,
                  (void*)value);
        return;
    }

    g_value_unset(value);
    JAW_GET_VALUE(obj, ); // create global JNI reference `jobject atk_value`

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
    if (classAtkValue == NULL) {
        g_warning("%s: Failed to find AtkValue class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkValue, "get_current_value", "()Ljava/lang/Number;");
    if (jmid == NULL) {
        g_warning("%s: Failed to find get_current_value method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject jnumber = (*jniEnv)->CallObjectMethod(jniEnv, atk_value, jmid);
    if (jnumber == NULL) {
        g_warning("%s: Failed to get jnumber by calling get_current_value method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    private_get_g_value_from_java_number(jniEnv, jnumber, value);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
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

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_VALUE(obj, ); // create global JNI reference `jobject atk_value`

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
    if (classAtkValue == NULL) {
        g_warning("%s: Failed to find AtkValue class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "set_value",
                                            "(Ljava/lang/Number;)V");
    if (jmid == NULL) {
        g_warning("%s: Failed to find set_value method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_value, jmid, (jdouble)value);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_value_convert_double_to_gdouble:
 * @jniEnv: JNI environment
 * @jdouble: a Java Double object (jobject)
 * @result: (out): pointer to store the converted double value
 *
 * Converts a Java Double object to a gdouble primitive value.
 *
 * Returns: TRUE if conversion was successful, FALSE if jdouble is NULL
 **/
static gboolean jaw_value_convert_double_to_gdouble(JNIEnv *jniEnv,
                                                     jobject jdouble,
                                                     gdouble *result) {
    if (jdouble == NULL) {
        return FALSE;
    }

    jclass classDouble = (*jniEnv)->FindClass(jniEnv, "java/lang/Double");
    if (classDouble == NULL) {
        g_warning("%s: Failed to find Double class", G_STRFUNC);
        return FALSE;
    }

    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classDouble, "doubleValue", "()D");
    if (jmid == NULL) {
        g_warning("%s: Failed to find doubleValue method", G_STRFUNC);
        return FALSE;
    }

    *result = (gdouble)(*jniEnv)->CallDoubleMethod(jniEnv, jdouble, jmid);
    return TRUE;
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

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
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
    if (classAtkValue == NULL) {
        g_warning("%s: Failed to find AtkValue class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmidMin =
        (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "get_minimum_value",
                               "()Ljava/lang/Double;");
    if (jmidMin == NULL) {
        g_warning("%s: Failed to find get_minimum_value method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmidMax =
        (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "get_maximum_value",
                               "()Ljava/lang/Double;");
    if (jmidMax == NULL) {
        g_warning("%s: Failed to find get_maximum_value method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jobject jmin = (*jniEnv)->CallObjectMethod(jniEnv, atk_value, jmidMin);
    jobject jmax = (*jniEnv)->CallObjectMethod(jniEnv, atk_value, jmidMax);

    gdouble min_value, max_value;
    gboolean has_min = jaw_value_convert_double_to_gdouble(jniEnv, jmin, &min_value);
    gboolean has_max = jaw_value_convert_double_to_gdouble(jniEnv, jmax, &max_value);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    // If either min or max is NULL, we cannot construct a valid range
    if (!has_min || !has_max) {
        return NULL;
    }

    AtkRange *ret = atk_range_new(min_value, max_value, NULL); // NULL description
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

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
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
    if (classAtkValue == NULL) {
        g_warning("%s: Failed to find AtkValue class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkValue, "get_increment", "()D");
    if (jmid == NULL) {
        g_warning("%s: Failed to find get_increment method", G_STRFUNC);
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
