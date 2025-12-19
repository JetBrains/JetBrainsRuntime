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

#include "jawcache.h"
#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkImage:
 *
 * The ATK Interface implemented by components
 *  which expose image or pixmap content on-screen.
 *
 * #AtkImage should be implemented by #AtkObject subtypes on behalf of
 * components which display image/pixmap information onscreen, and
 * which provide information (other than just widget borders, etc.)
 * via that image content.  For instance, icons, buttons with icons,
 * toolbar elements, and image viewing panes typically should
 * implement #AtkImage.
 *
 * #AtkImage primarily provides two types of information: coordinate
 * information (useful for screen review mode of screenreaders, and
 * for use by onscreen magnifiers), and descriptive information.  The
 * descriptive information is provided for alternative, text-only
 * presentation of the most significant information present in the
 * image.
 */

jclass cachedImageAtkImageClass = NULL;
jmethodID cachedImageCreateAtkImageMethod = NULL;
jmethodID cachedImageGetImagePositionMethod = NULL;
jmethodID cachedImageGetImageDescriptionMethod = NULL;
jmethodID cachedImageGetImageSizeMethod = NULL;
jclass cachedImagePointClass = NULL;
jfieldID cachedImagePointXFieldID = NULL;
jfieldID cachedImagePointYFieldID = NULL;
jclass cachedImageDimensionClass = NULL;
jfieldID cachedImageDimensionWidthFieldID = NULL;
jfieldID cachedImageDimensionHeightFieldID = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_image_init_jni_cache(JNIEnv *jniEnv);

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
    JAW_GET_OBJ_IFACE(image,                                                   \
                      org_GNOME_Accessibility_AtkInterface_INTERFACE_IMAGE,    \
                      ImageData, atk_image, jniEnv, atk_image, def_ret)

/**
 * AtkImageIface:
 * @get_image_position:
 * @get_image_description:
 * @get_image_size
 * @set_image_description:
 * @get_image_locale:
 **/
