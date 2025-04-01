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
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

static AtkObject *jaw_table_ref_at(AtkTable *table, gint row, gint column);
static gint jaw_table_get_index_at(AtkTable *table, gint row, gint column);
static gint jaw_table_get_column_at_index(AtkTable *table, gint index);
static gint jaw_table_get_row_at_index(AtkTable *table, gint index);
static gint jaw_table_get_n_columns(AtkTable *table);
static gint jaw_table_get_n_rows(AtkTable *table);
static gint jaw_table_get_column_extent_at(AtkTable *table, gint row,
                                           gint column);
static gint jaw_table_get_row_extent_at(AtkTable *table, gint row, gint column);
static AtkObject *jaw_table_get_caption(AtkTable *table);
static const gchar *jaw_table_get_column_description(AtkTable *table,
                                                     gint column);
static const gchar *jaw_table_get_row_description(AtkTable *table, gint row);
static AtkObject *jaw_table_get_column_header(AtkTable *table, gint column);
static AtkObject *jaw_table_get_row_header(AtkTable *table, gint row);
static AtkObject *jaw_table_get_summary(AtkTable *table);
static gint jaw_table_get_selected_columns(AtkTable *table, gint **selected);
static gint jaw_table_get_selected_rows(AtkTable *table, gint **selected);
static gboolean jaw_table_is_column_selected(AtkTable *table, gint column);
static gboolean jaw_table_is_row_selected(AtkTable *table, gint row);
static gboolean jaw_table_is_selected(AtkTable *table, gint row, gint column);
static gboolean jaw_table_add_row_selection(AtkTable *table, gint row);
static gboolean jaw_table_remove_row_selection(AtkTable *table, gint row);
static gboolean jaw_table_add_column_selection(AtkTable *table, gint column);
static gboolean jaw_table_remove_column_selection(AtkTable *table, gint column);
static void jaw_table_set_row_description(AtkTable *table, gint row,
                                          const gchar *description);
static void jaw_table_set_column_description(AtkTable *table, gint column,
                                             const gchar *description);
static void jaw_table_set_row_header(AtkTable *table, gint row,
                                     AtkObject *header);
static void jaw_table_set_column_header(AtkTable *table, gint column,
                                        AtkObject *header);
static void jaw_table_set_caption(AtkTable *table, AtkObject *caption);
static void jaw_table_set_summary(AtkTable *table, AtkObject *summary);

typedef struct _TableData {
    jobject atk_table;
    gchar *description;
    jstring jstrDescription;
} TableData;

#define JAW_GET_TABLE(table, def_ret)                                          \
    JAW_GET_OBJ_IFACE(table, INTERFACE_TABLE, TableData, atk_table, jniEnv,    \
                      atk_table, def_ret)

void jaw_table_interface_init(AtkTableIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function jaw_table_interface_init");
        return;
    }

    iface->ref_at = jaw_table_ref_at;
    iface->get_index_at = jaw_table_get_index_at;
    iface->get_column_at_index = jaw_table_get_column_at_index;
    iface->get_row_at_index = jaw_table_get_row_at_index;
    iface->get_n_columns = jaw_table_get_n_columns;
    iface->get_n_rows = jaw_table_get_n_rows;
    iface->get_column_extent_at = jaw_table_get_column_extent_at;
    iface->get_row_extent_at = jaw_table_get_row_extent_at;
    iface->get_caption = jaw_table_get_caption;
    iface->get_column_description = jaw_table_get_column_description;
    iface->get_column_header = jaw_table_get_column_header;
    iface->get_row_description = jaw_table_get_row_description;
    iface->get_row_header = jaw_table_get_row_header;
    iface->get_summary = jaw_table_get_summary;
    iface->set_caption = jaw_table_set_caption;
    iface->set_column_description = jaw_table_set_column_description;
    iface->set_column_header = jaw_table_set_column_header;
    iface->set_row_description = jaw_table_set_row_description;
    iface->set_row_header = jaw_table_set_row_header;
    iface->set_summary = jaw_table_set_summary;
    iface->get_selected_columns = jaw_table_get_selected_columns;
    iface->get_selected_rows = jaw_table_get_selected_rows;
    iface->is_column_selected = jaw_table_is_column_selected;
    iface->is_row_selected = jaw_table_is_row_selected;
    iface->is_selected = jaw_table_is_selected;
    iface->add_row_selection = jaw_table_add_row_selection;
    iface->remove_row_selection = jaw_table_remove_row_selection;
    iface->add_column_selection = jaw_table_add_column_selection;
    iface->remove_column_selection = jaw_table_remove_column_selection;
}

