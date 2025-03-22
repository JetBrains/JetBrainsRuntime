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

static gboolean jaw_action_do_action(AtkAction *action, gint i);
static gint jaw_action_get_n_actions(AtkAction *action);
static const gchar *jaw_action_get_description(AtkAction *action, gint i);
static const gchar *jaw_action_get_keybinding(AtkAction *action, gint i);
static gboolean jaw_action_set_description(AtkAction *action, gint i,
                                           const gchar *description);
static const gchar *jaw_action_get_localized_name(AtkAction *action, gint i);

typedef struct _ActionData {
    jobject atk_action;
    gchar *localized_name;
    jstring jstrLocalizedName;
    gchar *action_description;
    jstring jstrActionDescription;
    gchar *action_keybinding;
    jstring jstrActionKeybinding;
} ActionData;

#define JAW_GET_ACTION(action, def_ret)                                        \
    JAW_GET_OBJ_IFACE(action, INTERFACE_ACTION, ActionData, atk_action,        \
                      jniEnv, atk_action, def_ret)

/*
 * Atk.Action methods:
 * do_action
 * get_description
 * get_keybinding
 * get_localized_name
 * get_n_actions
 * get_name
 * set_description
 */
void jaw_action_interface_init(AtkActionIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function");
        return;
    }

    iface->do_action = jaw_action_do_action;
    iface->get_description = jaw_action_get_description;
    iface->get_keybinding = jaw_action_get_keybinding;
    iface->get_localized_name = jaw_action_get_localized_name;
    iface->get_n_actions = jaw_action_get_n_actions;
    iface->get_name = jaw_action_get_description;
    iface->set_description = jaw_action_set_description;
}

gpointer jaw_action_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    ActionData *data = g_new0(ActionData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, NULL);

    jclass classAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    CHECK_NULL(classAction, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classAction, "create_atk_action",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkAction;");
    CHECK_NULL(jmid, NULL);
    jobject jatk_action =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classAction, jmid, ac);
    CHECK_NULL(jatk_action, NULL);

    data->atk_action = (*jniEnv)->NewGlobalRef(jniEnv, jatk_action);

    return data;
}

void jaw_action_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function");
        return;
    }

    ActionData *data = (ActionData *)p;
    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (!jniEnv || !data) {
        return;
    }

    if (data->localized_name != NULL) {
        if (data->jstrLocalizedName != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrLocalizedName,
                                             data->localized_name);
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
            data->jstrLocalizedName = NULL;
        }
        data->localized_name = NULL;
    }

    if (data->action_description != NULL) {
        if (data->jstrActionDescription != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(
                jniEnv, data->jstrActionDescription, data->action_description);
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
            data->jstrActionDescription = NULL;
        }
        data->action_description = NULL;
    }

    if (data->action_keybinding != NULL) {
        if (data->jstrActionKeybinding != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrActionKeybinding,
                                             data->action_keybinding);
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionKeybinding);
            data->jstrActionKeybinding = NULL;
        }
        data->action_keybinding = NULL;
    }

    if (data->atk_action != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_action);
        data->atk_action = NULL;
    }
}

static gboolean jaw_action_do_action(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_GET_ACTION(action, FALSE);

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkAction, "do_action", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return FALSE;
    }
    jboolean jresult =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_action, jmid, (jint)i);
    // deleting ref that was created in JAW_GET_ACTION(action, FALSE);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    CHECK_NULL(jresult, FALSE);

    return jresult;
}

static gint jaw_action_get_n_actions(AtkAction *action) {
    JAW_DEBUG_C("%p", action);

    if (!action) {
        g_warning("Null argument passed to function");
        return 0;
    }

    JAW_GET_ACTION(action, 0);

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkAction, "get_n_actions", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return 0;
    }
    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_action, jmid);
    // deleting ref that was created in JAW_GET_ACTION(action, FALSE);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    CHECK_NULL(ret, 0);
    return ret;
}

static const gchar *jaw_action_get_description(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_ACTION(action, NULL);

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "get_description", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
    // deleting ref that was created in JAW_GET_ACTION(action, FALSE);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    CHECK_NULL(jstr, NULL);

    if (data->action_description != NULL) {
        if (data->jstrActionDescription != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(
                jniEnv, data->jstrActionDescription, data->action_description);
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
            data->jstrActionDescription = NULL;
        }
        data->action_description = NULL;
    }

    if (jstr) {
        data->jstrActionDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        data->action_description = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, data->jstrActionDescription, NULL);
    }

    return data->action_description;
}

static gboolean jaw_action_set_description(AtkAction *action, gint i,
                                           const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", action, i, description);

    if (!action) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_GET_ACTION(action, FALSE);

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "set_description", "(ILjava/lang/String;)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return FALSE;
    }
    jboolean jisset = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_action, jmid, (jint)i, (jstring)description);
    // deleting ref that was created in JAW_GET_ACTION(action, FALSE);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    CHECK_NULL(jisset, FALSE);
    return jisset;
}

static const gchar *jaw_action_get_localized_name(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_ACTION(action, NULL);

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "get_localized_name", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
    // deleting ref that was created in JAW_GET_ACTION(action, NULL);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    CHECK_NULL(jstr, NULL);

    if (data->localized_name != NULL && data->jstrLocalizedName != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrLocalizedName,
                                         data->localized_name);
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
    }
    data->jstrLocalizedName = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->localized_name = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrLocalizedName, NULL);
    return data->localized_name;
}

static const gchar *jaw_action_get_keybinding(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_ACTION(action, NULL);

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "get_keybinding", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
    // deleting ref that was created in JAW_GET_ACTION(action, FALSE);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    CHECK_NULL(jstr, NULL);

    if (data->action_keybinding != NULL && data->jstrActionKeybinding != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrActionKeybinding,
                                         data->action_keybinding);

        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionKeybinding);
    }

    data->jstrActionKeybinding = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->action_keybinding = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrActionKeybinding, NULL);
    return data->action_keybinding;
}
