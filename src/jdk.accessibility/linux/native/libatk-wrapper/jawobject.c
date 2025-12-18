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
#include "jawcache.h"
#include "jawimpl.h"
#include "jawtoplevel.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkObject:
 *
 * The base object class for the Accessibility Toolkit API.
 *
 * This class is the primary class for accessibility support via the
 * Accessibility ToolKit (ATK).  Objects which are instances of
 * #AtkObject (or instances of AtkObject-derived types) are queried
 * for properties which relate basic (and generic) properties of a UI
 * component such as name and description.  Instances of #AtkObject
 * may also be queried as to whether they implement other ATK
 * interfaces (e.g. #AtkAction, #AtkComponent, etc.), as appropriate
 * to the role which a given UI component plays in a user interface.
 *
 * All UI components in an application which provide useful
 * information or services to the user must provide corresponding
 * #AtkObject instances on request (in GTK+, for instance, usually on
 * a call to #gtk_widget_get_accessible ()), either via ATK support
 * built into the toolkit for the widget class or ancestor class, or
 * in the case of custom widgets, if the inherited #AtkObject
 * implementation is insufficient, via instances of a new #AtkObject
 * subclass.
 *
 * See [class@AtkObjectFactory], [class@AtkRegistry].  (GTK+ users see also
 * #GtkAccessible).
 *
 */

#ifdef __cplusplus
extern "C" {
#endif

jclass cachedObjectAtkObjectClass = NULL;
jmethodID cachedObjectGetAccessibleParentMethod = NULL;
jmethodID cachedObjectSetAccessibleParentMethod = NULL;
jmethodID cachedObjectGetAccessibleNameMethod = NULL;
jmethodID cachedObjectSetAccessibleNameMethod = NULL;
jmethodID cachedObjectGetAccessibleDescriptionMethod = NULL;
jmethodID cachedObjectSetAccessibleDescriptionMethod = NULL;
jmethodID cachedObjectGetAccessibleChildrenCountMethod = NULL;
jmethodID cachedObjectGetAccessibleIndexInParentMethod = NULL;
jmethodID cachedObjectGetArrayAccessibleStateMethod = NULL;
jmethodID cachedObjectGetLocaleMethod = NULL;
jmethodID cachedObjectGetArrayAccessibleRelationMethod = NULL;
jmethodID cachedObjectGetAccessibleChildMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_object_init_jni_cache(JNIEnv *jniEnv);

static void jaw_object_initialize(AtkObject *jaw_obj, gpointer data);
static void jaw_object_dispose(GObject *gobject);
static void jaw_object_finalize(GObject *gobject);

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

    if (klass == NULL) {
        g_warning("%s: Null argument klass passed to the function", G_STRFUNC);
        return;
    }

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
    // atk_class->get_layer is done by atk
    atk_class->get_mdi_zorder =
        NULL; // missing java support for atk_class->get_mdi_zorder
    atk_class->ref_state_set = jaw_object_ref_state_set;
    atk_class->set_name = jaw_object_set_name;
    atk_class->set_description = jaw_object_set_description;
    atk_class->set_parent = jaw_object_set_parent;
    atk_class->set_role = jaw_object_set_role;
    atk_class->initialize = jaw_object_initialize;
    atk_class->get_attributes = NULL; // TODO: atk_class->get_attributes
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

    if (atk_obj == NULL) {
        g_warning("%s: Null argument atk_obj passed to the function",
                  G_STRFUNC);
        return;
    }

    ATK_OBJECT_CLASS(jaw_object_parent_class)->initialize(atk_obj, data);
}

gpointer jaw_object_get_interface_data(JawObject *jaw_obj, guint iface) {
    JAW_DEBUG_C("%p, %u", jaw_obj, iface);

    if (jaw_obj == NULL) {
        g_warning("%s: Null argument jaw_obj passed to the function",
                  G_STRFUNC);
        return NULL;
    }

    JawObjectClass *klass = JAW_OBJECT_GET_CLASS(jaw_obj);
    JAW_CHECK_NULL(klass, NULL);
    if (klass->get_interface_data)
        return klass->get_interface_data(jaw_obj, iface);

    return NULL;
}

static void jaw_object_init(JawObject *object) {
    JAW_DEBUG_ALL("%p", object);

    if (object == NULL) {
        g_warning("%s: Null argument object passed to the function", G_STRFUNC);
        return;
    }

    AtkObject *atk_obj = ATK_OBJECT(object);
    JAW_CHECK_NULL(atk_obj, );
    atk_obj->description = NULL;

    object->state_set = atk_state_set_new();
}

static void jaw_object_dispose(GObject *gobject) {
    JAW_DEBUG_C("%p", gobject);

    if (gobject == NULL) {
        g_warning("%s: Null argument gobject passed to the function",
                  G_STRFUNC);
        G_OBJECT_CLASS(jaw_object_parent_class)->dispose(gobject);
        return;
    }

    G_OBJECT_CLASS(jaw_object_parent_class)->dispose(gobject);
}

static void jaw_object_finalize(GObject *gobject) {
    JAW_DEBUG_ALL("%p", gobject);

    if (gobject == NULL) {
        g_warning("%s: Null argument gobject passed to the function",
                  G_STRFUNC);
        return;
    }

    /* Customized finalize code */
    JawObject *jaw_obj = JAW_OBJECT(gobject);
    if (jaw_obj == NULL) {
        g_debug("%s: jaw_obj is NULL", G_STRFUNC);
        G_OBJECT_CLASS(jaw_object_parent_class)->finalize(gobject);
        return;
    }
    AtkObject *atk_obj = ATK_OBJECT(gobject);
    if (atk_obj == NULL) {
        g_debug("%s: atk_obj is NULL", G_STRFUNC);
        G_OBJECT_CLASS(jaw_object_parent_class)->finalize(gobject);
        return;
    }
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_debug("%s: jniEnv is NULL", G_STRFUNC);
        G_OBJECT_CLASS(jaw_object_parent_class)->finalize(gobject);
        return;
    }

    if (jaw_obj->jstrName != NULL) {
        if (atk_obj->name != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrName,
                                             atk_obj->name);
            atk_obj->name = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrName);
        jaw_obj->jstrName = NULL;
    }

    if (jaw_obj->jstrDescription != NULL) {
        if (atk_obj->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrDescription,
                                             atk_obj->description);
            atk_obj->description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrDescription);
        jaw_obj->jstrDescription = NULL;
    }

    if (jaw_obj->jstrLocale != NULL) {
        if (jaw_obj->locale != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrLocale,
                                             jaw_obj->locale);
            jaw_obj->locale = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrLocale);
        jaw_obj->jstrLocale = NULL;
    }

    if (jaw_obj->state_set != NULL) {
        g_object_unref(G_OBJECT(jaw_obj->state_set));
    }

    /* Chain up to parent's finalize method */
    G_OBJECT_CLASS(jaw_object_parent_class)->finalize(gobject);
}

