/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 * Copyright (C) 2015 Magdalen Berns <m.berns@thismagpie.com>
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

#include "jawutil.h"
#include "jawobject.h"
#include "jawtoplevel.h"
#include <glib.h>
#include <glib/gprintf.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

static void jaw_util_class_init(JawUtilClass *klass, void *klass_data);
static guint jaw_util_add_key_event_listener(AtkKeySnoopFunc listener,
                                             gpointer data);
static void jaw_util_remove_key_event_listener(guint remove_listener);
static AtkObject *jaw_util_get_root(void);
static const gchar *jaw_util_get_toolkit_name(void);
static const gchar *jaw_util_get_toolkit_version(void);

static JavaVM *cachedJVM = NULL;

typedef struct _JawKeyListenerInfo {
    AtkKeySnoopFunc listener;
    gpointer data; // data that should be sent to the listener
} JawKeyListenerInfo;

// Maps unique keys to `JawKeyListenerInfo`
static GHashTable *key_listener_list = NULL;

GType jaw_util_get_type(void) {
    JAW_DEBUG_ALL("");
    static GType type = 0;

    if (!type) {
        static const GTypeInfo tinfo = {
            sizeof(JawUtilClass),
            (GBaseInitFunc)NULL,                 /*base init*/
            (GBaseFinalizeFunc)NULL,             /*base finalize */
            (GClassInitFunc)jaw_util_class_init, /* class init */
            (GClassFinalizeFunc)NULL,            /*class finalize */
            NULL,                                /* class data */
            sizeof(JawUtil),                     /* instance size */
            0,                                   /* nb preallocs */
            (GInstanceInitFunc)NULL,             /* instance init */
            NULL                                 /* value table */
        };

        type = g_type_register_static(ATK_TYPE_UTIL, "JawUtil", &tinfo, 0);
    }

    return type;
}

static void jaw_util_class_init(JawUtilClass *kclass, void *klass_data) {
    JAW_DEBUG_ALL("%p, %p", kclass, klass_data);

    AtkUtilClass *atk_class;
    gpointer data;

    data = g_type_class_peek(ATK_TYPE_UTIL);
    atk_class = ATK_UTIL_CLASS(data);

    atk_class->add_key_event_listener = jaw_util_add_key_event_listener;
    atk_class->remove_key_event_listener = jaw_util_remove_key_event_listener;
    atk_class->get_root = jaw_util_get_root;
    atk_class->get_toolkit_name = jaw_util_get_toolkit_name;
    atk_class->get_toolkit_version = jaw_util_get_toolkit_version;
}

/**
 * Notifies a key event to the registered key listener.
 *
 * @param key
 * @param value Pointer to the `JawKeyListenerInfo`
 * @param data Pointer to the `AtkKeyEventStruct`
 *
 * @return TRUE if the listener processes the event successfully,
 *         FALSE if any argument is null or the listener returns FALSE.
 */
static gboolean notify_hf(gpointer key, gpointer value, gpointer data) {
    JAW_DEBUG_C("%p, %p, %p", key, value, data);

    if (!value || !data) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JawKeyListenerInfo *info = (JawKeyListenerInfo *)value;
    JAW_CHECK_NULL(info, FALSE);
    AtkKeyEventStruct *key_event = (AtkKeyEventStruct *)data;
    JAW_CHECK_NULL(key_event, FALSE);

    AtkKeySnoopFunc func = info->listener;
    gpointer func_data = info->data;

    JAW_DEBUG_C("key event %d %x %x %d '%s' %u %u", key_event->type,
                key_event->state, key_event->keyval, key_event->length,
                key_event->string, (unsigned)key_event->keycode,
                (unsigned)key_event->timestamp);

    return (*func)(key_event, func_data) ? TRUE : FALSE;
}

/**
 * Inserts key value pair into a hash table.
 *
 * @param key Pointer to the hash table's key
 * @param value Pointer to the associated value
 * @param data Pointer to the `GHashTable`
 */
