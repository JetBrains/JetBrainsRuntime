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

#include "jawobject.h"
#include "jawimpl.h"
#include "jawtoplevel.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

#ifdef __cplusplus
extern "C" {
#endif

static void jaw_object_initialize(AtkObject *jaw_obj, gpointer data);
static void jaw_object_dispose(GObject *gobject);
static void jaw_object_finalize(GObject *gobject);

/* AtkObject */
static const gchar *jaw_object_get_name(AtkObject *atk_obj);
static const gchar *jaw_object_get_description(AtkObject *atk_obj);

static gint jaw_object_get_n_children(AtkObject *atk_obj);

static gint jaw_object_get_index_in_parent(AtkObject *atk_obj);

static AtkRole jaw_object_get_role(AtkObject *atk_obj);
static AtkStateSet *jaw_object_ref_state_set(AtkObject *atk_obj);
static AtkObject *jaw_object_get_parent(AtkObject *obj);

static void jaw_object_set_name(AtkObject *atk_obj, const gchar *name);
static void jaw_object_set_description(AtkObject *atk_obj,
                                       const gchar *description);
static void jaw_object_set_parent(AtkObject *atk_obj, AtkObject *parent);
static void jaw_object_set_role(AtkObject *atk_obj, AtkRole role);
static const gchar *jaw_object_get_object_locale(AtkObject *atk_obj);
static AtkRelationSet *jaw_object_ref_relation_set(AtkObject *atk_obj);
static AtkObject *jaw_object_ref_child(AtkObject *atk_obj, gint i);

static gpointer parent_class = NULL;
static JawObject *jaw_object_table_lookup(JNIEnv *jniEnv, jobject ac);

enum {
    ACTIVATE,
    CREATE,
    DEACTIVATE,
    DESTROY,
    MAXIMIZE,
    MINIMIZE,
    MOVE,
    RESIZE,
    RESTORE,
    LAST_SIGNAL
};

static guint jaw_window_signals[LAST_SIGNAL] = {
    0,
};

G_DEFINE_TYPE(JawObject, jaw_object, ATK_TYPE_OBJECT);

#define JAW_GET_OBJECT(atk_obj, def_ret)                                       \
    JAW_GET_OBJ(atk_obj, JAW_OBJECT, JawObject, jaw_obj, acc_context, jniEnv,  \
                ac, def_ret)

static guint jaw_window_add_signal(const gchar *name, JawObjectClass *klass) {
    JAW_DEBUG_C("%s, %p", name, klass);
    return g_signal_new(name, G_TYPE_FROM_CLASS(klass), G_SIGNAL_RUN_LAST, 0,
                        (GSignalAccumulator)NULL, NULL,
                        g_cclosure_marshal_VOID__VOID, G_TYPE_NONE, 0);
}

static void jaw_object_class_init(JawObjectClass *klass) {
    JAW_DEBUG_ALL("%p", klass);
    GObjectClass *gobject_class = G_OBJECT_CLASS(klass);
    gobject_class->dispose = jaw_object_dispose;
    gobject_class->finalize = jaw_object_finalize;

    AtkObjectClass *atk_class = ATK_OBJECT_CLASS(klass);
    parent_class = g_type_class_peek_parent(klass);

    atk_class->get_name = jaw_object_get_name;
    atk_class->get_description = jaw_object_get_description;
    atk_class->get_parent = jaw_object_get_parent;
    atk_class->get_n_children = jaw_object_get_n_children;
    atk_class->ref_child = jaw_object_ref_child;
    atk_class->get_index_in_parent = jaw_object_get_index_in_parent;
    atk_class->ref_relation_set = jaw_object_ref_relation_set;
    atk_class->get_role = jaw_object_get_role;
    // Done by atk: atk_class->get_layer
    // TODO: missing java support for atk_class->get_mdi_zorder
    atk_class->ref_state_set = jaw_object_ref_state_set;
    atk_class->set_name = jaw_object_set_name;
    atk_class->set_description = jaw_object_set_description;
    atk_class->set_parent = jaw_object_set_parent;
    atk_class->set_role = jaw_object_set_role;
    atk_class->initialize = jaw_object_initialize;
    // TODO: atk_class->get_attributes
    atk_class->get_object_locale = jaw_object_get_object_locale;

    jaw_window_signals[ACTIVATE] = jaw_window_add_signal("activate", klass);
    jaw_window_signals[CREATE] = jaw_window_add_signal("create", klass);
    jaw_window_signals[DEACTIVATE] = jaw_window_add_signal("deactivate", klass);
    jaw_window_signals[DESTROY] = jaw_window_add_signal("destroy", klass);
    jaw_window_signals[MAXIMIZE] = jaw_window_add_signal("maximize", klass);
    jaw_window_signals[MINIMIZE] = jaw_window_add_signal("minimize", klass);
    jaw_window_signals[MOVE] = jaw_window_add_signal("move", klass);
    jaw_window_signals[RESIZE] = jaw_window_add_signal("resize", klass);
    jaw_window_signals[RESTORE] = jaw_window_add_signal("restore", klass);

    klass->get_interface_data = NULL;
}

static void jaw_object_initialize(AtkObject *atk_obj, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", atk_obj, data);
    ATK_OBJECT_CLASS(jaw_object_parent_class)->initialize(atk_obj, data);
}

gpointer jaw_object_get_interface_data(JawObject *jaw_obj, guint iface) {
    JAW_DEBUG_C("%p, %u", jaw_obj, iface);
    JawObjectClass *klass = JAW_OBJECT_GET_CLASS(jaw_obj);
    if (klass->get_interface_data)
        return klass->get_interface_data(jaw_obj, iface);

    return NULL;
}

static void jaw_object_init(JawObject *object) {
    JAW_DEBUG_ALL("%p", object);
    AtkObject *atk_obj = ATK_OBJECT(object);
    atk_obj->description = NULL;

    object->state_set = atk_state_set_new();
}

static void jaw_object_dispose(GObject *gobject) {
    JAW_DEBUG_C("%p", gobject);
    /* Customized dispose code */

    /* Chain up to parent's dispose method */
    G_OBJECT_CLASS(jaw_object_parent_class)->dispose(gobject);
}

static void jaw_object_finalize(GObject *gobject) {
    JAW_DEBUG_ALL("%p", gobject);
    /* Customized finalize code */
    JawObject *jaw_obj = JAW_OBJECT(gobject);
    AtkObject *atk_obj = ATK_OBJECT(gobject);
    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (atk_obj->name != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrName,
                                         atk_obj->name);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrName);
        jaw_obj->jstrName = NULL;
        atk_obj->name = NULL;
    }

    if (atk_obj->description != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrDescription,
                                         atk_obj->description);

        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrDescription);
        jaw_obj->jstrDescription = NULL;
        atk_obj->description = NULL;
    }

    if (jaw_obj->locale != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrLocale,
                                         jaw_obj->locale);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrLocale);
        jaw_obj->jstrLocale = NULL;
        jaw_obj->locale = NULL;
    }

    if (G_OBJECT(jaw_obj->state_set) != NULL) {
        g_object_unref(G_OBJECT(jaw_obj->state_set));
    }

    /* Chain up to parent's finalize method */
    G_OBJECT_CLASS(jaw_object_parent_class)->finalize(gobject);
}