gpointer jaw_table_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("Null argument passed to function jaw_table_data_init");
        return NULL;
    }

    TableData *data = g_new0(TableData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classTable) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classTable, "create_atk_table",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTable;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_table =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classTable, jmid, ac);
    if (!jatk_table) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->atk_table = (*jniEnv)->NewGlobalRef(jniEnv, jatk_table);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_table_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function jaw_table_data_finalize");
        return;
    }

    TableData *data = (TableData *)p;
    JAW_CHECK_NULL(data, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data->jstrDescription != NULL) {
        if (data->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                             data->description);
            data->description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
        data->jstrDescription = NULL;
    }
    if (data && data->atk_table) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_table);
        data->atk_table = NULL;
    }
}

/**
 * jaw_table_ref_at:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 * @column: a #gint representing a column in @table
 *
 * Get a reference to the table cell at @row, @column. This cell
 * should implement the interface #AtkTableCell
 *
 * Returns: (transfer full): an #AtkObject representing the referred
 * to accessible
 **/
static AtkObject *jaw_table_ref_at(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_ref_at");
        return NULL;
    }

    JawObject *jaw_obj = JAW_OBJECT(table);
    if (!jaw_obj) {
        JAW_DEBUG_I("jaw_obj == NULL");
        return NULL;
    }
    TableData *data = jaw_object_get_interface_data(jaw_obj, INTERFACE_TABLE);
    JAW_CHECK_NULL(data, NULL);
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);
    jobject atk_table = (*jniEnv)->NewGlobalRef(jniEnv, data->atk_table);
    if (!atk_table) {
        JAW_DEBUG_I("atk_table == NULL");
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "ref_at",
                               "(II)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid,
                                              (jint)row, (jint)column);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);

    // From the documentation of the `atk_table_ref_at`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (G_OBJECT(jaw_impl) != NULL) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

static gint jaw_table_get_index_at(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_get_index_at");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_index_at", "(II)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jindex = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)row,
                                           (jint)column);
    if (!jindex) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jindex;
}

static gint jaw_table_get_column_at_index(AtkTable *table, gint index) {
    JAW_DEBUG_C("%p, %d", table, index);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_column_at_index");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_column_at_index", "(I)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jcolumn =
        (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)index);
    if (!jcolumn) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jcolumn;
}

static gint jaw_table_get_row_at_index(AtkTable *table, gint index) {
    JAW_DEBUG_C("%p, %d", table, index);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_row_at_index");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_row_at_index", "(I)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jrow = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)index);
    if (!jrow) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jrow;
}

static gint jaw_table_get_n_columns(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_get_n_columns");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_n_columns", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jcolumns = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid);
    if (!jcolumns) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jcolumns;
}

static gint jaw_table_get_n_rows(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_get_n_rows");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_n_rows", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jrows = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid);
    if (!jrows) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jrows;
}

static gint jaw_table_get_column_extent_at(AtkTable *table, gint row,
                                           gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_column_extent_at");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_column_extent_at", "(II)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jextent = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)row,
                                            (jint)column);
    if (!jextent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jextent;
}

static gint jaw_table_get_row_extent_at(AtkTable *table, gint row,
                                        gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_row_extent_at");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_row_extent_at", "(II)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jextent = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)row,
                                            (jint)column);
    if (!jextent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jextent;
}

