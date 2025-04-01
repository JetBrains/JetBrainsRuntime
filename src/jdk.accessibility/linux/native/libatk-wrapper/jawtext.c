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

static gchar *jaw_text_get_text(AtkText *text, gint start_offset,
                                gint end_offset);

static gunichar jaw_text_get_character_at_offset(AtkText *text, gint offset);

static gchar *jaw_text_get_text_at_offset(AtkText *text, gint offset,
                                          AtkTextBoundary boundary_type,
                                          gint *start_offset, gint *end_offset);

static gchar *jaw_text_get_text_before_offset(AtkText *text, gint offset,
                                              AtkTextBoundary boundary_type,
                                              gint *start_offset,
                                              gint *end_offset);

static gchar *jaw_text_get_text_after_offset(AtkText *text, gint offset,
                                             AtkTextBoundary boundary_type,
                                             gint *start_offset,
                                             gint *end_offset);

static gchar *jaw_text_get_string_at_offset(AtkText *text, gint offset,
                                            AtkTextGranularity granularity,
                                            gint *start_offset,
                                            gint *end_offset);

static gint jaw_text_get_caret_offset(AtkText *text);

static void jaw_text_get_character_extents(AtkText *text, gint offset, gint *x,
                                           gint *y, gint *width, gint *height,
                                           AtkCoordType coords);

static gint jaw_text_get_character_count(AtkText *text);

static gint jaw_text_get_offset_at_point(AtkText *text, gint x, gint y,
                                         AtkCoordType coords);
static void jaw_text_get_range_extents(AtkText *text, gint start_offset,
                                       gint end_offset, AtkCoordType coord_type,
                                       AtkTextRectangle *rect);

static gint jaw_text_get_n_selections(AtkText *text);

static gchar *jaw_text_get_selection(AtkText *text, gint selection_num,
                                     gint *start_offset, gint *end_offset);

static gboolean jaw_text_add_selection(AtkText *text, gint start_offset,
                                       gint end_offset);

static gboolean jaw_text_remove_selection(AtkText *text, gint selection_num);

static gboolean jaw_text_set_selection(AtkText *text, gint selection_num,
                                       gint start_offset, gint end_offset);

static gboolean jaw_text_set_caret_offset(AtkText *text, gint offset);

typedef struct _TextData {
    jobject atk_text;
    gchar *text;
    jstring jstrText;
} TextData;

#define JAW_GET_TEXT(text, def_ret)                                            \
    JAW_GET_OBJ_IFACE(text, INTERFACE_TEXT, TextData, atk_text, jniEnv,        \
                      atk_text, def_ret)

void jaw_text_interface_init(AtkTextIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function jaw_text_interface_init");
        return;
    }

    iface->get_text = jaw_text_get_text;
    iface->get_text_after_offset = jaw_text_get_text_after_offset;
    iface->get_text_at_offset = jaw_text_get_text_at_offset;
    iface->get_character_at_offset = jaw_text_get_character_at_offset;
    iface->get_text_before_offset = jaw_text_get_text_before_offset;
    iface->get_string_at_offset = jaw_text_get_string_at_offset;
    iface->get_caret_offset = jaw_text_get_caret_offset;
    // TODO: iface->get_run_attributes by iterating getCharacterAttribute or
    // using getTextSequenceAt with ATTRIBUTE_RUN
    // TODO: iface->get_default_attributes
    iface->get_character_extents = jaw_text_get_character_extents;
    iface->get_character_count = jaw_text_get_character_count;
    iface->get_offset_at_point = jaw_text_get_offset_at_point;
    iface->get_n_selections = jaw_text_get_n_selections;
    iface->get_selection = jaw_text_get_selection;
    iface->add_selection = jaw_text_add_selection;
    iface->remove_selection = jaw_text_remove_selection;
    iface->set_selection = jaw_text_set_selection;
    iface->set_caret_offset = jaw_text_set_caret_offset;

    iface->get_range_extents = jaw_text_get_range_extents;
    // TODO: iface->get_bounded_ranges from getTextBounds
    // TODO: iface->get_string_at_offset

    // TODO: missing java support for:
    // iface->scroll_substring_to
    // iface->scroll_substring_to_point
}

