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
 * AtkSelection:
 *
 * The ATK interface implemented by container objects whose #AtkObject children
 * can be selected.
 *
 * #AtkSelection should be implemented by UI components with children
 * which are exposed by #atk_object_ref_child and
 * #atk_object_get_n_children, if the use of the parent UI component
 * ordinarily involves selection of one or more of the objects
 * corresponding to those #AtkObject children - for example,
 * selectable lists.
 *
 * Note that other types of "selection" (for instance text selection)
 * are accomplished a other ATK interfaces - #AtkSelection is limited
 * to the selection/deselection of children.
 */

static jclass cachedSelectionAtkSelectionClass = NULL;
static jmethodID cachedSelectionCreateAtkSelectionMethod = NULL;
static jmethodID cachedSelectionAddSelectionMethod = NULL;
static jmethodID cachedSelectionClearSelectionMethod = NULL;
static jmethodID cachedSelectionRefSelectionMethod = NULL;
static jmethodID cachedSelectionGetSelectionCountMethod = NULL;
static jmethodID cachedSelectionIsChildSelectedMethod = NULL;
static jmethodID cachedSelectionRemoveSelectionMethod = NULL;
static jmethodID cachedSelectionSelectAllSelectionMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_selection_init_jni_cache(JNIEnv *jniEnv);

static gboolean jaw_selection_add_selection(AtkSelection *selection, gint i);
static gboolean jaw_selection_clear_selection(AtkSelection *selection);
static AtkObject *jaw_selection_ref_selection(AtkSelection *selection, gint i);
static gint jaw_selection_get_selection_count(AtkSelection *selection);
static gboolean jaw_selection_is_child_selected(AtkSelection *selection,
                                                gint i);
static gboolean jaw_selection_remove_selection(AtkSelection *selection, gint i);
static gboolean jaw_selection_select_all_selection(AtkSelection *selection);

typedef struct _SelectionData {
    jobject atk_selection;
} SelectionData;

#define JAW_GET_SELECTION(selection, def_ret)                                  \
    JAW_GET_OBJ_IFACE(                                                         \
        selection, org_GNOME_Accessibility_AtkInterface_INTERFACE_SELECTION,   \
        SelectionData, atk_selection, jniEnv, atk_selection, def_ret)

/**
 * AtkSelectionIface:
 * @add_selection:
 * @clear_selection:
 * @ref_selection:
 * @get_selection_count:
 * @is_child_selected:
 * @remove_selection:
 * @select_all_selection:
 **/
void jaw_selection_interface_init(AtkSelectionIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (iface == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->add_selection = jaw_selection_add_selection;
    iface->clear_selection = jaw_selection_clear_selection;
    iface->ref_selection = jaw_selection_ref_selection;
    iface->get_selection_count = jaw_selection_get_selection_count;
    iface->is_child_selected = jaw_selection_is_child_selected;
    iface->remove_selection = jaw_selection_remove_selection;
    iface->select_all_selection = jaw_selection_select_all_selection;
}

gpointer jaw_selection_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (ac == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_selection_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, JAW_DEFAULT_LOCAL_FRAME_SIZE) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_selection = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedSelectionAtkSelectionClass,
        cachedSelectionCreateAtkSelectionMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_selection == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jatk_selection using "
                  "create_atk_selection method",
                  G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    SelectionData *data = g_new0(SelectionData, 1);
    data->atk_selection = (*jniEnv)->NewGlobalRef(jniEnv, jatk_selection);
    if (data->atk_selection == NULL) {
        g_warning("%s: Failed to create global ref for atk_selection",
                  G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_selection_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning(
            "Null argument passed to function jaw_selection_data_finalize");
        return;
    }

    SelectionData *data = (SelectionData *)p;
    if (data == NULL) {
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->atk_selection != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_selection);
            data->atk_selection = NULL;
        }
    }

    g_free(data);
}

/**
 * jaw_selection_add_selection:
 * @selection: a #GObject instance that implements AtkSelectionIface
 * @i: a #gint specifying the child index.
 *
 * Adds the specified accessible child of the object to the
 * object's selection.
 *
 * Returns: TRUE if success, FALSE otherwise.
 **/
