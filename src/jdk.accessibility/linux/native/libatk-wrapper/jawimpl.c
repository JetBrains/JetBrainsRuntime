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
// static void			jaw_impl_init				(JawImpl
// *impl);
static void jaw_impl_dispose(GObject *gobject);
static void jaw_impl_finalize(GObject *gobject);

static gpointer jaw_impl_get_interface_data(JawObject *jaw_obj, guint iface);

/* AtkObject */
static void jaw_impl_initialize(AtkObject *atk_obj, gpointer data);

typedef struct _JawInterfaceInfo {
    void (*finalize)(gpointer);
    gpointer data;
} JawInterfaceInfo;

static gpointer jaw_impl_parent_class = NULL;

static GMutex typeTableMutex;
static GHashTable *typeTable = NULL;
// static GMutex objectTableMutex;
// static GHashTable *objectTable = NULL;

// static void object_table_insert(JNIEnv *jniEnv, jobject ac, JawImpl
// *jaw_impl) {
//     JAW_DEBUG_C("%p, %p, %p", jniEnv, ac, jaw_impl);
//
//     if (!jniEnv || !ac || !jaw_impl) {
//         g_warning("Null argument passed to function");
//         return;
//     }
//
//     jclass atkObject =
//         (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
//     CHECK_NULL(atkObject, );
//     jmethodID jmid = (*jniEnv)->GetStaticMethodID(
//         jniEnv, atkObject, "hash_code",
//         "(Ljavax/accessibility/AccessibleContext;)I");
//     CHECK_NULL(jmid, );
//     gint hash_key =
//         (gint)(*jniEnv)->CallStaticIntMethod(jniEnv, atkObject, jmid, ac);
//     CHECK_NULL(hash_key, );
//     jaw_impl->hash_key = hash_key;
//
//     g_mutex_lock(&objectTableMutex);
//     g_hash_table_insert(objectTable, GINT_TO_POINTER(jaw_impl->hash_key),
//                         jaw_impl);
//     g_mutex_unlock(&objectTableMutex);
// }

// static JawImpl *object_table_lookup(JNIEnv *jniEnv, jobject ac) {
//     JAW_DEBUG_C("%p, %p", jniEnv, ac);
//
//     if (!jniEnv || !ac) {
//         g_warning("Null argument passed to function");
//         return NULL;
//     }
//
//     jclass atkObject =
//         (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
//     CHECK_NULL(atkObject, NULL);
//     jmethodID jmid = (*jniEnv)->GetStaticMethodID(
//         jniEnv, atkObject, "hash_code",
//         "(Ljavax/accessibility/AccessibleContext;)I");
//     CHECK_NULL(jmid, NULL);
//     gint hash_key =
//         (gint)(*jniEnv)->CallStaticIntMethod(jniEnv, atkObject, jmid, ac);
//     CHECK_NULL(hash_key, NULL);
//     gpointer value = NULL;
//     g_mutex_lock(&objectTableMutex);
//     if (objectTable == NULL) {
//         g_mutex_unlock(&objectTableMutex);
//         return NULL;
//     }
//     value = g_hash_table_lookup(objectTable, GINT_TO_POINTER(hash_key));
//     g_mutex_unlock(&objectTableMutex);
//     return (JawImpl *)value;
// }

// GHashTable *jaw_impl_get_object_hash_table(void) {
//     JAW_DEBUG_ALL("");
//     return objectTable;
// }

// GMutex *jaw_impl_get_object_hash_table_mutex(void) {
//     JAW_DEBUG_ALL("");
//     return &objectTableMutex;
// }

