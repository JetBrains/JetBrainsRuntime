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

#include "jawcache.h"
#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

#ifdef __cplusplus
extern "C" {
#endif

static jclass cachedValueAtkValueClass = NULL;
static jmethodID cachedValueCreateAtkValueMethod = NULL;
static jmethodID cachedValueGetCurrentValueMethod = NULL;
static jmethodID cachedValueSetValueMethod = NULL;
static jmethodID cachedValueGetMinimumValueMethod = NULL;
static jmethodID cachedValueGetMaximumValueMethod = NULL;
static jmethodID cachedValueGetIncrementMethod = NULL;
static jclass cachedValueByteClass = NULL;
static jclass cachedValueDoubleClass = NULL;
static jclass cachedValueFloatClass = NULL;
static jclass cachedValueIntegerClass = NULL;
static jclass cachedValueLongClass = NULL;
static jclass cachedValueShortClass = NULL;
static jmethodID cachedValueByteValueMethod = NULL;
static jmethodID cachedValueDoubleValueMethod = NULL;
static jmethodID cachedValueFloatValueMethod = NULL;
static jmethodID cachedValueIntValueMethod = NULL;
static jmethodID cachedValueLongValueMethod = NULL;
static jmethodID cachedValueShortValueMethod = NULL;
static jmethodID cachedValueDoubleConstructorMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_value_init_jni_cache(JNIEnv *jniEnv);

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
        g_warning("%s: Null argument iface passed to the function", G_STRFUNC);
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
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_value_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_value = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedValueAtkValueClass, cachedValueCreateAtkValueMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_value == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning(
            "%s: Failed to create jatk_value using create_atk_value method",
            G_STRFUNC);
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
        g_warning(
            "%s: Null argument passed to the function (jniEnv=%p, value=%p)",
            G_STRFUNC, (void *)jniEnv, (void *)value);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, cachedValueByteClass)) {
        g_value_init(value, G_TYPE_CHAR);
        g_value_set_schar(value,
                          (gchar)(*jniEnv)->CallByteMethod(
                              jniEnv, jnumber, cachedValueByteValueMethod));
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, cachedValueDoubleClass)) {
        g_value_init(value, G_TYPE_DOUBLE);
        g_value_set_double(value,
                           (gdouble)(*jniEnv)->CallDoubleMethod(
                               jniEnv, jnumber, cachedValueDoubleValueMethod));
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, cachedValueFloatClass)) {
        g_value_init(value, G_TYPE_FLOAT);
        g_value_set_float(value,
                          (gfloat)(*jniEnv)->CallFloatMethod(
                              jniEnv, jnumber, cachedValueFloatValueMethod));
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, cachedValueIntegerClass)) {
        jint v = (*jniEnv)->CallIntMethod(jniEnv, jnumber,
                                          cachedValueIntValueMethod);
        if ((*jniEnv)->ExceptionCheck(jniEnv)) {
            jaw_jni_clear_exception(jniEnv);
            g_warning("%s: Exception in Integer.intValue()", G_STRFUNC);
            return;
        }

        g_value_init(value, G_TYPE_INT);
        g_value_set_int(value, (gint)v);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, cachedValueShortClass)) {
        jshort v = (*jniEnv)->CallShortMethod(jniEnv, jnumber,
                                              cachedValueShortValueMethod);
        if ((*jniEnv)->ExceptionCheck(jniEnv)) {
            jaw_jni_clear_exception(jniEnv);
            g_warning("%s: Exception in Short.shortValue()", G_STRFUNC);
            return;
        }

        g_value_init(value, G_TYPE_INT);
        g_value_set_int(value, (gint)v);
        return;
    }

    if ((*jniEnv)->IsInstanceOf(jniEnv, jnumber, cachedValueLongClass)) {
        g_value_init(value, G_TYPE_INT64);
        g_value_set_int64(value,
                          (gint64)(*jniEnv)->CallLongMethod(
                              jniEnv, jnumber, cachedValueLongValueMethod));
        return;
    }
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
                  G_STRFUNC, (void *)obj, (void *)value);
        return;
    }

    if (G_VALUE_TYPE(value) != G_TYPE_INVALID) {
        g_value_unset(value);
    }

    JAW_GET_VALUE(obj, ); // create local JNI reference `jobject atk_value`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jobject jnumber = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_value, cachedValueGetCurrentValueMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Exception occurred while calling get_current_value",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    if (jnumber == NULL) {
        g_warning(
            "%s: Failed to get jnumber by calling get_current_value method",
            G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    private_get_g_value_from_java_number(jniEnv, jnumber, value);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
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

    JAW_GET_VALUE(obj, ); // create local JNI reference `jobject atk_value`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);  
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jobject jdoubleValue = (*jniEnv)->NewObject(
        jniEnv, cachedValueDoubleClass, cachedValueDoubleConstructorMethod,
        (jdouble)value);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jdoubleValue == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create Double object", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_value, cachedValueSetValueMethod,
                              jdoubleValue);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Exception occurred while calling set_value", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
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

    *result = (gdouble)(*jniEnv)->CallDoubleMethod(
        jniEnv, jdouble, cachedValueDoubleValueMethod);
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
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jmin = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_value, cachedValueGetMinimumValueMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Exception occurred while calling get_minimum_value",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jobject jmax = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_value, cachedValueGetMaximumValueMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Exception occurred while calling get_maximum_value",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    gdouble min_value, max_value;
    gboolean has_min =
        jaw_value_convert_double_to_gdouble(jniEnv, jmin, &min_value);
    gboolean has_max =
        jaw_value_convert_double_to_gdouble(jniEnv, jmax, &max_value);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    // If either min or max is NULL, we cannot construct a valid range
    if (!has_min || !has_max) {
        return NULL;
    }

    AtkRange *ret =
        atk_range_new(min_value, max_value, NULL); // NULL description
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

    JAW_GET_VALUE(obj, 0);

    gdouble ret = (*jniEnv)->CallDoubleMethod(jniEnv, atk_value,
                                              cachedValueGetIncrementMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Exception occurred while calling get_increment",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_value);

    return ret;
}

