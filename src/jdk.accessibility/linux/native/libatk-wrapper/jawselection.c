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
    JAW_GET_OBJ_IFACE(selection, INTERFACE_SELECTION, SelectionData,           \
                      atk_selection, jniEnv, atk_selection, def_ret)

void jaw_selection_interface_init(AtkSelectionIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function");
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

    if (!ac) {
        g_warning("Null argument passed to function jaw_selection_data_init");
        return NULL;
    }

    SelectionData *data = g_new0(SelectionData, 1);
    JAW_CHECK_NULL(data, NULL);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classSelection) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classSelection, "create_atk_selection",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkSelection;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_selection =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classSelection, jmid, ac);
    if (!jatk_selection) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    data->atk_selection = (*jniEnv)->NewGlobalRef(jniEnv, jatk_selection);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_selection_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning(
            "Null argument passed to function jaw_selection_data_finalize");
        return;
    }

    SelectionData *data = (SelectionData *)p;
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data && data->atk_selection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_selection);
        data->atk_selection = NULL;
    }
}

static gboolean jaw_selection_add_selection(AtkSelection *selection, gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (!selection) {
        g_warning(
            "Null argument passed to function jaw_selection_add_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(selection, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classAtkSelection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkSelection,
                                            "add_selection", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jbool =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_selection, jmid, (jint)i);
    if (!jbool) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jbool;
}

static gboolean jaw_selection_clear_selection(AtkSelection *selection) {
    JAW_DEBUG_C("%p", selection);

    if (!selection) {
        g_warning(
            "Null argument passed to function jaw_selection_clear_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(selection, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classAtkSelection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkSelection,
                                            "clear_selection", "()Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jbool = (*jniEnv)->CallBooleanMethod(jniEnv, atk_selection, jmid);
    if (!jbool) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

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

    if (!selection) {
        g_warning(
            "Null argument passed to function jaw_selection_ref_selection");
        return NULL;
    }

    JAW_GET_SELECTION(selection, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classAtkSelection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkSelection, "ref_selection",
                               "(I)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject child_ac =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_selection, jmid, (jint)i);
    if (!child_ac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    AtkObject *obj =
        (AtkObject *)jaw_impl_get_instance_from_jaw(jniEnv, child_ac);

    // From the documentation of the `atk_selection_ref_selection`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (obj) {
        g_object_ref(G_OBJECT(obj));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return obj;
}

static gint jaw_selection_get_selection_count(AtkSelection *selection) {
    JAW_DEBUG_C("%p", selection);

    if (!selection) {
        g_warning("Null argument passed to function "
                  "jaw_selection_get_selection_count");
        return 0;
    }

    JAW_GET_SELECTION(selection, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classAtkSelection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkSelection,
                                            "get_selection_count", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jcount = (*jniEnv)->CallIntMethod(jniEnv, atk_selection, jmid);
    if (!jcount) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)jcount;
}

static gboolean jaw_selection_is_child_selected(AtkSelection *selection,
                                                gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (!selection) {
        g_warning(
            "Null argument passed to function jaw_selection_is_child_selected");
        return FALSE;
    }

    JAW_GET_SELECTION(selection, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!jniEnv) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkSelection,
                                            "is_child_selected", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jbool =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_selection, jmid, (jint)i);
    if (!jbool) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jbool;
}

static gboolean jaw_selection_remove_selection(AtkSelection *selection,
                                               gint i) {
    JAW_DEBUG_C("%p, %d", selection, i);

    if (!selection) {
        g_warning(
            "Null argument passed to function jaw_selection_remove_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(selection, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classAtkSelection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkSelection,
                                            "remove_selection", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jbool =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_selection, jmid, (jint)i);
    if (!jbool) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jbool;
}

static gboolean jaw_selection_select_all_selection(AtkSelection *selection) {
    JAW_DEBUG_C("%p", selection);

    if (!selection) {
        g_warning("Null argument passed to function "
                  "jaw_selection_select_all_selection");
        return FALSE;
    }

    JAW_GET_SELECTION(selection, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_selection); // deleting ref that was created in
                            // JAW_GET_SELECTION
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkSelection =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkSelection");
    if (!classAtkSelection) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkSelection,
                                            "select_all_selection", "()Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jbool = (*jniEnv)->CallBooleanMethod(jniEnv, atk_selection, jmid);
    if (!jbool) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_selection);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jbool;
}