static void aggregate_interface(JNIEnv *jniEnv, JawObject *jaw_obj,
                                guint tflag) {
    JAW_DEBUG_C("%p, %p, %u", jniEnv, jaw_obj, tflag);

    if (!jniEnv || !jaw_obj) {
        g_warning("Null argument passed to function aggregate_interface");
        return;
    }

    JawImpl *jaw_impl = JAW_IMPL(tflag, jaw_obj);
    CHECK_NULL(jaw_impl, );

    jaw_impl->tflag = tflag;

    jobject ac = (*jniEnv)->NewGlobalRef(jniEnv, jaw_obj->acc_context);
    CHECK_NULL(ac, );

    jaw_impl->ifaceTable = g_hash_table_new(NULL, NULL);

    if (tflag & INTERFACE_ACTION) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_action_data_init(ac);
        info->finalize = jaw_action_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_ACTION,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_COMPONENT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_component_data_init(ac);
        info->finalize = jaw_component_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_COMPONENT,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_TEXT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_text_data_init(ac);
        info->finalize = jaw_text_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_TEXT,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_EDITABLE_TEXT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_editable_text_data_init(ac);
        info->finalize = jaw_editable_text_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable,
                            (gpointer)INTERFACE_EDITABLE_TEXT, (gpointer)info);
    }

    if (tflag & INTERFACE_HYPERTEXT) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_hypertext_data_init(ac);
        info->finalize = jaw_hypertext_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_HYPERTEXT,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_IMAGE) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_image_data_init(ac);
        info->finalize = jaw_image_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_IMAGE,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_SELECTION) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_selection_data_init(ac);
        info->finalize = jaw_selection_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_SELECTION,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_VALUE) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_value_data_init(ac);
        info->finalize = jaw_value_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_VALUE,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_TABLE) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_table_data_init(ac);
        info->finalize = jaw_table_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable, (gpointer)INTERFACE_TABLE,
                            (gpointer)info);
    }

    if (tflag & INTERFACE_TABLE_CELL) {
        JawInterfaceInfo *info = g_new(JawInterfaceInfo, 1);
        info->data = jaw_table_cell_data_init(ac);
        info->finalize = jaw_table_cell_data_finalize;
        g_hash_table_insert(jaw_impl->ifaceTable,
                            (gpointer)INTERFACE_TABLE_CELL, (gpointer)info);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
}

JawImpl *jaw_impl_create_instance(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);

    if (!ac || !jniEnv) {
        g_warning("Null argument passed to function jaw_impl_create_instance");
        return NULL;
    }

    JawImpl *jaw_impl;

    jobject temp_ref = (*jniEnv)->NewGlobalRef(jniEnv, ac);
    guint tflag = jaw_util_get_tflag_from_jobj(jniEnv, temp_ref);

    jaw_impl = (JawImpl *)g_object_new(JAW_TYPE_IMPL(tflag), NULL);
    if (jaw_impl == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, temp_ref);
        return NULL;
    }

    JawObject *jaw_obj = JAW_OBJECT(jaw_impl);
    if (jaw_obj == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, temp_ref);
        return NULL;
    }

    jobject weak_ref = (*jniEnv)->NewWeakGlobalRef(jniEnv, temp_ref);
    jaw_obj->acc_context = weak_ref;
    jaw_obj->storedData = g_hash_table_new(g_str_hash, g_str_equal);
    aggregate_interface(jniEnv, jaw_obj, tflag);
    atk_object_initialize(ATK_OBJECT(jaw_impl), NULL);
    (*jniEnv)->DeleteGlobalRef(jniEnv, temp_ref);

    return jaw_impl;
}

JawImpl *jaw_impl_find_instance(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);

    if (!ac || !jniEnv) {
        g_warning("Null argument passed to function jaw_impl_find_instance");
        return NULL;
    }

    jclass classAtkWrapper =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkWrapper");
    CHECK_NULL(classAtkWrapper, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classAtkWrapper, "get_native_resources",
        "(Ljavax/accessibility/AccessibleContext;)J");
    CHECK_NULL(jmid, NULL);
    jlong reference = (gint)(*jniEnv)->CallStaticLongMethod(
        jniEnv, classAtkWrapper, jmid, ac);
    CHECK_NULL(reference, NULL);

    JawImpl *jaw_impl = (JawImpl *)reference;

    return jaw_impl;
}

JawImpl *jaw_impl_get_instance_from_jaw(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);

    if (!jniEnv || !ac) {
        g_warning(
            "Null argument passed to function jaw_impl_get_instance_from_jaw");
        return NULL;
    }

    jclass classWrapper =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkWrapper");
    CHECK_NULL(classWrapper, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classWrapper, "getInstanceFromSwing",
        "(Ljavax/accessibility/AccessibleContext;)J");
    CHECK_NULL(jmid, NULL);
    jlong ptr = (*jniEnv)->CallStaticLongMethod(jniEnv, classWrapper, jmid, ac);
    CHECK_NULL(ptr, NULL);
    return (JawImpl *)(uintptr_t)ptr;
}

