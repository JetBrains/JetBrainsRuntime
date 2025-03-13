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

#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

static void jaw_image_get_image_position(AtkImage *image, gint *x, gint *y,
                                         AtkCoordType coord_type);
static const gchar *jaw_image_get_image_description(AtkImage *image);
static void jaw_image_get_image_size(AtkImage *image, gint *width,
                                     gint *height);

typedef struct _ImageData {
    jobject atk_image;
    gchar *image_description;
    jstring jstrImageDescription;
} ImageData;

#define JAW_GET_IMAGE(image, def_ret)                                          \
    JAW_GET_OBJ_IFACE(image, INTERFACE_IMAGE, ImageData, atk_image, jniEnv,    \
                      atk_image, def_ret)

void jaw_image_interface_init(AtkImageIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function");
        return;
    }

    iface->get_image_position = jaw_image_get_image_position;
    iface->get_image_description = jaw_image_get_image_description;
    iface->get_image_size = jaw_image_get_image_size;
    iface->set_image_description = NULL; /* TODO */
    // TODO: iface->get_image_locale from AccessibleContext.getLocale()
}

gpointer jaw_image_data_init(jobject ac) {
    JAW_DEBUG_C("%p", ac);

    if (!ac) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    ImageData *data = g_new0(ImageData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, NULL);
    jclass classImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    CHECK_NULL(classImage, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classImage, "create_atk_image",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkImage;");
    CHECK_NULL(jmid, NULL);
    jobject jatk_image =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classImage, jmid, ac);
    CHECK_NULL(jatk_image, NULL);
    data->atk_image = (*jniEnv)->NewGlobalRef(jniEnv, jatk_image);

    return data;
}

void jaw_image_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function");
        return;
    }

    ImageData *data = (ImageData *)p;
    CHECK_NULL(data, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, );

    if (data->image_description != NULL) {
        if (data->image_description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrImageDescription,
                                             data->image_description);
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrImageDescription);
            data->jstrImageDescription = NULL;
        }
        data->image_description = NULL;
    }

    if (data && data->atk_image) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_image);
        data->atk_image = NULL;
    }
}

static void jaw_image_get_image_position(AtkImage *image, gint *x, gint *y,
                                         AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %p, %p, %d", image, x, y, coord_type);

    if (!image || !x || !y) {
        g_warning("Null argument passed to function");
        return;
    }

    JAW_GET_IMAGE(image, );

    (*x) = -1;
    (*y) = -1;

    jclass classAtkImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (!classAtkImage) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkImage, "get_image_position", "(I)Ljava/awt/Point;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        return;
    }
    jobject jpoint =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_image, jmid, (jint)coord_type);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
    CHECK_NULL(jpoint, );

    jclass classPoint = (*jniEnv)->FindClass(jniEnv, "java/awt/Point");
    CHECK_NULL(classPoint, );
    jfieldID jfidX = (*jniEnv)->GetFieldID(jniEnv, classPoint, "x", "I");
    CHECK_NULL(jfidX, );
    jfieldID jfidY = (*jniEnv)->GetFieldID(jniEnv, classPoint, "y", "I");
    CHECK_NULL(jfidY, );
    jint jx = (*jniEnv)->GetIntField(jniEnv, jpoint, jfidX);
    CHECK_NULL(jx, );
    jint jy = (*jniEnv)->GetIntField(jniEnv, jpoint, jfidY);
    CHECK_NULL(jy, );

    (*x) = (gint)jx;
    (*y) = (gint)jy;
}

static const gchar *jaw_image_get_image_description(AtkImage *image) {
    JAW_DEBUG_C("%p", image);

    if (!image) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_IMAGE(image, NULL);

    jclass classAtkImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (!classAtkImage) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkImage, "get_image_description", "()Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        return NULL;
    }
    jstring jstr = (*jniEnv)->CallObjectMethod(jniEnv, atk_image, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
    CHECK_NULL(jstr, NULL);

    if (data->image_description != NULL && data->jstrImageDescription != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrImageDescription,
                                         data->image_description);
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrImageDescription);
    }

    data->jstrImageDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->image_description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrImageDescription, NULL);

    return data->image_description;
}

static void jaw_image_get_image_size(AtkImage *image, gint *width,
                                     gint *height) {
    JAW_DEBUG_C("%p, %p, %p", image, width, height);

    if (!image || !width || !height) {
        g_warning("Null argument passed to function");
        return;
    }

    JAW_GET_IMAGE(image, );
    (*width) = -1;
    (*height) = -1;

    jclass classAtkImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (!classAtkImage) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkImage, "get_image_size", "()Ljava/awt/Dimension;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        return;
    }
    jobject jdimension = (*jniEnv)->CallObjectMethod(jniEnv, atk_image, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
    CHECK_NULL(jdimension, );

    jclass classDimension = (*jniEnv)->FindClass(jniEnv, "java/awt/Dimension");
    CHECK_NULL(classDimension, );
    jfieldID jfidWidth =
        (*jniEnv)->GetFieldID(jniEnv, classDimension, "width", "I");
    CHECK_NULL(jfidWidth, );
    jfieldID jfidHeight =
        (*jniEnv)->GetFieldID(jniEnv, classDimension, "height", "I");
    CHECK_NULL(jfidHeight, );
    jint jwidth = (*jniEnv)->GetIntField(jniEnv, jdimension, jfidWidth);
    CHECK_NULL(jwidth, );
    jint jheight = (*jniEnv)->GetIntField(jniEnv, jdimension, jfidHeight);
    CHECK_NULL(jheight, );

    (*width) = (gint)jwidth;
    (*height) = (gint)jheight;
}
