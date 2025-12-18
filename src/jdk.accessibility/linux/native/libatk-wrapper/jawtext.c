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
#include "jawcache.h"
#include <atk/atk.h>
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkText:
 *
 * The ATK interface implemented by components with text content.
 *
 * #AtkText should be implemented by #AtkObjects on behalf of widgets
 * that have text content which is either attributed or otherwise
 * non-trivial.  #AtkObjects whose text content is simple,
 * unattributed, and very brief may expose that content via
 * #atk_object_get_name instead; however if the text is editable,
 * multi-line, typically longer than three or four words, attributed,
 * selectable, or if the object already uses the 'name' ATK property
 * for other information, the #AtkText interface should be used to
 * expose the text content.  In the case of editable text content,
 * #AtkEditableText (a subtype of the #AtkText interface) should be
 * implemented instead.
 *
 *  #AtkText provides not only traversal facilities and change
 * notification for text content, but also caret tracking and glyph
 * bounding box calculations.  Note that the text strings are exposed
 * as UTF-8, and are therefore potentially multi-byte, and
 * caret-to-byte offset mapping makes no assumptions about the
 * character length; also bounding box glyph-to-offset mapping may be
 * complex for languages which use ligatures.
 */

jclass cachedTextAtkTextClass = NULL;
jmethodID cachedTextCreateAtkTextMethod = NULL;
jmethodID cachedTextGetTextMethod = NULL;
jmethodID cachedTextGetCharacterAtOffsetMethod = NULL;
jmethodID cachedTextGetTextAfterOffsetMethod = NULL;
jmethodID cachedTextGetTextAtOffsetMethod = NULL;
jmethodID cachedTextGetTextBeforeOffsetMethod = NULL;
jmethodID cachedTextGetStringAtOffsetMethod = NULL;
jmethodID cachedTextGetCaretOffsetMethod = NULL;
jmethodID cachedTextGetCharacterExtentsMethod = NULL;
jmethodID cachedTextGetCharacterCountMethod = NULL;
jmethodID cachedTextGetOffsetAtPointMethod = NULL;
jmethodID cachedTextGetRangeExtentsMethod = NULL;
jmethodID cachedTextGetNSelectionsMethod = NULL;
jmethodID cachedTextGetSelectionMethod = NULL;
jmethodID cachedTextAddSelectionMethod = NULL;
jmethodID cachedTextRemoveSelectionMethod = NULL;
jmethodID cachedTextSetSelectionMethod = NULL;
jmethodID cachedTextSetCaretOffsetMethod = NULL;

jclass cachedTextStringSequenceClass = NULL;
jfieldID cachedTextStrFieldID = NULL;
jfieldID cachedTextStartOffsetFieldID = NULL;
jfieldID cachedTextEndOffsetFieldID = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_text_init_jni_cache(JNIEnv *jniEnv);

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
} TextData;

#define JAW_GET_TEXT(text, def_ret)                                            \
    JAW_GET_OBJ_IFACE(text,                                                    \
                      org_GNOME_Accessibility_AtkInterface_INTERFACE_TEXT,     \
                      TextData, atk_text, jniEnv, atk_text, def_ret)