void jaw_image_interface_init(AtkImageIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (iface == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->get_image_position = jaw_image_get_image_position;
    iface->get_image_description = jaw_image_get_image_description;
    iface->get_image_size = jaw_image_get_image_size;
    iface->set_image_description = NULL; // TODO: iface->set_image_description
    iface->get_image_locale = NULL;      // TODO: iface->get_image_locale from
                                         //  AccessibleContext.getLocale()
}

gpointer jaw_image_data_init(jobject ac) {
    JAW_DEBUG_C("%p", ac);

    if (ac == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    if (!jaw_image_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jobject jatk_image = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedImageAtkImageClass, cachedImageCreateAtkImageMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_image == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning(
            "%s: Failed to create jatk_image using create_atk_image method",
            G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    ImageData *data = g_new0(ImageData, 1);
    data->atk_image = (*jniEnv)->NewGlobalRef(jniEnv, jatk_image);
    if (data->atk_image == NULL) {
        g_warning("%s: Failed to create global ref for atk_image", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_image_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    ImageData *data = (ImageData *)p;
    if (data == NULL) {
        g_warning("%s: data is null after cast", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->jstrImageDescription != NULL) {
            if (data->image_description != NULL) {
                (*jniEnv)->ReleaseStringUTFChars(jniEnv,
                                                 data->jstrImageDescription,
                                                 data->image_description);
                data->image_description = NULL;
            }
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrImageDescription);
            data->jstrImageDescription = NULL;
        }

        if (data->atk_image != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_image);
            data->atk_image = NULL;
        }
    }

    g_free(data);
}

/**
 * jaw_image_get_image_position:
 * @image: a #GObject instance that implements AtkImageIface
 * @x: (out) (optional): address of #gint to put x coordinate position;
 *otherwise, -1 if value cannot be obtained.
 * @y: (out) (optional): address of #gint to put y coordinate position;
 *otherwise, -1 if value cannot be obtained.
 * @coord_type: specifies whether the coordinates are relative to the screen
 * or to the components top level window
 *
 * Gets the position of the image in the form of a point specifying the
 * images top-left corner.
 *
 * If the position can not be obtained (e.g. missing support), x and y are set
 * to -1.
 **/
static void jaw_image_get_image_position(AtkImage *image, gint *x, gint *y,
                                         AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %p, %p, %d", image, x, y, coord_type);

    if (image == NULL || x == NULL || y == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_IMAGE(image, ); // create local JNI reference `jobject atk_image`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_image);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    (*x) = -1;
    (*y) = -1;

    jobject jpoint = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_image, cachedImageGetImagePositionMethod, (jint)coord_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jpoint == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jpoint using get_image_position method",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*x) =
        (gint)(*jniEnv)->GetIntField(jniEnv, jpoint, cachedImagePointXFieldID);
    (*y) =
        (gint)(*jniEnv)->GetIntField(jniEnv, jpoint, cachedImagePointYFieldID);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_image);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_image_get_image_description:
 * @image: a #GObject instance that implements AtkImageIface
 *
 * Get a textual description of this image.
 *
 * Returns: a string representing the image description or NULL
 **/
static const gchar *jaw_image_get_image_description(AtkImage *image) {
    JAW_DEBUG_C("%p", image);

    if (image == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_IMAGE(image,
                  NULL); // create local JNI reference `jobject atk_image`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_image);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_image, cachedImageGetImageDescriptionMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning(
            "%s: Failed to create jstr using get_image_description method",
            G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->jstrImageDescription != NULL) {
        if (data->image_description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrImageDescription,
                                             data->image_description);
            data->image_description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrImageDescription);
        data->jstrImageDescription = NULL;
    }

    data->jstrImageDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->image_description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrImageDescription, NULL);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_image);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->image_description;
}

/**
 * jaw_image_get_image_size:
 * @image: a #GObject instance that implements AtkImageIface
 * @width: (out) (optional): filled with the image width, or -1 if the value
 *cannot be obtained.
 * @height: (out) (optional): filled with the image height, or -1 if the value
 *cannot be obtained.
 *
 * Get the width and height in pixels for the specified image.
 * The values of @width and @height are returned as -1 if the
 * values cannot be obtained (for instance, if the object is not onscreen).
 *
 * If the size can not be obtained (e.g. missing support), x and y are set
 * to -1.
 **/

static void jaw_image_get_image_size(AtkImage *image, gint *width,
                                     gint *height) {
    JAW_DEBUG_C("%p, %p, %p", image, width, height);

    if (image == NULL || width == NULL || height == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_IMAGE(image, ); // create local JNI reference `jobject atk_image`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_image);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    (*width) = -1;
    (*height) = -1;

    jobject jdimension = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_image, cachedImageGetImageSizeMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jdimension == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jdimension using get_image_size method",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_image);

    (*width) = (gint)(*jniEnv)->GetIntField(jniEnv, jdimension,
                                            cachedImageDimensionWidthFieldID);
    (*height) = (gint)(*jniEnv)->GetIntField(jniEnv, jdimension,
                                             cachedImageDimensionHeightFieldID);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static gboolean jaw_image_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkImage class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedImageAtkImageClass = (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedImageAtkImageClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkImage class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedImageCreateAtkImageMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedImageAtkImageClass, "create_atk_image",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkImage;");

    cachedImageGetImagePositionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedImageAtkImageClass,
                               "get_image_position", "(I)Ljava/awt/Point;");

    cachedImageGetImageDescriptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedImageAtkImageClass,
                               "get_image_description", "()Ljava/lang/String;");

    cachedImageGetImageSizeMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedImageAtkImageClass,
                               "get_image_size", "()Ljava/awt/Dimension;");

    jclass localPointClass = (*jniEnv)->FindClass(jniEnv, "java/awt/Point");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localPointClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Point class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedImagePointClass = (*jniEnv)->NewGlobalRef(jniEnv, localPointClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localPointClass);

    if ((*jniEnv)->ExceptionCheck(jniEnv) || cachedImagePointClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create global reference for Point class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedImagePointXFieldID =
        (*jniEnv)->GetFieldID(jniEnv, cachedImagePointClass, "x", "I");
    cachedImagePointYFieldID =
        (*jniEnv)->GetFieldID(jniEnv, cachedImagePointClass, "y", "I");

    jclass localDimensionClass =
        (*jniEnv)->FindClass(jniEnv, "java/awt/Dimension");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localDimensionClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find Dimension class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedImageDimensionClass =
        (*jniEnv)->NewGlobalRef(jniEnv, localDimensionClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localDimensionClass);

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedImageDimensionClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create global reference for Dimension class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedImageDimensionWidthFieldID =
        (*jniEnv)->GetFieldID(jniEnv, cachedImageDimensionClass, "width", "I");
    cachedImageDimensionHeightFieldID =
        (*jniEnv)->GetFieldID(jniEnv, cachedImageDimensionClass, "height", "I");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedImageCreateAtkImageMethod == NULL ||
        cachedImageGetImagePositionMethod == NULL ||
        cachedImageGetImageDescriptionMethod == NULL ||
        cachedImageGetImageSizeMethod == NULL ||
        cachedImagePointXFieldID == NULL || cachedImagePointYFieldID == NULL ||
        cachedImageDimensionWidthFieldID == NULL ||
        cachedImageDimensionHeightFieldID == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning(
            "%s: Failed to cache one or more AtkImage method IDs or field IDs",
            G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedImageAtkImageClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedImageAtkImageClass);
        cachedImageAtkImageClass = NULL;
    }
    if (cachedImagePointClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedImagePointClass);
        cachedImagePointClass = NULL;
    }
    if (cachedImageDimensionClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedImageDimensionClass);
        cachedImageDimensionClass = NULL;
    }
    cachedImageCreateAtkImageMethod = NULL;
    cachedImageGetImagePositionMethod = NULL;
    cachedImageGetImageDescriptionMethod = NULL;
    cachedImageGetImageSizeMethod = NULL;
    cachedImagePointXFieldID = NULL;
    cachedImagePointYFieldID = NULL;
    cachedImageDimensionWidthFieldID = NULL;
    cachedImageDimensionHeightFieldID = NULL;

    g_mutex_unlock(&cache_mutex);

    g_debug("%s: classes and methods cached successfully", G_STRFUNC);

    return FALSE;
}

void jaw_image_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedImageAtkImageClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedImageAtkImageClass);
        cachedImageAtkImageClass = NULL;
    }
    if (cachedImagePointClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedImagePointClass);
        cachedImagePointClass = NULL;
    }
    if (cachedImageDimensionClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedImageDimensionClass);
        cachedImageDimensionClass = NULL;
    }
    cachedImageCreateAtkImageMethod = NULL;
    cachedImageGetImagePositionMethod = NULL;
    cachedImageGetImageDescriptionMethod = NULL;
    cachedImageGetImageSizeMethod = NULL;
    cachedImagePointXFieldID = NULL;
    cachedImagePointYFieldID = NULL;
    cachedImageDimensionWidthFieldID = NULL;
    cachedImageDimensionHeightFieldID = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