static gboolean jaw_value_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClassAtkValue =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkValue");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClassAtkValue == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkValue class", G_STRFUNC);
        g_mutex_unlock(&cache_mutex);
        return FALSE;
    }

    cachedValueAtkValueClass =
        (*jniEnv)->NewGlobalRef(jniEnv, localClassAtkValue);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClassAtkValue);

    if (cachedValueAtkValueClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkValue class",
                  G_STRFUNC);
        g_mutex_unlock(&cache_mutex);
        return FALSE;
    }

    cachedValueCreateAtkValueMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedValueAtkValueClass, "create_atk_value",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkValue;");

    cachedValueGetCurrentValueMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedValueAtkValueClass,
                               "get_current_value", "()Ljava/lang/Number;");

    cachedValueSetValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueAtkValueClass, "set_value", "(Ljava/lang/Number;)V");

    cachedValueGetMinimumValueMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedValueAtkValueClass,
                               "get_minimum_value", "()Ljava/lang/Double;");

    cachedValueGetMaximumValueMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedValueAtkValueClass,
                               "get_maximum_value", "()Ljava/lang/Double;");

    cachedValueGetIncrementMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueAtkValueClass, "get_increment", "()D");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedValueCreateAtkValueMethod == NULL ||
        cachedValueGetCurrentValueMethod == NULL ||
        cachedValueSetValueMethod == NULL ||
        cachedValueGetMinimumValueMethod == NULL ||
        cachedValueGetMaximumValueMethod == NULL ||
        cachedValueGetIncrementMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkValue method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localByte = (*jniEnv)->FindClass(jniEnv, "java/lang/Byte");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localByte == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Byte class", G_STRFUNC);
        goto cleanup_and_fail;
    }
    cachedValueByteClass = (*jniEnv)->NewGlobalRef(jniEnv, localByte);
    (*jniEnv)->DeleteLocalRef(jniEnv, localByte);
    if (cachedValueByteClass == NULL) {
        g_warning("%s: Failed to create global reference for Byte class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localDouble = (*jniEnv)->FindClass(jniEnv, "java/lang/Double");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localDouble == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Double class", G_STRFUNC);
        goto cleanup_and_fail;
    }
    cachedValueDoubleClass = (*jniEnv)->NewGlobalRef(jniEnv, localDouble);
    (*jniEnv)->DeleteLocalRef(jniEnv, localDouble);
    if (cachedValueDoubleClass == NULL) {
        g_warning("%s: Failed to create global reference for Double class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localFloat = (*jniEnv)->FindClass(jniEnv, "java/lang/Float");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localFloat == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Float class", G_STRFUNC);
        goto cleanup_and_fail;
    }
    cachedValueFloatClass = (*jniEnv)->NewGlobalRef(jniEnv, localFloat);
    (*jniEnv)->DeleteLocalRef(jniEnv, localFloat);
    if (cachedValueFloatClass == NULL) {
        g_warning("%s: Failed to create global reference for Float class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localInteger = (*jniEnv)->FindClass(jniEnv, "java/lang/Integer");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localInteger == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Integer class", G_STRFUNC);
        goto cleanup_and_fail;
    }
    cachedValueIntegerClass = (*jniEnv)->NewGlobalRef(jniEnv, localInteger);
    (*jniEnv)->DeleteLocalRef(jniEnv, localInteger);
    if (cachedValueIntegerClass == NULL) {
        g_warning("%s: Failed to create global reference for Integer class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localLong = (*jniEnv)->FindClass(jniEnv, "java/lang/Long");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localLong == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Long class", G_STRFUNC);
        goto cleanup_and_fail;
    }
    cachedValueLongClass = (*jniEnv)->NewGlobalRef(jniEnv, localLong);
    (*jniEnv)->DeleteLocalRef(jniEnv, localLong);
    if (cachedValueLongClass == NULL) {
        g_warning("%s: Failed to create global reference for Long class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localShort = (*jniEnv)->FindClass(jniEnv, "java/lang/Short");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localShort == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Short class", G_STRFUNC);
        goto cleanup_and_fail;
    }
    cachedValueShortClass = (*jniEnv)->NewGlobalRef(jniEnv, localShort);
    (*jniEnv)->DeleteLocalRef(jniEnv, localShort);
    if (cachedValueShortClass == NULL) {
        g_warning("%s: Failed to create global reference for Short class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedValueByteValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueByteClass, "byteValue", "()B");
    cachedValueDoubleValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueDoubleClass, "doubleValue", "()D");
    cachedValueFloatValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueFloatClass, "floatValue", "()F");
    cachedValueIntValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueIntegerClass, "intValue", "()I");
    cachedValueLongValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueLongClass, "longValue", "()J");
    cachedValueShortValueMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueShortClass, "shortValue", "()S");
    cachedValueDoubleConstructorMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedValueDoubleClass, "<init>", "(D)V");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedValueByteValueMethod == NULL ||
        cachedValueDoubleValueMethod == NULL ||
        cachedValueFloatValueMethod == NULL ||
        cachedValueIntValueMethod == NULL ||
        cachedValueLongValueMethod == NULL ||
        cachedValueShortValueMethod == NULL ||
        cachedValueDoubleConstructorMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache Number wrapper method IDs", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);

    g_debug("%s: classes and methods cached successfully", G_STRFUNC);

    return TRUE;

