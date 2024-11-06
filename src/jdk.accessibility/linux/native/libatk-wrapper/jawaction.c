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

static gboolean jaw_action_do_action(AtkAction *action, gint i);
static gint jaw_action_get_n_actions(AtkAction *action);
static const gchar* jaw_action_get_description(AtkAction *action, gint i);
static const gchar* jaw_action_get_keybinding (AtkAction *action, gint i);
static gboolean jaw_action_set_description (AtkAction *action, gint i, const gchar *description);
static const gchar* jaw_action_get_localized_name(AtkAction *action, gint i);

typedef struct _ActionData {
  jobject atk_action;
  gchar* localized_name;
  jstring jstrLocalizedName;
  gchar* action_description;
  jstring jstrActionDescription;
  gchar* action_keybinding;
  jstring jstrActionKeybinding;
} ActionData;

#define JAW_GET_ACTION(action, def_ret) \
    JAW_GET_OBJ_IFACE(action, INTERFACE_ACTION, ActionData, atk_action, jniEnv, atk_action, def_ret)

void
jaw_action_interface_init (AtkActionIface *iface, gpointer data)
{
  JAW_DEBUG_ALL("%p, %p", iface, data);
  iface->do_action = jaw_action_do_action;
  iface->get_n_actions = jaw_action_get_n_actions;
  // FIXME: missing java support for distinguishing name and description
  iface->get_description = jaw_action_get_description;
  iface->get_name = jaw_action_get_description;
  iface->get_keybinding = jaw_action_get_keybinding;
  iface->set_description = jaw_action_set_description;
  iface->get_localized_name = jaw_action_get_localized_name;
}

gpointer
jaw_action_data_init (jobject ac)
{
  JAW_DEBUG_ALL("%p", ac);
  ActionData *data = g_new0(ActionData, 1);

  JNIEnv *jniEnv = jaw_util_get_jni_env();
  jclass classAction = (*jniEnv)->FindClass(jniEnv,
                                            "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetStaticMethodID(jniEnv,
                                          classAction,
                                          "createAtkAction",
                                          "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/AtkAction;");
  jobject jatk_action = (*jniEnv)->CallStaticObjectMethod(jniEnv, classAction, jmid, ac);
  data->atk_action = (*jniEnv)->NewGlobalRef(jniEnv, jatk_action);

  return data;
}

void
jaw_action_data_finalize (gpointer p)
{
  JAW_DEBUG_ALL("%p", p);
  ActionData *data = (ActionData*)p;
  JNIEnv *jniEnv = jaw_util_get_jni_env();

  if (data && data->atk_action) {
    if (data->localized_name != NULL) {
      (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrLocalizedName, data->localized_name);
      (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
      data->jstrLocalizedName = NULL;
      data->localized_name = NULL;
    }

    if (data->action_description != NULL) {
      (*jniEnv)->ReleaseStringUTFChars(jniEnv,
                                       data->jstrActionDescription,
                                       data->action_description);
      (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
      data->jstrActionDescription = NULL;
      data->action_description = NULL;
    }

    if (data->action_keybinding != NULL) {
      (*jniEnv)->ReleaseStringUTFChars(jniEnv,
                                       data->jstrActionKeybinding,
                                       data->action_keybinding);
      (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionKeybinding);
      data->jstrActionKeybinding = NULL;
      data->action_keybinding = NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_action);
    data->atk_action = NULL;
  }
}

static gboolean
jaw_action_do_action (AtkAction *action, gint i)
{
  JAW_DEBUG_C("%p, %d", action, i);
  JAW_GET_ACTION(action, FALSE);

  jclass classAtkAction = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkAction,
                                          "do_action",
                                          "(I)Z");
  jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                  atk_action,
                                                  jmid,
                                                  (jint)i);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
  return jresult;
}

static gint
jaw_action_get_n_actions (AtkAction *action)
{
  JAW_DEBUG_C("%p", action);
  JAW_GET_ACTION(action, 0);

  jclass classAtkAction = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkAction,
                                          "get_n_actions", "()I");

  gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_action, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
  return ret;
}

static const gchar*
jaw_action_get_description (AtkAction *action, gint i)
{
  JAW_DEBUG_C("%p, %d", action, i);
  JAW_GET_ACTION(action, NULL);

  jclass classAtkAction = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkAction,
                                          "get_description",
                                          "(I)Ljava/lang/String;");
  jstring jstr = (*jniEnv)->CallObjectMethod(jniEnv,
                                             atk_action,
                                             jmid,
                                             (jint)i);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);

  if (data->action_description != NULL)
  {
    (*jniEnv)->ReleaseStringUTFChars(jniEnv,
                                     data->jstrActionDescription,
                                     data->action_description);
    (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
    data->jstrActionDescription = NULL;
    data->action_description = NULL;
  }

  if (jstr) {
    data->jstrActionDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->action_description = (gchar*)(*jniEnv)->GetStringUTFChars(jniEnv,
                                                                    data->jstrActionDescription,
                                                                    NULL);
  }

  return data->action_description;
}

static gboolean
jaw_action_set_description (AtkAction *action, gint i, const gchar *description)
{
  JAW_DEBUG_C("%p, %d, %s", action, i, description);
  JAW_GET_ACTION(action, FALSE);

  jclass classAtkAction = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkAction,
                                          "setDescription",
                                          "(ILjava/lang/String;)Z");
  jboolean jisset = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                 atk_action,
                                                 jmid,
                                                 (jint)i,
                                                 (jstring)description);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
  return jisset;
}

static const gchar*
jaw_action_get_localized_name (AtkAction *action, gint i)
{
  JAW_DEBUG_C("%p, %d", action, i);
  JAW_GET_ACTION(action, NULL);

  jclass classAtkAction = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                       classAtkAction,
                                       "getLocalizedName",
                                       "(I)Ljava/lang/String;");
  jstring jstr = (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
  if (data->localized_name != NULL)
  {
    (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrLocalizedName, data->localized_name);
    (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
  }
  data->jstrLocalizedName = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
  data->localized_name = (gchar*)(*jniEnv)->GetStringUTFChars(jniEnv, data->jstrLocalizedName, NULL);
  return data->localized_name;
}

static const gchar*
jaw_action_get_keybinding (AtkAction *action, gint i)
{
  JAW_DEBUG_C("%p, %d", action, i);
  JAW_GET_ACTION(action, NULL);

  jclass classAtkAction = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkAction");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkAction,
                                          "get_keybinding",
                                          "(I)Ljava/lang/String;");
  jstring jstr = (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);

  if (data->action_keybinding != NULL)
  {
    (*jniEnv)->ReleaseStringUTFChars(jniEnv,
                                     data->jstrActionKeybinding,
                                     data->action_keybinding);

    (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionKeybinding);
  }

  data->jstrActionKeybinding = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
  data->action_keybinding = (gchar*)(*jniEnv)->GetStringUTFChars(jniEnv,
                                                                 data->jstrActionKeybinding,
                                                                 NULL);
  return data->action_keybinding;
}
