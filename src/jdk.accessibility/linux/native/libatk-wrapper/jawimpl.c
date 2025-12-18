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

#include "jawimpl.h"
#include "jawobject.h"
#include "jawtoplevel.h"
#include "jawutil.h"
#include <assert.h>
#include <glib-object.h>
#include <glib.h>
#include <glib/gprintf.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

static void jaw_impl_class_init(JawImplClass *klass);
static void jaw_impl_dispose(GObject *gobject);
static void jaw_impl_finalize(GObject *gobject);
static gpointer jaw_impl_get_interface_data(JawObject *jaw_obj, guint iface);
static void jaw_impl_initialize(AtkObject *atk_obj, gpointer data);

typedef struct _JawInterfaceInfo {
    void (*finalize)(gpointer);
    gpointer data;
} JawInterfaceInfo;

static gpointer jaw_impl_parent_class = NULL;

static GMutex typeTableMutex;
static GHashTable *typeTable = NULL;

static void aggregate_interface(JNIEnv *jniEnv, JawObject *jaw_obj,
                                guint tflag) {
    JAW_DEBUG_C("%p, %p, %u", jniEnv, jaw_obj, tflag);

    if (!jniEnv || !jaw_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JawImpl *jaw_impl = JAW_IMPL(tflag, jaw_obj);
    JAW_CHECK_NULL(jaw_impl, );

    jaw_impl->tflag = tflag;

    jobject ac = (*jniEnv)->NewGlobalRef(jniEnv, jaw_obj->acc_context);
    JAW_CHECK_NULL(ac, );

    jaw_impl->ifaceTable = g_hash_table_new(NULL, NULL);

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_ACTION) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_action_data_init(ac);
        info->finalize = jaw_action_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_ACTION,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_COMPONENT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_component_data_init(ac);
        info->finalize = jaw_component_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_COMPONENT,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_TEXT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_text_data_init(ac);
        info->finalize = jaw_text_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_TEXT,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_EDITABLE_TEXT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_editable_text_data_init(ac);
        info->finalize = jaw_editable_text_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)
                org_GNOME_Accessibility_AtkInterface_INTERFACE_EDITABLE_TEXT,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_HYPERTEXT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_hypertext_data_init(ac);
        info->finalize = jaw_hypertext_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_HYPERTEXT,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_IMAGE) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_image_data_init(ac);
        info->finalize = jaw_image_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_IMAGE,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_SELECTION) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_selection_data_init(ac);
        info->finalize = jaw_selection_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_SELECTION,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_VALUE) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_value_data_init(ac);
        info->finalize = jaw_value_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_VALUE,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_table_data_init(ac);
        info->finalize = jaw_table_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE,
            (gpointer)info);
    }

    if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE_CELL) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_table_cell_data_init(ac);
        info->finalize = jaw_table_cell_data_finalize;
        g_hash_table_insert(
            jaw_impl->ifaceTable,
            (gpointer)org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE_CELL,
            (gpointer)info);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
}