cleanup_and_fail:
    if (cachedValueAtkValueClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueAtkValueClass);
        cachedValueAtkValueClass = NULL;
    }
    if (cachedValueByteClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueByteClass);
        cachedValueByteClass = NULL;
    }
    if (cachedValueDoubleClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueDoubleClass);
        cachedValueDoubleClass = NULL;
    }
    if (cachedValueFloatClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueFloatClass);
        cachedValueFloatClass = NULL;
    }
    if (cachedValueIntegerClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueIntegerClass);
        cachedValueIntegerClass = NULL;
    }
    if (cachedValueLongClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueLongClass);
        cachedValueLongClass = NULL;
    }
    if (cachedValueShortClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueShortClass);
        cachedValueShortClass = NULL;
    }

    cachedValueCreateAtkValueMethod = NULL;
    cachedValueGetCurrentValueMethod = NULL;
    cachedValueSetValueMethod = NULL;
    cachedValueGetMinimumValueMethod = NULL;
    cachedValueGetMaximumValueMethod = NULL;
    cachedValueGetIncrementMethod = NULL;
    cachedValueByteValueMethod = NULL;
    cachedValueDoubleValueMethod = NULL;
    cachedValueFloatValueMethod = NULL;
    cachedValueIntValueMethod = NULL;
    cachedValueLongValueMethod = NULL;
    cachedValueShortValueMethod = NULL;
    cachedValueDoubleConstructorMethod = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_value_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedValueAtkValueClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueAtkValueClass);
        cachedValueAtkValueClass = NULL;
    }
    if (cachedValueByteClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueByteClass);
        cachedValueByteClass = NULL;
    }
    if (cachedValueDoubleClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueDoubleClass);
        cachedValueDoubleClass = NULL;
    }
    if (cachedValueFloatClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueFloatClass);
        cachedValueFloatClass = NULL;
    }
    if (cachedValueIntegerClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueIntegerClass);
        cachedValueIntegerClass = NULL;
    }
    if (cachedValueLongClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueLongClass);
        cachedValueLongClass = NULL;
    }
    if (cachedValueShortClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedValueShortClass);
        cachedValueShortClass = NULL;
    }
    cachedValueCreateAtkValueMethod = NULL;
    cachedValueGetCurrentValueMethod = NULL;
    cachedValueSetValueMethod = NULL;
    cachedValueGetMinimumValueMethod = NULL;
    cachedValueGetMaximumValueMethod = NULL;
    cachedValueGetIncrementMethod = NULL;
    cachedValueByteValueMethod = NULL;
    cachedValueDoubleValueMethod = NULL;
    cachedValueFloatValueMethod = NULL;
    cachedValueIntValueMethod = NULL;
    cachedValueLongValueMethod = NULL;
    cachedValueShortValueMethod = NULL;
    cachedValueDoubleConstructorMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}

#ifdef __cplusplus
}
#endif