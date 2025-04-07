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
    JAW_GET_OBJ_IFACE(text, INTERFACE_EDITABLE_TEXT, EditableTextData,         \
                      atk_editable_text, jniEnv, atk_editable_text, def_ret)

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

    if (!iface) {
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

    if (!ac) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    EditableTextData *data = g_new0(EditableTextData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classEditableText) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classEditableText, "create_atk_editable_text",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkEditableText;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_editable_text =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classEditableText, jmid, ac);
    if (!jatk_editable_text) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->atk_editable_text =
        (*jniEnv)->NewGlobalRef(jniEnv, jatk_editable_text);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_editable_text_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    EditableTextData *data = (EditableTextData *)p;
    JAW_CHECK_NULL(data, );

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data && data->atk_editable_text) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_editable_text);
        data->atk_editable_text = NULL;
    }
}

void jaw_editable_text_set_text_contents(AtkEditableText *text,
                                         const gchar *string) {
    JAW_DEBUG_C("%p, %s", text, string);

    if (!text || !string) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkEditableText,
                               "set_text_contents", "(Ljava/lang/String;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, string);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text, jmid, jstr);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_insert_text(AtkEditableText *text, const gchar *string,
                                   gint length, gint *position) {
    JAW_DEBUG_C("%p, %s, %d, %p", text, string, length, position);

    if (!text || !string || !position) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkEditableText, "insert_text", "(Ljava/lang/String;I)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, string);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text, jmid, jstr,
                              (jint)*position);

    *position = *position + length;
    atk_text_set_caret_offset(ATK_TEXT(jaw_obj), *position);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_copy_text(AtkEditableText *text, gint start_pos,
                                 gint end_pos) {
    JAW_DEBUG_C("%p, %d, %d", text, start_pos, end_pos);

    if (!text) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkEditableText,
                                            "copy_text", "(II)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text, jmid, (jint)start_pos,
                              (jint)end_pos);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_cut_text(AtkEditableText *text, gint start_pos,
                                gint end_pos) {
    JAW_DEBUG_C("%p, %d, %d", text, start_pos, end_pos);

    if (!text) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkEditableText,
                                            "cut_text", "(II)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text, jmid, (jint)start_pos,
                              (jint)end_pos);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_delete_text(AtkEditableText *text, gint start_pos,
                                   gint end_pos) {
    JAW_DEBUG_C("%p, %d, %d", text, start_pos, end_pos);

    if (!text) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkEditableText,
                                            "delete_text", "(II)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text, jmid, (jint)start_pos,
                              (jint)end_pos);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

void jaw_editable_text_paste_text(AtkEditableText *text, gint position) {
    JAW_DEBUG_C("%p, %d", text, position);

    if (!text) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_EDITABLETEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkEditableText,
                                            "paste_text", "(I)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_editable_text, jmid, (jint)position);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
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

    if (!text || !attrib_set) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_EDITABLETEXT(text, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_editable_text); // deleting ref that was created in
                                // JAW_GET_EDITABLETEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkEditableText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkEditableText");
    if (!classAtkEditableText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkEditableText, "set_run_attributes",
        "(Ljavax/swing/text/AttributeSet;II)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_editable_text, jmid, (jobject)attrib_set,
        (jint)start_offset, (jint)end_offset);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_editable_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}