/**
 * jaw_object_get_parent:
 * @accessible: an #AtkObject
 *
 * Gets the accessible parent of the accessible.
 *
 * Returns: (transfer none): an #AtkObject representing the accessible
 * parent of the accessible
 **/
static AtkObject *jaw_object_get_parent(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    AtkObject *root = atk_get_root();
    int toplevel_child_index =
        jaw_toplevel_get_child_index(JAW_TOPLEVEL(root), atk_obj);
    if (toplevel_child_index != -1) {
        return ATK_OBJECT(root);
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jparent = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetAccessibleParentMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jparent == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    AtkObject *parent_obj =
        (AtkObject *)jaw_impl_find_instance(jniEnv, jparent);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    if (parent_obj != NULL) {
        return parent_obj;
    }

    g_warning(
        "jaw_object_get_parent: didn't find jaw for parent, returning null");
    return NULL;
}

/**
 * jaw_object_set_parent:
 * @accessible: an #AtkObject
 *
 * Gets the accessible parent of the accessible.
 *
 * Returns: (transfer none): an #AtkObject representing the accessible
 * parent of the accessible
 **/
static void jaw_object_set_parent(AtkObject *atk_obj, AtkObject *parent) {
    JAW_DEBUG_C("%p, %p", atk_obj, parent);

    if (!atk_obj || !parent) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_OBJECT(atk_obj, ); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    JawObject *jaw_par = JAW_OBJECT(parent);
    if (jaw_par == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject pa = (*jniEnv)->NewGlobalRef(jniEnv, jaw_par->acc_context);
    if (pa == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallStaticVoidMethod(jniEnv, cachedObjectAtkObjectClass,
                                    cachedObjectSetAccessibleParentMethod, ac,
                                    pa);
    jaw_jni_clear_exception(jniEnv);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, pa);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_object_get_name:
 * @role: The #AtkRole whose name is required
 *
 * Gets the description string describing the #AtkRole @role.
 *
 * Returns: the string describing the AtkRole
 */
static const gchar *jaw_object_get_name(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    atk_obj->name = (gchar *)ATK_OBJECT_CLASS(parent_class)->get_name(atk_obj);

    if (atk_object_get_role(atk_obj) == ATK_ROLE_COMBO_BOX &&
        atk_object_get_n_accessible_children(atk_obj) == 1) {
        AtkSelection *selection = ATK_SELECTION(atk_obj);
        if (selection != NULL) {
            // The caller of the method takes ownership of the returned data,
            // and is responsible for freeing it.
            AtkObject *child = atk_selection_ref_selection(selection, 0);
            if (child != NULL) {
                const gchar *name = atk_object_get_name(child);
                g_object_unref(G_OBJECT(child));
                if (name)
                    JAW_DEBUG_C("-> %s", name);
                return name;
            }
        }
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass, cachedObjectGetAccessibleNameMethod,
        ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
    }

    if (jaw_obj->jstrName != NULL) {
        if (atk_obj->name != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrName,
                                             atk_obj->name);
            atk_obj->name = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrName);
        jaw_obj->jstrName = NULL;
    }

    if (jstr != NULL) {
        jaw_obj->jstrName = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        atk_obj->name = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, jaw_obj->jstrName, NULL);
    }

    if (atk_obj->name != NULL) {
        JAW_DEBUG_C("-> %s", atk_obj->name);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return atk_obj->name;
}

/**
 * jaw_object_set_name:
 * @accessible: an #AtkObject
 * @name: a character string to be set as the accessible name
 *
 * Sets the accessible name of the accessible. You can't set the name
 * to NULL. This is reserved for the initial value. In this aspect
 * NULL is similar to ATK_ROLE_UNKNOWN. If you want to set the name to
 * a empty value you can use "".
 **/
static void jaw_object_set_name(AtkObject *atk_obj, const gchar *name) {
    JAW_DEBUG_C("%p, %s", atk_obj, name);

    if (!atk_obj || !name) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_OBJECT(atk_obj, ); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jstring jstr = NULL;
    if (name != NULL) {
        jstr = (*jniEnv)->NewStringUTF(jniEnv, name);
    }

    (*jniEnv)->CallStaticVoidMethod(jniEnv, cachedObjectAtkObjectClass,
                                    cachedObjectSetAccessibleNameMethod, ac,
                                    jstr);
    jaw_jni_clear_exception(jniEnv);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_object_get_description:
 * @accessible: an #AtkObject
 *
 * Gets the accessible description of the accessible.
 *
 * Returns: a character string representing the accessible description
 * of the accessible.
 *
 **/
static const gchar *jaw_object_get_description(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetAccessibleDescriptionMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (jaw_obj->jstrDescription != NULL) {
        if (atk_obj->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrDescription,
                                             atk_obj->description);
            atk_obj->description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrDescription);
        jaw_obj->jstrDescription = NULL;
    }

    if (jstr != NULL) {
        jaw_obj->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        atk_obj->description = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, jaw_obj->jstrDescription, NULL);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return atk_obj->description;
}

/**
 * jaw_object_set_description:
 * @accessible: an #AtkObject
 * @description: a character string to be set as the accessible description
 *
 * Sets the accessible description of the accessible. You can't set
 * the description to NULL. This is reserved for the initial value. In
 * this aspect NULL is similar to ATK_ROLE_UNKNOWN. If you want to set
 * the name to a empty value you can use "".
 **/
static void jaw_object_set_description(AtkObject *atk_obj,
                                       const gchar *description) {
    JAW_DEBUG_C("%p, %s", atk_obj, description);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_OBJECT(atk_obj, ); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jstring jstr = NULL;
    if (description != NULL) {
        jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    }

    (*jniEnv)->CallStaticVoidMethod(jniEnv, cachedObjectAtkObjectClass,
                                    cachedObjectSetAccessibleDescriptionMethod,
                                    ac, jstr);
    jaw_jni_clear_exception(jniEnv);

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_object_get_n_children:
 * @accessible: an #AtkObject
 *
 * Gets the number of accessible children of the accessible.
 *
 * Returns: an integer representing the number of accessible children
 * of the accessible.
 **/
static gint jaw_object_get_n_children(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_OBJECT(atk_obj, 0); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return 0;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jint count = (*jniEnv)->CallStaticIntMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetAccessibleChildrenCountMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)count;
}

/**
 * jaw_object_get_index_in_parent:
 * @accessible: an #AtkObject
 *
 * Gets the 0-based index of this accessible in its parent; returns -1 if the
 * accessible does not have an accessible parent.
 *
 * Returns: an integer which is the index of the accessible in its parent
 **/
static gint jaw_object_get_index_in_parent(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return -1;
    }

    int toplevel_child_index =
        jaw_toplevel_get_child_index(JAW_TOPLEVEL(atk_get_root()), atk_obj);
    if (toplevel_child_index != -1) {
        return toplevel_child_index;
    }

    JAW_GET_OBJECT(atk_obj, -1); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return -1;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return -1;
    }

    jint index = (*jniEnv)->CallStaticIntMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetAccessibleIndexInParentMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return -1;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)index;
}

