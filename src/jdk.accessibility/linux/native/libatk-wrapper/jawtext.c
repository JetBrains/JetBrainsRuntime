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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

#include <atk/atk.h>
#include <glib.h>
#include "jawimpl.h"
#include "jawutil.h"

static gchar* jaw_text_get_text(AtkText *text,
                                gint start_offset,
                                gint end_offset);

static gunichar jaw_text_get_character_at_offset(AtkText *text, gint offset);

static gchar* jaw_text_get_text_at_offset(AtkText *text,
                                          gint offset,
                                          AtkTextBoundary boundary_type,
                                          gint *start_offset,
                                          gint *end_offset);

static gchar* jaw_text_get_text_before_offset(AtkText *text,
                                              gint offset,
                                              AtkTextBoundary boundary_type,
                                              gint *start_offset,
                                              gint *end_offset);

static gchar* jaw_text_get_text_after_offset(AtkText *text,
                                             gint offset,
                                             AtkTextBoundary boundary_type,
                                             gint *start_offset,
                                             gint *end_offset);

static gint jaw_text_get_caret_offset(AtkText *text);

static void jaw_text_get_character_extents(AtkText *text,
                                            gint offset,
                                            gint *x,
                                            gint *y,
                                            gint *width,
                                            gint *height,
                                            AtkCoordType coords);

static gint jaw_text_get_character_count(AtkText *text);

static gint jaw_text_get_offset_at_point(AtkText *text,
                                          gint x,
                                          gint y,
                                          AtkCoordType coords);
static void jaw_text_get_range_extents(AtkText *text,
                                        gint start_offset,
                                        gint end_offset,
                                        AtkCoordType coord_type,
                                        AtkTextRectangle *rect);

static gint jaw_text_get_n_selections(AtkText *text);

static gchar* jaw_text_get_selection(AtkText *text,
                                     gint selection_num,
                                     gint *start_offset,
                                     gint *end_offset);

static gboolean jaw_text_add_selection(AtkText *text,
                                       gint start_offset,
                                       gint end_offset);

static gboolean jaw_text_remove_selection(AtkText *text, gint selection_num);

static gboolean jaw_text_set_selection(AtkText *text,
                                       gint selection_num,
                                       gint start_offset,
                                       gint end_offset);

static gboolean jaw_text_set_caret_offset(AtkText *text, gint offset);

typedef struct _TextData {
  jobject atk_text;
  gchar* text;
  jstring jstrText;
}TextData;

#define JAW_GET_TEXT(text, def_ret) \
  JAW_GET_OBJ_IFACE(text, INTERFACE_TEXT, TextData, atk_text, jniEnv, atk_text, def_ret)