static AtkObject *jaw_object_get_parent(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    if (jaw_toplevel_get_child_index(JAW_TOPLEVEL(atk_get_root()), atk_obj) !=
        -1)
        return ATK_OBJECT(atk_get_root());

    JAW_GET_OBJECT(atk_obj, NULL);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getAccessibleParent",
        "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/"
        "AccessibleContext;");
    jobject jparent =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    if (jparent != NULL) {
        AtkObject *parent_obj =
            (AtkObject *)jaw_object_table_lookup(jniEnv, jparent);

        if (parent_obj != NULL)
            return parent_obj;

        g_warning("didn't find jaw for parent, returning null");
    }

    g_warning("didn't find parent, returning null");
    return NULL;
}

static void jaw_object_set_parent(AtkObject *atk_obj, AtkObject *parent) {
    JAW_DEBUG_C("%p, %p", atk_obj, parent);
    JAW_GET_OBJECT(atk_obj, );

    JawObject *jaw_par = JAW_OBJECT(parent);
    jobject pa = (*jniEnv)->NewGlobalRef(jniEnv, jaw_par->acc_context);
    if (!pa) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return;
    }

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "setAccessibleParent",
        "(Ljavax/accessibility/AccessibleContext;Ljavax/accessibility/"
        "AccessibleContext;)V");
    (*jniEnv)->CallStaticVoidMethod(jniEnv, atkObject, jmid, ac, pa);
    // FIXME do we need to emit the signal 'children-changed::add'?
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, pa);
}

