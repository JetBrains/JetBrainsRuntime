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
#include "jawobject.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkEditableText:
 *
 * The ATK interface implemented by components containing user-editable text
 * content.
 *
 * #AtkEditableText should be implemented by UI components which
 * contain text which the user can edit, via the #AtkObject
 * corresponding to that component (see #AtkObject).
 *
 * #AtkEditableText is a subclass of #AtkText, and as such, an object
 * which implements #AtkEditableText is by definition an #AtkText
 * implementor as well.
 *
 * See [iface@AtkText]
 */

static jclass cachedEditableTextAtkEditableTextClass = NULL;
static jmethodID cachedEditableTextCreateAtkEditableTextMethod = NULL;
static jmethodID cachedEditableTextSetTextContentsMethod = NULL;
static jmethodID cachedEditableTextInsertTextMethod = NULL;
static jmethodID cachedEditableTextCopyTextMethod = NULL;
static jmethodID cachedEditableTextCutTextMethod = NULL;
static jmethodID cachedEditableTextDeleteTextMethod = NULL;
static jmethodID cachedEditableTextPasteTextMethod = NULL;
static jmethodID cachedEditableTextSetRunAttributesMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_editable_text_init_jni_cache(JNIEnv *jniEnv);

static void jaw_editable_text_set_text_contents(AtkEditableText *text,
                                                const gchar *string);
static void jaw_editable_text_insert_text(AtkEditableText *text,
                                          const gchar *string, gint length,
                                          gint *position);
static void jaw_editable_text_copy_text(AtkEditableText *text, gint start_pos,
                                        gint end_pos);
static void jaw_editable_text_cut_text(AtkEditableText *text, gint start_pos,
                                       gint end_pos);
static void jaw_editable_text_delete_text(AtkEditableText *text, gint start_pos,
                                          gint end_pos);
static void jaw_editable_text_paste_text(AtkEditableText *text, gint position);

static gboolean
jaw_editable_text_set_run_attributes(AtkEditableText *text,
                                     AtkAttributeSet *attrib_set,
                                     gint start_offset, gint end_offset);

typedef struct _EditableTextData {
    jobject atk_editable_text;
} EditableTextData;

#define JAW_GET_EDITABLETEXT(text, def_ret)                                    \
    JAW_GET_OBJ_IFACE(                                                         \
        text, org_GNOME_Accessibility_AtkInterface_INTERFACE_EDITABLE_TEXT,    \
        EditableTextData, atk_editable_text, jniEnv, atk_editable_text,        \
        def_ret)

/**
 * AtkEditableTextIface:
 * @set_run_attributes:
 * @set_text_contents:
 * @copy_text:
 * @cut_text:
 * @delete_text:
 * @paste_text:
 **/
void jaw_editable_text_interface_init(AtkEditableTextIface *iface,
                                      gpointer data) {
    JAW_DEBUG_ALL("%p,%p", iface, data);

    if (iface == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->set_run_attributes = jaw_editable_text_set_run_attributes;
    iface->set_text_contents = jaw_editable_text_set_text_contents;
    iface->insert_text = jaw_editable_text_insert_text;
    iface->copy_text = jaw_editable_text_copy_text;
    iface->cut_text = jaw_editable_text_cut_text;
    iface->delete_text = jaw_editable_text_delete_text;
    iface->paste_text = jaw_editable_text_paste_text;
}

gpointer jaw_editable_text_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (ac == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);
    if (!jaw_editable_text_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, JAW_DEFAULT_LOCAL_FRAME_SIZE) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_editable_text = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedEditableTextAtkEditableTextClass,
        cachedEditableTextCreateAtkEditableTextMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_editable_text == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jatk_editable_text using "
                  "create_atk_editable_text method",
                  G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    EditableTextData *data = g_new0(EditableTextData, 1);
    data->atk_editable_text =
        (*jniEnv)->NewGlobalRef(jniEnv, jatk_editable_text);
    if (data->atk_editable_text == NULL) {
        g_warning("%s: Failed to create global ref for atk_editable_text",
                  G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_editable_text_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_debug("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    EditableTextData *data = (EditableTextData *)p;
    if (data == NULL) {
        g_debug("%s: data is null after cast", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->atk_editable_text != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_editable_text);
            data->atk_editable_text = NULL;
        }
    }

    g_free(data);
}

void jaw_editable_text_set_text_contents(AtkEditableText *text,
                                         const gchar *string) {
    JAW_DEBUG_C("%p, %s", text, string);

    if (text == NULL || string == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(
        text, ); // create local JNI reference `jobject atk_editable_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, JAW_DEFAULT_LOCAL_FRAME_SIZE) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_editable_text);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, string);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jstr using NewStringUTF", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text,
                              cachedEditableTextSetTextContentsMethod, jstr);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_insert_text(AtkEditableText *text, const gchar *string,
                                   gint length, gint *position) {
    JAW_DEBUG_C("%p, %s, %d, %p", text, string, length, position);

    if (text == NULL || string == NULL || position == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(
        text, ); // create local JNI reference `jobject atk_editable_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, JAW_DEFAULT_LOCAL_FRAME_SIZE) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_editable_text);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, string);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jstr using NewStringUTF", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text,
                              cachedEditableTextInsertTextMethod, jstr,
                              (jint)*position);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    *position = *position + length;
    atk_text_set_caret_offset(ATK_TEXT(text), *position);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_copy_text(AtkEditableText *text, gint start_pos,
                                 gint end_pos) {
    JAW_DEBUG_C("%p, %d, %d", text, start_pos, end_pos);

    if (text == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(
        text, ); // create local JNI reference `jobject atk_editable_text`

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text,
                              cachedEditableTextCopyTextMethod, (jint)start_pos,
                              (jint)end_pos);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
}

void jaw_editable_text_cut_text(AtkEditableText *text, gint start_pos,
                                gint end_pos) {
    JAW_DEBUG_C("%p, %d, %d", text, start_pos, end_pos);

    if (text == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(
        text, ); // create local JNI reference `jobject atk_editable_text`

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text,
                              cachedEditableTextCutTextMethod, (jint)start_pos,
                              (jint)end_pos);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
}

void jaw_editable_text_delete_text(AtkEditableText *text, gint start_pos,
                                   gint end_pos) {
    JAW_DEBUG_C("%p, %d, %d", text, start_pos, end_pos);

    if (text == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(
        text, ); // create local JNI reference `jobject atk_editable_text`

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text,
                              cachedEditableTextDeleteTextMethod,
                              (jint)start_pos, (jint)end_pos);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
}

void jaw_editable_text_paste_text(AtkEditableText *text, gint position) {
    JAW_DEBUG_C("%p, %d", text, position);

    if (text == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(
        text, ); // create local JNI reference `jobject atk_editable_text`

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text,
                              cachedEditableTextPasteTextMethod,
                              (jint)position);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
}

/**
 *jaw_editable_text_set_run_attributes:
 *@text: an #AtkEditableText
 *@attrib_set: an #AtkAttributeSet
 *@start_offset: start of range in which to set attributes
 *@end_offset: end of range in which to set attributes
 *
 *Returns: %TRUE if attributes successfully set for the specified
 *range, otherwise %FALSE
 **/
static gboolean
jaw_editable_text_set_run_attributes(AtkEditableText *text,
                                     AtkAttributeSet *attrib_set,
                                     gint start_offset, gint end_offset) {
    JAW_DEBUG_C("%p, %p, %d, %d", text, attrib_set, start_offset, end_offset);

    if (text == NULL || attrib_set == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_EDITABLETEXT(
        text, FALSE); // create local JNI reference `jobject atk_editable_text`

    // TODO: make a proper conversion between attrib_set and swing AttributeSet,
    // current implementation is incorrect
    jboolean jresult = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_editable_text, cachedEditableTextSetRunAttributesMethod,
        (jobject)attrib_set, (jint)start_offset, (jint)end_offset);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_editable_text);

    return jresult;
}

static gboolean jaw_editable_text_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkEditableText class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedEditableTextAtkEditableTextClass =
        (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedEditableTextAtkEditableTextClass == NULL) {
        g_warning(
            "%s: Failed to create global reference for AtkEditableText class",
            G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedEditableTextCreateAtkEditableTextMethod =
        (*jniEnv)->GetStaticMethodID(
            jniEnv, cachedEditableTextAtkEditableTextClass,
            "create_atk_editable_text",
            "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
            "AtkEditableText;");

    cachedEditableTextSetTextContentsMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedEditableTextAtkEditableTextClass,
                               "set_text_contents", "(Ljava/lang/String;)V");

    cachedEditableTextInsertTextMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedEditableTextAtkEditableTextClass,
                               "insert_text", "(Ljava/lang/String;I)V");

    cachedEditableTextCopyTextMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedEditableTextAtkEditableTextClass, "copy_text", "(II)V");

    cachedEditableTextCutTextMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedEditableTextAtkEditableTextClass, "cut_text", "(II)V");

    cachedEditableTextDeleteTextMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedEditableTextAtkEditableTextClass, "delete_text", "(II)V");

    cachedEditableTextPasteTextMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedEditableTextAtkEditableTextClass, "paste_text", "(I)V");

    cachedEditableTextSetRunAttributesMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedEditableTextAtkEditableTextClass, "set_run_attributes",
        "(Ljavax/swing/text/AttributeSet;II)Z");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedEditableTextCreateAtkEditableTextMethod == NULL ||
        cachedEditableTextSetTextContentsMethod == NULL ||
        cachedEditableTextInsertTextMethod == NULL ||
        cachedEditableTextCopyTextMethod == NULL ||
        cachedEditableTextCutTextMethod == NULL ||
        cachedEditableTextDeleteTextMethod == NULL ||
        cachedEditableTextPasteTextMethod == NULL ||
        cachedEditableTextSetRunAttributesMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkEditableText method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);

    g_debug("%s: classes and methods cached successfully", G_STRFUNC);

    return TRUE;