/**
 * jaw_object_get_role:
 * @accessible: an #AtkObject
 *
 * Gets the role of the accessible.
 *
 * Returns: an #AtkRole which is the role of the accessible
 **/
static AtkRole jaw_object_get_role(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return ATK_ROLE_INVALID;
    }

    if (atk_obj->role != ATK_ROLE_INVALID &&
        atk_obj->role != ATK_ROLE_UNKNOWN) {
        JAW_DEBUG_C("-> %d", atk_obj->role);
        return atk_obj->role;
    }

    JAW_GET_OBJECT(
        atk_obj, ATK_ROLE_INVALID); // create global JNI reference `jobject ac`
    AtkRole role = jaw_util_get_atk_role_from_AccessibleContext(ac);
    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    JAW_DEBUG_C("-> %d", role);
    return role;
}

/**
 * jaw_object_set_role:
 * @accessible: an #AtkObject
 * @role: an #AtkRole to be set as the role
 *
 * Sets the role of the accessible.
 **/
static void jaw_object_set_role(AtkObject *atk_obj, AtkRole role) {
    JAW_DEBUG_C("%p, %d", atk_obj, role);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    atk_obj->role = role;
}

#if !ATK_CHECK_VERSION(2, 38, 0)
static gboolean is_collapsed_java_state(JNIEnv *jniEnv, jobject jobj) {
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
        (*jniEnv)->GetStaticFieldID(jniEnv, classAccessibleState, "COLLAPSED",
                                    "Ljavax/accessibility/AccessibleState;");
    if (!jfid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jobject jstate =
        (*jniEnv)->GetStaticObjectField(jniEnv, classAccessibleState, jfid);

    // jobj and jstate may be null
    if ((*jniEnv)->IsSameObject(jniEnv, jobj, jstate)) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return TRUE;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
    return FALSE;
}
#endif

/**
 * atk_object_ref_state_set:
 * @accessible: an #AtkObject
 *
 * Gets a reference to the state set of the accessible; the caller must
 * unreference it when it is no longer needed.
 *
 * Returns: (transfer full): a reference to an #AtkStateSet which is the state
 * set of the accessible
 **/
static AtkStateSet *jaw_object_ref_state_set(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    AtkStateSet *state_set = jaw_obj->state_set;
    if (state_set == NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    atk_state_set_clear_states(state_set);

    jobject jstate_arr = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetArrayAccessibleStateMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstate_arr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jsize jarr_size = (*jniEnv)->GetArrayLength(jniEnv, jstate_arr);
    jsize i;
    for (i = 0; i < jarr_size; i++) {
        jobject jstate =
            (*jniEnv)->GetObjectArrayElement(jniEnv, jstate_arr, i);
#if !ATK_CHECK_VERSION(2, 38, 0)
        if (jstate && is_collapsed_java_state(jniEnv, jstate)) {
            (*jniEnv)->DeleteLocalRef(jniEnv, jstate);
            continue;
        }
#endif
        AtkStateType state_type =
            jaw_util_get_atk_state_type_from_java_state(jniEnv, jstate);
        atk_state_set_add_state(state_set, state_type);
        if (state_type == ATK_STATE_ENABLED) {
            atk_state_set_add_state(state_set, ATK_STATE_SENSITIVE);
        }
        (*jniEnv)->DeleteLocalRef(jniEnv, jstate);
    }

    g_object_ref(G_OBJECT(state_set)); // because transfer full

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return state_set;
}

/**
 * jaw_object_get_object_locale:
 * @accessible: an #AtkObject
 *
 * Gets a UTF-8 string indicating the POSIX-style LC_MESSAGES locale
 * of @accessible.
 *
 * Returns: a UTF-8 string indicating the POSIX-style LC_MESSAGES
 *          locale of @accessible.
 **/
static const gchar *jaw_object_get_object_locale(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jstr = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass, cachedObjectGetLocaleMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (jaw_obj->jstrLocale != NULL) {
        if (jaw_obj->locale != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, jaw_obj->jstrLocale,
                                             jaw_obj->locale);
            jaw_obj->locale = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, jaw_obj->jstrLocale);
        jaw_obj->jstrLocale = NULL;
    }

    if (jstr != NULL) {
        jaw_obj->jstrLocale = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
        jaw_obj->locale = (gchar *)(*jniEnv)->GetStringUTFChars(
            jniEnv, jaw_obj->jstrLocale, NULL);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jaw_obj->locale;
}

/**
 * jaw_object_ref_relation_set:
 * @accessible: an #AtkObject
 *
 * Gets the #AtkRelationSet associated with the object.
 *
 * Returns: (transfer full) : an #AtkRelationSet representing the relation set
 * of the object.
 **/
static AtkRelationSet *jaw_object_ref_relation_set(AtkObject *atk_obj) {
    JAW_DEBUG_C("%p)", atk_obj);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 20) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    if (atk_obj->relation_set != NULL) {
        g_object_unref(G_OBJECT(atk_obj->relation_set));
    }
    atk_obj->relation_set = atk_relation_set_new();

    jobject jwrap_key_target_arr = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetArrayAccessibleRelationMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jwrap_key_target_arr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);

    jsize jarr_size = (*jniEnv)->GetArrayLength(jniEnv, jwrap_key_target_arr);
    if (!jarr_size) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jclass wrapKeyTarget = (*jniEnv)->FindClass(
        jniEnv, "org/GNOME/Accessibility/AtkObject$WrapKeyAndTarget");
    if (!wrapKeyTarget) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID fIdRelations =
        (*jniEnv)->GetFieldID(jniEnv, wrapKeyTarget, "relations",
                              "[Ljavax/accessibility/AccessibleContext;");
    if (!fIdRelations) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jfieldID fIdKey = (*jniEnv)->GetFieldID(jniEnv, wrapKeyTarget, "key",
                                            "Ljava/lang/String;");
    if (!fIdKey) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jsize i;
    for (i = 0; i < jarr_size; i++) {
        jobject jwrap_key_target =
            (*jniEnv)->GetObjectArrayElement(jniEnv, jwrap_key_target_arr, i);
        if (!jwrap_key_target) {
            continue;
        }
        jstring jrel_key =
            (*jniEnv)->GetObjectField(jniEnv, jwrap_key_target, fIdKey);
        if (!jrel_key) {
            (*jniEnv)->DeleteLocalRef(jniEnv, jwrap_key_target);
            continue;
        }
        AtkRelationType rel_type =
            jaw_impl_get_atk_relation_type(jniEnv, jrel_key);
        if (!rel_type) {
            (*jniEnv)->DeleteLocalRef(jniEnv, jwrap_key_target);
            (*jniEnv)->DeleteLocalRef(jniEnv, jrel_key);
            continue;
        }
        jobjectArray jtarget_arr =
            (*jniEnv)->GetObjectField(jniEnv, jwrap_key_target, fIdRelations);
        if (!jtarget_arr) {
            (*jniEnv)->DeleteLocalRef(jniEnv, jwrap_key_target);
            (*jniEnv)->DeleteLocalRef(jniEnv, jrel_key);
            continue;
        }
        jsize jtarget_size = (*jniEnv)->GetArrayLength(jniEnv, jtarget_arr);
        if (!jtarget_size) {
            (*jniEnv)->DeleteLocalRef(jniEnv, jwrap_key_target);
            (*jniEnv)->DeleteLocalRef(jniEnv, jrel_key);
            (*jniEnv)->DeleteLocalRef(jniEnv, jtarget_arr);
            continue;
        }

        jsize j;
        for (j = 0; j < jtarget_size; j++) {
            jobject jtarget =
                (*jniEnv)->GetObjectArrayElement(jniEnv, jtarget_arr, j);
            if (!jtarget) {
                continue;
            }
            JawImpl *target_obj = jaw_impl_find_instance(jniEnv, jtarget);
            if (target_obj == NULL) {
                g_warning(
                    "jaw_object_ref_relation_set: target_obj == NULL occurs\n");
            } else {
                atk_object_add_relationship(atk_obj, rel_type,
                                            ATK_OBJECT(target_obj));
            }
            (*jniEnv)->DeleteLocalRef(jniEnv, jtarget);
        }

        (*jniEnv)->DeleteLocalRef(jniEnv, jwrap_key_target);
        (*jniEnv)->DeleteLocalRef(jniEnv, jrel_key);
        (*jniEnv)->DeleteLocalRef(jniEnv, jtarget_arr);
    }

    if (atk_obj->relation_set == NULL) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    if (atk_obj->relation_set != NULL) { // because transfer full
        g_object_ref(G_OBJECT(atk_obj->relation_set));
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return atk_obj->relation_set;
}

/**
 * jaw_object_ref_child:
 * @accessible: an #AtkObject
 * @i: a gint representing the position of the child, starting from 0
 *
 * Gets a reference to the specified accessible child of the object.
 *
 * Returns: an #AtkObject representing the specified
 * accessible child of the accessible.
 **/
static AtkObject *jaw_object_ref_child(AtkObject *atk_obj, gint i) {
    JAW_DEBUG_C("%p, %d", atk_obj, i);

    if (!atk_obj) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_OBJECT(atk_obj, NULL); // create global JNI reference `jobject ac`

    if (!jaw_object_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            ac); // deleting ref that was created in JAW_GET_OBJECT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject child_ac = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedObjectAtkObjectClass,
        cachedObjectGetAccessibleChildMethod, ac, i);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || child_ac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    AtkObject *obj = (AtkObject *)jaw_impl_find_instance(jniEnv, child_ac);
    // From the documentation of `ref_child` in AtkObject
    // (https://docs.gtk.org/atk/vfunc.Object.ref_child.html): The returned data
    // is owned by the instance, so the object is not referenced before being
    // returned. Documentation in the repository states nothing about it. In
    // fact, `ref_child` is used in `atk_object_ref_accessible_child`, where the
    // caller takes ownership of the returned data and is responsible for
    // freeing it. `atk_object_ref_accessible_child` does not reference the
    // object. Therefore, I assume that `ref_child` should reference the object.
    if (obj != NULL) {
        g_object_ref(G_OBJECT(obj));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, ac);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return obj;
}