gpointer jaw_text_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("Null argument passed to function jaw_text_data_init");
        return NULL;
    }

    TextData *data = g_new0(TextData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classText) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classText, "create_atk_text",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkText;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_text =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classText, jmid, ac);
    if (!jatk_text) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    data->atk_text = (*jniEnv)->NewGlobalRef(jniEnv, jatk_text);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_text_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function jaw_text_data_finalize");
        return;
    }

    TextData *data = (TextData *)p;
    JAW_CHECK_NULL(data, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data->jstrText != NULL) {
        if (data->text != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrText,
                                             data->text);
            data->text = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrText);
        data->jstrText = NULL;
    }

    if (data->atk_text != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_text);
        data->atk_text = NULL;
    }
}

static gchar *jaw_text_get_gtext_from_jstr(JNIEnv *jniEnv, jstring jstr) {
    JAW_DEBUG_C("%p, %p", jniEnv, jstr);

    if (!jniEnv || !jstr) {
        g_warning(
            "Null argument passed to function jaw_text_get_gtext_from_jstr");
        return NULL;
    }

    gchar *tmp_text = (gchar *)(*jniEnv)->GetStringUTFChars(jniEnv, jstr, NULL);
    JAW_CHECK_NULL(tmp_text, NULL);
    gchar *text = g_strdup(tmp_text);
    (*jniEnv)->ReleaseStringUTFChars(jniEnv, jstr, tmp_text);

    return text;
}

static gchar *jaw_text_get_gtext_from_string_seq(JNIEnv *jniEnv,
                                                 jobject jStrSeq,
                                                 gint *start_offset,
                                                 gint *end_offset) {
    if (!jniEnv || !start_offset || !end_offset) {
        g_warning("Null argument passed to function "
                  "jaw_text_get_gtext_from_string_seq");
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classStringSeq = (*jniEnv)->FindClass(
        jniEnv, "org/GNOME/Accessibility/AtkText$StringSequence");
    if (!classStringSeq) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID jfidStr = (*jniEnv)->GetFieldID(jniEnv, classStringSeq, "str",
                                             "Ljava/lang/String;");
    if (!jfidStr) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID jfidStart =
        (*jniEnv)->GetFieldID(jniEnv, classStringSeq, "start_offset", "I");
    if (!jfidStart) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID jfidEnd =
        (*jniEnv)->GetFieldID(jniEnv, classStringSeq, "end_offset", "I");
    if (!jfidEnd) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jStr = (*jniEnv)->GetObjectField(jniEnv, jStrSeq, jfidStr);
    if (!jStr) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jint jStart = (*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidStart);
    if (!jStart) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jint jEnd = (*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidEnd);
    if (!jEnd) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*start_offset) = (gint)jStart;
    (*end_offset) = (gint)jEnd;

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_jstr(jniEnv, jStr);
}

static gchar *jaw_text_get_text(AtkText *text, gint start_offset,
                                gint end_offset) {
    JAW_DEBUG_C("%p, %d, %d", text, start_offset, end_offset);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_get_text");
        return NULL;
    }

    JAW_GET_TEXT(text, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText, "get_text",
                                            "(II)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, jmid, (jint)start_offset, (jint)end_offset);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_jstr(jniEnv, jstr);
}

static gunichar jaw_text_get_character_at_offset(AtkText *text, gint offset) {
    JAW_DEBUG_C("%p, %d", text, offset);

    if (!text) {
        g_warning("Null argument passed to function "
                  "jaw_text_get_character_at_offset");
        return 0;
    }

    JAW_GET_TEXT(text, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText,
                                            "get_character_at_offset", "(I)C");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jchar jcharacter =
        (*jniEnv)->CallCharMethod(jniEnv, atk_text, jmid, (jint)offset);
    if (!jcharacter) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gunichar)jcharacter;
}

