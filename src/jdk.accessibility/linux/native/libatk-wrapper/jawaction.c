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

jclass cachedActionAtkActionClass = NULL;
jmethodID cachedActionCreateAtkActionMethod = NULL;
jmethodID cachedActionDoActionMethod = NULL;
jmethodID cachedActionGetNActionsMethod = NULL;
jmethodID cachedActionGetDescriptionMethod = NULL;
jmethodID cachedActionSetDescriptionMethod = NULL;
jmethodID cachedActionGetLocalizedNameMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

// TODO: introduce JAW_ACTION_DEFAULT_LOCAL_FRAME_SIZE 10

/**
 * jaw_action_init_jni_cache:
 * @jniEnv: JNI environment
 *
 * Initializes and caches JNI class and method references for performance.
 * This avoids repeated expensive JNI lookups on every method call.
 *
 * Returns: %TRUE if initialization succeeded, %FALSE otherwise
 **/
static gboolean jaw_action_init_jni_cache(JNIEnv *jniEnv);

typedef struct _ActionData {
    jobject atk_action;
    const gchar *localized_name;
    jstring jstrLocalizedName;
    const gchar *action_description;
    jstring jstrActionDescription;
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

    if (iface == NULL) {
        g_warning("%s: Null argument iface passed to the function", G_STRFUNC);
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

    if (ac == NULL) {
        g_warning("%s: Null argument ac passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_action_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_action = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedActionAtkActionClass, cachedActionCreateAtkActionMethod,
        ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_action == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create AtkAction Java object via "
                  "create_atk_action()",
                  G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    ActionData *data = g_new0(ActionData, 1);
    data->atk_action = (*jniEnv)->NewGlobalRef(jniEnv, jatk_action);
    if (data->atk_action == NULL) {
        g_warning("%s: Failed to create global ref for atk_action", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_action_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_debug("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    ActionData *data = (ActionData *)p;
    if (data == NULL) {
        g_warning("%s: data is null after cast", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->jstrLocalizedName != NULL) {
            if (data->localized_name != NULL) {
                (*jniEnv)->ReleaseStringUTFChars(
                    jniEnv, data->jstrLocalizedName, data->localized_name);
                data->localized_name = NULL;
            }
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
            data->jstrLocalizedName = NULL;
        }

        if (data->jstrActionDescription != NULL) {
            if (data->action_description != NULL) {
                (*jniEnv)->ReleaseStringUTFChars(jniEnv,
                                                 data->jstrActionDescription,
                                                 data->action_description);
                data->action_description = NULL;
            }
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
            data->jstrActionDescription = NULL;
        }

        if (data->atk_action != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_action);
            data->atk_action = NULL;
        }
    }

    g_free(data);
}

/**
 * jaw_action_do_action:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 *
 * Perform the specified action on the object.
 *
 * Returns: %TRUE if success, %FALSE otherwise
 **/
static gboolean jaw_action_do_action(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (action == NULL) {
        g_warning("%s: Null action passed (index=%d)", G_STRFUNC, i);
        return FALSE;
    }

    JAW_GET_ACTION(action,
                   FALSE); // create local JNI reference `jobject atk_action`

    jboolean jresult = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_action, cachedActionDoActionMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);

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
 * Returns: the number of actions, or 0 if @action does not
 * implement this interface.
 **/
static gint jaw_action_get_n_actions(AtkAction *action) {
    JAW_DEBUG_C("%p", action);

    if (action == NULL) {
        g_warning("%s: Null action passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_ACTION(action,
                   0); // create local JNI reference `jobject atk_action`

    gint ret = (gint)(*jniEnv)->CallIntMethod(jniEnv, atk_action,
                                              cachedActionGetNActionsMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);

    return ret;
}

/**
 * jaw_action_get_description:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 *
 * Returns a description of the specified action of the object.
 *
 * Returns: (nullable): a description string for action @i, or %NULL if
 * @action does not implement this interface or if an error occurs.
 **/
static const gchar *jaw_action_get_description(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (action == NULL) {
        g_warning("%s: Null action passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_ACTION(action,
                   NULL); // create local JNI reference `jobject atk_action`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_action, cachedActionGetDescriptionMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_debug("%s: No description available for action (index=%d, action=%p)",
                G_STRFUNC, i, action);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
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

    data->jstrActionDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    if (data->jstrActionDescription == NULL) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    data->action_description =
        (*jniEnv)->GetStringUTFChars(jniEnv, data->jstrActionDescription, NULL);

    if ((*jniEnv)->ExceptionCheck(jniEnv) || data->action_description == NULL) {
        jaw_jni_clear_exception(jniEnv);

        if (data->jstrActionDescription != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrActionDescription);
            data->jstrActionDescription = NULL;
        }

        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->action_description;
}

/**
 * jaw_action_set_description:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 * @description: the description to be assigned to this action
 *
 * Returns: %TRUE if the description was successfully set, %FALSE otherwise.
 **/
static gboolean jaw_action_set_description(AtkAction *action, gint i,
                                           const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", action, i, description);

    if (action == NULL) {
        g_warning("%s: Null action passed (index=%d)", G_STRFUNC, i);
        return FALSE;
    }
    if (description == NULL) {
        g_warning("%s:  Null description passed (index=%d)", G_STRFUNC, i);
        return FALSE;
    }

    JAW_GET_ACTION(action,
                   FALSE); // create local JNI reference `jobject atk_action`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jstring jdescription = (*jniEnv)->NewStringUTF(jniEnv, description);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jdescription == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create Java string for description",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    jboolean jisset = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_action, cachedActionSetDescriptionMethod, (jint)i,
        (jstring)jdescription);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jisset;
}

/**
 * jaw_action_get_localized_name:
 * @action: a #GObject instance that implements AtkActionIface
 * @i: the action index corresponding to the action to be performed
 *
 * Returns: (nullable): a localized name string for action @i, or %NULL
 *   if @action does not implement this interface or if an error occurs.
 **/
static const gchar *jaw_action_get_localized_name(AtkAction *action, gint i) {
    JAW_DEBUG_C("%p, %d", action, i);

    if (action == NULL) {
        g_warning("%s: Null argument action passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_ACTION(action,
                   NULL); // create local JNI reference `jobject atk_action`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_action, cachedActionGetLocalizedNameMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_debug(
            "%s: No localized name available for action (index=%d, action=%p)",
            G_STRFUNC, i, action);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
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
    data->jstrLocalizedName = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    if (data->jstrLocalizedName == NULL) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->localized_name =
        (*jniEnv)->GetStringUTFChars(jniEnv, data->jstrLocalizedName, NULL);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || data->localized_name == NULL) {
        jaw_jni_clear_exception(jniEnv);

        if (data->jstrLocalizedName != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrLocalizedName);
            data->jstrLocalizedName = NULL;
        }

        (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_action);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->localized_name;
}

static gboolean jaw_action_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkAction");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkAction class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedActionAtkActionClass = (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedActionAtkActionClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkAction class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedActionCreateAtkActionMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedActionAtkActionClass, "create_atk_action",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkAction;");

    cachedActionDoActionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedActionAtkActionClass, "do_action", "(I)Z");

    cachedActionGetNActionsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedActionAtkActionClass, "get_n_actions", "()I");

    cachedActionGetDescriptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedActionAtkActionClass,
                               "get_description", "(I)Ljava/lang/String;");

    cachedActionSetDescriptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedActionAtkActionClass,
                               "set_description", "(ILjava/lang/String;)Z");

    cachedActionGetLocalizedNameMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedActionAtkActionClass,
                               "get_localized_name", "(I)Ljava/lang/String;");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedActionCreateAtkActionMethod == NULL ||
        cachedActionDoActionMethod == NULL ||
        cachedActionGetNActionsMethod == NULL ||
        cachedActionGetDescriptionMethod == NULL ||
        cachedActionSetDescriptionMethod == NULL ||
        cachedActionGetLocalizedNameMethod == NULL) {
        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkAction method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedActionAtkActionClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedActionAtkActionClass);
        cachedActionAtkActionClass = NULL;
    }
    cachedActionCreateAtkActionMethod = NULL;
    cachedActionDoActionMethod = NULL;
    cachedActionGetNActionsMethod = NULL;
    cachedActionGetDescriptionMethod = NULL;
    cachedActionSetDescriptionMethod = NULL;
    cachedActionGetLocalizedNameMethod = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_action_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedActionAtkActionClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedActionAtkActionClass);
        cachedActionAtkActionClass = NULL;
    }
    cachedActionCreateAtkActionMethod = NULL;
    cachedActionDoActionMethod = NULL;
    cachedActionGetNActionsMethod = NULL;
    cachedActionGetDescriptionMethod = NULL;
    cachedActionSetDescriptionMethod = NULL;
    cachedActionGetLocalizedNameMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}