// JawImpl *jaw_impl_find_instance(JNIEnv *jniEnv, jobject ac) {
//     JAW_DEBUG_C("%p, %p", jniEnv, ac);
//
//     if (!jniEnv || !ac) {
//         g_warning("Null argument passed to function");
//         return NULL;
//     }
//
//     JawImpl *jaw_impl = object_table_lookup(jniEnv, ac);
//     if (jaw_impl == NULL) {
//         JAW_DEBUG_I("jaw_impl == NULL");
//         return NULL;
//     }
//
//     return jaw_impl;
// }

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

        if (tflag & INTERFACE_ACTION)
            g_type_add_interface_static(type, ATK_TYPE_ACTION,
                                        &atk_action_info);

        if (tflag & INTERFACE_COMPONENT)
            g_type_add_interface_static(type, ATK_TYPE_COMPONENT,
                                        &atk_component_info);

        if (tflag & INTERFACE_TEXT)
            g_type_add_interface_static(type, ATK_TYPE_TEXT, &atk_text_info);

        if (tflag & INTERFACE_EDITABLE_TEXT)
            g_type_add_interface_static(type, ATK_TYPE_EDITABLE_TEXT,
                                        &atk_editable_text_info);

        if (tflag & INTERFACE_HYPERTEXT)
            g_type_add_interface_static(type, ATK_TYPE_HYPERTEXT,
                                        &atk_hypertext_info);

        if (tflag & INTERFACE_IMAGE)
            g_type_add_interface_static(type, ATK_TYPE_IMAGE, &atk_image_info);

        if (tflag & INTERFACE_SELECTION)
            g_type_add_interface_static(type, ATK_TYPE_SELECTION,
                                        &atk_selection_info);

        if (tflag & INTERFACE_VALUE)
            g_type_add_interface_static(type, ATK_TYPE_VALUE, &atk_value_info);

        if (tflag & INTERFACE_TABLE)
            g_type_add_interface_static(type, ATK_TYPE_TABLE, &atk_table_info);

        if (tflag & INTERFACE_TABLE_CELL)
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
        g_warning("Null argument passed to function jaw_impl_class_init");
        return;
    }

    GObjectClass *gobject_class = G_OBJECT_CLASS(klass);
    CHECK_NULL(gobject_class, );
    gobject_class->dispose = jaw_impl_dispose;
    gobject_class->finalize = jaw_impl_finalize;

    AtkObjectClass *atk_class = ATK_OBJECT_CLASS(klass);
    CHECK_NULL(atk_class, );
    atk_class->initialize = jaw_impl_initialize;

    JawObjectClass *jaw_class = JAW_OBJECT_CLASS(klass);
    CHECK_NULL(jaw_class, );
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
        g_warning("Null argument passed to function jaw_impl_finalize");
        return;
    }

    JawObject *jaw_obj = JAW_OBJECT(gobject);
    CHECK_NULL(jaw_obj, );
    JawImpl *jaw_impl = (JawImpl *)jaw_obj;
    CHECK_NULL(jaw_impl, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, );

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
        g_warning(
            "Null argument passed to function jaw_impl_get_interface_data");
        return NULL;
    }

    JawImpl *jaw_impl = (JawImpl *)jaw_obj;
    CHECK_NULL(jaw_obj, NULL);

    if (jaw_impl == NULL || jaw_impl->ifaceTable == NULL)
        return NULL;

    JawInterfaceInfo *info =
        g_hash_table_lookup(jaw_impl->ifaceTable, GUINT_TO_POINTER(iface));

    if (info != NULL)
        return info->data;

    return NULL;
}

static void jaw_impl_initialize(AtkObject *atk_obj, gpointer data) {
    JAW_DEBUG_C("%p, %p", atk_obj, data);

    if (!atk_obj || !data) {
        g_warning("Null argument passed to function jaw_impl_initialize");
        return;
    }

    ATK_OBJECT_CLASS(jaw_impl_parent_class)->initialize(atk_obj, data);

    JawObject *jaw_obj = JAW_OBJECT(atk_obj);
    CHECK_NULL(jaw_obj, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, );

    jclass classAtkWrapper =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkWrapper");
    CHECK_NULL(classAtkWrapper, );
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classAtkWrapper, "registerPropertyChangeListener",
        "(Ljavax/accessibility/AccessibleContext;)V");
    CHECK_NULL(jmid, );
    jobject ac = (*jniEnv)->NewGlobalRef(jniEnv, jaw_obj->acc_context);
    CHECK_NULL(ac, );
    (*jniEnv)->CallStaticVoidMethod(jniEnv, classAtkWrapper, jmid, ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
}

static gboolean is_java_relation_key(JNIEnv *jniEnv, jstring jKey,
                                     const gchar *strKey) {
    JAW_DEBUG_C("%p, %p, %s", jniEnv, jKey, strKey);

    if (!jniEnv || !strKey) {
        g_warning("Null argument passed to function is_java_relation_key");
        return FALSE;
    }

    jclass classAccessibleRelation =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/AccessibleRelation");
    CHECK_NULL(classAccessibleRelation, FALSE);

    jfieldID jfid = (*jniEnv)->GetStaticFieldID(jniEnv, classAccessibleRelation,
                                                strKey, "Ljava/lang/String;");
    CHECK_NULL(jfid, FALSE);

    jstring jConstKey =
        (*jniEnv)->GetStaticObjectField(jniEnv, classAccessibleRelation, jfid);

    // jKey and jConstKey may be null
    return (*jniEnv)->IsSameObject(jniEnv, jKey, jConstKey);
}

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