cleanup_and_fail:
    if (cachedEditableTextAtkEditableTextClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv,
                                   cachedEditableTextAtkEditableTextClass);
        cachedEditableTextAtkEditableTextClass = NULL;
    }
    cachedEditableTextCreateAtkEditableTextMethod = NULL;
    cachedEditableTextSetTextContentsMethod = NULL;
    cachedEditableTextInsertTextMethod = NULL;
    cachedEditableTextCopyTextMethod = NULL;
    cachedEditableTextCutTextMethod = NULL;
    cachedEditableTextDeleteTextMethod = NULL;
    cachedEditableTextPasteTextMethod = NULL;
    cachedEditableTextSetRunAttributesMethod = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_editable_text_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedEditableTextAtkEditableTextClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv,
                                   cachedEditableTextAtkEditableTextClass);
        cachedEditableTextAtkEditableTextClass = NULL;
    }
    cachedEditableTextCreateAtkEditableTextMethod = NULL;
    cachedEditableTextSetTextContentsMethod = NULL;
    cachedEditableTextInsertTextMethod = NULL;
    cachedEditableTextCopyTextMethod = NULL;
    cachedEditableTextCutTextMethod = NULL;
    cachedEditableTextDeleteTextMethod = NULL;
    cachedEditableTextPasteTextMethod = NULL;
    cachedEditableTextSetRunAttributesMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