/**
 * jaw_text_get_text_after_offset:
 * @text: an #AtkText
 * @offset: position
 * @boundary_type: An #AtkTextBoundary
 * @start_offset: (out): the starting character offset of the returned string
 * @end_offset: (out): the offset of the first character after the
 *              returned substring
 *
 * Gets the specified text.
 *
 * Deprecated: 2.9.3 in atk: Please use atk_text_get_string_at_offset() instead.
 *
 * Returns: a newly allocated string containing the text after @offset bounded
 *          by the specified @boundary_type. Use g_free() to free the returned
 *          string.
 **/
static gchar *jaw_text_get_text_after_offset(AtkText *text, gint offset,
                                             AtkTextBoundary boundary_type,
                                             gint *start_offset,
                                             gint *end_offset) {
    JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, boundary_type, start_offset,
                end_offset);

    if (!text || !start_offset || !end_offset) {
        g_warning(
            "Null argument passed to function jaw_text_get_text_after_offset");
        return NULL;
    }

    JAW_GET_TEXT(text, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkText, "get_text_after_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, jmid, (jint)offset, (jint)boundary_type);
    if (!jStrSeq) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset,
                                              end_offset);
}

/**
 * jaw_text_get_text_at_offset:
 * @text: an #AtkText
 * @offset: position
 * @boundary_type: An #AtkTextBoundary
 * @start_offset: (out): the starting character offset of the returned string
 * @end_offset: (out): the offset of the first character after the
 *              returned substring
 *
 * Deprecated: This method is deprecated since ATK version
 * 2.9.4. Please use atk_text_get_string_at_offset() instead.
 *
 * Returns: a newly allocated string containing the text at @offset bounded
 *          by the specified @boundary_type. Use g_free() to free the returned
 *          string.
 **/
static gchar *jaw_text_get_text_at_offset(AtkText *text, gint offset,
                                          AtkTextBoundary boundary_type,
                                          gint *start_offset,
                                          gint *end_offset) {
    JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, boundary_type, start_offset,
                end_offset);

    if (!text || !start_offset || !end_offset) {
        g_warning(
            "Null argument passed to function jaw_text_get_text_at_offset");
        return NULL;
    }

    JAW_GET_TEXT(text, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkText, "get_text_at_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, jmid, (jint)offset, (jint)boundary_type);
    if (!jStrSeq) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset,
                                              end_offset);
}

/**
 * jaw_text_get_text_before_offset:
 * @text: an #AtkText
 * @offset: position
 * @boundary_type: An #AtkTextBoundary
 * @start_offset: (out): the starting character offset of the returned string
 * @end_offset: (out): the offset of the first character after the
 *              returned substring
 *
 * Gets the specified text.
 *
 * Deprecated in atk: 2.9.3: Please use atk_text_get_string_at_offset() instead.
 *
 * Returns: a newly allocated string containing the text before @offset bounded
 *          by the specified @boundary_type. Use g_free() to free the returned
 *          string.
 **/
static gchar *jaw_text_get_text_before_offset(AtkText *text, gint offset,
                                              AtkTextBoundary boundary_type,
                                              gint *start_offset,
                                              gint *end_offset) {
    JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, boundary_type, start_offset,
                end_offset);

    if (!text || !start_offset || !end_offset) {
        g_warning(
            "Null argument passed to function jaw_text_get_text_before_offset");
        return NULL;
    }

    JAW_GET_TEXT(text, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkText, "get_text_before_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, jmid, (jint)offset, (jint)boundary_type);
    if (!jStrSeq) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset,
                                              end_offset);
}