/**
 * jaw_table_get_caption:
 * @table: a GObject instance that implements AtkTableInterface
 *
 * Gets the caption for the @table.
 *
 * Returns: (nullable) (transfer none): a AtkObject* representing the
 * table caption, or %NULL if value does not implement this interface.
 **/
static AtkObject *jaw_table_get_caption(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_get_caption");
        return 0;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_caption",
                               "()Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
    // From documentation of the `atk_table_get_caption` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the obj before returning it.

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

static const gchar *jaw_table_get_column_description(AtkTable *table,
                                                     gint column) {
    JAW_DEBUG_C("%p, %d", table, column);

    if (!table) {
        g_warning("Null argument passed to function "
                  "jaw_table_get_column_description");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_column_description",
                               "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)column);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->description != NULL && data->jstrDescription != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                         data->description);
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
    }

    data->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrDescription, NULL);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->description;
}

static const gchar *jaw_table_get_row_description(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_row_description");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTable, "get_row_description", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)row);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->description != NULL && data->jstrDescription != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                         data->description);
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
    }

    data->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrDescription, NULL);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->description;
}

/**
 * atk_table_get_column_header:
 * @table: a GObject instance that implements AtkTableIface
 * @column: a #gint representing a column in the table
 *
 * Gets the column header of a specified column in an accessible table.
 *
 * Returns: (nullable) (transfer none): a AtkObject* representing the
 * specified column header, or %NULL if value does not implement this
 * interface.
 **/
static AtkObject *jaw_table_get_column_header(AtkTable *table, gint column) {
    JAW_DEBUG_C("%p, %d", table, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_column_header");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_column_header",
                               "(I)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)column);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
    // From documentation of the `atk_table_get_column_header` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the obj before returning it.

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_row_header:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in the table
 *
 * Gets the row header of a specified row in an accessible table.
 *
 * Returns: (nullable) (transfer none): a AtkObject* representing the
 * specified row header, or %NULL if value does not implement this
 * interface.
 **/
static AtkObject *jaw_table_get_row_header(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_get_row_header");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_row_header",
                               "(I)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)row);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
    // From documentation of the `atk_table_get_row_header` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the jaw_impl before returning it.

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_summary:
 * @table: a GObject instance that implements AtkTableIface
 *
 * Gets the summary description of the table.
 *
 * Returns: (transfer full): a AtkObject* representing a summary description
 * of the table, or zero if value does not implement this interface.
 **/
static AtkObject *jaw_table_get_summary(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_get_summary");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_summary",
                               "()Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
    // From the documentation of the `atk_table_get_summary`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (jaw_impl) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

static gint jaw_table_get_selected_columns(AtkTable *table, gint **selected) {
    JAW_DEBUG_C("%p, %p", table, selected);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_selected_columns");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_selected_columns", "()[I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jintArray jcolumnArray =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, jcolumnArray);
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return length;
}

static gint jaw_table_get_selected_rows(AtkTable *table, gint **selected) {
    JAW_DEBUG_C("%p, %p", table, selected);

    if (!table || !selected) {
        g_warning(
            "Null argument passed to function jaw_table_get_selected_rows");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_selected_rows", "()[I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jintArray jrowArray = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jrowArray) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, jrowArray);
    if (!length) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    };

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return length;
}

static gboolean jaw_table_is_column_selected(AtkTable *table, gint column) {
    JAW_DEBUG_C("%p, %d", table, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_is_column_selected");
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "is_column_selected", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jboolean jselected =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_table, jmid, (jint)column);
    if (!jselected) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jselected;
}

static gboolean jaw_table_is_row_selected(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_is_row_selected");
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "is_row_selected", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jselected =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_table, jmid, (jint)row);
    if (!jselected) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jselected;
}

static gboolean jaw_table_is_selected(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning("Null argument passed to function jaw_table_is_selected");
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return FALSE;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "is_selected", "(II)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jselected = (*jniEnv)->CallBooleanMethod(jniEnv, atk_table, jmid,
                                                      (jint)row, (jint)column);
    if (!jselected) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jselected;
}

static gboolean jaw_table_add_row_selection(AtkTable *table, gint row) {
    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_add_row_selection");
        return FALSE;
    }

    g_warning(
        "It is impossible to add row selection on AccessibleTable Java Object");
    return FALSE;
}