static gboolean jaw_selection_add_selection(AtkSelection *selection, gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (selection == NULL) {
        g_warning(
            "Null argument passed to function jaw_selection_add_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(
        selection,
        FALSE); // create local JNI reference `jobject atk_selection`

    jboolean jbool = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_selection, cachedSelectionAddSelectionMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);

    return jbool;
}

/**
 * jaw_selection_clear_selection:
 * @selection: a #GObject instance that implements AtkSelectionIface
 *
 * Clears the selection in the object so that no children in the object
 * are selected.
 *
 * Returns: TRUE if success, FALSE otherwise.
 **/
static gboolean jaw_selection_clear_selection(AtkSelection *selection) {
    JAW_DEBUG_C("%p", selection);

    if (selection == NULL) {
        g_warning(
            "Null argument passed to function jaw_selection_clear_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(
        selection,
        FALSE); // create local JNI reference `jobject atk_selection`

    jboolean jbool = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_selection, cachedSelectionClearSelectionMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);

    return jbool;
}

/**
 * jaw_selection_ref_selection:
 * @selection: a #GObject instance that implements AtkSelectionIface
 * @i: a #gint specifying the index in the selection set.  (e.g. the
 * ith selection as opposed to the ith child).
 *
 * Gets a reference to the accessible object representing the specified
 * selected child of the object.
 *
 * Returns: (nullable) (transfer full): an #AtkObject representing the
 * selected accessible, or %NULL if @selection does not implement this
 * interface.
 **/
static AtkObject *jaw_selection_ref_selection(AtkSelection *selection, gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (selection == NULL) {
        g_warning(
            "Null argument passed to function jaw_selection_ref_selection");
        return NULL;
    }

    JAW_GET_SELECTION(
        selection, NULL); // create local JNI reference `jobject atk_selection`

    if ((*jniEnv)->PushLocalFrame(jniEnv, JAW_DEFAULT_LOCAL_FRAME_SIZE) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_selection);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject child_ac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_selection, cachedSelectionRefSelectionMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || child_ac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    AtkObject *obj = (AtkObject *)jaw_impl_find_instance(jniEnv, child_ac);

    // From the documentation of the `ref_selection`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (obj != NULL) {
        g_object_ref(G_OBJECT(obj));
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return obj;
}

/**
 * jaw_selection_get_selection_count:
 * @selection: a #GObject instance that implements AtkSelectionIface
 *
 * Gets the number of accessible children currently selected.
 *
 * Returns: a gint representing the number of items selected, or 0
 * if @selection does not implement this interface.
 **/
static gint jaw_selection_get_selection_count(AtkSelection *selection) {
    JAW_DEBUG_C("%p", selection);

    if (selection == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_SELECTION(selection,
                      0); // create local JNI reference `jobject atk_selection`

    jint jcount = (*jniEnv)->CallIntMethod(
        jniEnv, atk_selection, cachedSelectionGetSelectionCountMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);

    return (gint)jcount;
}

/**
 * jaw_selection_is_child_selected:
 * @selection: a #GObject instance that implements AtkSelectionIface
 * @i: a #gint specifying the child index.
 *
 * Determines if the current child of this object is selected
 *
 * Returns: a gboolean representing the specified child is selected, or 0
 * if @selection does not implement this interface.
 **/
static gboolean jaw_selection_is_child_selected(AtkSelection *selection,
                                                gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (selection == NULL) {
        g_warning(
            "Null argument passed to function jaw_selection_is_child_selected");
        return FALSE;
    }

    JAW_GET_SELECTION(
        selection,
        FALSE); // create local JNI reference `jobject atk_selection`

    jboolean jbool = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_selection, cachedSelectionIsChildSelectedMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);

    return jbool;
}

/**
 * jaw_selection_remove_selection:
 * @selection: a #GObject instance that implements AtkSelectionIface
 * @i: a #gint specifying the index in the selection set.  (e.g. the
 * ith selection as opposed to the ith child).
 *
 * Removes the specified child of the object from the object's selection.
 *
 * Returns: TRUE if success, FALSE otherwise.
 **/