static gboolean jaw_object_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkObject");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkObject class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedObjectAtkObjectClass = (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedObjectAtkObjectClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkObject class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedObjectGetAccessibleParentMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_accessible_parent",
        "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/"
        "AccessibleContext;");

    cachedObjectSetAccessibleParentMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "set_accessible_parent",
        "(Ljavax/accessibility/AccessibleContext;Ljavax/accessibility/"
        "AccessibleContext;)V");

    cachedObjectGetAccessibleNameMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_accessible_name",
        "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    cachedObjectSetAccessibleNameMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "set_accessible_name",
        "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)V");

    cachedObjectGetAccessibleDescriptionMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_accessible_description",
        "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    cachedObjectSetAccessibleDescriptionMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "set_accessible_description",
        "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)V");

    cachedObjectGetAccessibleChildrenCountMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_accessible_children_count",
        "(Ljavax/accessibility/AccessibleContext;)I");

    cachedObjectGetAccessibleIndexInParentMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_accessible_index_in_parent",
        "(Ljavax/accessibility/AccessibleContext;)I");

    cachedObjectGetArrayAccessibleStateMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_array_accessible_state",
        "(Ljavax/accessibility/AccessibleContext;)[Ljavax/accessibility/"
        "AccessibleState;");

    cachedObjectGetLocaleMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_locale",
        "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    cachedObjectGetArrayAccessibleRelationMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_array_accessible_relation",
        "(Ljavax/accessibility/AccessibleContext;)[Lorg/GNOME/Accessibility/"
        "AtkObject$WrapKeyAndTarget;");

    cachedObjectGetAccessibleChildMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedObjectAtkObjectClass, "get_accessible_child",
        "(Ljavax/accessibility/AccessibleContext;I)Ljavax/accessibility/"
        "AccessibleContext;");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedObjectGetAccessibleParentMethod == NULL ||
        cachedObjectSetAccessibleParentMethod == NULL ||
        cachedObjectGetAccessibleNameMethod == NULL ||
        cachedObjectSetAccessibleNameMethod == NULL ||
        cachedObjectGetAccessibleDescriptionMethod == NULL ||
        cachedObjectSetAccessibleDescriptionMethod == NULL ||
        cachedObjectGetAccessibleChildrenCountMethod == NULL ||
        cachedObjectGetAccessibleIndexInParentMethod == NULL ||
        cachedObjectGetArrayAccessibleStateMethod == NULL ||
        cachedObjectGetLocaleMethod == NULL ||
        cachedObjectGetArrayAccessibleRelationMethod == NULL ||
        cachedObjectGetAccessibleChildMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkObject method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedObjectAtkObjectClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedObjectAtkObjectClass);
        cachedObjectAtkObjectClass = NULL;
    }
    cachedObjectGetAccessibleParentMethod = NULL;
    cachedObjectSetAccessibleParentMethod = NULL;
    cachedObjectGetAccessibleNameMethod = NULL;
    cachedObjectSetAccessibleNameMethod = NULL;
    cachedObjectGetAccessibleDescriptionMethod = NULL;
    cachedObjectSetAccessibleDescriptionMethod = NULL;
    cachedObjectGetAccessibleChildrenCountMethod = NULL;
    cachedObjectGetAccessibleIndexInParentMethod = NULL;
    cachedObjectGetArrayAccessibleStateMethod = NULL;
    cachedObjectGetLocaleMethod = NULL;
    cachedObjectGetArrayAccessibleRelationMethod = NULL;
    cachedObjectGetAccessibleChildMethod = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_object_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedObjectAtkObjectClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedObjectAtkObjectClass);
        cachedObjectAtkObjectClass = NULL;
    }
    cachedObjectGetAccessibleParentMethod = NULL;
    cachedObjectSetAccessibleParentMethod = NULL;
    cachedObjectGetAccessibleNameMethod = NULL;
    cachedObjectSetAccessibleNameMethod = NULL;
    cachedObjectGetAccessibleDescriptionMethod = NULL;
    cachedObjectSetAccessibleDescriptionMethod = NULL;
    cachedObjectGetAccessibleChildrenCountMethod = NULL;
    cachedObjectGetAccessibleIndexInParentMethod = NULL;
    cachedObjectGetArrayAccessibleStateMethod = NULL;
    cachedObjectGetLocaleMethod = NULL;
    cachedObjectGetArrayAccessibleRelationMethod = NULL;
    cachedObjectGetAccessibleChildMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}

#ifdef __cplusplus
}
#endif