static gboolean jaw_table_remove_row_selection(AtkTable *table, gint row) {
    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_remove_row_selection");
        return FALSE;
    }

    g_warning("It is impossible to remove row selection on AccessibleTable "
              "Java Object");
    return FALSE;
}

static gboolean jaw_table_add_column_selection(AtkTable *table, gint column) {
    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_add_column_selection");
        return FALSE;
    }

    g_warning("It is impossible to add column selection on AccessibleTable "
              "Java Object");
    return FALSE;
}

static gboolean jaw_table_remove_column_selection(AtkTable *table,
                                                  gint column) {
    if (!table) {
        g_warning("Null argument passed to function "
                  "jaw_table_remove_column_selection");
        return FALSE;
    }

    g_warning("It is impossible to remove column selection on AccessibleTable "
              "Java Object");
    return FALSE;
}

static void jaw_table_set_row_description(AtkTable *table, gint row,
                                          const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", table, row, description);

    if (!table || !description) {
        g_warning(
            "Null argument passed to function jaw_table_set_row_description");
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTable, "set_row_description", "(ILjava/lang/String;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, (jint)row, jstr);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static void jaw_table_set_column_description(AtkTable *table, gint column,
                                             const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", table, column, description);

    if (!table || !description) {
        g_warning("Null argument passed to function "
                  "jaw_table_set_column_description");
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "set_column_description",
                               "(ILjava/lang/String;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, (jint)column, jstr);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static void jaw_table_set_row_header(AtkTable *table, gint row,
                                     AtkObject *header) {
    if (!table || !header) {
        g_warning("Null argument passed to function jaw_table_set_row_header");
        return;
    }

    g_warning("It is impossible to set a single row header on AccessibleTable "
              "Java Object");
}

static void jaw_table_set_column_header(AtkTable *table, gint column,
                                        AtkObject *header) {
    if (!table || !header) {
        g_warning(
            "Null argument passed to function jaw_table_set_column_header");
        return;
    }

    g_warning("It is impossible to set a single column header on "
              "AccessibleTable Java Object");
}

static void jaw_table_set_caption(AtkTable *table, AtkObject *caption) {
    JAW_DEBUG_C("%p, %p", table, caption);

    if (!table || !caption) {
        g_warning("Null argument passed to function jaw_table_set_caption");
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return;
    }

    JawObject *jcaption = JAW_OBJECT(caption);
    if (!jcaption) {
        JAW_DEBUG_I("jcaption == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass accessible =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/Accessible");
    if (!(*jniEnv)->IsInstanceOf(jniEnv, jcaption->acc_context, accessible)) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject obj = (*jniEnv)->NewGlobalRef(jniEnv, jcaption->acc_context);
    if (!obj) {
        JAW_DEBUG_I("jcaption obj == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "set_caption",
                               "(Ljavax/accessibility/Accessible;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static void jaw_table_set_summary(AtkTable *table, AtkObject *summary) {
    JAW_DEBUG_C("%p, %p", table, summary);

    if (!table || !summary) {
        g_warning("Null argument passed to function jaw_table_set_summary");
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("Failed to create a new local reference frame");
        return;
    }

    JawObject *jsummary = JAW_OBJECT(summary);
    if (!jsummary) {
        JAW_DEBUG_I("jsummary == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass accessible =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/Accessible");
    if (!(*jniEnv)->IsInstanceOf(jniEnv, jsummary->acc_context, accessible)) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject obj = (*jniEnv)->NewGlobalRef(jniEnv, jsummary->acc_context);
    if (!obj) {
        JAW_DEBUG_I("jsummary obj == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "set_summary",
                               "(Ljavax/accessibility/Accessible;)V");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}