JawImpl *jaw_impl_create_instance(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);

    if (!ac || !jniEnv) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JawImpl *jaw_impl;

    guint tflag = jaw_util_get_tflag_from_jobj(jniEnv, ac);

    jaw_impl = (JawImpl *)g_object_new(JAW_TYPE_IMPL(tflag), NULL);
    if (jaw_impl == NULL) {
        g_warning("%s: Failed to create new JawImpl object", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    JawObject *jaw_obj = JAW_OBJECT(jaw_impl);
    if (jaw_obj == NULL) {
        g_warning("%s: Failed to create JawObject", G_STRFUNC);
        g_object_unref(G_OBJECT(jaw_impl));
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    jobject weak_ref = (*jniEnv)->NewWeakGlobalRef(jniEnv, ac);
    jaw_obj->acc_context = weak_ref;
    jaw_obj->storedData = g_hash_table_new(g_str_hash, g_str_equal);
    aggregate_interface(jniEnv, jaw_obj, tflag);
    atk_object_initialize(ATK_OBJECT(jaw_impl), NULL);

    return jaw_impl;
}

JawImpl *jaw_impl_find_instance(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);

    if (!ac || !jniEnv) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    // Check if there is JawImpl associated with the accessible context
    jclass classAtkWrapper = (*jniEnv)->FindClass(
        jniEnv, "org/GNOME/Accessibility/AtkWrapperDisposer");
    if (classAtkWrapper == NULL) {
        g_warning("%s: Failed to find AtkWrapperDisposer class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid_get_native_resources = (*jniEnv)->GetStaticMethodID(
        jniEnv, classAtkWrapper, "get_resource",
        "(Ljavax/accessibility/AccessibleContext;)J");
    if (jmid_get_native_resources == NULL) {
        g_warning("%s: Failed to find get_resource method", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jlong reference = (*jniEnv)->CallStaticLongMethod(
        jniEnv, classAtkWrapper, jmid_get_native_resources, ac);

    // If a valid reference exists, return the existing JawImpl instance
    if (reference != -1) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return (JawImpl *)reference;
    }

    return NULL;
}

static void jaw_impl_class_intern_init(gpointer klass, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", klass, data);

    if (!klass) {
        g_warning(
            "Null argument passed to function jaw_impl_class_intern_init");
        return;
    }

    if (jaw_impl_parent_class == NULL) {
        jaw_impl_parent_class = g_type_class_peek_parent(klass);
    }

    jaw_impl_class_init((JawImplClass *)klass);
}

GType jaw_impl_get_type(guint tflag) {
    JAW_DEBUG_C("%u", tflag);
    GType type;

    static const GInterfaceInfo atk_action_info = {
        (GInterfaceInitFunc)jaw_action_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_component_info = {
        (GInterfaceInitFunc)jaw_component_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_text_info = {
        (GInterfaceInitFunc)jaw_text_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_editable_text_info = {
        (GInterfaceInitFunc)jaw_editable_text_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_hypertext_info = {
        (GInterfaceInitFunc)jaw_hypertext_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_image_info = {
        (GInterfaceInitFunc)jaw_image_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_selection_info = {
        (GInterfaceInitFunc)jaw_selection_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_value_info = {
        (GInterfaceInitFunc)jaw_value_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_table_info = {
        (GInterfaceInitFunc)jaw_table_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    static const GInterfaceInfo atk_table_cell_info = {
        (GInterfaceInitFunc)jaw_table_cell_interface_init,
        (GInterfaceFinalizeFunc)NULL, NULL};

    g_mutex_lock(&typeTableMutex);
    if (typeTable == NULL) {
        typeTable = g_hash_table_new(NULL, NULL);
    }

    type = GPOINTER_TO_GTYPE(
        g_hash_table_lookup(typeTable, GUINT_TO_POINTER(tflag)));
    g_mutex_unlock(&typeTableMutex);
    if (type == 0) {
        GTypeInfo tinfo = {
            sizeof(JawImplClass),
            (GBaseInitFunc)NULL,                        /* base init */
            (GBaseFinalizeFunc)NULL,                    /* base finalize */
            (GClassInitFunc)jaw_impl_class_intern_init, /*class init */
            (GClassFinalizeFunc)NULL,                   /* class finalize */
            NULL,                                       /* class data */
            sizeof(JawImpl),                            /* instance size */
            0,                                          /* nb preallocs */
            (GInstanceInitFunc)NULL,                    /* instance init */
            NULL                                        /* value table */
        };

        gchar className[20];
        g_sprintf(className, "JawImpl_%d", tflag);

        type = g_type_register_static(JAW_TYPE_OBJECT, className, &tinfo, 0);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_ACTION)
            g_type_add_interface_static(type, ATK_TYPE_ACTION,
                                        &atk_action_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_COMPONENT)
            g_type_add_interface_static(type, ATK_TYPE_COMPONENT,
                                        &atk_component_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_TEXT)
            g_type_add_interface_static(type, ATK_TYPE_TEXT, &atk_text_info);

        if (tflag &
            org_GNOME_Accessibility_AtkInterface_INTERFACE_EDITABLE_TEXT)
            g_type_add_interface_static(type, ATK_TYPE_EDITABLE_TEXT,
                                        &atk_editable_text_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_HYPERTEXT)
            g_type_add_interface_static(type, ATK_TYPE_HYPERTEXT,
                                        &atk_hypertext_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_IMAGE)
            g_type_add_interface_static(type, ATK_TYPE_IMAGE, &atk_image_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_SELECTION)
            g_type_add_interface_static(type, ATK_TYPE_SELECTION,
                                        &atk_selection_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_VALUE)
            g_type_add_interface_static(type, ATK_TYPE_VALUE, &atk_value_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE)
            g_type_add_interface_static(type, ATK_TYPE_TABLE, &atk_table_info);

        if (tflag & org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE_CELL)
            g_type_add_interface_static(type, ATK_TYPE_TABLE_CELL,
                                        &atk_table_cell_info);

        g_mutex_lock(&typeTableMutex);
        g_hash_table_insert(typeTable, GINT_TO_POINTER(tflag),
                            JAW_TYPE_TO_POINTER(type));
        g_mutex_unlock(&typeTableMutex);
    }

    return type;
}

static void jaw_impl_class_init(JawImplClass *klass) {
    JAW_DEBUG_ALL("%p", klass);

    if (!klass) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    GObjectClass *gobject_class = G_OBJECT_CLASS(klass);
    JAW_CHECK_NULL(gobject_class, );
    gobject_class->dispose = jaw_impl_dispose;
    gobject_class->finalize = jaw_impl_finalize;

    AtkObjectClass *atk_class = ATK_OBJECT_CLASS(klass);
    JAW_CHECK_NULL(atk_class, );
    atk_class->initialize = jaw_impl_initialize;

    JawObjectClass *jaw_class = JAW_OBJECT_CLASS(klass);
    JAW_CHECK_NULL(jaw_class, );
    jaw_class->get_interface_data = jaw_impl_get_interface_data;
}

static void jaw_impl_dispose(GObject *gobject) {
    JAW_DEBUG_ALL("%p", gobject);
    /* Chain up to parent's dispose */
    G_OBJECT_CLASS(jaw_impl_parent_class)->dispose(gobject);
}

static void jaw_impl_finalize(GObject *gobject) {
    JAW_DEBUG_ALL("%p", gobject);

    if (!gobject) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JawObject *jaw_obj = JAW_OBJECT(gobject);
    JAW_CHECK_NULL(jaw_obj, );
    JawImpl *jaw_impl = (JawImpl *)jaw_obj;
    JAW_CHECK_NULL(jaw_impl, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    (*jniEnv)->DeleteWeakGlobalRef(jniEnv, jaw_obj->acc_context);
    jaw_obj->acc_context = NULL;

    /* Interface finalize */
    GHashTableIter iter;
    gpointer value;

    g_hash_table_iter_init(&iter, jaw_impl->ifaceTable);
    while (g_hash_table_iter_next(&iter, NULL, &value)) {
        JawInterfaceInfo *info = (JawInterfaceInfo *)value;
        if (info != NULL) {
            info->finalize(info->data);
            g_free(info);
        }

        g_hash_table_iter_remove(&iter);
    }
    if (jaw_impl->ifaceTable != NULL) {
        g_hash_table_unref(jaw_impl->ifaceTable);
    }
    if (jaw_obj->storedData != NULL) {
        g_hash_table_destroy(jaw_obj->storedData);
    }

    /* Chain up to parent's finalize */
    G_OBJECT_CLASS(jaw_impl_parent_class)->finalize(gobject);
}

static gpointer jaw_impl_get_interface_data(JawObject *jaw_obj, guint iface) {
    JAW_DEBUG_C("%p, %u", jaw_obj, iface);

    if (!jaw_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JawImpl *jaw_impl = (JawImpl *)jaw_obj;
    JAW_CHECK_NULL(jaw_obj, NULL);

    if (jaw_impl == NULL || jaw_impl->ifaceTable == NULL) {
        return NULL;
    }

    JawInterfaceInfo *info =
        g_hash_table_lookup(jaw_impl->ifaceTable, GUINT_TO_POINTER(iface));

    if (info != NULL) {
        return info->data;
    }

    return NULL;
}

static void jaw_impl_initialize(AtkObject *atk_obj, gpointer data) {
    JAW_DEBUG_C("%p, %p", atk_obj, data);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    ATK_OBJECT_CLASS(jaw_impl_parent_class)->initialize(atk_obj, data);

    JawObject *jaw_obj = JAW_OBJECT(atk_obj);
    JAW_CHECK_NULL(jaw_obj, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkWrapper =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkWrapper");
    if (classAtkWrapper == NULL) {
        g_warning("%s: Failed to find AtkWrapper class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classAtkWrapper, "register_property_change_listener",
        "(Ljavax/accessibility/AccessibleContext;)V");
    if (jmid == NULL) {
        g_warning("%s: Failed to find register_property_change_listener method",
                  G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    };
    jobject ac = (*jniEnv)->NewGlobalRef(jniEnv, jaw_obj->acc_context);
    if (ac == NULL) {
        g_warning("%s: Failed to create global reference to acc_context",
                  G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallStaticVoidMethod(jniEnv, classAtkWrapper, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * Checks if the given jKey (name of the relation) matches the value of static
 * field in AccessibleRelation identified by strKey.
 *
 * @param jniEnv JNI environment pointer
 * @param jKey   key of AccessibleRelation, the name of the relation
 * @param strKey name of the static AccessibleRelation field
 * @return       TRUE if jKey equals the corresponding static field, FALSE
 * otherwise
 */
static gboolean is_java_relation_key(JNIEnv *jniEnv, jstring jKey,
                                     const gchar *strKey) {
    JAW_DEBUG_C("%p, %p, %s", jniEnv, jKey, strKey);

    if (!jniEnv || !strKey) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAccessibleRelation =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/AccessibleRelation");
    if (classAccessibleRelation == NULL) {
        g_warning("%s: Failed to find AccessibleRelation class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    jfieldID jfid = (*jniEnv)->GetStaticFieldID(jniEnv, classAccessibleRelation,
                                                strKey, "Ljava/lang/String;");
    if (jfid == NULL) {
        g_warning("%s: Failed to find static field %s", G_STRFUNC, strKey);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    jstring jConstKey =
        (*jniEnv)->GetStaticObjectField(jniEnv, classAccessibleRelation, jfid);

    // jKey and jConstKey may be null
    jboolean result = (*jniEnv)->IsSameObject(jniEnv, jKey, jConstKey);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

/**
 * Compares the given key of Java AccessibleRelation (jrel_key) with some of
 * AccessibleRelation fields. If a match is found, returns the
 * corresponding AtkRelationType; otherwise, returns ATK_RELATION_NULL.
 *
 * @param jniEnv   JNI environment pointer
 * @param jrel_key key of AccessibleRelation, the name of the relation
 * @return         Corresponding AtkRelationType or ATK_RELATION_NULL
 */
AtkRelationType jaw_impl_get_atk_relation_type(JNIEnv *jniEnv,
                                               jstring jrel_key) {
    JAW_DEBUG_C("%p, %p", jniEnv, jrel_key);
    if (is_java_relation_key(jniEnv, jrel_key, "CHILD_NODE_OF"))
        return ATK_RELATION_NODE_CHILD_OF;
    if (is_java_relation_key(jniEnv, jrel_key, "CONTROLLED_BY"))
        return ATK_RELATION_CONTROLLED_BY;
    if (is_java_relation_key(jniEnv, jrel_key, "CONTROLLER_FOR"))
        return ATK_RELATION_CONTROLLER_FOR;
    if (is_java_relation_key(jniEnv, jrel_key, "EMBEDDED_BY"))
        return ATK_RELATION_EMBEDDED_BY;
    if (is_java_relation_key(jniEnv, jrel_key, "EMBEDS"))
        return ATK_RELATION_EMBEDS;
    if (is_java_relation_key(jniEnv, jrel_key, "FLOWS_FROM"))
        return ATK_RELATION_FLOWS_FROM;
    if (is_java_relation_key(jniEnv, jrel_key, "FLOWS_TO"))
        return ATK_RELATION_FLOWS_TO;
    if (is_java_relation_key(jniEnv, jrel_key, "LABEL_FOR"))
        return ATK_RELATION_LABEL_FOR;
    if (is_java_relation_key(jniEnv, jrel_key, "LABELED_BY"))
        return ATK_RELATION_LABELLED_BY;
    if (is_java_relation_key(jniEnv, jrel_key, "MEMBER_OF"))
        return ATK_RELATION_MEMBER_OF;
    if (is_java_relation_key(jniEnv, jrel_key, "PARENT_WINDOW_OF"))
        return ATK_RELATION_PARENT_WINDOW_OF;
    if (is_java_relation_key(jniEnv, jrel_key, "SUBWINDOW_OF"))
        return ATK_RELATION_SUBWINDOW_OF;
    return ATK_RELATION_NULL;
}

#ifdef __cplusplus
}
#endif