static void insert_hf(gpointer key, gpointer value, gpointer data) {
    JAW_DEBUG_C("%p, %p, %p", key, value, data);

    if (!key || !value || !data) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    GHashTable *hash_table = (GHashTable *)data;
    JAW_CHECK_NULL(hash_table, );
    g_hash_table_insert(hash_table, key, value);
}

gboolean jaw_util_dispatch_key_event(AtkKeyEventStruct *event) {
    JAW_DEBUG_C("%p", event);

    if (!event) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    gint consumed = 0;
    if (key_listener_list) {
        GHashTable *new_hash = g_hash_table_new(NULL, NULL);
        if (new_hash) {
            g_hash_table_foreach(key_listener_list, insert_hf, new_hash);
            consumed = g_hash_table_foreach_steal(new_hash, notify_hf, event);
            g_hash_table_destroy(new_hash);
        }
    }
    JAW_DEBUG_C("consumed: %d", consumed);

    return (consumed > 0) ? TRUE : FALSE;
}

/**
 * jaw_util_add_key_event_listener:
 * @listener: the listener to notify
 * @data: a #gpointer that points to a block of data that should be sent to the
 *registered listeners, along with the event notification, when it occurs.
 *
 * Adds the specified function to the list of functions to be called
 *        when a key event occurs.  The @data element will be passed to the
 *        #AtkKeySnoopFunc (@listener) as the @func_data param, on notification.
 *
 * Returns: added event listener id, or 0 on failure.
 **/
static guint jaw_util_add_key_event_listener(AtkKeySnoopFunc listener,
                                             gpointer data) {
    JAW_DEBUG_C("%p, %p", listener, data);

    if (!listener) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    static guint key = 0;

    if (!key_listener_list) {
        key_listener_list = g_hash_table_new(NULL, NULL);
    }

    JawKeyListenerInfo *info = g_new0(JawKeyListenerInfo, 1);
    info->listener = listener;
    info->data = data;

    key++;
    g_hash_table_insert(key_listener_list, GUINT_TO_POINTER(key), info);

    return key;
}

/**
 * jaw_util_remove_key_event_listener:
 * @listener_id: the id of the event listener to remove
 *
 * @listener_id is the value returned by #atk_add_key_event_listener
 * when you registered that event listener.
 *
 * Removes the specified event listener.
 **/
static void jaw_util_remove_key_event_listener(guint remove_listener) {
    JAW_DEBUG_C("%u", remove_listener);
    gpointer *value = g_hash_table_lookup(key_listener_list,
                                          GUINT_TO_POINTER(remove_listener));
    if (value)
        g_free(value);

    g_hash_table_remove(key_listener_list, GUINT_TO_POINTER(remove_listener));
}

/**
 * jaw_util_get_root:
 *
 * Gets the root accessible container for the current application.
 *
 * Returns: (transfer none): the root accessible container for the current
 * application
 **/
static AtkObject *jaw_util_get_root(void) {
    JAW_DEBUG_C("");
    static JawToplevel *root = NULL;

    root = g_object_new(JAW_TYPE_TOPLEVEL, NULL);
    atk_object_initialize(ATK_OBJECT(root), NULL);

    return ATK_OBJECT(root);
}

/**
 * jaw_util_get_toolkit_name:
 *
 * Gets name string for the GUI toolkit implementing ATK for this application.
 *
 * Returns: name string for the GUI toolkit implementing ATK for this
 *application
 **/
static const gchar *jaw_util_get_toolkit_name(void) {
    JAW_DEBUG_C("");
    return "J2SE-access-bridge";
}

/**
 * jaw_util_get_toolkit_version:
 *
 * Gets version string for the GUI toolkit implementing ATK for this
 *application.
 *
 * Returns: version string for the GUI toolkit implementing ATK for this
 *application
 **/
static const gchar *jaw_util_get_toolkit_version(void) {
    JAW_DEBUG_C("");
    return "1.0";
}

