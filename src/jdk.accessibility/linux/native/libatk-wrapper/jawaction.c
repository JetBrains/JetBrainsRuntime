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
 * AtkAction:
 *
 * The ATK interface provided by UI components
 * which the user can activate/interact with.
 *
 * #AtkAction should be implemented by instances of #AtkObject classes
 * with which the user can interact directly, i.e. buttons,
 * checkboxes, scrollbars, e.g. components which are not "passive"
 * providers of UI information.
 *
 * Exceptions: when the user interaction is already covered by another
 * appropriate interface such as #AtkEditableText (insert/delete text,
 * etc.) or #AtkValue (set value) then these actions should not be
 * exposed by #AtkAction as well.
 *
 * Though most UI interactions on components should be invocable via
 * keyboard as well as mouse, there will generally be a close mapping
 * between "mouse actions" that are possible on a component and the
 * AtkActions.  Where mouse and keyboard actions are redundant in
 * effect, #AtkAction should expose only one action rather than
 * exposing redundant actions if possible.  By convention we have been
 * using "mouse centric" terminology for #AtkAction names.
 *
 */

static gboolean jaw_action_do_action(AtkAction *action, gint i);
static gint jaw_action_get_n_actions(AtkAction *action);
static const gchar *jaw_action_get_description(AtkAction *action, gint i);
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
    JAW_GET_OBJ_IFACE(action,                                                  \
                      org_GNOME_Accessibility_AtkInterface_INTERFACE_ACTION,   \
                      ActionData, atk_action, jniEnv, atk_action, def_ret)

/**
 * AtkActionIface:
 * @do_action:
 * @get_n_actions:
 * @get_description:
 * @get_name:
 * @get_keybinding:
 * @set_description:
 * @get_localized_name:
 **/
void jaw_action_interface_init(AtkActionIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->do_action = jaw_action_do_action;
    iface->get_n_actions = jaw_action_get_n_actions;
    iface->get_description = jaw_action_get_description;
    iface->get_name = jaw_action_get_description;
    iface->get_keybinding =
        NULL; // missing java support: There is no dependency between
              // javax.accessibility.AccessibleAction and keybindings,
              //  so there is no way to return the correct keybinding based on
              //  AccessibleContext.
    iface->set_description = jaw_action_set_description;
    iface->get_localized_name = jaw_action_get_localized_name;
}

gpointer jaw_action_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    ActionData *data = g_new0(ActionData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAction) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classAction, "create_atk_action",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkAction;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_action =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classAction, jmid, ac);
    if (!jatk_action) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    data->atk_action = (*jniEnv)->NewGlobalRef(jniEnv, jatk_action);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_action_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    ActionData *data = (ActionData *)p;
    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (!jniEnv || !data) {
        return;
    }

    if (data->jstrLocalizedName != NULL) {
        if (data->localized_name != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrLocalizedName,
                                             data->localized_name);
            data->localized_name = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
        data->jstrLocalizedName = NULL;
    }

    if (data->jstrActionDescription != NULL) {
        if (data->action_description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(
                jniEnv, data->jstrActionDescription, data->action_description);
            data->action_description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
        data->jstrActionDescription = NULL;
    }

    if (data->jstrActionKeybinding != NULL) {
        if (data->action_keybinding != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrActionKeybinding,
                                             data->action_keybinding);
            data->action_keybinding = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionKeybinding);
        data->jstrActionKeybinding = NULL;
    }

    if (data->atk_action != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_action);
        data->atk_action = NULL;
    }
}

/**
 * jaw_action_do_action:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 *
 * Perform the specified action on the object.
 *
 * Returns: %TRUE if success, %FALSE otherwise
 *
 **/
static gboolean jaw_action_do_action(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_ACTION(action, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_action); // deleting ref that was created in JAW_GET_ACTION
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkAction, "do_action", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_action, jmid, (jint)i);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}

/**
 * jaw_action_get_n_actions:
 * @action: a #GObject instance that implements AtkActionIface
 *
 * Gets the number of accessible actions available on the object.
 * If there are more than one, the first one is considered the
 * "default" action of the object.
 *
 * Returns: a the number of actions, or 0 if @action does not
 * implement this interface.
 **/
static gint jaw_action_get_n_actions(AtkAction *action) {
    JAW_DEBUG_C("%p", action);

    if (!action) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_ACTION(action, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_action); // deleting ref that was created in JAW_GET_ACTION
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkAction, "get_n_actions", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_action, jmid);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ret;
}

/**
 * jaw_action_get_description:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 *
 * Returns a description of the specified action of the object.
 *
 * Returns: (nullable): a description string, or %NULL if @action does
 * not implement this interface.
 **/
static const gchar *jaw_action_get_description(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_ACTION(action, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_action); // deleting ref that was created in JAW_GET_ACTION
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "get_description", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->jstrActionDescription != NULL) {
        if (data->action_description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(
                jniEnv, data->jstrActionDescription, data->action_description);
            data->action_description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
        data->jstrActionDescription = NULL;
    }

    if (jstr) {
        data->jstrActionDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        data->action_description = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, data->jstrActionDescription, NULL);
    }
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->action_description;
}

/**
 * jaw_action_set_description:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 * @desc: the description to be assigned to this action
 *
 * Sets a description of the specified action of the object.
 *
 * Returns: a gboolean representing if the description was successfully set;
 **/
static gboolean jaw_action_set_description(AtkAction *action, gint i,
                                           const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", action, i, description);

    if (!action) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_ACTION(action, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_action); // deleting ref that was created in JAW_GET_ACTION
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "set_description", "(ILjava/lang/String;)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jisset = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_action, jmid, (jint)i, (jstring)description);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jisset;
}

/**
 * jaw_action_get_localized_name:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 *
 * Returns the localized name of the specified action of the object.
 *
 * Returns: (nullable): a name string, or %NULL if @action does not
 * implement this interface.
 **/
static const gchar *jaw_action_get_localized_name(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (!action) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_ACTION(action, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_action); // deleting ref that was created in JAW_GET_ACTION
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkAction =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if (!classAtkAction) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkAction, "get_localized_name", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_action, jmid, (jint)i);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->jstrLocalizedName != NULL) {
        if (data->localized_name != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrLocalizedName,
                                             data->localized_name);
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
    }
    data->jstrLocalizedName = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->localized_name = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrLocalizedName, NULL);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->localized_name;
}