void
jaw_text_interface_init (AtkTextIface *iface, gpointer data)
{
  JAW_DEBUG_ALL("%p, %p", iface, data);
  iface->get_text = jaw_text_get_text;
  iface->get_text_after_offset = jaw_text_get_text_after_offset;
  iface->get_text_at_offset = jaw_text_get_text_at_offset;
  iface->get_character_at_offset = jaw_text_get_character_at_offset;
  iface->get_text_before_offset = jaw_text_get_text_before_offset;
  iface->get_caret_offset = jaw_text_get_caret_offset;
  // TODO: iface->get_run_attributes by iterating getCharacterAttribute or using getTextSequenceAt with ATTRIBUTE_RUN
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

gpointer
jaw_text_data_init (jobject ac)
{
  JAW_DEBUG_ALL("%p", ac);
  TextData *data = g_new0(TextData, 1);

  JNIEnv *jniEnv = jaw_util_get_jni_env();
  jclass classText = (*jniEnv)->FindClass(jniEnv,
                                          "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetStaticMethodID(jniEnv,
                                          classText,
                                          "createAtkText",
                                          "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/AtkText;");
  jobject jatk_text = (*jniEnv)->CallStaticObjectMethod(jniEnv, classText, jmid, ac);
  data->atk_text = (*jniEnv)->NewGlobalRef(jniEnv, jatk_text);

  return data;
}

void
jaw_text_data_finalize (gpointer p)
{
  JAW_DEBUG_ALL("%p", p);
  TextData *data = (TextData*)p;
  JNIEnv *jniEnv = jaw_util_get_jni_env();

  if (data && data->atk_text)
  {
    if (data->text != NULL)
    {
      (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrText, data->text);
      (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrText);
      data->jstrText = NULL;
      data->text = NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_text);
    data->atk_text = NULL;
  }
}

static gchar*
jaw_text_get_gtext_from_jstr (JNIEnv *jniEnv, jstring jstr)
{
  JAW_DEBUG_C("%p, %p", jniEnv, jstr);
  if (jstr == NULL)
  {
    return NULL;
  }

  gchar* tmp_text = (gchar*)(*jniEnv)->GetStringUTFChars(jniEnv, jstr, NULL);
  gchar* text = g_strdup(tmp_text);
  (*jniEnv)->ReleaseStringUTFChars(jniEnv, jstr, tmp_text);

  return text;
}

static gchar*
jaw_text_get_text (AtkText *text, gint start_offset, gint end_offset)
{
  JAW_DEBUG_C("%p, %d, %d", text, start_offset, end_offset);
  JAW_GET_TEXT(text, NULL);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_text",
                                          "(II)Ljava/lang/String;");

  jstring jstr = (*jniEnv)->CallObjectMethod(jniEnv,
                                             atk_text,
                                             jmid,
                                             (jint)start_offset,
                                             (jint)end_offset );
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return jaw_text_get_gtext_from_jstr(jniEnv, jstr);
}

static gunichar
jaw_text_get_character_at_offset (AtkText *text, gint offset)
{
  JAW_DEBUG_C("%p, %d", text, offset);
  JAW_GET_TEXT(text, 0);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_character_at_offset", "(I)C");
  jchar jcharacter = (*jniEnv)->CallCharMethod(jniEnv,
                                               atk_text,
                                               jmid,
                                               (jint)offset );
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return (gunichar)jcharacter;
}

static gchar *jaw_text_get_gtext_from_string_seq(JNIEnv *jniEnv,
                                                 jobject jStrSeq,
                                                 gint *start_offset,
                                                 gint *end_offset)
{
  jclass classStringSeq = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkText$StringSequence");
  jfieldID jfidStr = (*jniEnv)->GetFieldID(jniEnv,
                                           classStringSeq,
                                           "str",
                                           "Ljava/lang/String;");
  jfieldID jfidStart = (*jniEnv)->GetFieldID(jniEnv,
                                             classStringSeq,
                                             "start_offset",
                                             "I");
  jfieldID jfidEnd = (*jniEnv)->GetFieldID(jniEnv,
                                           classStringSeq,
                                           "end_offset",
                                           "I");

  jstring jStr = (*jniEnv)->GetObjectField(jniEnv, jStrSeq, jfidStr);
  jint jStart = (*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidStart);
  jint jEnd = (*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidEnd);

  (*start_offset) = (gint)jStart;
  (*end_offset) = (gint)jEnd;

  return jaw_text_get_gtext_from_jstr(jniEnv, jStr);
}

static gchar*
jaw_text_get_text_at_offset (AtkText *text,
                             gint offset,
                             AtkTextBoundary boundary_type,
                             gint *start_offset, gint *end_offset)
{
  JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, boundary_type, start_offset, end_offset);
  JAW_GET_TEXT(text, NULL);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_text_at_offset",
                                          "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
  jobject jStrSeq = (*jniEnv)->CallObjectMethod(jniEnv,
                                                atk_text,
                                                jmid,
                                                (jint)offset,
                                                (jint)boundary_type );
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  if (jStrSeq == NULL)
  {
    return NULL;
  }

  return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset, end_offset);
}