static const gchar *jaw_object_get_name(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    atk_obj->name = (gchar *)ATK_OBJECT_CLASS(parent_class)->get_name(atk_obj);

    if (atk_object_get_role(atk_obj) == ATK_ROLE_COMBO_BOX &&
        atk_object_get_n_accessible_children(atk_obj) == 1) {
        AtkSelection *selection = ATK_SELECTION(atk_obj);
        if (selection != NULL) {
            AtkObject *child = atk_selection_ref_selection(selection, 0);
            if (child != NULL) {
                const gchar *name = atk_object_get_name(child);
                g_object_unref(child);
                if (name)
                    JAW_DEBUG_C("-> %s", name);
                return name;
            }
        }
    }

    JAW_GET_OBJECT(atk_obj, NULL);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getAccessibleName",
        "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");
    jstring jstr =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    if (atk_obj->name != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrName,
                                         atk_obj->name);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrName);
        jaw_obj->jstrName = NULL;
        atk_obj->name = NULL;
    }

    if (jstr != NULL) {
        jaw_obj->jstrName = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        atk_obj->name = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, jaw_obj->jstrName, NULL);
    }

    if (atk_obj->name)
        JAW_DEBUG_C("-> %s", atk_obj->name);
    return atk_obj->name;
}

static void jaw_object_set_name(AtkObject *atk_obj, const gchar *name) {
    JAW_DEBUG_C("%p, %s", atk_obj, name);
    JAW_GET_OBJECT(atk_obj, );

    jstring jstr = NULL;
    if (name) {
        jstr = (*jniEnv)->NewStringUTF(jniEnv, name);
    }

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "setAccessibleName",
        "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)V");
    (*jniEnv)->CallStaticVoidMethod(jniEnv, atkObject, jmid, ac, jstr);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
}

static const gchar *jaw_object_get_description(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    JAW_GET_OBJECT(atk_obj, NULL);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getAccessibleDescription",
        "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");
    jstring jstr =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    if (atk_obj->description != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrDescription,
                                         atk_obj->description);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrDescription);
        jaw_obj->jstrDescription = NULL;
        atk_obj->description = NULL;
    }

    if (jstr != NULL) {
        jaw_obj->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        atk_obj->description = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, jaw_obj->jstrDescription, NULL);
    }

    return atk_obj->description;
}

static void jaw_object_set_description(AtkObject *atk_obj,
                                       const gchar *description) {
    JAW_DEBUG_C("%p, %s", atk_obj, description);
    JAW_GET_OBJECT(atk_obj, );

    jstring jstr = NULL;
    if (description) {
        jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    }

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "setAccessibleDescription",
        "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)");
    (*jniEnv)->CallStaticVoidMethod(jniEnv, atkObject, jmid, ac, jstr);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
}

static gint jaw_object_get_n_children(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    JAW_GET_OBJECT(atk_obj, 0);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getAccessibleChildrenCount",
        "(Ljavax/accessibility/AccessibleContext;)I");
    jint count = (*jniEnv)->CallStaticIntMethod(jniEnv, atkObject, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    return (gint)count;
}