void jaw_text_interface_init(AtkTextIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (iface == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->get_text = jaw_text_get_text;
    iface->get_text_after_offset = jaw_text_get_text_after_offset;
    iface->get_text_at_offset = jaw_text_get_text_at_offset;
    iface->get_character_at_offset = jaw_text_get_character_at_offset;
    iface->get_text_before_offset = jaw_text_get_text_before_offset;
    iface->get_string_at_offset = jaw_text_get_string_at_offset;
    iface->get_caret_offset = jaw_text_get_caret_offset;
    iface->get_run_attributes =
        NULL; // TODO: iface->get_run_attributes by iterating
              // getCharacterAttribute or using getTextSequenceAt with
              // ATTRIBUTE_RUN
    iface->get_default_attributes = NULL; // TODO: iface->get_default_attributes
    iface->get_character_extents = jaw_text_get_character_extents;
    iface->get_character_count = jaw_text_get_character_count;
    iface->get_offset_at_point = jaw_text_get_offset_at_point;
    iface->get_n_selections = jaw_text_get_n_selections;
    iface->get_selection = jaw_text_get_selection;
    iface->add_selection = jaw_text_add_selection;
    iface->remove_selection = jaw_text_remove_selection;
    iface->set_selection = jaw_text_set_selection;
    iface->set_caret_offset = jaw_text_set_caret_offset;

    /*
     * signal handlers
     */
    // iface->text_changed implemented by atk
    // iface->text_caret_moved implemented by atk
    // iface->text_selection_changed implemented bt atk
    // iface->text_attributes_changed implemented by atk
    iface->get_range_extents = jaw_text_get_range_extents;
    iface->get_bounded_ranges =
        NULL; // TODO: iface->get_bounded_ranges from getTextBounds
    iface->get_string_at_offset = NULL; // TODO: iface->get_string_at_offset

    /*
     * Scrolls this text range so it becomes visible on the screen.
     *
     * scroll_substring_to lets the implementation compute an appropriate target
     * position on the screen, with type used as a positioning hint.
     *
     * scroll_substring_to_point lets the client specify a precise target
     * position on the screen for the top-left of the substring.
     *
     * Since ATK 2.32
     */
    iface->scroll_substring_to = NULL;       // missing java support
    iface->scroll_substring_to_point = NULL; // missing java support
}

gpointer jaw_text_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (ac == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_text_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_text = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedTextAtkTextClass, cachedTextCreateAtkTextMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_text == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jatk_text using create_atk_text method", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    TextData *data = g_new0(TextData, 1);
    data->atk_text = (*jniEnv)->NewGlobalRef(jniEnv, jatk_text);
    if (data->atk_text == NULL) {
        g_warning("%s: Failed to create global ref for atk_text", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_text_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    TextData *data = (TextData *)p;
    if (data == NULL) {
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->atk_text != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_text);
            data->atk_text = NULL;
        }
    }

    g_free(data);
}

static gchar *private_jaw_text_get_gtext_from_jstr(JNIEnv *jniEnv,
                                                   jstring jstr) {
    JAW_DEBUG_C("%p, %p", jniEnv, jstr);

    if (jniEnv == NULL || jstr == NULL) {
        g_warning("%s: Null argument passed. jniEnv=%p, jstr=%p",
                  G_STRFUNC,
                  (void*)jniEnv,
                  (void*)jstr);
        return NULL;
    }

    gchar *tmp_text = (gchar *)(*jniEnv)->GetStringUTFChars(jniEnv, jstr, NULL);
    JAW_CHECK_NULL(tmp_text, NULL);
    gchar *text = g_strdup(tmp_text);
    (*jniEnv)->ReleaseStringUTFChars(jniEnv, jstr, tmp_text);

    return text;
}

static gchar *private_jaw_text_get_gtext_from_string_seq(JNIEnv *jniEnv,
                                                         jobject jStrSeq,
                                                         gint *start_offset,
                                                         gint *end_offset) {
    if (jniEnv == NULL || start_offset == NULL || end_offset == NULL) {
        g_warning("%s: Null argument. jniEnv=%p, start_offset=%p, end_offset=%p",
                  G_STRFUNC,
                  (void*)jniEnv,
                  (void*)start_offset,
                  (void*)end_offset);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jStr = (*jniEnv)->GetObjectField(jniEnv, jStrSeq, cachedTextStrFieldID);
    if (jStr == NULL) {
        g_warning("%s: Failed to get jStr field", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*start_offset) = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, cachedTextStartOffsetFieldID);
    (*end_offset) = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, cachedTextEndOffsetFieldID);

    gchar *result = private_jaw_text_get_gtext_from_jstr(jniEnv, jStr);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

/**
 * atk_text_get_text:
 * @text: an #AtkText
 * @start_offset: a starting character offset within @text
 * @end_offset: an ending character offset within @text, or -1 for the end of
 *the string.
 *
 * Gets the specified text.
 *
 * Returns: a newly allocated string containing the text from @start_offset up
 *          to, but not including @end_offset. Use g_free() to free the returned
 *          string.
 **/
static gchar *jaw_text_get_text(AtkText *text, gint start_offset,
                                gint end_offset) {
    JAW_DEBUG_C("%p, %d, %d", text, start_offset, end_offset);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TEXT(text, NULL); // create global JNI reference `jobject atk_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, cachedTextGetTextMethod, (jint)start_offset, (jint)end_offset);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jstr using get_text method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    gchar *result = private_jaw_text_get_gtext_from_jstr(jniEnv, jstr);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

/**
 * jaw_text_get_character_at_offset:
 * @text: an #AtkText
 * @offset: a character offset within @text
 *
 * Gets the specified text.
 *
 * Returns: the character at @offset or 0 in the case of failure.
 **/
static gunichar jaw_text_get_character_at_offset(AtkText *text, gint offset) {
    JAW_DEBUG_C("%p, %d", text, offset);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TEXT(text, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jchar jcharacter =
        (*jniEnv)->CallCharMethod(jniEnv, atk_text, cachedTextGetCharacterAtOffsetMethod, (jint)offset);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    if (jcharacter == '\0') {
        g_warning("%s: jcharacter is '\\0'", G_STRFUNC);
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

    if (text == NULL || start_offset == NULL || end_offset == NULL) {
        g_warning("%s: Null argument. text=%p, start_offset=%p, end_offset=%p",
                  G_STRFUNC,
                  (void*)text,
                  (void*)start_offset,
                  (void*)end_offset);
        return NULL;
    }

    JAW_GET_TEXT(text, NULL); // create global JNI reference `jobject atk_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, cachedTextGetTextAfterOffsetMethod, (jint)offset, (jint)boundary_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jStrSeq == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jStrSeq using get_text_after_offset method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    gchar *result = private_jaw_text_get_gtext_from_string_seq(
        jniEnv, jStrSeq, start_offset, end_offset);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
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

    if (text == NULL || start_offset == NULL || end_offset == NULL) {
        g_warning("%s: Null argument. text=%p, start_offset=%p, end_offset=%p",
                  G_STRFUNC,
                  (void*)text,
                  (void*)start_offset,
                  (void*)end_offset);
        return NULL;
    }

    JAW_GET_TEXT(text, NULL); // create global JNI reference `jobject atk_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, cachedTextGetTextAtOffsetMethod, (jint)offset, (jint)boundary_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jStrSeq == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jStrSeq using get_text_at_offset method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    char *result = private_jaw_text_get_gtext_from_string_seq(
        jniEnv, jStrSeq, start_offset, end_offset);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
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

    if (text == NULL || start_offset == NULL || end_offset == NULL) {
        g_warning("%s: Null argument. text=%p, start_offset=%p, end_offset=%p",
                  G_STRFUNC,
                  (void*)text,
                  (void*)start_offset,
                  (void*)end_offset);
        return NULL;
    }

    JAW_GET_TEXT(text, NULL); // create global JNI reference `jobject atk_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, cachedTextGetTextBeforeOffsetMethod, (jint)offset, (jint)boundary_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jStrSeq == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jStrSeq using get_text_before_offset method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    char *result = private_jaw_text_get_gtext_from_string_seq(
        jniEnv, jStrSeq, start_offset, end_offset);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
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

    if (text == NULL || start_offset == NULL || end_offset == NULL) {
        g_warning("%s: Null argument. text=%p, start_offset=%p, end_offset=%p",
                  G_STRFUNC,
                  (void*)text,
                  (void*)start_offset,
                  (void*)end_offset);
        return NULL;
    }

    JAW_GET_TEXT(text, NULL); // create global JNI reference `jobject atk_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jStrSeq = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_text, cachedTextGetStringAtOffsetMethod, (jint)offset, (jint)granularity);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jStrSeq == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jStrSeq using get_string_at_offset method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    char *result = private_jaw_text_get_gtext_from_string_seq(
        jniEnv, jStrSeq, start_offset, end_offset);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

/**
 * jaw_text_get_caret_offset:
 * @text: an #AtkText
 *
 * Gets the offset of the position of the caret (cursor).
 *
 * Returns: the character offset of the position of the caret or -1 if
 *          the caret is not located inside the element or in the case of
 *          any other failure.
 **/
static gint jaw_text_get_caret_offset(AtkText *text) {
    JAW_DEBUG_C("%p", text);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TEXT(text, -1);

    jint joffset = (*jniEnv)->CallIntMethod(jniEnv, atk_text, cachedTextGetCaretOffsetMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return (gint)joffset;
}

/**
 * jaw_text_get_character_extents:
 * @text: an #AtkText
 * @offset: The offset of the text character for which bounding information is
 *required.
 * @x: (out) (optional): Pointer for the x coordinate of the bounding box
 * @y: (out) (optional): Pointer for the y coordinate of the bounding box
 * @width: (out) (optional): Pointer for the width of the bounding box
 * @height: (out) (optional): Pointer for the height of the bounding box
 * @coords: specify whether coordinates are relative to the screen or widget
 *window
 *
 * If the extent can not be obtained (e.g. missing support), all of x, y, width,
 * height are set to -1.
 *
 * Get the bounding box containing the glyph representing the character at
 *     a particular text offset.
 **/
static void jaw_text_get_character_extents(AtkText *text, gint offset, gint *x,
                                           gint *y, gint *width, gint *height,
                                           AtkCoordType coords) {
    JAW_DEBUG_C("%p, %d, %p, %p, %p, %p, %d", text, offset, x, y, width, height,
                coords);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return;
    }

    if (x != NULL) *x = -1;
    if (y != NULL) *y = -1;
    if (width != NULL) *width = -1;
    if (height != NULL) *height = -1;

    JAW_GET_TEXT(text, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jobject jrect = (*jniEnv)->CallObjectMethod(jniEnv, atk_text, cachedTextGetCharacterExtentsMethod,
                                                (jint)offset, (jint)coords);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jrect == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jrect using get_character_extents method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    gint temp_x, temp_y, temp_width, temp_height;
    jaw_util_get_rect_info(jniEnv, jrect, &temp_x, &temp_y, &temp_width, &temp_height);

    if (x != NULL) *x = temp_x;
    if (y != NULL) *y = temp_y;
    if (width != NULL) *width = temp_width;
    if (height != NULL) *height = temp_height;

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_text_get_character_count:
 * @text: an #AtkText
 *
 * Gets the character count.
 *
 * Returns: the number of characters or -1 in case of failure.
 **/
static gint jaw_text_get_character_count(AtkText *text) {
    JAW_DEBUG_C("%p", text);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TEXT(text, -1);

    jint jcount = (*jniEnv)->CallIntMethod(jniEnv, atk_text, cachedTextGetCharacterCountMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return (gint)jcount;
}

/**
 * jaw_text_get_offset_at_point:
 * @text: an #AtkText
 * @x: screen x-position of character
 * @y: screen y-position of character
 * @coords: specify whether coordinates are relative to the screen or
 * widget window
 *
 * Gets the offset of the character located at coordinates @x and @y. @x and @y
 * are interpreted as being relative to the screen or this widget's window
 * depending on @coords.
 *
 * Returns: the offset to the character which is located at  the specified
 *          @x and @y coordinates of -1 in case of failure.
 **/
static gint jaw_text_get_offset_at_point(AtkText *text, gint x, gint y,
                                         AtkCoordType coords) {
    JAW_DEBUG_C("%p, %d, %d, %d", text, x, y, coords);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TEXT(text, -1);

    jint joffset = (*jniEnv)->CallIntMethod(jniEnv, atk_text, cachedTextGetOffsetAtPointMethod, (jint)x,
                                            (jint)y, (jint)coords);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return (gint)joffset;
}

/**
 * jaw_text_get_range_extents:
 * @text: an #AtkText
 * @start_offset: The offset of the first text character for which boundary
 *        information is required.
 * @end_offset: The offset of the text character after the last character
 *        for which boundary information is required.
 * @coord_type: Specify whether coordinates are relative to the screen or widget
 *window.
 * @rect: (out): A pointer to a AtkTextRectangle which is filled in by this
 *function.
 *
 * Get the bounding box for text within the specified range.
 *
 * If the extents can not be obtained (e.g. or missing support), the rectangle
 * fields are set to -1.
 *
 * In Atk Since: 1.3
 **/
static void jaw_text_get_range_extents(AtkText *text, gint start_offset,
                                       gint end_offset, AtkCoordType coord_type,
                                       AtkTextRectangle *rect) {
    JAW_DEBUG_C("%p, %d, %d, %d, %p", text, start_offset, end_offset,
                coord_type, rect);

    if (text == NULL || rect == NULL) {
        g_warning("%s: Null argument. text=%p, rect=%p",
                  G_STRFUNC,
                  (void*)text,
                  (void*)rect);
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
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jobject jrect = (*jniEnv)->CallObjectMethod(jniEnv, atk_text, cachedTextGetRangeExtentsMethod,
                                                (jint)start_offset, (jint)end_offset, (jint)coord_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jrect == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jrect using get_range_extents method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jaw_util_get_rect_info(jniEnv, jrect, &(rect->x), &(rect->y),
                           &(rect->width), &(rect->height));

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_text_get_n_selections:
 * @text: an #AtkText
 *
 * Gets the number of selected regions.
 *
 * Returns: The number of selected regions, or -1 in the case of failure.
 **/
static gint jaw_text_get_n_selections(AtkText *text) {
    JAW_DEBUG_C("%p", text);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TEXT(text, -1);

    jint jselections = (*jniEnv)->CallIntMethod(jniEnv, atk_text, cachedTextGetNSelectionsMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return (gint)jselections;
}

/**
 * jaw_text_get_selection:
 * @text: an #AtkText
 * @selection_num: The selection number.  The selected regions are
 * assigned numbers that correspond to how far the region is from the
 * start of the text.  The selected region closest to the beginning
 * of the text region is assigned the number 0, etc.  Note that adding,
 * moving or deleting a selected region can change the numbering.
 * @start_offset: (out): passes back the starting character offset of the
 *selected region
 * @end_offset: (out): passes back the ending character offset (offset
 *immediately past) of the selected region
 *
 * Gets the text from the specified selection.
 *
 * Returns: a newly allocated string containing the selected text. Use g_free()
 *          to free the returned string.
 **/
static gchar *jaw_text_get_selection(AtkText *text, gint selection_num,
                                     gint *start_offset, gint *end_offset) {
    JAW_DEBUG_C("%p, %d, %p, %p", text, selection_num, start_offset,
                end_offset);

    if (text == NULL || start_offset == NULL || end_offset == NULL) {
        g_warning("%s: Null argument. text=%p, start_offset=%p, end_offset=%p",
                  G_STRFUNC,
                  (void*)text,
                  (void*)start_offset,
                  (void*)end_offset);
        return NULL;
    }

    JAW_GET_TEXT(text, NULL); // create global JNI reference `jobject atk_text`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_text); // deleting ref that was created in JAW_GET_TEXT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    // Java AccessibleText only supports a single selection, so selection_num is not used.
    jobject jStrSeq = (*jniEnv)->CallObjectMethod(jniEnv, atk_text, cachedTextGetSelectionMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jStrSeq == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jStrSeq using get_selection method", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    jstring jStr = (*jniEnv)->GetObjectField(jniEnv, jStrSeq, cachedTextStrFieldID);
    if (jStr == NULL) {
        g_warning("%s: Failed to get jStr field", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    *start_offset = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, cachedTextStartOffsetFieldID);
    *end_offset = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, cachedTextEndOffsetFieldID);

    gchar *result = private_jaw_text_get_gtext_from_jstr(jniEnv, jStr);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

/**
 * jaw_text_add_selection:
 * @text: an #AtkText
 * @start_offset: the starting character offset of the selected region
 * @end_offset: the offset of the first character after the selected region.
 *
 * Adds a selection bounded by the specified offsets.
 *
 * Returns: %TRUE if successful, %FALSE otherwise
 **/
static gboolean jaw_text_add_selection(AtkText *text, gint start_offset,
                                       gint end_offset) {
    JAW_DEBUG_C("%p, %d, %d", text, start_offset, end_offset);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv, atk_text, cachedTextAddSelectionMethod,
                                                    (jint)start_offset, (jint)end_offset);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return jresult;
}

/**
 * jaw_text_remove_selection:
 * @text: an #AtkText
 * @selection_num: The selection number.  The selected regions are
 * assigned numbers that correspond to how far the region is from the
 * start of the text.  The selected region closest to the beginning
 * of the text region is assigned the number 0, etc.  Note that adding,
 * moving or deleting a selected region can change the numbering.
 *
 * Removes the specified selection.
 *
 * Returns: %TRUE if successful, %FALSE otherwise
 **/
static gboolean jaw_text_remove_selection(AtkText *text, gint selection_num) {
    JAW_DEBUG_C("%p, %d", text, selection_num);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv, atk_text, cachedTextRemoveSelectionMethod,
                                                    (jint)selection_num);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return jresult;
}

/**
 * jaw_text_set_selection:
 * @text: an #AtkText
 * @selection_num: The selection number.  The selected regions are
 * assigned numbers that correspond to how far the region is from the
 * start of the text.  The selected region closest to the beginning
 * of the text region is assigned the number 0, etc.  Note that adding,
 * moving or deleting a selected region can change the numbering.
 * @start_offset: the new starting character offset of the selection
 * @end_offset: the new end position of (e.g. offset immediately past)
 * the selection
 *
 * Changes the start and end offset of the specified selection.
 *
 * Returns: %TRUE if successful, %FALSE otherwise
 **/
static gboolean jaw_text_set_selection(AtkText *text, gint selection_num,
                                       gint start_offset, gint end_offset) {
    JAW_DEBUG_C("%p, %d, %d, %d", text, selection_num, start_offset,
                end_offset);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv, atk_text, cachedTextSetSelectionMethod,
                                                    (jint)selection_num, (jint)start_offset,
                                                    (jint)end_offset);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return jresult;
}

/**
 * jaw_text_set_caret_offset:
 * @text: an #AtkText
 * @offset: the character offset of the new caret position
 *
 * Sets the caret (cursor) position to the specified @offset.
 *
 * In the case of rich-text content, this method should either grab focus
 * or move the sequential focus navigation starting point (if the application
 * supports this concept) as if the user had clicked on the new caret position.
 * Typically, this means that the target of this operation is the node
 *containing the new caret position or one of its ancestors. In other words,
 *after this method is called, if the user advances focus, it should move to the
 *first focusable node following the new caret position.
 *
 * Calling this method should also scroll the application viewport in a way
 * that matches the behavior of the application's typical caret motion or tab
 * navigation as closely as possible. This also means that if the application's
 * caret motion or focus navigation does not trigger a scroll operation, this
 * method should not trigger one either. If the application does not have a
 *caret motion or focus navigation operation, this method should try to scroll
 *the new caret position into view while minimizing unnecessary scroll motion.
 *
 * Returns: %TRUE if successful, %FALSE otherwise.
 **/
static gboolean jaw_text_set_caret_offset(AtkText *text, gint offset) {
    JAW_DEBUG_C("%p, %d", text, offset);

    if (text == NULL) {
        g_warning("%s: Null argument text passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TEXT(text, FALSE);

    jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv, atk_text, cachedTextSetCaretOffsetMethod,
                                                    (jint)offset);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

    return jresult;
}

static gboolean jaw_text_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClassAtkText = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClassAtkText == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkText class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedTextAtkTextClass = (*jniEnv)->NewGlobalRef(jniEnv, localClassAtkText);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClassAtkText);

    if (cachedTextAtkTextClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkText class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedTextCreateAtkTextMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedTextAtkTextClass, "create_atk_text",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/AtkText;");

    cachedTextGetTextMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_text", "(II)Ljava/lang/String;");

    cachedTextGetCharacterAtOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_character_at_offset", "(I)C");

    cachedTextGetTextAfterOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_text_after_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");

    cachedTextGetTextAtOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_text_at_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");

    cachedTextGetTextBeforeOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_text_before_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");

    cachedTextGetStringAtOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_string_at_offset",
        "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");

    cachedTextGetCaretOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_caret_offset", "()I");

    cachedTextGetCharacterExtentsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_character_extents", "(II)Ljava/awt/Rectangle;");

    cachedTextGetCharacterCountMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_character_count", "()I");

    cachedTextGetOffsetAtPointMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_offset_at_point", "(III)I");

    cachedTextGetRangeExtentsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_range_extents", "(III)Ljava/awt/Rectangle;");

    cachedTextGetNSelectionsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_n_selections", "()I");

    cachedTextGetSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "get_selection",
        "()Lorg/GNOME/Accessibility/AtkText$StringSequence;");

    cachedTextAddSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "add_selection", "(II)Z");

    cachedTextRemoveSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "remove_selection", "(I)Z");

    cachedTextSetSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "set_selection", "(III)Z");

    cachedTextSetCaretOffsetMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTextAtkTextClass, "set_caret_offset", "(I)Z");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedTextCreateAtkTextMethod == NULL ||
        cachedTextGetTextMethod == NULL ||
        cachedTextGetCharacterAtOffsetMethod == NULL ||
        cachedTextGetTextAfterOffsetMethod == NULL ||
        cachedTextGetTextAtOffsetMethod == NULL ||
        cachedTextGetTextBeforeOffsetMethod == NULL ||
        cachedTextGetStringAtOffsetMethod == NULL ||
        cachedTextGetCaretOffsetMethod == NULL ||
        cachedTextGetCharacterExtentsMethod == NULL ||
        cachedTextGetCharacterCountMethod == NULL ||
        cachedTextGetOffsetAtPointMethod == NULL ||
        cachedTextGetRangeExtentsMethod == NULL ||
        cachedTextGetNSelectionsMethod == NULL ||
        cachedTextGetSelectionMethod == NULL ||
        cachedTextAddSelectionMethod == NULL ||
        cachedTextRemoveSelectionMethod == NULL ||
        cachedTextSetSelectionMethod == NULL ||
        cachedTextSetCaretOffsetMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkText method IDs", G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localClassStringSeq = (*jniEnv)->FindClass(
        jniEnv, "org/GNOME/Accessibility/AtkText$StringSequence");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClassStringSeq == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkText$StringSequence class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedTextStringSequenceClass = (*jniEnv)->NewGlobalRef(jniEnv, localClassStringSeq);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClassStringSeq);

    if ((*jniEnv)->ExceptionCheck(jniEnv) || cachedTextStringSequenceClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create global reference for AtkText$StringSequence class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedTextStrFieldID = (*jniEnv)->GetFieldID(
        jniEnv, cachedTextStringSequenceClass, "str", "Ljava/lang/String;");

    cachedTextStartOffsetFieldID = (*jniEnv)->GetFieldID(
        jniEnv, cachedTextStringSequenceClass, "start_offset", "I");

    cachedTextEndOffsetFieldID = (*jniEnv)->GetFieldID(
        jniEnv, cachedTextStringSequenceClass, "end_offset", "I");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedTextStrFieldID == NULL ||
        cachedTextStartOffsetFieldID == NULL ||
        cachedTextEndOffsetFieldID == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache StringSequence field IDs", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedTextAtkTextClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedTextAtkTextClass);
        cachedTextAtkTextClass = NULL;
    }
    if (cachedTextStringSequenceClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedTextStringSequenceClass);
        cachedTextStringSequenceClass = NULL;
    }
    cachedTextCreateAtkTextMethod = NULL;
    cachedTextGetTextMethod = NULL;
    cachedTextGetCharacterAtOffsetMethod = NULL;
    cachedTextGetTextAfterOffsetMethod = NULL;
    cachedTextGetTextAtOffsetMethod = NULL;
    cachedTextGetTextBeforeOffsetMethod = NULL;
    cachedTextGetStringAtOffsetMethod = NULL;
    cachedTextGetCaretOffsetMethod = NULL;
    cachedTextGetCharacterExtentsMethod = NULL;
    cachedTextGetCharacterCountMethod = NULL;
    cachedTextGetOffsetAtPointMethod = NULL;
    cachedTextGetRangeExtentsMethod = NULL;
    cachedTextGetNSelectionsMethod = NULL;
    cachedTextGetSelectionMethod = NULL;
    cachedTextAddSelectionMethod = NULL;
    cachedTextRemoveSelectionMethod = NULL;
    cachedTextSetSelectionMethod = NULL;
    cachedTextSetCaretOffsetMethod = NULL;
    cachedTextStrFieldID = NULL;
    cachedTextStartOffsetFieldID = NULL;
    cachedTextEndOffsetFieldID = NULL;
    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_text_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedTextAtkTextClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedTextAtkTextClass);
        cachedTextAtkTextClass = NULL;
    }
    if (cachedTextStringSequenceClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedTextStringSequenceClass);
        cachedTextStringSequenceClass = NULL;
    }
    cachedTextCreateAtkTextMethod = NULL;
    cachedTextGetTextMethod = NULL;
    cachedTextGetCharacterAtOffsetMethod = NULL;
    cachedTextGetTextAfterOffsetMethod = NULL;
    cachedTextGetTextAtOffsetMethod = NULL;
    cachedTextGetTextBeforeOffsetMethod = NULL;
    cachedTextGetStringAtOffsetMethod = NULL;
    cachedTextGetCaretOffsetMethod = NULL;
    cachedTextGetCharacterExtentsMethod = NULL;
    cachedTextGetCharacterCountMethod = NULL;
    cachedTextGetOffsetAtPointMethod = NULL;
    cachedTextGetRangeExtentsMethod = NULL;
    cachedTextGetNSelectionsMethod = NULL;
    cachedTextGetSelectionMethod = NULL;
    cachedTextAddSelectionMethod = NULL;
    cachedTextRemoveSelectionMethod = NULL;
    cachedTextSetSelectionMethod = NULL;
    cachedTextSetCaretOffsetMethod = NULL;
    cachedTextStrFieldID = NULL;
    cachedTextStartOffsetFieldID = NULL;
    cachedTextEndOffsetFieldID = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