static gchar*
jaw_text_get_text_before_offset (AtkText *text,
                                 gint offset,
                                 AtkTextBoundary boundary_type,
                                 gint *start_offset, gint *end_offset)
{
  JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, boundary_type, start_offset, end_offset);
  JAW_GET_TEXT(text, NULL);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_text_before_offset",
                                          "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
  jobject jStrSeq = (*jniEnv)->CallObjectMethod(jniEnv,
                                                atk_text,
                                                jmid,
                                                (jint)offset,
                                                (jint)boundary_type );
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  if (jStrSeq == NULL)
  {
    return NULL;
  }

  return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset, end_offset);
}

static gchar*
jaw_text_get_text_after_offset (AtkText *text,
                                gint offset,
                                AtkTextBoundary boundary_type,
                                gint *start_offset, gint *end_offset)
{
  JAW_DEBUG_C("%p, %d, %d, %p, %p", text, offset, boundary_type, start_offset, end_offset);
  JAW_GET_TEXT(text, NULL);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_text_after_offset",
                                          "(II)Lorg/GNOME/Accessibility/AtkText$StringSequence;");
  jobject jStrSeq = (*jniEnv)->CallObjectMethod(jniEnv,
                                                atk_text,
                                                jmid,
                                                (jint)offset,
                                                (jint)boundary_type );
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  if (jStrSeq == NULL)
  {
    return NULL;
  }

  return jaw_text_get_gtext_from_string_seq(jniEnv, jStrSeq, start_offset, end_offset);
}

static gint
jaw_text_get_caret_offset (AtkText *text)
{
  JAW_DEBUG_C("%p", text);
  JAW_GET_TEXT(text, 0);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_caret_offset",
                                          "()I");
  jint joffset = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return (gint)joffset;
}

static void
jaw_text_get_character_extents (AtkText *text,
                                gint offset, gint *x, gint *y,
                                gint *width, gint *height,
                                AtkCoordType coords)
{
  JAW_DEBUG_C("%p, %d, %p, %p, %p, %p, %d", text, offset, x, y, width, height, coords);
  *x = -1;
  *y = -1;
  *width = -1;
  *height = -1;
  JAW_GET_TEXT(text, );

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_character_extents",
                                          "(II)Ljava/awt/Rectangle;");
  jobject jrect = (*jniEnv)->CallObjectMethod(jniEnv,
                                              atk_text,
                                              jmid,
                                              (jint)offset,
                                              (jint)coords);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  if (jrect == NULL)
  {
    JAW_DEBUG_I("jrect == NULL");
    return;
  }

  jaw_util_get_rect_info(jniEnv, jrect, x, y, width, height);
}

static gint
jaw_text_get_character_count (AtkText *text)
{
  JAW_DEBUG_C("%p", text);
  JAW_GET_TEXT(text, 0);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_character_count",
                                          "()I");
  jint jcount = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return (gint)jcount;
}

static gint
jaw_text_get_offset_at_point (AtkText *text, gint x, gint y, AtkCoordType coords)
{
  JAW_DEBUG_C("%p, %d, %d, %d", text, x, y, coords);
  JAW_GET_TEXT(text, 0);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_offset_at_point",
                                          "(III)I");
  jint joffset = (*jniEnv)->CallIntMethod(jniEnv,
                                          atk_text,
                                          jmid,
                                          (jint)x,
                                          (jint)y,
                                          (jint)coords);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return (gint)joffset;
}

static void
jaw_text_get_range_extents (AtkText *text,
                            gint start_offset,
                            gint end_offset,
                            AtkCoordType coord_type,
                            AtkTextRectangle *rect)
{
  JAW_DEBUG_C("%p, %d, %d, %d, %p", text, start_offset, end_offset, coord_type, rect);
  if (rect == NULL)
  {
    return;
  }
  rect->x = -1;
  rect->y = -1;
  rect->width = -1;
  rect->height = -1;

  JAW_GET_TEXT(text, );

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_range_extents",
                                          "(III)Ljava/awt/Rectangle;");
  jobject jrect = (*jniEnv)->CallObjectMethod(jniEnv,
                                              atk_text,
                                              jmid,
                                              (jint)start_offset,
                                              (jint)end_offset,
                                              (jint)coord_type);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  if (!jrect)
  {
    return;
  }

  jaw_util_get_rect_info(jniEnv, jrect, &(rect->x), &(rect->y), &(rect->width), &(rect->height));
}

