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

    jclass classImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (classImage == NULL) {
        g_warning("%s: Failed to find AtkImage class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classImage, "create_atk_image",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkImage;");
    if (jmid == NULL) {
        g_warning("%s: Failed to find create_atk_image method", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jobject jatk_image =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classImage, jmid, ac);
    if (jatk_image == NULL) {
        g_warning("%s: Failed to create jatk_image using create_atk_image method", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    ImageData *data = g_new0(ImageData, 1);
    data->atk_image = (*jniEnv)->NewGlobalRef(jniEnv, jatk_image);

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
                (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrImageDescription,
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
 * @x: (out) (optional): address of #gint to put x coordinate position; otherwise, -1 if value cannot be obtained.
 * @y: (out) (optional): address of #gint to put y coordinate position; otherwise, -1 if value cannot be obtained.
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

    JAW_GET_IMAGE(image, ); // create global JNI reference `jobject atk_image`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_image); // deleting ref that was created in JAW_GET_IMAGE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    (*x) = -1;
    (*y) = -1;

    jclass classAtkImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (classAtkImage == NULL) {
        g_warning("%s: Failed to find AtkImage class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkImage, "get_image_position", "(I)Ljava/awt/Point;");
    if (jmid == NULL) {
        g_warning("%s: Failed to find get_image_position method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jobject jpoint =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_image, jmid, (jint)coord_type);
    if (jpoint == NULL) {
        g_warning("%s: Failed to create jpoint using get_image_position method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jclass classPoint = (*jniEnv)->FindClass(jniEnv, "java/awt/Point");
    if (classPoint == NULL) {
        g_warning("%s: Failed to find Point class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jfieldID jfidX = (*jniEnv)->GetFieldID(jniEnv, classPoint, "x", "I");
    if (jfidX == NULL) {
        g_warning("%s: Failed to find x field", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jfieldID jfidY = (*jniEnv)->GetFieldID(jniEnv, classPoint, "y", "I");
    if (jfidY == NULL) {
        g_warning("%s: Failed to find y field", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*x) = (gint)(*jniEnv)->GetIntField(jniEnv, jpoint, jfidX);
    (*y) = (gint)(*jniEnv)->GetIntField(jniEnv, jpoint, jfidY);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
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

    JAW_GET_IMAGE(image, NULL); // create global JNI reference `jobject atk_image`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_image); // deleting ref that was created in JAW_GET_IMAGE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (classAtkImage == NULL) {
        g_warning("%s: Failed to find AtkImage class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkImage, "get_image_description", "()Ljava/lang/String;");
    if (jmid == NULL) {
        g_warning("%s: Failed to find get_image_description method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(jniEnv, atk_image, jmid);
    if (jstr == NULL) {
        g_warning("%s: Failed to create jstr using get_image_description method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
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

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->image_description;
}

/**
 * jaw_image_get_image_size:
 * @image: a #GObject instance that implements AtkImageIface
 * @width: (out) (optional): filled with the image width, or -1 if the value cannot be obtained.
 * @height: (out) (optional): filled with the image height, or -1 if the value cannot be obtained.
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

    JAW_GET_IMAGE(image, ); // create global JNI reference `jobject atk_image`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_image); // deleting ref that was created in JAW_GET_IMAGE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    (*width) = -1;
    (*height) = -1;

    jclass classAtkImage =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkImage");
    if (classAtkImage == NULL) {
        g_warning("%s: Failed to find AtkImage class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkImage, "get_image_size", "()Ljava/awt/Dimension;");
    if (jmid == NULL) {
        g_warning("%s: Failed to find get_image_size method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jobject jdimension = (*jniEnv)->CallObjectMethod(jniEnv, atk_image, jmid);
    if (jdimension == NULL) {
        g_warning("%s: Failed to create jdimension using get_image_size method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_image);

    jclass classDimension = (*jniEnv)->FindClass(jniEnv, "java/awt/Dimension");
    if (classDimension == NULL) {
        g_warning("%s: Failed to find Dimension class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidWidth =
        (*jniEnv)->GetFieldID(jniEnv, classDimension, "width", "I");
    if (jfidWidth == NULL) {
        g_warning("%s: Failed to find width field", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidHeight =
        (*jniEnv)->GetFieldID(jniEnv, classDimension, "height", "I");
    if (jfidHeight == NULL) {
        g_warning("%s: Failed to find height field", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*width) = (gint)(*jniEnv)->GetIntField(jniEnv, jdimension, jfidWidth);
    (*height) = (gint)(*jniEnv)->GetIntField(jniEnv, jdimension, jfidHeight);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}