/**
 * jaw_text_get_string_at_offset:
 * @text: an #AtkText
 * @offset: position
 * @granularity: An #AtkTextGranularity
 * @start_offset: (out): the starting character offset of the returned string,
 *or -1 in the case of error (e.g. invalid offset, not implemented)
 * @end_offset: (out): the offset of the first character after the returned
 *string, or -1 in the case of error (e.g. invalid offset, not implemented)
 *
 * Gets a portion of the text exposed through an #AtkText according to a given
 *@offset and a specific @granularity, along with the start and end offsets
 *defining the boundaries of such a portion of text.
 *
 * If @granularity is ATK_TEXT_GRANULARITY_CHAR the character at the
 * offset is returned.
 *
 * If @granularity is ATK_TEXT_GRANULARITY_WORD the returned string
 * is from the word start at or before the offset to the word start after
 * the offset.
 *
 * The returned string will contain the word at the offset if the offset
 * is inside a word and will contain the word before the offset if the
 * offset is not inside a word.
 *
 * If @granularity is ATK_TEXT_GRANULARITY_SENTENCE the returned string
 * is from the sentence start at or before the offset to the sentence
 * start after the offset.
 *
 * The returned string will contain the sentence at the offset if the offset
 * is inside a sentence and will contain the sentence before the offset
 * if the offset is not inside a sentence.
 *
 * If @granularity is ATK_TEXT_GRANULARITY_LINE the returned string
 * is from the line start at or before the offset to the line
 * start after the offset.
 *
 * If @granularity is ATK_TEXT_GRANULARITY_PARAGRAPH the returned string
 * is from the start of the paragraph at or before the offset to the start
 * of the following paragraph after the offset.
 *
 * Since: 2.10 (in atk)
 *
 * Returns: (nullable): a newly allocated string containing the text at
 *          the @offset bounded by the specified @granularity. Use g_free()
 *          to free the returned string.  Returns %NULL if the offset is invalid
 *          or no implementation is available.
 **/
static gchar *jaw_text_get_string_at_offset(AtkText *text, gint offset,
                                            AtkTextGranularity granularity,
                                            gint *start_offset,
                                            gint *end_offset) {
    JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, granularity, start_offset,
                end_offset);

    if (!text || !start_offset || !end_offset) {
        g_warning(
            "jaw_text_get_text_after_offset: Null argument passed to function");
        return NULL;
    }

    JAW_GET_TEXT(text, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkText, "get_string_at_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, jmid, (jint)offset, (jint)granularity);
    if (!jStrSeq) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset,
                                              end_offset);
}

static gint jaw_text_get_caret_offset(AtkText *text) {
    JAW_DEBUG_C("%p", text);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_get_caret_offset");
        return 0;
    }

    JAW_GET_TEXT(text, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkText, "get_caret_offset", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint joffset = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid);
    if (!joffset) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)joffset;
}

static void jaw_text_get_character_extents(AtkText *text, gint offset, gint *x,
                                           gint *y, gint *width, gint *height,
                                           AtkCoordType coords) {
    JAW_DEBUG_C("%p, %d, %p, %p, %p, %p, %d", text, offset, x, y, width, height,
                coords);

    if (!text || !x || !y || !width || !height) {
        g_warning(
            "Null argument passed to function jaw_text_get_character_extents");
        return;
    }

    *x = -1;
    *y = -1;
    *width = -1;
    *height = -1;

    JAW_GET_TEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkText, "get_character_extents",
                               "(II)Ljava/awt/Rectangle;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject jrect = (*jniEnv)->CallObjectMethod(jniEnv, atk_text, jmid,
                                                (jint)offset, (jint)coords);
    if (!jrect) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    jaw_util_get_rect_info(jniEnv, jrect, x, y, width, height);
}

static gint jaw_text_get_character_count(AtkText *text) {
    JAW_DEBUG_C("%p", text);

    if (!text) {
        g_warning(
            "Null argument passed to function jaw_text_get_character_count");
        return 0;
    }

    JAW_GET_TEXT(text, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText,
                                            "get_character_count", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jcount = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid);
    if (!jcount) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)jcount;
}

static gint jaw_text_get_offset_at_point(AtkText *text, gint x, gint y,
                                         AtkCoordType coords) {
    JAW_DEBUG_C("%p, %d, %d, %d", text, x, y, coords);

    if (!text) {
        g_warning(
            "Null argument passed to function jaw_text_get_offset_at_point");
        return 0;
    }

    JAW_GET_TEXT(text, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText,
                                            "get_offset_at_point", "(III)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint joffset = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid, (jint)x,
                                            (jint)y, (jint)coords);
    if (!joffset) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)joffset;
}