guint jaw_util_get_tflag_from_jobj(JNIEnv *jniEnv, jobject jObj) {
    JAW_DEBUG_C("%p, %p", jniEnv, jObj);

    if (!jniEnv) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return -1;
    }

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    JAW_CHECK_NULL(atkObject, -1);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "get_tflag_from_obj", "(Ljava/lang/Object;)I");
    JAW_CHECK_NULL(jmid, -1);
    return (guint)(*jniEnv)->CallStaticIntMethod(jniEnv, atkObject, jmid, jObj);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserve) {
    JAW_DEBUG_JNI("%p, %p", jvm, reserve);
    if (jvm == NULL) {
        JAW_DEBUG_I("JavaVM pointer was NULL when initializing library");
        g_error("JavaVM pointer was NULL when initializing library");
        return JNI_ERR;
    }
    cachedJVM = jvm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *jvm, void *reserve) {
    JAW_DEBUG_JNI("%p, %p", jvm, reserve);

    if (jvm == NULL) {
        JAW_DEBUG_I("JavaVM pointer was NULL when unloading library");
        g_error("JavaVM pointer was NULL when unloading library");
        return;
    }

    g_warning("JNI_OnUnload() called but this is not supported yet\n");
}

JNIEnv *jaw_util_get_jni_env(void) {
    if (cachedJVM == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *env;
    jint res = (*cachedJVM)->GetEnv(cachedJVM, (void **)&env, JNI_VERSION_1_6);
    if (res == JNI_OK && env != NULL) {
        return env;
    }

    switch (res) {
    case JNI_EDETACHED:
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "JavaAtkWrapper-JNI-Attached-Thread";
        args.group = NULL;
        res =
            (*cachedJVM)
                ->AttachCurrentThreadAsDaemon(cachedJVM, (void **)&env, &args);
        if (res == JNI_OK && env != NULL) {
            return env;
        }
        g_printerr("\n *** Attach failed. *** JNIEnv thread is detached.\n");
        break;
    case JNI_EVERSION:
        g_printerr(" *** Version error *** \n");
        break;
    default:
        g_printerr(" *** Unknown result %d *** \n", res);
        break;
    }

    fflush(stderr);
    return NULL;
}

/* Currently unused: our thread lives forever until application termination.  */
void jaw_util_detach(void) {
    JAW_DEBUG_C("");
    JavaVM *jvm;
    jvm = cachedJVM;
    (*jvm)->DetachCurrentThread(jvm);
}

static jobject jaw_util_get_java_acc_role(JNIEnv *jniEnv,
                                          const gchar *roleName) {
    if (!jniEnv || !roleName) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAccessibleRole =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/AccessibleRole");
    if (!classAccessibleRole) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID jfid =
        (*jniEnv)->GetStaticFieldID(jniEnv, classAccessibleRole, roleName,
                                    "Ljavax/accessibility/AccessibleRole;");
    if (!jfid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jrole =
        (*jniEnv)->GetStaticObjectField(jniEnv, classAccessibleRole, jfid);

    return (*jniEnv)->PopLocalFrame(jniEnv, jrole);
}

static gboolean jaw_util_is_java_acc_role(JNIEnv *jniEnv, jobject acc_role,
                                          const gchar *roleName) {
    if (!jniEnv || !roleName) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    jobject jrole = jaw_util_get_java_acc_role(jniEnv, roleName);

    if ((*jniEnv)->IsSameObject(jniEnv, acc_role, jrole)) {
        return TRUE;
    } else {
        return FALSE;
    }
}

AtkRole
jaw_util_get_atk_role_from_AccessibleContext(jobject jAccessibleContext) {
    JAW_DEBUG_C("%p", jAccessibleContext);

    if (!jAccessibleContext) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return ATK_ROLE_UNKNOWN;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        return ATK_ROLE_UNKNOWN;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return ATK_ROLE_UNKNOWN;
    }

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    if (!atkObject) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }
    jmethodID jmidgar = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "get_accessible_role",
        "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/"
        "AccessibleRole;");
    if (!jmidgar) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }
    jobject ac_role = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, atkObject, jmidgar, jAccessibleContext);
    if (!ac_role) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }
    jclass classAccessibleRole =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/AccessibleRole");
    if (!classAccessibleRole) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }

    if (!(*jniEnv)->IsInstanceOf(jniEnv, ac_role, classAccessibleRole)) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_INVALID;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "ALERT")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_ALERT;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "AWT_COMPONENT")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "CANVAS")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_CANVAS;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "CHECK_BOX")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_CHECK_BOX;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "COLOR_CHOOSER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_COLOR_CHOOSER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "COLUMN_HEADER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_COLUMN_HEADER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "COMBO_BOX")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_COMBO_BOX;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "DATE_EDITOR")) {
        return ATK_ROLE_DATE_EDITOR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "DESKTOP_ICON")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_DESKTOP_ICON;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "DESKTOP_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_DESKTOP_FRAME;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "DIALOG")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_DIALOG;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "DIRECTORY_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_DIRECTORY_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "EDITBAR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_EDITBAR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "FILE_CHOOSER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_FILE_CHOOSER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "FILLER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_FILLER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "FONT_CHOOSER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_FONT_CHOOSER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "FOOTER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_FOOTER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "FRAME")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_FRAME;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "GLASS_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_GLASS_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "GROUP_BOX")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PANEL;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "HEADER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_HEADER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "HTML_CONTAINER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_HTML_CONTAINER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "HYPERLINK")) {
        return ATK_ROLE_LINK;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "ICON")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_ICON;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "INTERNAL_FRAME")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_INTERNAL_FRAME;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "LABEL")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_LABEL;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "LAYERED_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_LAYERED_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "LIST")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_LIST;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "LIST_ITEM")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_LIST_ITEM;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "MENU")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_MENU;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "MENU_BAR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_MENU_BAR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "MENU_ITEM")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_MENU_ITEM;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "OPTION_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_OPTION_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PAGE_TAB")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PAGE_TAB;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PAGE_TAB_LIST")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PAGE_TAB_LIST;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PANEL")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PANEL;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PARAGRAPH")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PARAGRAPH;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PASSWORD_TEXT")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PASSWORD_TEXT;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "POPUP_MENU")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_POPUP_MENU;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PROGRESS_BAR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PROGRESS_BAR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "PUSH_BUTTON")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PUSH_BUTTON;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "RADIO_BUTTON")) {

        jmethodID jmidgap = (*jniEnv)->GetStaticMethodID(
            jniEnv, atkObject, "get_accessible_parent",
            "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/"
            "AccessibleContext;");
        if (!jmidgap) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return ATK_ROLE_UNKNOWN;
        }

        jobject jparent = (*jniEnv)->CallStaticObjectMethod(
            jniEnv, atkObject, jmidgap, jAccessibleContext); // must be deleted
        if (!jparent) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return ATK_ROLE_RADIO_BUTTON;
        }

        jobject parent_role = (*jniEnv)->CallStaticObjectMethod(
            jniEnv, atkObject, jmidgar, jparent); // must be deleted
        if (!parent_role) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return ATK_ROLE_UNKNOWN;
        }
        if (jaw_util_is_java_acc_role(jniEnv, parent_role, "MENU")) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return ATK_ROLE_RADIO_MENU_ITEM;
        }

        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_RADIO_BUTTON;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "ROOT_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_ROOT_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "ROW_HEADER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_ROW_HEADER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "RULER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_RULER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SCROLL_BAR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_SCROLL_BAR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SCROLL_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_SCROLL_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SEPARATOR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_SEPARATOR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SLIDER")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_SLIDER;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SPIN_BOX")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_SPIN_BUTTON;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SPLIT_PANE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_SPLIT_PANE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "STATUS_BAR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_STATUSBAR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "SWING_COMPONENT")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "TABLE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_TABLE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "TEXT")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_TEXT;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "TOGGLE_BUTTON")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_TOGGLE_BUTTON;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "TOOL_BAR")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_TOOL_BAR;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "TOOL_TIP")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_TOOL_TIP;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "TREE")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_TREE;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "UNKNOWN")) {
        jmethodID jmidgap = (*jniEnv)->GetStaticMethodID(
            jniEnv, atkObject, "get_accessible_parent",
            "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/"
            "AccessibleContext;");
        if (!jmidgap) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return ATK_ROLE_UNKNOWN;
        }
        jobject jparent = (*jniEnv)->CallStaticObjectMethod(
            jniEnv, atkObject, jmidgap, jAccessibleContext); // must be deleted
        if (jparent == NULL) {
            (*jniEnv)->PopLocalFrame(jniEnv, NULL);
            return ATK_ROLE_APPLICATION;
        }

        (*jniEnv)->PopLocalFrame(jniEnv, NULL);

        return ATK_ROLE_UNKNOWN;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "VIEWPORT")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_VIEWPORT;
    }

    if (jaw_util_is_java_acc_role(jniEnv, ac_role, "WINDOW")) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_WINDOW;
    }

    jmethodID jmideic = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "equals_ignore_case_locale_with_role",
        "(Ljavax/accessibility/AccessibleRole;)Z");
    if (!jmideic) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_UNKNOWN;
    }
    if ((*jniEnv)->CallStaticBooleanMethod(jniEnv, atkObject, jmideic,
                                           ac_role)) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return ATK_ROLE_PARAGRAPH;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_ROLE_UNKNOWN; /* ROLE_EXTENDED */
}