static gboolean jaw_selection_remove_selection(AtkSelection *selection,
                                               gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (selection == NULL) {
        g_warning(
            "Null argument passed to function jaw_selection_remove_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(
        selection,
        FALSE); // create local JNI reference `jobject atk_selection`

    jboolean jbool = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_selection, cachedSelectionRemoveSelectionMethod, (jint)i);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);

    return jbool;
}

/**
 * jaw_selection_select_all_selection:
 * @selection: a #GObject instance that implements AtkSelectionIface
 *
 * Causes every child of the object to be selected if the object
 * supports multiple selections.
 *
 * Returns: TRUE if success, FALSE otherwise.
 **/
static gboolean jaw_selection_select_all_selection(AtkSelection *selection) {
    JAW_DEBUG_C("%p", selection);

    if (selection == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_SELECTION(
        selection,
        FALSE); // create local JNI reference `jobject atk_selection`

    jboolean jbool = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_selection, cachedSelectionSelectAllSelectionMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_selection);

    return jbool;
}

static gboolean jaw_selection_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkSelection class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedSelectionAtkSelectionClass =
        (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedSelectionAtkSelectionClass == NULL) {
        g_warning(
            "%s: Failed to create global reference for AtkSelection class",
            G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedSelectionCreateAtkSelectionMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "create_atk_selection",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkSelection;");

    cachedSelectionAddSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "add_selection", "(I)Z");

    cachedSelectionClearSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "clear_selection", "()Z");

    cachedSelectionRefSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "ref_selection",
        "(I)Ljavax/accessibility/AccessibleContext;");

    cachedSelectionGetSelectionCountMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "get_selection_count", "()I");

    cachedSelectionIsChildSelectedMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "is_child_selected", "(I)Z");

    cachedSelectionRemoveSelectionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedSelectionAtkSelectionClass, "remove_selection", "(I)Z");

    cachedSelectionSelectAllSelectionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedSelectionAtkSelectionClass,
                               "select_all_selection", "()Z");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedSelectionCreateAtkSelectionMethod == NULL ||
        cachedSelectionAddSelectionMethod == NULL ||
        cachedSelectionClearSelectionMethod == NULL ||
        cachedSelectionRefSelectionMethod == NULL ||
        cachedSelectionGetSelectionCountMethod == NULL ||
        cachedSelectionIsChildSelectedMethod == NULL ||
        cachedSelectionRemoveSelectionMethod == NULL ||
        cachedSelectionSelectAllSelectionMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkSelection method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);

    g_debug("%s: classes and methods cached successfully", G_STRFUNC);

    return TRUE;

cleanup_and_fail:
    if (cachedSelectionAtkSelectionClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedSelectionAtkSelectionClass);
        cachedSelectionAtkSelectionClass = NULL;
    }
    cachedSelectionCreateAtkSelectionMethod = NULL;
    cachedSelectionAddSelectionMethod = NULL;
    cachedSelectionClearSelectionMethod = NULL;
    cachedSelectionRefSelectionMethod = NULL;
    cachedSelectionGetSelectionCountMethod = NULL;
    cachedSelectionIsChildSelectedMethod = NULL;
    cachedSelectionRemoveSelectionMethod = NULL;
    cachedSelectionSelectAllSelectionMethod = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_selection_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedSelectionAtkSelectionClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedSelectionAtkSelectionClass);
        cachedSelectionAtkSelectionClass = NULL;
    }
    cachedSelectionCreateAtkSelectionMethod = NULL;
    cachedSelectionAddSelectionMethod = NULL;
    cachedSelectionClearSelectionMethod = NULL;
    cachedSelectionRefSelectionMethod = NULL;
    cachedSelectionGetSelectionCountMethod = NULL;
    cachedSelectionIsChildSelectedMethod = NULL;
    cachedSelectionRemoveSelectionMethod = NULL;
    cachedSelectionSelectAllSelectionMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