static gint jaw_object_get_index_in_parent(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    if (jaw_toplevel_get_child_index(JAW_TOPLEVEL(atk_get_root()), atk_obj) !=
        -1) {
        return jaw_toplevel_get_child_index(JAW_TOPLEVEL(atk_get_root()),
                                            atk_obj);
    }

    JAW_GET_OBJECT(atk_obj, 0);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getAccessibleIndexInParent",
        "(Ljavax/accessibility/AccessibleContext;)I");
    jint index = (*jniEnv)->CallStaticIntMethod(jniEnv, atkObject, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    return (gint)index;
}

static AtkRole jaw_object_get_role(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    if (atk_obj->role != ATK_ROLE_INVALID &&
        atk_obj->role != ATK_ROLE_UNKNOWN) {
        JAW_DEBUG_C("-> %d", atk_obj->role);
        return atk_obj->role;
    }

    JAW_GET_OBJECT(atk_obj, ATK_ROLE_INVALID);

    AtkRole role = jaw_util_get_atk_role_from_AccessibleContext(ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    JAW_DEBUG_C("-> %d", role);
    return role;
}

static void jaw_object_set_role(AtkObject *atk_obj, AtkRole role) {
    JAW_DEBUG_C("%p, %d", atk_obj, role);
    atk_obj->role = role;
}

#if !ATK_CHECK_VERSION(2, 38, 0)
static gboolean is_collapsed_java_state(JNIEnv *jniEnv, jobject jobj) {
    jclass classAccessibleState =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/AccessibleState");
    jfieldID jfid =
        (*jniEnv)->GetStaticFieldID(jniEnv, classAccessibleState, "COLLAPSED",
                                    "Ljavax/accessibility/AccessibleState;");
    jobject jstate =
        (*jniEnv)->GetStaticObjectField(jniEnv, classAccessibleState, jfid);

    if ((*jniEnv)->IsSameObject(jniEnv, jobj, jstate)) {
        return TRUE;
    }

    return FALSE;
}
#endif

static AtkStateSet *jaw_object_ref_state_set(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    JAW_GET_OBJECT(atk_obj, NULL);

    AtkStateSet *state_set = jaw_obj->state_set;
    atk_state_set_clear_states(state_set);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getArrayAccessibleState",
        "(Ljavax/accessibility/AccessibleContext;)[Ljavax/accessibility/"
        "AccessibleState;");
    jobject jstate_arr =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    if (jstate_arr == NULL)
        return NULL;

    jsize jarr_size = (*jniEnv)->GetArrayLength(jniEnv, jstate_arr);
    jsize i;
    for (i = 0; i < jarr_size; i++) {
        jobject jstate =
            (*jniEnv)->GetObjectArrayElement(jniEnv, jstate_arr, i);
#if !ATK_CHECK_VERSION(2, 38, 0)
        if (is_collapsed_java_state(jniEnv, jstate)) {
            continue;
        }
#endif
        AtkStateType state_type =
            jaw_util_get_atk_state_type_from_java_state(jniEnv, jstate);
        atk_state_set_add_state(state_set, state_type);
        if (state_type == ATK_STATE_ENABLED) {
            atk_state_set_add_state(state_set, ATK_STATE_SENSITIVE);
        }
    }

    g_object_ref(G_OBJECT(state_set));

    return state_set;
}

static const gchar *jaw_object_get_object_locale(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);
    JAW_GET_OBJECT(atk_obj, NULL);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getLocale",
        "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");
    jobject jstr =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    if (jaw_obj->locale != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrLocale,
                                         jaw_obj->locale);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrLocale);
        jaw_obj->jstrLocale = NULL;
        jaw_obj->locale = NULL;
    }

    if (jstr != NULL) {
        jaw_obj->jstrLocale = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        jaw_obj->locale = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, jaw_obj->jstrLocale, NULL);
    }

    return jaw_obj->locale;
}