static gboolean is_same_java_state(JNIEnv *jniEnv, jobject jobj,
                                   const gchar *strState) {
    if (!jniEnv || !strState) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAccessibleState =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/AccessibleState");
    if (!classAccessibleState) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jfieldID jfid =
        (*jniEnv)->GetStaticFieldID(jniEnv, classAccessibleState, strState,
                                    "Ljavax/accessibility/AccessibleState;");
    if (!jfid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jobject jstate =
        (*jniEnv)->GetStaticObjectField(jniEnv, classAccessibleState, jfid);
    if (!jstate) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    if ((*jniEnv)->IsSameObject(jniEnv, jobj, jstate)) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return TRUE;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return FALSE;
}

AtkStateType jaw_util_get_atk_state_type_from_java_state(JNIEnv *jniEnv,
                                                         jobject jobj) {
    if (!jniEnv) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return ATK_STATE_INVALID;
    }

    if (is_same_java_state(jniEnv, jobj, "ACTIVE"))
        return ATK_STATE_ACTIVE;

    if (is_same_java_state(jniEnv, jobj, "ARMED"))
        return ATK_STATE_ARMED;

    if (is_same_java_state(jniEnv, jobj, "BUSY"))
        return ATK_STATE_BUSY;

    if (is_same_java_state(jniEnv, jobj, "CHECKED"))
        return ATK_STATE_CHECKED;

    if (is_same_java_state(jniEnv, jobj, "COLLAPSED"))
#if ATK_CHECK_VERSION(2, 38, 0)
        return ATK_STATE_COLLAPSED;
#else
        return ATK_STATE_INVALID;
#endif

    if (is_same_java_state(jniEnv, jobj, "EDITABLE"))
        return ATK_STATE_EDITABLE;

    if (is_same_java_state(jniEnv, jobj, "ENABLED"))
        return ATK_STATE_ENABLED;

    if (is_same_java_state(jniEnv, jobj, "EXPANDABLE"))
        return ATK_STATE_EXPANDABLE;

    if (is_same_java_state(jniEnv, jobj, "EXPANDED"))
        return ATK_STATE_EXPANDED;

    if (is_same_java_state(jniEnv, jobj, "FOCUSABLE"))
        return ATK_STATE_FOCUSABLE;

    if (is_same_java_state(jniEnv, jobj, "FOCUSED"))
        return ATK_STATE_FOCUSED;

    if (is_same_java_state(jniEnv, jobj, "HORIZONTAL"))
        return ATK_STATE_HORIZONTAL;

    if (is_same_java_state(jniEnv, jobj, "ICONIFIED"))
        return ATK_STATE_ICONIFIED;

    if (is_same_java_state(jniEnv, jobj, "INDETERMINATE"))
        return ATK_STATE_INDETERMINATE;

    if (is_same_java_state(jniEnv, jobj, "MANAGES_DESCENDANTS"))
        return ATK_STATE_MANAGES_DESCENDANTS;

    if (is_same_java_state(jniEnv, jobj, "MODAL"))
        return ATK_STATE_MODAL;

    if (is_same_java_state(jniEnv, jobj, "MULTI_LINE"))
        return ATK_STATE_MULTI_LINE;

    if (is_same_java_state(jniEnv, jobj, "MULTISELECTABLE"))
        return ATK_STATE_MULTISELECTABLE;

    if (is_same_java_state(jniEnv, jobj, "OPAQUE"))
        return ATK_STATE_OPAQUE;

    if (is_same_java_state(jniEnv, jobj, "PRESSED"))
        return ATK_STATE_PRESSED;

    if (is_same_java_state(jniEnv, jobj, "RESIZABLE"))
        return ATK_STATE_RESIZABLE;

    if (is_same_java_state(jniEnv, jobj, "SELECTABLE"))
        return ATK_STATE_SELECTABLE;

    if (is_same_java_state(jniEnv, jobj, "SELECTED"))
        return ATK_STATE_SELECTED;

    if (is_same_java_state(jniEnv, jobj, "SHOWING"))
        return ATK_STATE_SHOWING;

    if (is_same_java_state(jniEnv, jobj, "SINGLE_LINE"))
        return ATK_STATE_SINGLE_LINE;

    if (is_same_java_state(jniEnv, jobj, "TRANSIENT"))
        return ATK_STATE_TRANSIENT;

    if (is_same_java_state(jniEnv, jobj, "TRUNCATED"))
        return ATK_STATE_TRUNCATED;

    if (is_same_java_state(jniEnv, jobj, "VERTICAL"))
        return ATK_STATE_VERTICAL;

    if (is_same_java_state(jniEnv, jobj, "VISIBLE"))
        return ATK_STATE_VISIBLE;

    return ATK_STATE_INVALID;
}