static void jaw_text_get_range_extents(AtkText *text, gint start_offset,
                                       gint end_offset, AtkCoordType coord_type,
                                       AtkTextRectangle *rect) {
    JAW_DEBUG_C("%p, %d, %d, %d, %p", text, start_offset, end_offset,
                coord_type, rect);

    if (!text || !rect) {
        g_warning(
            "Null argument passed to function jaw_text_get_range_extents");
        return;
    }

    rect->x = -1;
    rect->y = -1;
    rect->width = -1;
    rect->height = -1;

    JAW_GET_TEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkText, "get_range_extents", "(III)Ljava/awt/Rectangle;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject jrect =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_text, jmid, (jint)start_offset,
                                    (jint)end_offset, (jint)coord_type);
    if (!jrect) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    jaw_util_get_rect_info(jniEnv, jrect, &(rect->x), &(rect->y),
                           &(rect->width), &(rect->height));
}

static gint jaw_text_get_n_selections(AtkText *text) {
    JAW_DEBUG_C("%p", text);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_get_n_selections");
        return 0;
    }

    JAW_GET_TEXT(text, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkText, "get_n_selections", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jselections = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid);
    if (!jselections) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)jselections;
}

static gchar *jaw_text_get_selection(AtkText *text, gint selection_num,
                                     gint *start_offset, gint *end_offset) {
    JAW_DEBUG_C("%p, %d, %p, %p", text, selection_num, start_offset,
                end_offset);

    if (!text || !start_offset || !end_offset) {
        g_warning("Null argument passed to function jaw_text_get_selection");
        return NULL;
    }

    JAW_GET_TEXT(text, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkText, "get_selection",
        "()Lorg/GNOME/Accessibility/AtkText$StringSequence;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jStrSeq = (*jniEnv)->CallObjectMethod(jniEnv, atk_text, jmid);
    if (!jStrSeq) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    jclass classStringSeq = (*jniEnv)->FindClass(
        jniEnv, "org/GNOME/Accessibility/AtkText$StringSequence");
    JAW_CHECK_NULL(classStringSeq, NULL);
    jfieldID jfidStr = (*jniEnv)->GetFieldID(jniEnv, classStringSeq, "str",
                                             "Ljava/lang/String;");
    if (!jfidStr) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID jfidStart =
        (*jniEnv)->GetFieldID(jniEnv, classStringSeq, "start_offset", "I");
    if (!jfidStart) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID jfidEnd =
        (*jniEnv)->GetFieldID(jniEnv, classStringSeq, "end_offset", "I");
    if (!jfidEnd) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jstring jStr = (*jniEnv)->GetObjectField(jniEnv, jStrSeq, jfidStr);
    if (!jStr) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    *start_offset = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidStart);
    if (!start_offset) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    *end_offset = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidEnd);
    if (!end_offset) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_text_get_gtext_from_jstr(jniEnv, jStr);
}

static gboolean jaw_text_add_selection(AtkText *text, gint start_offset,
                                       gint end_offset) {
    JAW_DEBUG_C("%p, %d, %d", text, start_offset, end_offset);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_add_selection");
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkText, "add_selection", "(II)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_text, jmid, (jint)start_offset, (jint)end_offset);
    if (!jresult) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}

static gboolean jaw_text_remove_selection(AtkText *text, gint selection_num) {
    JAW_DEBUG_C("%p, %d", text, selection_num);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_remove_selection");
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText,
                                            "remove_selection", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv, atk_text, jmid,
                                                    (jint)selection_num);
    if (!jresult) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}

static gboolean jaw_text_set_selection(AtkText *text, gint selection_num,
                                       gint start_offset, gint end_offset) {
    JAW_DEBUG_C("%p, %d, %d, %d", text, selection_num, start_offset,
                end_offset);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_set_selection");
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkText, "set_selection", "(III)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_text, jmid, (jint)selection_num, (jint)start_offset,
        (jint)end_offset);
    if (!jresult) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}

static gboolean jaw_text_set_caret_offset(AtkText *text, gint offset) {
    JAW_DEBUG_C("%p, %d", text, offset);

    if (!text) {
        g_warning("Null argument passed to function jaw_text_set_caret_offset");
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkText =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if (!classAtkText) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText,
                                            "set_caret_offset", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_text, jmid, (jint)offset);
    if (!jresult) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}