static AtkRelationSet *jaw_object_ref_relation_set(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p)", atk_obj);
    JAW_GET_OBJECT(atk_obj, NULL);

    if (atk_obj->relation_set)
        g_object_unref(G_OBJECT(atk_obj->relation_set));
    atk_obj->relation_set = atk_relation_set_new();

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getArrayAccessibleRelation",
        "(Ljavax/accessibility/AccessibleContext;)[Lorg/GNOME/Accessibility/"
        "AtkObject$WrapKeyAndTarget;");
    jobject jwrap_key_target_arr =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    jsize jarr_size = (*jniEnv)->GetArrayLength(jniEnv, jwrap_key_target_arr);
    jclass wrapKeyTarget = (*jniEnv)->FindClass(
        jniEnv, "org/GNOME/Accessibility/AtkObject$WrapKeyAndTarget");
    jfieldID fIdRelations =
        (*jniEnv)->GetFieldID(jniEnv, wrapKeyTarget, "relations",
                              "[Ljavax/accessibility/AccessibleContext;");
    jfieldID fIdKey = (*jniEnv)->GetFieldID(jniEnv, wrapKeyTarget, "key",
                                            "Ljava/lang/String;");

    jsize i;
    for (i = 0; i < jarr_size; i++) {
        jobject jwrap_key_target =
            (*jniEnv)->GetObjectArrayElement(jniEnv, jwrap_key_target_arr, i);
        jstring jrel_key =
            (*jniEnv)->GetObjectField(jniEnv, jwrap_key_target, fIdKey);
        AtkRelationType rel_type =
            jaw_impl_get_atk_relation_type(jniEnv, jrel_key);
        jobjectArray jtarget_arr =
            (*jniEnv)->GetObjectField(jniEnv, jwrap_key_target, fIdRelations);
        jsize jtarget_size = (*jniEnv)->GetArrayLength(jniEnv, jtarget_arr);

        jsize j;
        for (j = 0; j < jtarget_size; j++) {
            jobject jtarget =
                (*jniEnv)->GetObjectArrayElement(jniEnv, jtarget_arr, j);
            JawImpl *target_obj =
                jaw_impl_get_instance_from_jaw(jniEnv, jtarget);
            if (target_obj == NULL)
                g_warning(
                    "jaw_object_ref_relation_set: target_obj == NULL occurs\n");
            else
                atk_object_add_relationship(atk_obj, rel_type,
                                            ATK_OBJECT(target_obj));
        }
    }

    if (atk_obj->relation_set == NULL)
        return NULL;
    if (G_OBJECT(atk_obj->relation_set) != NULL)
        g_object_ref(atk_obj->relation_set);

    return atk_obj->relation_set;
}

static AtkObject *jaw_object_ref_child(AtkObject *atk_obj, gint i) {
    JAW_DEBUG_C("%p, %d", atk_obj, i);
    JAW_GET_OBJECT(atk_obj, NULL);

    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "getAccessibleChild",
        "(Ljavax/accessibility/AccessibleContext;I)Ljavax/accessibility/"
        "AccessibleContext;");
    jobject child_ac =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, atkObject, jmid, ac, i);
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    AtkObject *obj =
        (AtkObject *)jaw_impl_get_instance_from_jaw(jniEnv, child_ac);
    if (G_OBJECT(obj) != NULL)
        g_object_ref(G_OBJECT(obj));

    return obj;
}

static JawObject *jaw_object_table_lookup(JNIEnv *jniEnv, jobject ac) {
    JAW_DEBUG_C("%p, %p", jniEnv, ac);
    GHashTable *object_table = jaw_impl_get_object_hash_table();
    GMutex *object_table_mutex = jaw_impl_get_object_hash_table_mutex();
    jclass atkObject =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, atkObject, "hashCode",
        "(Ljavax/accessibility/AccessibleContext;)I");
    gint hash_key =
        (gint)(*jniEnv)->CallStaticIntMethod(jniEnv, atkObject, jmid, ac);
    gpointer value = NULL;
    if (object_table == NULL)
        return NULL;

    g_mutex_lock(object_table_mutex);
    value = g_hash_table_lookup(object_table, GINT_TO_POINTER(hash_key));
    g_mutex_unlock(object_table_mutex);
    return (JawObject *)value;
}

#ifdef __cplusplus
}
#endif