static gint
jaw_text_get_n_selections (AtkText *text)
{
  JAW_DEBUG_C("%p", text);
  JAW_GET_TEXT(text, 0);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_n_selections",
                                          "()I");
  jint jselections = (*jniEnv)->CallIntMethod(jniEnv, atk_text, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return (gint)jselections;
}

static gchar*
jaw_text_get_selection (AtkText *text, gint selection_num, gint *start_offset, gint *end_offset)
{
  JAW_DEBUG_C("%p, %d, %p, %p", text, selection_num, start_offset, end_offset);
  JAW_GET_TEXT(text, NULL);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "get_selection",
                                          "()Lorg/GNOME/Accessibility/AtkText$StringSequence;");
  jobject jStrSeq = (*jniEnv)->CallObjectMethod(jniEnv, atk_text, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  if (jStrSeq == NULL)
  {
    return NULL;
  }

  jclass classStringSeq = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkText$StringSequence");
  jfieldID jfidStr = (*jniEnv)->GetFieldID(jniEnv,
                                           classStringSeq,
                                           "str",
                                           "Ljava/lang/String;");
  jfieldID jfidStart = (*jniEnv)->GetFieldID(jniEnv,
                                             classStringSeq,
                                             "start_offset",
                                             "I");
  jfieldID jfidEnd = (*jniEnv)->GetFieldID(jniEnv,
                                           classStringSeq,
                                           "end_offset",
                                           "I");

  jstring jStr = (*jniEnv)->GetObjectField(jniEnv, jStrSeq, jfidStr);
  *start_offset = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidStart);
  *end_offset = (gint)(*jniEnv)->GetIntField(jniEnv, jStrSeq, jfidEnd);

  return jaw_text_get_gtext_from_jstr(jniEnv, jStr);
}

static gboolean
jaw_text_add_selection (AtkText *text, gint start_offset, gint end_offset)
{
  JAW_DEBUG_C("%p, %d, %d", text, start_offset, end_offset);
  JAW_GET_TEXT(text, FALSE);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                         classAtkText,
                                         "add_selection",
                                         "(II)Z");
  jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                  atk_text,
                                                  jmid,
                                                  (jint)start_offset,
                                                  (jint)end_offset);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return jresult;
}

static gboolean
jaw_text_remove_selection (AtkText *text, gint selection_num)
{
  JAW_DEBUG_C("%p, %d", text, selection_num);
  JAW_GET_TEXT(text, FALSE);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "remove_selection",
                                          "(I)Z");
  jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                  atk_text,
                                                  jmid,
                                                  (jint)selection_num);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return jresult;
}

static gboolean
jaw_text_set_selection (AtkText *text, gint selection_num, gint start_offset, gint end_offset)
{
  JAW_DEBUG_C("%p, %d, %d, %d", text, selection_num, start_offset, end_offset);
  JAW_GET_TEXT(text, FALSE);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkText, "set_selection", "(III)Z");
  jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                  atk_text,
                                                  jmid,
                                                  (jint)selection_num,
                                                  (jint)start_offset,
                                                  (jint)end_offset);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return jresult;
}

static gboolean
jaw_text_set_caret_offset (AtkText *text, gint offset)
{
  JAW_DEBUG_C("%p, %d", text, offset);
  JAW_GET_TEXT(text, FALSE);

  jclass classAtkText = (*jniEnv)->FindClass(jniEnv,
                                             "org/GNOME/Accessibility/AtkText");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkText,
                                          "set_caret_offset",
                                          "(I)Z");
  jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                  atk_text,
                                                  jmid,
                                                  (jint)offset);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_text);

  return jresult;
}