void jaw_util_get_rect_info(JNIEnv *jniEnv, jobject jrect, gint *x, gint *y,
                            gint *width, gint *height) {
    JAW_DEBUG_C("%p, %p, %p, %p, %p, %p", jniEnv, jrect, x, y, width, height);

    if (!jniEnv || !x || !y || !width || !height || !jrect) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classRectangle = (*jniEnv)->FindClass(jniEnv, "java/awt/Rectangle");
    if (!classRectangle) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidX = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "x", "I");
    if (!jfidX) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidY = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "y", "I");
    if (!jfidY) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidWidth =
        (*jniEnv)->GetFieldID(jniEnv, classRectangle, "width", "I");
    if (!jfidWidth) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidHeight =
        (*jniEnv)->GetFieldID(jniEnv, classRectangle, "height", "I");
    if (!jfidHeight) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*x) = (gint)(*jniEnv)->GetIntField(jniEnv, jrect, jfidX);
    (*y) = (gint)(*jniEnv)->GetIntField(jniEnv, jrect, jfidY);
    (*width) = (gint)(*jniEnv)->GetIntField(jniEnv, jrect, jfidWidth);
    (*height) = (gint)(*jniEnv)->GetIntField(jniEnv, jrect, jfidHeight);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

#ifdef __cplusplus
}
#endif
